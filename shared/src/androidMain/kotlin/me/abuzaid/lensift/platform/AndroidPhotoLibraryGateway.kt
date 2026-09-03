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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.awaitClose
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
        resolver.queryImages(defaultQuery).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                cursor.currentRow().takeIf { it.width > 0 && it.height > 0 }?.let(rows::add)
            }
        }

        rows.sortedWith(mediaImageOrder).forEach { row ->
            currentCoroutineContext().ensureActive()
            emit(row.toDescriptor())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun decodeLuma(assetId: AssetId, targetLongestEdge: Int): LumaFrame {
        require(targetLongestEdge > 0) { "Target longest edge must be positive" }
        return lumaDecoder.decode(assetId.mediaId(), minOf(targetLongestEdge, MAX_LUMA_EDGE))
    }

    override fun originalByteChunks(assetId: AssetId): Flow<ByteArray> = flow {
        val source = resolver.openOriginal(assetId.mediaId())
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

    override fun observeChanges(): Flow<LibraryChange> = callbackFlow {
        val registration = resolver.observeImages {
            trySend(LibraryChange.AccessMayHaveChanged)
        }
        awaitClose(registration::close)
    }

    private fun MediaImageRow.toDescriptor(): PhotoDescriptor {
        val normalizedByteCount = byteCount?.takeIf { it >= 0 }
        val normalizedCapturedAt = capturedAtMillis?.takeIf { it > 0 }
        val normalizedModifiedAt = modifiedAtSeconds?.takeIf { it > 0 }
        val favorite = isFavorite == true
        return PhotoDescriptor(
            id = AssetId("$ASSET_ID_PREFIX$id"),
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

    private val MediaImageRow.normalizedOrientation: Int
        get() = ((orientationDegrees % 360) + 360) % 360

    private fun AssetId.mediaId(): Long {
        require(value.startsWith(ASSET_ID_PREFIX)) { "Asset ID does not belong to Android MediaStore" }
        return value.removePrefix(ASSET_ID_PREFIX).toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("Android MediaStore asset ID is malformed")
    }

    private companion object {
        const val ASSET_ID_PREFIX = "android-media:"
        const val ORIGINAL_CHUNK_BYTES = 64 * 1024
        const val MAX_LUMA_EDGE = 512

        val readableAccessStates = setOf(AccessState.Full, AccessState.Partial)
        val defaultQuery = MediaStoreQuery(
            columns = MediaImageColumn.entries,
            stillImagesOnly = true,
            sort = MediaStoreSort.NewestFirstThenId,
        )
        val mediaImageOrder = compareByDescending<MediaImageRow> {
            it.capturedAtMillis ?: Long.MIN_VALUE
        }.thenBy(MediaImageRow::id)
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

internal enum class MediaImageColumn {
    Id,
    Width,
    Height,
    Size,
    DateTaken,
    DateModified,
    Orientation,
    Favorite,
}

internal enum class MediaStoreSort { NewestFirstThenId }

internal data class MediaStoreQuery(
    val columns: List<MediaImageColumn>,
    val stillImagesOnly: Boolean,
    val sort: MediaStoreSort,
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
    fun queryImages(query: MediaStoreQuery): ImageCursorFacade
    fun openOriginal(mediaId: Long): ByteSourceFacade
    fun observeImages(onChange: () -> Unit): ObserverRegistration
}

internal interface LumaDecoderFacade {
    suspend fun decode(mediaId: Long, targetLongestEdge: Int): LumaFrame
}

private class AndroidContentResolverFacade(
    private val contentResolver: ContentResolver,
) : ContentResolverFacade {
    override fun queryImages(query: MediaStoreQuery): ImageCursorFacade {
        val projection = query.columns.map(MediaImageColumn::platformName).toTypedArray()
        val selection = if (query.stillImagesOnly) {
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        } else {
            null
        }
        val selectionArgs = if (query.stillImagesOnly) {
            arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
        } else {
            null
        }
        val sortOrder = when (query.sort) {
            MediaStoreSort.NewestFirstThenId ->
                "${MediaStore.Images.ImageColumns.DATE_TAKEN} DESC, ${MediaStore.Images.ImageColumns._ID} ASC"
        }
        val cursor = checkNotNull(
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder,
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

    override fun currentRow(): MediaImageRow = MediaImageRow(
        id = cursor.requiredLong(MediaStore.Images.ImageColumns._ID),
        width = cursor.requiredInt(MediaStore.Images.ImageColumns.WIDTH),
        height = cursor.requiredInt(MediaStore.Images.ImageColumns.HEIGHT),
        byteCount = cursor.optionalLong(MediaStore.Images.ImageColumns.SIZE),
        capturedAtMillis = cursor.optionalLong(MediaStore.Images.ImageColumns.DATE_TAKEN),
        modifiedAtSeconds = cursor.optionalLong(MediaStore.Images.ImageColumns.DATE_MODIFIED),
        orientationDegrees = cursor.optionalInt(MediaStore.Images.ImageColumns.ORIENTATION) ?: 0,
        isFavorite = cursor.optionalLong(MediaStore.MediaColumns.IS_FAVORITE)?.let { it != 0L },
    )

    override fun close() = cursor.close()
}

private class InputStreamByteSource(private val input: InputStream) : ByteSourceFacade {
    override fun read(buffer: ByteArray): Int = input.read(buffer)
    override fun close() = input.close()
}

private fun Cursor.requiredLong(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.requiredInt(column: String): Int = getInt(getColumnIndexOrThrow(column))

private fun Cursor.optionalLong(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

private fun Cursor.optionalInt(column: String): Int? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getInt(index)
}

private val MediaImageColumn.platformName: String
    get() = when (this) {
        MediaImageColumn.Id -> MediaStore.Images.ImageColumns._ID
        MediaImageColumn.Width -> MediaStore.Images.ImageColumns.WIDTH
        MediaImageColumn.Height -> MediaStore.Images.ImageColumns.HEIGHT
        MediaImageColumn.Size -> MediaStore.Images.ImageColumns.SIZE
        MediaImageColumn.DateTaken -> MediaStore.Images.ImageColumns.DATE_TAKEN
        MediaImageColumn.DateModified -> MediaStore.Images.ImageColumns.DATE_MODIFIED
        MediaImageColumn.Orientation -> MediaStore.Images.ImageColumns.ORIENTATION
        MediaImageColumn.Favorite -> MediaStore.MediaColumns.IS_FAVORITE
    }

internal fun mediaUri(mediaId: Long): Uri =
    Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId.toString())
