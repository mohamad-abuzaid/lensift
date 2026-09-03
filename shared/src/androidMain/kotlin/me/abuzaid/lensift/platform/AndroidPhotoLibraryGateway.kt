package me.abuzaid.lensift.platform

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.LumaFrame
import me.abuzaid.lensift.domain.PhotoDescriptor
import me.abuzaid.lensift.gateway.AccessState
import me.abuzaid.lensift.gateway.LibraryChange
import me.abuzaid.lensift.gateway.PhotoLibraryGateway
import java.io.InputStream

class AndroidPhotoLibraryGateway internal constructor(
    private val resolver: ContentResolverFacade,
    private val permissionReader: PhotoPermissionReader,
    private val lumaDecoder: LumaDecoderFacade,
) : PhotoLibraryGateway {
    constructor(context: Context) : this(
        resolver = AndroidContentResolverFacade(context.applicationContext.contentResolver),
        permissionReader = AndroidPhotoPermissionReader(context.applicationContext),
        lumaDecoder = AndroidLumaDecoder(
            AndroidImageDecodeFacade(context.applicationContext.contentResolver),
        ),
    )

    internal constructor(
        resolver: ContentResolverFacade,
        permissionState: PhotoPermissionState,
        lumaDecoder: LumaDecoderFacade,
    ) : this(resolver, PhotoPermissionReader { permissionState }, lumaDecoder)

    override suspend fun currentAccess(): AccessState = permissionReader.read().toAccessState()

    override fun enumerateAccessibleImages(): Flow<PhotoDescriptor> = flow {
        if (currentAccess() !in readableAccessStates) return@flow

        val rows = ArrayList<MediaImageRow>()
        resolver.queryImages(androidMediaStoreImageQuery()).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                cursor.currentRow().takeIf { it.width > 0 && it.height > 0 }?.let(rows::add)
            }
        }

        rows.sortedWith(mediaImageOrder).forEach { row ->
            currentCoroutineContext().ensureActive()
            emit(row.toPhotoDescriptor())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun decodeLuma(assetId: AssetId, targetLongestEdge: Int): LumaFrame {
        require(targetLongestEdge > 0) { "Target longest edge must be positive" }
        return lumaDecoder.decode(assetId.toAndroidMediaId(), minOf(targetLongestEdge, MAX_LUMA_EDGE))
    }

    override fun originalByteChunks(assetId: AssetId): Flow<ByteArray> = flow {
        val source = resolver.openOriginal(assetId.toAndroidMediaId())
        source.use {
            val buffer = ByteArray(ORIGINAL_CHUNK_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                check(count <= buffer.size) { "Original source exceeded the requested chunk size" }
                if (count == 0) continue
                emit(buffer.copyOf(count))
                currentCoroutineContext().ensureActive()
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun observeChanges(): Flow<LibraryChange> = flow {
        val delivery = AndroidLibraryChangeDelivery()
        val registration = resolver.observeImages(delivery::onChange)
        try {
            while (delivery.awaitPendingChange()) {
                emit(LibraryChange.AccessMayHaveChanged)
            }
        } finally {
            delivery.close()
            registration.close()
        }
    }

    private companion object {
        const val ORIGINAL_CHUNK_BYTES = 64 * 1024
        const val MAX_LUMA_EDGE = 512

        val readableAccessStates = setOf(AccessState.Full, AccessState.Partial)
    }
}

/** Owns one coarse pending bit and one conflated wake, independent of callbackFlow capacity. */
private class AndroidLibraryChangeDelivery {
    private val lock = Any()
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)
    private var pending = false
    private var terminal = false

    fun onChange() {
        val shouldWake = synchronized(lock) {
            if (terminal) false else {
                pending = true
                true
            }
        }
        if (shouldWake && wake.trySend(Unit).isFailure) close()
    }

    suspend fun awaitPendingChange(): Boolean {
        while (wake.receiveCatching().isSuccess) {
            val shouldDeliver = synchronized(lock) {
                if (terminal || !pending) false else {
                    pending = false
                    true
                }
            }
            if (shouldDeliver) return true
        }
        return false
    }

    fun close() {
        synchronized(lock) {
            terminal = true
            pending = false
        }
        wake.close()
    }
}

internal data class PhotoPermissionState(
    val apiLevel: Int,
    val hasReadMediaImages: Boolean,
    val hasSelectedPhotos: Boolean,
    val hasReadExternalStorage: Boolean,
)

private fun PhotoPermissionState.toAccessState(): AccessState = when {
    apiLevel >= 34 && hasReadMediaImages -> AccessState.Full
    apiLevel >= 34 && hasSelectedPhotos -> AccessState.Partial
    apiLevel >= 33 && hasReadMediaImages -> AccessState.Full
    apiLevel < 33 && hasReadExternalStorage -> AccessState.Full
    else -> AccessState.Denied
}

internal fun interface PhotoPermissionReader {
    fun read(): PhotoPermissionState
}

private class AndroidPhotoPermissionReader(private val context: Context) : PhotoPermissionReader {
    override fun read(): PhotoPermissionState = PhotoPermissionState(
        apiLevel = Build.VERSION.SDK_INT,
        hasReadMediaImages = Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES),
        hasSelectedPhotos = Build.VERSION.SDK_INT >= 34 &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        hasReadExternalStorage = Build.VERSION.SDK_INT < 33 &&
            granted(Manifest.permission.READ_EXTERNAL_STORAGE),
    )

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

internal data class MediaStoreQuerySpec(
    val projection: List<String>,
    val selection: String,
    val selectionArguments: List<String>,
    val sortOrder: String,
)

/** Framework-independent contract passed unchanged to the production ContentResolver query. */
internal fun androidMediaStoreImageQuery(): MediaStoreQuerySpec = MediaStoreQuerySpec(
    projection = listOf(
        COLUMN_ID,
        COLUMN_WIDTH,
        COLUMN_HEIGHT,
        COLUMN_SIZE,
        COLUMN_DATE_TAKEN,
        COLUMN_DATE_MODIFIED,
        COLUMN_ORIENTATION,
        COLUMN_FAVORITE,
    ),
    selection = "$COLUMN_MEDIA_TYPE = ?",
    selectionArguments = listOf(MEDIA_TYPE_IMAGE.toString()),
    sortOrder = "$COLUMN_DATE_TAKEN DESC, $COLUMN_ID ASC",
)

internal data class MediaImageRow(
    val id: Long,
    val width: Int,
    val height: Int,
    val byteCount: Long?,
    val capturedAtMillis: Long?,
    val modifiedAtSeconds: Long?,
    val orientationDegrees: Int,
    val isFavorite: Boolean?,
)

internal interface MediaStoreRowValues {
    fun long(column: String): Long?
}

/** The exact raw-column mapping used by the production Cursor adapter. */
internal fun MediaStoreRowValues.toMediaImageRow(): MediaImageRow = MediaImageRow(
    id = requireNotNull(long(COLUMN_ID)) { "MediaStore row has no ID" },
    width = requireNotNull(long(COLUMN_WIDTH)) { "MediaStore row has no width" }.toIntExact(COLUMN_WIDTH),
    height = requireNotNull(long(COLUMN_HEIGHT)) { "MediaStore row has no height" }.toIntExact(COLUMN_HEIGHT),
    byteCount = long(COLUMN_SIZE),
    capturedAtMillis = long(COLUMN_DATE_TAKEN),
    modifiedAtSeconds = long(COLUMN_DATE_MODIFIED),
    orientationDegrees = (long(COLUMN_ORIENTATION) ?: 0L).toIntExact(COLUMN_ORIENTATION),
    isFavorite = long(COLUMN_FAVORITE)?.let { it != 0L },
)

internal fun MediaImageRow.toPhotoDescriptor(): PhotoDescriptor {
    val normalizedByteCount = byteCount?.takeIf { it >= 0 }
    val normalizedCapturedAt = capturedAtMillis?.takeIf { it > 0 }
    val normalizedModifiedAt = modifiedAtSeconds?.takeIf { it > 0 }
    val normalizedOrientation = ((orientationDegrees % 360) + 360) % 360
    val favorite = isFavorite == true
    return PhotoDescriptor(
        id = id.toAndroidAssetId(),
        contentSignature = buildString {
            append("android-v1")
            append("|w=").append(width)
            append("|h=").append(height)
            append("|s=").append(normalizedByteCount ?: "null")
            append("|taken=").append(normalizedCapturedAt ?: "null")
            append("|modified=").append(normalizedModifiedAt ?: "null")
            append("|orientation=").append(normalizedOrientation)
            append("|favorite=").append(favorite)
        },
        width = width,
        height = height,
        byteCount = normalizedByteCount,
        capturedAtEpochMillis = normalizedCapturedAt,
        isFavorite = favorite,
        isEdited = false,
    )
}

internal fun Long.toAndroidAssetId(): AssetId {
    require(this >= 0) { "Android MediaStore content ID must not be negative" }
    return AssetId("$ASSET_ID_PREFIX$this")
}

internal fun AssetId.toAndroidMediaId(): Long {
    require(value.startsWith(ASSET_ID_PREFIX)) { "Asset ID does not belong to Android MediaStore" }
    return value.removePrefix(ASSET_ID_PREFIX).toLongOrNull()
        ?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("Android MediaStore asset ID is malformed")
}

internal interface ImageCursorFacade : AutoCloseable {
    fun moveToNext(): Boolean
    fun currentRow(): MediaImageRow
}

internal interface ByteSourceFacade : AutoCloseable {
    fun read(buffer: ByteArray): Int
}

internal fun interface ObserverRegistration : AutoCloseable {
    override fun close()
}

internal interface ContentResolverFacade {
    fun queryImages(query: MediaStoreQuerySpec): ImageCursorFacade
    fun openOriginal(mediaId: Long): ByteSourceFacade
    fun observeImages(onChange: () -> Unit): ObserverRegistration
}

internal interface LumaDecoderFacade {
    suspend fun decode(mediaId: Long, targetLongestEdge: Int): LumaFrame
}

private class AndroidContentResolverFacade(
    private val contentResolver: ContentResolver,
) : ContentResolverFacade {
    override fun queryImages(query: MediaStoreQuerySpec): ImageCursorFacade {
        val cursor = checkNotNull(
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                query.projection.toTypedArray(),
                query.selection,
                query.selectionArguments.toTypedArray(),
                query.sortOrder,
            ),
        ) { "MediaStore image query returned no cursor" }
        return AndroidImageCursor(cursor)
    }

    override fun openOriginal(mediaId: Long): ByteSourceFacade {
        val stream = checkNotNull(contentResolver.openInputStream(mediaUri(mediaId))) {
            "MediaStore returned no original stream for asset"
        }
        return InputStreamByteSource(stream)
    }

    override fun observeImages(onChange: () -> Unit): ObserverRegistration {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        return ObserverRegistration { contentResolver.unregisterContentObserver(observer) }
    }
}

private class AndroidImageCursor(private val cursor: Cursor) : ImageCursorFacade {
    override fun moveToNext(): Boolean = cursor.moveToNext()

    override fun currentRow(): MediaImageRow = AndroidCursorRowValues(cursor).toMediaImageRow()

    override fun close() = cursor.close()
}

private class AndroidCursorRowValues(private val cursor: Cursor) : MediaStoreRowValues {
    override fun long(column: String): Long? = cursor.optionalLong(column)
}

private class InputStreamByteSource(private val input: InputStream) : ByteSourceFacade {
    override fun read(buffer: ByteArray): Int = input.read(buffer)
    override fun close() = input.close()
}

private fun Cursor.optionalLong(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

private fun Long.toIntExact(column: String): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "MediaStore $column is outside the Int range"
    }
    return toInt()
}

internal fun mediaUri(mediaId: Long): Uri =
    Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId.toString())

private const val ASSET_ID_PREFIX = "android-media:"
private const val COLUMN_ID = "_id"
private const val COLUMN_WIDTH = "width"
private const val COLUMN_HEIGHT = "height"
private const val COLUMN_SIZE = "_size"
private const val COLUMN_DATE_TAKEN = "datetaken"
private const val COLUMN_DATE_MODIFIED = "date_modified"
private const val COLUMN_ORIENTATION = "orientation"
private const val COLUMN_FAVORITE = "is_favorite"
private const val COLUMN_MEDIA_TYPE = "media_type"
private const val MEDIA_TYPE_IMAGE = 1

private val mediaImageOrder = compareByDescending<MediaImageRow> {
    it.capturedAtMillis ?: Long.MIN_VALUE
}.thenBy(MediaImageRow::id)
