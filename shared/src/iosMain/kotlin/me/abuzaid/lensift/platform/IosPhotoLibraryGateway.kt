@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package me.abuzaid.lensift.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.plus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.suspendCancellableCoroutine
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.LumaFrame
import me.abuzaid.lensift.domain.PhotoDescriptor
import me.abuzaid.lensift.gateway.AccessState
import me.abuzaid.lensift.gateway.LibraryChange
import me.abuzaid.lensift.gateway.PhotoLibraryGateway
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.Foundation.NSSortDescriptor
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAssetResourceTypeFullSizePhoto
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHAuthorizationStatusRestricted
import platform.Photos.PHChange
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.Photos.PHImageCancelledKey
import platform.Photos.PHImageErrorKey
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsVersionCurrent
import platform.Photos.PHImageResultIsDegradedKey
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotoLibraryChangeObserverProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

class IosPhotoLibraryGateway internal constructor(
    private val photoKit: PhotoKitFacade,
    private val lumaDecoder: IosLumaDecoder = IosLumaDecoder(photoKit),
) : PhotoLibraryGateway {
    constructor() : this(ApplePhotoKitFacade())

    override suspend fun currentAccess(): AccessState = photoKit.currentAuthorization().toAccessState()

    override fun enumerateAccessibleImages(): Flow<PhotoDescriptor> = flow {
        if (currentAccess() !in readableAccessStates) return@flow
        photoKit.enumerateImages(iosPhotoKitImageQuery())
            .asSequence()
            .filter { it.width > 0 && it.height > 0 }
            .sortedWith(photoKitImageOrder)
            .forEach { row ->
                currentCoroutineContext().ensureActive()
                emit(row.toPhotoDescriptor())
            }
    }.flowOn(Dispatchers.Default)

    override suspend fun decodeLuma(assetId: AssetId, targetLongestEdge: Int): LumaFrame {
        require(targetLongestEdge > 0) { "Target longest edge must be positive" }
        return lumaDecoder.decode(
            localIdentifier = assetId.toIosLocalIdentifier(),
            assetId = assetId,
            targetLongestEdge = minOf(targetLongestEdge, MAX_LUMA_EDGE),
        )
    }

    override fun originalByteChunks(assetId: AssetId): Flow<ByteArray> = callbackFlow {
        val delivery = OriginalStreamDelivery(
            assetId = assetId,
            maximumChunkBytes = ORIGINAL_CHUNK_BYTES,
            send = { trySend(it).isSuccess },
            close = { cause -> close(cause) },
        )
        val request = photoKit.requestOriginalData(
            localIdentifier = assetId.toIosLocalIdentifier(),
            request = iosOriginalRequest(),
            onData = delivery::onData,
            onComplete = delivery::onComplete,
        )
        delivery.attach(request)
        awaitClose(delivery::cancel)
    }.buffer(capacity = ORIGINAL_STREAM_BUFFER_CAPACITY)

    override fun observeChanges(): Flow<LibraryChange> = callbackFlow {
        val observation = photoKit.observeImageChanges { changedIdentifiers ->
            val event = changedIdentifiers
                ?.sorted()
                ?.mapTo(linkedSetOf()) { it.toIosAssetId() }
                ?.takeIf(Set<AssetId>::isNotEmpty)
                ?.let(LibraryChange::Changed)
                ?: LibraryChange.AccessMayHaveChanged
            trySend(event)
        }
        awaitClose(observation::close)
    }

    private companion object {
        const val MAX_LUMA_EDGE = 512
        const val ORIGINAL_CHUNK_BYTES = 64 * 1024
        const val ORIGINAL_STREAM_BUFFER_CAPACITY = 64
        val readableAccessStates = setOf(AccessState.Full, AccessState.Partial)
    }
}

internal enum class PhotoKitAuthorizationStatus { Authorized, Limited, Denied, Restricted, NotDetermined }

private fun PhotoKitAuthorizationStatus.toAccessState(): AccessState = when (this) {
    PhotoKitAuthorizationStatus.Authorized -> AccessState.Full
    PhotoKitAuthorizationStatus.Limited -> AccessState.Partial
    PhotoKitAuthorizationStatus.Denied -> AccessState.Denied
    PhotoKitAuthorizationStatus.Restricted -> AccessState.Restricted
    PhotoKitAuthorizationStatus.NotDetermined -> AccessState.NotDetermined
}

internal enum class PhotoKitMediaType { Image }
internal enum class PhotoKitSortKey { CreationDate, LocalIdentifier }

internal data class PhotoKitSortDescriptor(
    val key: PhotoKitSortKey,
    val ascending: Boolean,
)

