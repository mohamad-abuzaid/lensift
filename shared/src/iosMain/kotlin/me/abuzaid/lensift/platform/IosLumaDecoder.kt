@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package me.abuzaid.lensift.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.LumaFrame
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

internal class IosLumaDecoder(
    private val photoKit: PhotoKitFacade,
) {
    suspend fun decode(
        localIdentifier: String,
        assetId: AssetId,
        targetLongestEdge: Int,
    ): LumaFrame {
        require(targetLongestEdge > 0) { "Target longest edge must be positive" }
        val image = photoKit.requestDecodedImage(
            localIdentifier = localIdentifier,
            request = IosImageRequest(
                targetLongestEdge = targetLongestEdge,
                networkAccessAllowed = false,
                normalizeOrientation = true,
            ),
        ) ?: throw PhotoAssetUnavailableException(assetId)
        check(maxOf(image.width, image.height) <= targetLongestEdge) {
            "Decoded image exceeded the requested longest edge"
        }
        return LumaFrame(
            width = image.width,
            height = image.height,
            pixels = ByteArray(image.width * image.height) { index ->
                val offset = index * RGBA_COMPONENTS
                rgbaToLuma(image.rgba, offset)
            },
        )
    }
}

internal class DecodedRgbaImage(
    val width: Int,
    val height: Int,
    rgba: ByteArray,
) {
    internal val rgba = rgba.copyOf()

    init {
        require(width > 0 && height > 0) { "Decoded dimensions must be positive" }
        require(width.toLong() * height.toLong() * RGBA_COMPONENTS.toLong() == rgba.size.toLong()) {
            "RGBA buffer must contain exactly width * height * 4 bytes"
        }
    }
}

private fun rgbaToLuma(rgba: ByteArray, offset: Int): Byte {
    val red = rgba[offset].toInt() and 0xff
    val green = rgba[offset + 1].toInt() and 0xff
    val blue = rgba[offset + 2].toInt() and 0xff
    return ((77 * red + 150 * green + 29 * blue + 128) ushr 8).toByte()
}

/**
 * ImageIO creates a transformed thumbnail, so EXIF orientation is normalized before CoreGraphics
 * renders into the bounded RGBA buffer. All retained Core Foundation objects are released here.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun decodePhotoKitImageData(data: NSData, targetLongestEdge: Int): DecodedRgbaImage =
    autoreleasepool {
        require(targetLongestEdge > 0) { "Target longest edge must be positive" }
        val bytes = data.bytes?.reinterpret<ByteVar>()
            ?: throw PhotoKitResourceUnavailableException("PhotoKit returned empty image data")
        val cfData = checkNotNull(CFDataCreate(null, bytes.reinterpret(), data.length.convert())) {
            "Could not copy PhotoKit image data"
        }
        try {
            val source = checkNotNull(CGImageSourceCreateWithData(cfData, null)) {
                "PhotoKit image data is not decodable"
            }
            try {
                val image = createOrientedThumbnail(source, targetLongestEdge)
                try {
                    image.toDecodedRgbaImage()
                } finally {
                    CGImageRelease(image)
                }
            } finally {
                CFRelease(source)
            }
        } finally {
            CFRelease(cfData)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun createOrientedThumbnail(
    source: kotlinx.cinterop.CPointer<cnames.structs.CGImageSource>,
    targetLongestEdge: Int,
) = memScoped {
    val maxSize = alloc<IntVar>()
    maxSize.value = targetLongestEdge
    val number = checkNotNull(CFNumberCreate(null, kCFNumberIntType, maxSize.ptr))
    try {
        val options = checkNotNull(
            CFDictionaryCreateMutable(
                allocator = null,
                capacity = 3,
                keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
                valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
            ),
        )
        try {
            CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
            CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
            CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, number)
            checkNotNull(CGImageSourceCreateThumbnailAtIndex(source, 0u, options)) {
                "ImageIO could not create an oriented thumbnail"
            }
        } finally {
            CFRelease(options)
        }
    } finally {
        CFRelease(number)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun kotlinx.cinterop.CPointer<cnames.structs.CGImage>.toDecodedRgbaImage(): DecodedRgbaImage {
    val width = CGImageGetWidth(this).toInt()
    val height = CGImageGetHeight(this).toInt()
    require(width > 0 && height > 0) { "ImageIO returned invalid dimensions" }
    val rgba = ByteArray(width * height * RGBA_COMPONENTS)
    val colorSpace = checkNotNull(platform.CoreGraphics.CGColorSpaceCreateDeviceRGB())
    try {
        rgba.usePinned { pinned ->
            val context = checkNotNull(
                CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = width.convert(),
                    height = height.convert(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (width * RGBA_COMPONENTS).convert(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
                ),
            )
            try {
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), this)
            } finally {
                CGContextRelease(context)
            }
        }
    } finally {
        CGColorSpaceRelease(colorSpace)
    }
    return DecodedRgbaImage(width, height, rgba)
}

private const val RGBA_COMPONENTS = 4
