package me.abuzaid.lensift.platform

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.LumaFrame
import me.abuzaid.lensift.gateway.AccessState
import me.abuzaid.lensift.gateway.LibraryChange
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPhotoLibraryGatewayTest {
    @Test
    fun `API 33 reports full access only for READ_MEDIA_IMAGES`() = runTest {
        val granted = gateway(permissionState(apiLevel = 33, readMediaImages = true))
        val denied = gateway(permissionState(apiLevel = 33))

        assertEquals(AccessState.Full, granted.currentAccess())
        assertEquals(AccessState.Denied, denied.currentAccess())
    }

    @Test
    fun `API 34 reports selected-photo grant as partial unless full access is granted`() = runTest {
        val partial = gateway(permissionState(apiLevel = 34, selectedPhotos = true))
        val full = gateway(
            permissionState(apiLevel = 34, readMediaImages = true, selectedPhotos = true),
        )

        assertEquals(AccessState.Partial, partial.currentAccess())
        assertEquals(AccessState.Full, full.currentAccess())
    }

    @Test
    fun `API 30 uses legacy external-storage access`() = runTest {
        assertEquals(
            AccessState.Full,
            gateway(permissionState(apiLevel = 30, readExternalStorage = true)).currentAccess(),
        )
        assertEquals(AccessState.Denied, gateway(permissionState(apiLevel = 30)).currentAccess())
    }

    @Test
    fun `access is re-read instead of persisted as truth`() = runTest {
        var state = permissionState(apiLevel = 34, selectedPhotos = true)
        val adapter = AndroidPhotoLibraryGateway(
            resolver = FakeContentResolver(),
            permissionReader = PhotoPermissionReader { state },
            lumaDecoder = RecordingLumaDecoder(LumaFrame(1, 1, byteArrayOf(0))),
        )

        assertEquals(AccessState.Partial, adapter.currentAccess())
        state = permissionState(apiLevel = 34, readMediaImages = true)
        assertEquals(AccessState.Full, adapter.currentAccess())
    }

    @Test
    fun `enumeration tolerates null size and date and emits opaque identifiers`() = runTest {
        val resolver = FakeContentResolver(
            rows = listOf(imageRow(id = 41, byteCount = null, capturedAtMillis = null)),
        )

        val descriptors = gateway(resolver = resolver).enumerateAccessibleImages().toList()

        assertEquals(1, descriptors.size)
        assertEquals(AssetId("android-media:41"), descriptors.single().id)
        assertNull(descriptors.single().byteCount)
        assertNull(descriptors.single().capturedAtEpochMillis)
        assertEquals(androidMediaStoreImageQuery(), resolver.lastQuery)
    }

    @Test
    fun `production MediaStore query projects and filters the required image columns`() {
        val query = androidMediaStoreImageQuery()

        assertEquals(
            listOf(
                "_id",
                "width",
                "height",
                "_size",
                "datetaken",
                "date_modified",
                "orientation",
                "is_favorite",
            ),
            query.projection,
        )
        assertEquals("media_type = ?", query.selection)
        assertEquals(listOf("1"), query.selectionArguments)
        assertEquals("datetaken DESC, _id ASC", query.sortOrder)
    }

    @Test
    fun `production raw-row mapper uses projected columns and normalizes provider sentinels`() {
        val rawValues = MapMediaStoreRowValues(
            mapOf(
                "_id" to 84L,
                "width" to 4032L,
                "height" to 3024L,
                "_size" to -1L,
                "datetaken" to 0L,
                "date_modified" to null,
                "orientation" to -90L,
                "is_favorite" to null,
            ),
        )

        val row = rawValues.toMediaImageRow()
        val descriptor = row.toPhotoDescriptor()

        assertEquals(
            MediaImageRow(
                id = 84,
                width = 4032,
                height = 3024,
                byteCount = -1,
                capturedAtMillis = 0,
                modifiedAtSeconds = null,
                orientationDegrees = -90,
                isFavorite = null,
            ),
            row,
        )
        assertEquals(AssetId("android-media:84"), descriptor.id)
        assertNull(descriptor.byteCount)
        assertNull(descriptor.capturedAtEpochMillis)
        assertFalse(descriptor.isFavorite)
        assertEquals(
            "android-v1|w=4032|h=3024|s=null|taken=null|modified=null|orientation=270|favorite=false",
            descriptor.contentSignature,
        )
    }

    @Test
    fun `opaque IDs are the only inputs converted to MediaStore content IDs`() {
        assertEquals(AssetId("android-media:91"), 91L.toAndroidAssetId())
        assertEquals(91L, AssetId("android-media:91").toAndroidMediaId())
        assertFailsWith<IllegalArgumentException> {
            AssetId("content://media/external/images/media/91").toAndroidMediaId()
        }
    }

    @Test
    fun `enumeration has stable newest-first ordering with id tie breaker`() = runTest {
        val resolver = FakeContentResolver(
            rows = listOf(
                imageRow(id = 9, capturedAtMillis = null),
                imageRow(id = 7, capturedAtMillis = 2_000),
                imageRow(id = 4, capturedAtMillis = 2_000),
                imageRow(id = 1, capturedAtMillis = 3_000),
            ),
        )

        val ids = gateway(resolver = resolver).enumerateAccessibleImages().toList().map { it.id.value }

        assertEquals(listOf("android-media:1", "android-media:4", "android-media:7", "android-media:9"), ids)
        assertEquals("datetaken DESC, _id ASC", resolver.lastQuery!!.sortOrder)
    }

    @Test
    fun `signature is stable for equal normalized metadata and changes with content metadata`() = runTest {
        val base = imageRow(
            id = 7,
            width = 400,
            height = 300,
            byteCount = 12_345,
            capturedAtMillis = 99_000,
            modifiedAtSeconds = 120,
            orientationDegrees = 0,
            isFavorite = true,
        )
        val resolver = FakeContentResolver(rows = listOf(base))
        val adapter = gateway(resolver = resolver)
        val first = adapter.enumerateAccessibleImages().toList().single().contentSignature
        resolver.rows = listOf(base.copy())
        val stable = adapter.enumerateAccessibleImages().toList().single().contentSignature
        resolver.rows = listOf(base.copy(orientationDegrees = 90))
        val rotated = adapter.enumerateAccessibleImages().toList().single().contentSignature
        resolver.rows = listOf(base.copy(modifiedAtSeconds = 121))
        val modified = adapter.enumerateAccessibleImages().toList().single().contentSignature

        assertEquals(first, stable)
        assertNotEquals(first, rotated)
        assertNotEquals(first, modified)
        assertFalse(first.contains("/"), "signatures must not contain a file location")
    }

    @Test
    fun `provider sentinel values normalize before descriptor signature is derived`() = runTest {
        val resolver = FakeContentResolver(
            rows = listOf(
                imageRow(id = 7, byteCount = -1, capturedAtMillis = 0, modifiedAtSeconds = 0),
            ),
        )
        val adapter = gateway(resolver = resolver)
        val sentinel = adapter.enumerateAccessibleImages().toList().single()
        resolver.rows = listOf(
            imageRow(id = 7, byteCount = null, capturedAtMillis = null, modifiedAtSeconds = null),
        )
        val absent = adapter.enumerateAccessibleImages().toList().single()

        assertNull(sentinel.byteCount)
        assertNull(sentinel.capturedAtEpochMillis)
        assertEquals(absent.contentSignature, sentinel.contentSignature)
    }

    @Test
    fun `query cursor closes after success and row failure`() = runTest {
        val successful = FakeContentResolver(rows = listOf(imageRow(id = 1)))
        gateway(resolver = successful).enumerateAccessibleImages().toList()
        assertTrue(successful.lastCursor!!.closed)

        val failing = FakeContentResolver(rows = listOf(imageRow(id = 2)), failAtRow = 0)
        assertFailsWith<IllegalStateException> {
            gateway(resolver = failing).enumerateAccessibleImages().toList()
        }
        assertTrue(failing.lastCursor!!.closed)
    }

    @Test
    fun `observer emits coarse values and unregisters when collection stops`() = runTest {
        val resolver = FakeContentResolver()
        val changes = mutableListOf<LibraryChange>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gateway(resolver = resolver).observeChanges().toList(changes)
        }

        resolver.notifyChange()
        resolver.notifyChange()
        assertEquals(
            listOf<LibraryChange>(
                LibraryChange.AccessMayHaveChanged,
                LibraryChange.AccessMayHaveChanged,
            ),
            changes,
        )

        job.cancelAndJoin()
        assertEquals(1, resolver.unregisterCount)
    }

    @Test
    fun `original stream emits bounded chunks and closes after normal completion`() = runTest {
        val source = FakeByteSource(ByteArray(65_537) { (it % 251).toByte() })
        val chunks = gateway(resolver = FakeContentResolver(byteSource = source))
            .originalByteChunks(AssetId("android-media:8"))
            .toList()

        assertEquals(listOf(65_536, 1), chunks.map(ByteArray::size))
        assertTrue(source.closed)
        assertTrue(source.largestRequestedRead <= 65_536)
    }

    @Test
    fun `original stream closes after downstream cancellation and read failure`() = runTest {
        val cancelledSource = FakeByteSource(ByteArray(70_000) { 1 })
        gateway(resolver = FakeContentResolver(byteSource = cancelledSource))
            .originalByteChunks(AssetId("android-media:2"))
            .take(1)
            .toList()
        assertTrue(cancelledSource.closed)

        val failingSource = FakeByteSource(ByteArray(4), failOnRead = true)
        assertFailsWith<IllegalStateException> {
            gateway(resolver = FakeContentResolver(byteSource = failingSource))
                .originalByteChunks(AssetId("android-media:3"))
                .toList()
        }
        assertTrue(failingSource.closed)
    }

    @Test
    fun `decode validates target and delegates only opaque media id`() = runTest {
        val decoder = RecordingLumaDecoder(LumaFrame(2, 1, byteArrayOf(1, 2)))
        val adapter = gateway(decoder = decoder)

        val frame = adapter.decodeLuma(AssetId("android-media:55"), targetLongestEdge = 256)

        assertEquals(2, frame.width)
        assertEquals(55L to 256, decoder.lastRequest)
        assertFailsWith<IllegalArgumentException> {
            adapter.decodeLuma(AssetId("android-media:55"), targetLongestEdge = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.decodeLuma(AssetId("not-an-android-id"), targetLongestEdge = 256)
        }
    }

    @Test
    fun `decode caps caller target at shared 512 pixel boundary`() = runTest {
        val decoder = RecordingLumaDecoder(LumaFrame(1, 1, byteArrayOf(0)))

        gateway(decoder = decoder).decodeLuma(
            AssetId("android-media:12"),
            targetLongestEdge = 1_024,
        )

        assertEquals(12L to 512, decoder.lastRequest)
    }

    @Test
    fun `luma decoder converts oriented bounded ARGB output to eight-bit luminance`() = runTest {
        val decode = FakeImageDecodeFacade(
            DecodedArgbImage(
                width = 1,
                height = 2,
                pixels = intArrayOf(0xffff0000.toInt(), 0xffffffff.toInt()),
            ),
        )
        val frame = AndroidLumaDecoder(decode).decode(mediaId = 3, targetLongestEdge = 2)

        assertEquals(1, frame.width)
        assertEquals(2, frame.height)
        assertContentEquals(byteArrayOf(77, 255.toByte()), frame.pixels)
        assertEquals(3L to 2, decode.lastRequest)
    }

    @Test
    fun `luma decoder rejects invalid targets and oversized decoder results`() = runTest {
        val oversized = AndroidLumaDecoder(
            FakeImageDecodeFacade(DecodedArgbImage(513, 1, IntArray(513))),
        )

        assertFailsWith<IllegalArgumentException> { oversized.decode(1, 0) }
        assertFailsWith<IllegalStateException> { oversized.decode(1, 512) }
    }

    private fun gateway(
        permissions: PhotoPermissionState = permissionState(apiLevel = 34, readMediaImages = true),
        resolver: FakeContentResolver = FakeContentResolver(),
        decoder: LumaDecoderFacade = RecordingLumaDecoder(LumaFrame(1, 1, byteArrayOf(0))),
    ): AndroidPhotoLibraryGateway = AndroidPhotoLibraryGateway(
        resolver = resolver,
        permissionState = permissions,
        lumaDecoder = decoder,
    )
}

