package me.abuzaid.lensift.platform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.abuzaid.lensift.domain.LumaFrame

internal class AndroidLumaDecoder(
    private val imageDecoder: ImageDecodeFacade,
) : LumaDecoderFacade {
    override suspend fun decode(mediaId: Long, targetLongestEdge: Int): LumaFrame {
        require(targetLongestEdge > 0) { "Target longest edge must be positive" }
        val image = withContext(Dispatchers.IO) {
            imageDecoder.decode(mediaId, targetLongestEdge)
        }
        check(maxOf(image.width, image.height) <= targetLongestEdge) {
            "Decoded image exceeded the requested longest edge"
        }
        return LumaFrame(
            width = image.width,
            height = image.height,
            pixels = ByteArray(image.pixels.size) { index -> image.pixels[index].toLuma() },
        )
    }
}

internal data class DecodedArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "Decoded dimensions must be positive" }
        require(width.toLong() * height.toLong() == pixels.size.toLong()) {
            "ARGB buffer must contain exactly width * height pixels"
        }
    }
}

internal fun interface ImageDecodeFacade {
    fun decode(mediaId: Long, targetLongestEdge: Int): DecodedArgbImage
}

internal class AndroidImageDecodeFacade(
    private val contentResolver: ContentResolver,
) : ImageDecodeFacade {
    override fun decode(mediaId: Long, targetLongestEdge: Int): DecodedArgbImage {
        require(targetLongestEdge > 0) { "Target longest edge must be positive" }
        val source = ImageDecoder.createSource(contentResolver, mediaUri(mediaId))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sourceWidth = info.size.width
            val sourceHeight = info.size.height
            val longestEdge = maxOf(sourceWidth, sourceHeight)
            if (longestEdge > targetLongestEdge) {
                val scale = targetLongestEdge.toDouble() / longestEdge.toDouble()
                decoder.setTargetSize(
                    (sourceWidth * scale).toInt().coerceAtLeast(1),
                    (sourceHeight * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        return bitmap.toArgbImageAndRecycle()
    }
}

private fun Bitmap.toArgbImageAndRecycle(): DecodedArgbImage {
    val pixels = IntArray(width * height)
    return try {
        getPixels(pixels, 0, width, 0, 0, width, height)
        DecodedArgbImage(width = width, height = height, pixels = pixels)
    } finally {
        recycle()
    }
}

private fun Int.toLuma(): Byte {
    val red = this ushr 16 and 0xff
    val green = this ushr 8 and 0xff
    val blue = this and 0xff
    return ((77 * red + 150 * green + 29 * blue + 128) ushr 8).toByte()
}