internal data class PhotoKitImageQuery(
    val mediaType: PhotoKitMediaType,
    val sortDescriptors: List<PhotoKitSortDescriptor>,
)

internal fun iosPhotoKitImageQuery(): PhotoKitImageQuery = PhotoKitImageQuery(
    mediaType = PhotoKitMediaType.Image,
    sortDescriptors = listOf(
        PhotoKitSortDescriptor(PhotoKitSortKey.CreationDate, ascending = false),
        PhotoKitSortDescriptor(PhotoKitSortKey.LocalIdentifier, ascending = true),
    ),
)

internal data class PhotoKitImageRow(
    val localIdentifier: String,
    val width: Int,
    val height: Int,
    val byteCount: Long?,
    val capturedAtEpochMillis: Long?,
    val modifiedAtEpochMillis: Long?,
    val isFavorite: Boolean,
    val isEdited: Boolean?,
)

internal val photoKitImageOrder = compareByDescending<PhotoKitImageRow> {
    it.capturedAtEpochMillis != null
}.thenByDescending {
    it.capturedAtEpochMillis
}.thenBy {
    it.localIdentifier
}

internal fun PhotoKitImageRow.toPhotoDescriptor(): PhotoDescriptor {
    val normalizedByteCount = byteCount?.takeIf { it >= 0 }
    val favorite = isFavorite
    val edited = isEdited == true
    return PhotoDescriptor(
        id = localIdentifier.toIosAssetId(),
        contentSignature = buildString {
            append("ios-v1")
            append("|w=").append(width)
            append("|h=").append(height)
            append("|s=").append(normalizedByteCount ?: "null")
            append("|created=").append(capturedAtEpochMillis ?: "null")
            append("|modified=").append(modifiedAtEpochMillis ?: "null")
            append("|favorite=").append(favorite)
            append("|edited=").append(edited)
        },
        width = width,
        height = height,
        byteCount = normalizedByteCount,
        capturedAtEpochMillis = capturedAtEpochMillis,
        isFavorite = favorite,
        isEdited = edited,
    )
}

internal fun String.toIosAssetId(): AssetId {
    require(isNotBlank()) { "PhotoKit local identifier must not be blank" }
    return AssetId("$IOS_ASSET_ID_PREFIX$this")
}

internal fun AssetId.toIosLocalIdentifier(): String {
    require(value.startsWith(IOS_ASSET_ID_PREFIX)) { "Asset ID does not belong to PhotoKit" }
    return value.removePrefix(IOS_ASSET_ID_PREFIX).takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("PhotoKit asset ID is malformed")
}

internal data class IosImageRequest(
    val targetLongestEdge: Int,
    val networkAccessAllowed: Boolean,
    val normalizeOrientation: Boolean,
)

internal enum class PhotoKitResourceType { FullSizePhoto, Photo }

internal data class IosOriginalRequest(
    val preferredResourceTypes: List<PhotoKitResourceType>,
    val networkAccessAllowed: Boolean,
    val maximumChunkBytes: Int,
)

/** Prefer the adjusted full-resolution still, then the original still, with no implicit network fetch. */
internal fun iosOriginalRequest(): IosOriginalRequest = IosOriginalRequest(
    preferredResourceTypes = listOf(PhotoKitResourceType.FullSizePhoto, PhotoKitResourceType.Photo),
    networkAccessAllowed = false,
    maximumChunkBytes = 64 * 1024,
)

internal fun interface PhotoKitRequest {
    fun cancel()
}

internal fun interface PhotoKitObservation {
    fun close()
}

internal interface PhotoKitFacade {
    fun currentAuthorization(): PhotoKitAuthorizationStatus
    fun enumerateImages(query: PhotoKitImageQuery): List<PhotoKitImageRow>

    suspend fun requestDecodedImage(
        localIdentifier: String,
        request: IosImageRequest,
    ): DecodedRgbaImage?

    fun requestOriginalData(
        localIdentifier: String,
        request: IosOriginalRequest,
        onData: (ByteArray) -> Unit,
        onComplete: (Throwable?) -> Unit,
    ): PhotoKitRequest

    fun observeImageChanges(onChange: (Set<String>?) -> Unit): PhotoKitObservation
}

class PhotoAssetUnavailableException(val assetId: AssetId) :
    Exception("Photo asset is not available locally: ${assetId.value}")

internal class PhotoKitResourceUnavailableException(message: String) : Exception(message)