private fun permissionState(
    apiLevel: Int,
    readMediaImages: Boolean = false,
    selectedPhotos: Boolean = false,
    readExternalStorage: Boolean = false,
) = PhotoPermissionState(
    apiLevel = apiLevel,
    hasReadMediaImages = readMediaImages,
    hasSelectedPhotos = selectedPhotos,
    hasReadExternalStorage = readExternalStorage,
)

private fun imageRow(
    id: Long,
    width: Int = 100,
    height: Int = 80,
    byteCount: Long? = 1_000,
    capturedAtMillis: Long? = 1_000,
    modifiedAtSeconds: Long? = 2,
    orientationDegrees: Int = 0,
    isFavorite: Boolean? = false,
) = MediaImageRow(
    id = id,
    width = width,
    height = height,
    byteCount = byteCount,
    capturedAtMillis = capturedAtMillis,
    modifiedAtSeconds = modifiedAtSeconds,
    orientationDegrees = orientationDegrees,
    isFavorite = isFavorite,
)

private class FakeContentResolver(
    var rows: List<MediaImageRow> = emptyList(),
    private val failAtRow: Int? = null,
    private val byteSource: ByteSourceFacade = FakeByteSource(ByteArray(0)),
) : ContentResolverFacade {
    var lastQuery: MediaStoreQuerySpec? = null
    var lastCursor: FakeImageCursor? = null
    var unregisterCount = 0
    private var observer: (() -> Unit)? = null

    override fun queryImages(query: MediaStoreQuerySpec): ImageCursorFacade {
        lastQuery = query
        return FakeImageCursor(rows, failAtRow).also { lastCursor = it }
    }

    override fun openOriginal(mediaId: Long): ByteSourceFacade = byteSource

    override fun observeImages(onChange: () -> Unit): ObserverRegistration {
        observer = onChange
        return ObserverRegistration {
            observer = null
            unregisterCount++
        }
    }

    fun notifyChange() = observer?.invoke() ?: Unit
}

private class MapMediaStoreRowValues(
    private val values: Map<String, Long?>,
) : MediaStoreRowValues {
    override fun long(column: String): Long? = values[column]
}

private class FakeImageCursor(
    private val rows: List<MediaImageRow>,
    private val failAtRow: Int?,
) : ImageCursorFacade {
    private var index = -1
    var closed = false

    override fun moveToNext(): Boolean = (++index < rows.size)

    override fun currentRow(): MediaImageRow {
        if (index == failAtRow) error("row failure")
        return rows[index]
    }

    override fun close() {
        closed = true
    }
}

private class FakeByteSource(
    private val bytes: ByteArray,
    private val failOnRead: Boolean = false,
) : ByteSourceFacade {
    private var offset = 0
    var closed = false
    var largestRequestedRead = 0

    override fun read(buffer: ByteArray): Int {
        if (failOnRead) error("read failure")
        largestRequestedRead = maxOf(largestRequestedRead, buffer.size)
        if (offset == bytes.size) return -1
        val count = minOf(buffer.size, bytes.size - offset)
        bytes.copyInto(buffer, endIndex = offset + count, startIndex = offset)
        offset += count
        return count
    }

    override fun close() {
        closed = true
    }
}

private class RecordingLumaDecoder(private val result: LumaFrame) : LumaDecoderFacade {
    var lastRequest: Pair<Long, Int>? = null

    override suspend fun decode(mediaId: Long, targetLongestEdge: Int): LumaFrame {
        lastRequest = mediaId to targetLongestEdge
        return result
    }
}

private class FakeImageDecodeFacade(private val result: DecodedArgbImage) : ImageDecodeFacade {
    var lastRequest: Pair<Long, Int>? = null

    override fun decode(mediaId: Long, targetLongestEdge: Int): DecodedArgbImage {
        lastRequest = mediaId to targetLongestEdge
        return result
    }
}