/** Serializes native callback races and makes any undeliverable byte chunk terminal. */
private class OriginalStreamDelivery(
    private val assetId: AssetId,
    private val maximumChunkBytes: Int,
    private val send: (ByteArray) -> Boolean,
    private val close: (Throwable?) -> Unit,
) {
    private val lock = NSLock()
    private var terminal = false
    private var cancellationSent = false
    private var request: PhotoKitRequest? = null

    fun attach(request: PhotoKitRequest) {
        val cancelImmediately = withLock {
            check(this.request == null) { "PhotoKit request was attached more than once" }
            this.request = request
            cancellationSent
        }
        if (cancelImmediately) request.cancel()
    }

    fun onData(chunk: ByteArray) {
        var requestToCancel: PhotoKitRequest? = null
        var failure: Throwable? = null
        withLock {
            if (terminal) return
            val accepted = chunk.size <= maximumChunkBytes && send(chunk.copyOf())
            if (!accepted) {
                terminal = true
                failure = PhotoKitResourceUnavailableException(
                    "PhotoKit original-byte delivery failed for ${assetId.value}",
                )
                if (!cancellationSent) {
                    cancellationSent = true
                    requestToCancel = request
                }
            }
        }
        requestToCancel?.cancel()
        failure?.let(close)
    }

    fun onComplete(error: Throwable?) {
        val shouldClose = withLock {
            if (terminal) false else {
                terminal = true
                true
            }
        }
        if (shouldClose) close(error)
    }

    fun cancel() {
        val requestToCancel = withLock {
            terminal = true
            if (cancellationSent) null else {
                cancellationSent = true
                request
            }
        }
        requestToCancel?.cancel()
    }

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class ApplePhotoKitFacade : PhotoKitFacade {
    private var latestFetchResult: PHFetchResult? = null
    private val imageManager = PHImageManager.defaultManager()
    private val resourceManager = PHAssetResourceManager.defaultManager()

    override fun currentAuthorization(): PhotoKitAuthorizationStatus = when (
        PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
    ) {
        PHAuthorizationStatusAuthorized -> PhotoKitAuthorizationStatus.Authorized
        PHAuthorizationStatusLimited -> PhotoKitAuthorizationStatus.Limited
        PHAuthorizationStatusDenied -> PhotoKitAuthorizationStatus.Denied
        PHAuthorizationStatusRestricted -> PhotoKitAuthorizationStatus.Restricted
        PHAuthorizationStatusNotDetermined -> PhotoKitAuthorizationStatus.NotDetermined
        else -> PhotoKitAuthorizationStatus.Restricted
    }

    override fun enumerateImages(query: PhotoKitImageQuery): List<PhotoKitImageRow> = autoreleasepool {
        val options = PHFetchOptions().apply {
            sortDescriptors = query.sortDescriptors
                .filter { it.key == PhotoKitSortKey.CreationDate }
                .map { NSSortDescriptor("creationDate", it.ascending) }
            wantsIncrementalChangeDetails = true
        }
        val result = PHAsset.fetchAssetsWithMediaType(
            when (query.mediaType) {
                PhotoKitMediaType.Image -> PHAssetMediaTypeImage
            },
            options,
        )
        latestFetchResult = result
        buildList(result.count.toInt()) {
            repeat(result.count.toInt()) { index ->
                val asset = result.objectAtIndex(index.toULong()) as? PHAsset ?: return@repeat
                add(asset.toPhotoKitImageRow())
            }
        }
    }

    override suspend fun requestDecodedImage(
        localIdentifier: String,
        request: IosImageRequest,
    ): DecodedRgbaImage? = suspendCancellableCoroutine { continuation ->
        val asset = autoreleasepool { findImageAsset(localIdentifier) }
        if (asset == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val options = PHImageRequestOptions().apply {
            networkAccessAllowed = request.networkAccessAllowed
            synchronous = false
            deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
            version = PHImageRequestOptionsVersionCurrent
        }
        var requestId = 0
        requestId = imageManager.requestImageDataAndOrientationForAsset(asset, options) { data, _, _, info ->
            if (!continuation.isActive) return@requestImageDataAndOrientationForAsset
            val error = info?.get(PHImageErrorKey) as? NSError
            val cancelled = info?.get(PHImageCancelledKey) as? Boolean == true
            val degraded = info?.get(PHImageResultIsDegradedKey) as? Boolean == true
            if (degraded) return@requestImageDataAndOrientationForAsset
            if (cancelled) {
                continuation.cancel()
            } else if (error != null) {
                continuation.resumeWithException(PhotoKitResourceUnavailableException(error.localizedDescription))
            } else if (data == null) {
                continuation.resume(null)
            } else {
                runCatching { decodePhotoKitImageData(data, request.targetLongestEdge) }
                    .onSuccess(continuation::resume)
                    .onFailure(continuation::resumeWithException)
            }
        }
        continuation.invokeOnCancellation { imageManager.cancelImageRequest(requestId) }
    }

    override fun requestOriginalData(
        localIdentifier: String,
        request: IosOriginalRequest,
        onData: (ByteArray) -> Unit,
        onComplete: (Throwable?) -> Unit,
    ): PhotoKitRequest = autoreleasepool {
        val asset = findImageAsset(localIdentifier)
        val resource = asset?.let { PHAssetResource.assetResourcesForAsset(it) }
            ?.filterIsInstance<PHAssetResource>()
            ?.let { selectOriginalResource(it, request.preferredResourceTypes) }
        if (resource == null) {
            onComplete(PhotoKitResourceUnavailableException("PhotoKit has no local photo resource"))
            return@autoreleasepool PhotoKitRequest {}
        }

        val options = PHAssetResourceRequestOptions().apply {
            networkAccessAllowed = request.networkAccessAllowed
        }
        val requestId = resourceManager.requestDataForAssetResource(
            resource = resource,
            options = options,
            dataReceivedHandler = { data ->
                if (data != null) data.copyToChunks(request.maximumChunkBytes, onData)
            },
            completionHandler = { error ->
                onComplete(error?.let { PhotoKitResourceUnavailableException(it.localizedDescription) })
            },
        )
        PhotoKitRequest { resourceManager.cancelDataRequest(requestId) }
    }

    override fun observeImageChanges(onChange: (Set<String>?) -> Unit): PhotoKitObservation {
        val observer = PhotoLibraryObserver(
            fetchResult = { latestFetchResult },
            updateFetchResult = { latestFetchResult = it },
            onChange = onChange,
        )
        val library = PHPhotoLibrary.sharedPhotoLibrary()
        library.registerChangeObserver(observer)
        return PhotoKitObservation { library.unregisterChangeObserver(observer) }
    }

    private fun findImageAsset(localIdentifier: String): PHAsset? {
        val result = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(localIdentifier), null)
        return if (result.count == 0UL) null else result.objectAtIndex(0u) as? PHAsset
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun PHAsset.toPhotoKitImageRow(): PhotoKitImageRow = PhotoKitImageRow(
    localIdentifier = localIdentifier,
    width = pixelWidth.toInt(),
    height = pixelHeight.toInt(),
    byteCount = null,
    capturedAtEpochMillis = creationDate?.timeIntervalSince1970?.times(1_000.0)?.roundToLong(),
    modifiedAtEpochMillis = modificationDate?.timeIntervalSince1970?.times(1_000.0)?.roundToLong(),
    isFavorite = favorite,
    isEdited = hasAdjustments,
)

@OptIn(ExperimentalForeignApi::class)
private fun selectOriginalResource(
    resources: List<PHAssetResource>,
    preference: List<PhotoKitResourceType>,
): PHAssetResource? = preference.firstNotNullOfOrNull { preferred ->
    val nativeType = when (preferred) {
        PhotoKitResourceType.FullSizePhoto -> PHAssetResourceTypeFullSizePhoto
        PhotoKitResourceType.Photo -> PHAssetResourceTypePhoto
    }
    resources.firstOrNull { it.type == nativeType }
}

@OptIn(ExperimentalForeignApi::class)
private class PhotoLibraryObserver(
    private val fetchResult: () -> PHFetchResult?,
    private val updateFetchResult: (PHFetchResult) -> Unit,
    private val onChange: (Set<String>?) -> Unit,
) : NSObject(), PHPhotoLibraryChangeObserverProtocol {
    override fun photoLibraryDidChange(changeInstance: PHChange) = autoreleasepool {
        val before = fetchResult()
        val details = before?.let(changeInstance::changeDetailsForFetchResult)
        if (details == null) {
            onChange(null)
            return@autoreleasepool
        }
        updateFetchResult(details.fetchResultAfterChanges)
        val identifiers = buildSet {
            details.insertedObjects.filterIsInstance<PHAsset>().forEach { add(it.localIdentifier) }
            details.changedObjects.filterIsInstance<PHAsset>().forEach { add(it.localIdentifier) }
            details.removedObjects.filterIsInstance<PHAsset>().forEach { add(it.localIdentifier) }
        }
        onChange(identifiers.ifEmpty { null })
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.copyToChunks(maximumChunkBytes: Int, consumer: (ByteArray) -> Unit) {
    require(maximumChunkBytes > 0) { "Maximum chunk size must be positive" }
    val source = bytes ?: return
    var offset = 0UL
    while (offset < length) {
        val count = minOf(maximumChunkBytes.toULong(), length - offset).toInt()
        val chunk = ByteArray(count)
        chunk.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), source.reinterpret<ByteVar>() + offset.toInt(), count.convert())
        }
        consumer(chunk)
        offset += count.toULong()
    }
}

private const val IOS_ASSET_ID_PREFIX = "ios-photo:"
