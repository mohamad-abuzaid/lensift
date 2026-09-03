package me.abuzaid.lensift.platform

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.abuzaid.lensift.domain.AssetId
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
class IosPhotoLibraryGatewayTest {
    @Test
    fun `all PhotoKit authorization states map to the shared access model`() = runTest {
        val expected = listOf(
            PhotoKitAuthorizationStatus.Authorized to AccessState.Full,
            PhotoKitAuthorizationStatus.Limited to AccessState.Partial,
            PhotoKitAuthorizationStatus.Denied to AccessState.Denied,
            PhotoKitAuthorizationStatus.Restricted to AccessState.Restricted,
            PhotoKitAuthorizationStatus.NotDetermined to AccessState.NotDetermined,
        )

        expected.forEach { (native, shared) ->
            assertEquals(shared, gateway(FakePhotoKitFacade(authorization = native)).currentAccess())
        }
    }

    @Test
    fun `current access reads live PhotoKit status on every call`() = runTest {
        val facade = FakePhotoKitFacade(authorization = PhotoKitAuthorizationStatus.Limited)
        val adapter = gateway(facade)

        assertEquals(AccessState.Partial, adapter.currentAccess())
        facade.authorization = PhotoKitAuthorizationStatus.Authorized
        assertEquals(AccessState.Full, adapter.currentAccess())
        assertEquals(2, facade.authorizationReads)
    }

    @Test
    fun `production query requests image assets and row mapping emits stable opaque descriptors`() = runTest {
        val facade = FakePhotoKitFacade(
            rows = listOf(
                imageRow(identifier = "z/opaque", capturedAtMillis = null, byteCount = null),
                imageRow(identifier = "b/opaque", capturedAtMillis = 2_000, modifiedAtMillis = 3_000),
                imageRow(identifier = "a/opaque", capturedAtMillis = 2_000, modifiedAtMillis = 3_000),
                imageRow(identifier = "ignored", width = 0),
            ),
        )

        val descriptors = gateway(facade).enumerateAccessibleImages().toList()

        assertEquals(3, descriptors.size)
        assertEquals(
            listOf(AssetId("ios-photo:a/opaque"), AssetId("ios-photo:b/opaque"), AssetId("ios-photo:z/opaque")),
            descriptors.map { it.id },
        )
        assertNull(descriptors.last().byteCount)
        assertNull(descriptors.last().capturedAtEpochMillis)
        assertEquals(PhotoKitMediaType.Image, facade.lastQuery?.mediaType)
        assertEquals(
            listOf(
                PhotoKitSortDescriptor(PhotoKitSortKey.CreationDate, ascending = false),
                PhotoKitSortDescriptor(PhotoKitSortKey.LocalIdentifier, ascending = true),
            ),
            facade.lastQuery?.sortDescriptors,
        )
    }

    @Test
    fun `descriptor signature is deterministic versioned and changes with source metadata`() {
        val original = imageRow(
            identifier = "same",
            width = 4_032,
            height = 3_024,
            byteCount = 99,
            capturedAtMillis = 1_000,
            modifiedAtMillis = 2_000,
            isFavorite = true,
            isEdited = true,
        ).toPhotoDescriptor()
        val repeated = imageRow(
            identifier = "same",
            width = 4_032,
            height = 3_024,
            byteCount = 99,
            capturedAtMillis = 1_000,
            modifiedAtMillis = 2_000,
            isFavorite = true,
            isEdited = true,
        ).toPhotoDescriptor()
        val modified = imageRow(
            identifier = "same",
            width = 4_032,
            height = 3_024,
            byteCount = 99,
            capturedAtMillis = 1_000,
            modifiedAtMillis = 2_001,
            isFavorite = true,
            isEdited = true,
        ).toPhotoDescriptor()

        assertEquals(original.contentSignature, repeated.contentSignature)
        assertTrue(original.contentSignature.startsWith("ios-v1|"))
        assertNotEquals(original.contentSignature, modified.contentSignature)
        assertEquals(true, original.isFavorite)
        assertEquals(true, original.isEdited)
    }

    @Test
    fun `enumeration is empty without readable authorization`() = runTest {
        val facade = FakePhotoKitFacade(authorization = PhotoKitAuthorizationStatus.Denied)

        assertTrue(gateway(facade).enumerateAccessibleImages().toList().isEmpty())
        assertNull(facade.lastQuery)
    }

    @Test
    fun `luma decode is capped local only orientation normalized and produces owned 8 bit pixels`() = runTest {
        val facade = FakePhotoKitFacade(
            decodedImage = DecodedRgbaImage(
                width = 2,
                height = 1,
                rgba = byteArrayOf(
                    255.toByte(), 0, 0, 255.toByte(),
                    0, 255.toByte(), 0, 255.toByte(),
                ),
            ),
        )

        val frame = gateway(facade).decodeLuma(AssetId("ios-photo:opaque"), 900)

        assertEquals("opaque", facade.lastDecodeIdentifier)
        assertEquals(
            IosImageRequest(
                targetLongestEdge = 512,
                networkAccessAllowed = false,
                normalizeOrientation = true,
            ),
            facade.lastImageRequest,
        )
        assertEquals(2, frame.width)
        assertEquals(1, frame.height)
        assertContentEquals(byteArrayOf(77.toByte(), 149.toByte()), frame.pixels)
        val leaked = frame.pixels
        leaked[0] = 0
        assertEquals(77.toByte(), frame.pixels[0])
    }

    @Test
    fun `invalid or oversized decode results fail at the adapter boundary`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            gateway(FakePhotoKitFacade()).decodeLuma(AssetId("ios-photo:a"), 0)
        }

        val oversized = gateway(
            FakePhotoKitFacade(
                decodedImage = DecodedRgbaImage(513, 1, ByteArray(513 * 4)),
            ),
        )
        assertFailsWith<IllegalStateException> {
            oversized.decodeLuma(AssetId("ios-photo:a"), 512)
        }
    }

    @Test
    fun `iCloud-only decode throws recoverable unavailable error without enabling network`() = runTest {
        val facade = FakePhotoKitFacade(decodedImage = null)

        val error = assertFailsWith<PhotoAssetUnavailableException> {
            gateway(facade).decodeLuma(AssetId("ios-photo:cloud-only"), 512)
        }

        assertEquals(AssetId("ios-photo:cloud-only"), error.assetId)
        assertFalse(requireNotNull(facade.lastImageRequest).networkAccessAllowed)
    }

    @Test
    fun `original bytes prefer full-size photo then photo and copy bounded chunks`() = runTest {
        val mutableChunk = byteArrayOf(1, 2, 3)
        val facade = FakePhotoKitFacade(originalChunks = listOf(mutableChunk, byteArrayOf(4)))

        val chunks = gateway(facade).originalByteChunks(AssetId("ios-photo:source")).toList()
        mutableChunk[0] = 9

        assertEquals("source", facade.lastOriginalIdentifier)
        assertEquals(
            IosOriginalRequest(
                preferredResourceTypes = listOf(
                    PhotoKitResourceType.FullSizePhoto,
                    PhotoKitResourceType.Photo,
                ),
                networkAccessAllowed = false,
                maximumChunkBytes = 64 * 1024,
            ),
            facade.lastOriginalRequest,
        )
        assertContentEquals(byteArrayOf(1, 2, 3), chunks.first())
        assertContentEquals(byteArrayOf(4), chunks.last())
    }

    @Test
    fun `original streaming reports unavailable resources and cancels native request with collection`() = runTest {
        val unavailable = FakePhotoKitFacade(originalError = PhotoKitResourceUnavailableException("missing"))
        assertFailsWith<PhotoKitResourceUnavailableException> {
            gateway(unavailable).originalByteChunks(AssetId("ios-photo:missing")).toList()
        }

        val suspended = FakePhotoKitFacade(holdOriginalOpen = true)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            gateway(suspended).originalByteChunks(AssetId("ios-photo:held")).toList()
        }
        assertEquals(1, suspended.openOriginalRequests)
        job.cancelAndJoin()
        assertEquals(1, suspended.cancelledOriginalRequests)
    }

    @Test
    fun `change observation maps specific identifiers and always unregisters`() = runTest {
        val facade = FakePhotoKitFacade()
        val changes = mutableListOf<LibraryChange>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            gateway(facade).observeChanges().take(2).toList(changes)
        }
        assertEquals(1, facade.activeObservers)

        facade.emitChange(setOf("b", "a", "a"))
        facade.emitChange(null)
        job.join()

        assertEquals(
            LibraryChange.Changed(setOf(AssetId("ios-photo:a"), AssetId("ios-photo:b"))),
            changes[0],
        )
        assertEquals(LibraryChange.AccessMayHaveChanged, changes[1])
        assertEquals(0, facade.activeObservers)
        assertEquals(1, facade.closedObservers)
    }

    @Test
    fun `each change collector owns exactly one observer until cancellation`() = runTest {
        val facade = FakePhotoKitFacade()
        val adapter = gateway(facade)
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { adapter.observeChanges().toList() }
        val second = launch(UnconfinedTestDispatcher(testScheduler)) { adapter.observeChanges().toList() }

        assertEquals(2, facade.activeObservers)
        first.cancelAndJoin()
        assertEquals(1, facade.activeObservers)
        second.cancelAndJoin()
        assertEquals(0, facade.activeObservers)
        assertEquals(2, facade.closedObservers)
    }

    private fun gateway(facade: FakePhotoKitFacade): IosPhotoLibraryGateway =
        IosPhotoLibraryGateway(facade, IosLumaDecoder(facade))
}

private class FakePhotoKitFacade(
    var authorization: PhotoKitAuthorizationStatus = PhotoKitAuthorizationStatus.Authorized,
    private val rows: List<PhotoKitImageRow> = emptyList(),
    private val decodedImage: DecodedRgbaImage? = DecodedRgbaImage(1, 1, byteArrayOf(0, 0, 0, 0)),
    private val originalChunks: List<ByteArray> = emptyList(),
    private val originalError: Throwable? = null,
    private val holdOriginalOpen: Boolean = false,
) : PhotoKitFacade {
    var authorizationReads = 0
    var lastQuery: PhotoKitImageQuery? = null
    var lastDecodeIdentifier: String? = null
    var lastImageRequest: IosImageRequest? = null
    var lastOriginalIdentifier: String? = null
    var lastOriginalRequest: IosOriginalRequest? = null
    var openOriginalRequests = 0
    var cancelledOriginalRequests = 0
    var activeObservers = 0
    var closedObservers = 0
    private val observers = mutableListOf<(Set<String>?) -> Unit>()

    override fun currentAuthorization(): PhotoKitAuthorizationStatus {
        authorizationReads++
        return authorization
    }

    override fun enumerateImages(query: PhotoKitImageQuery): List<PhotoKitImageRow> {
        lastQuery = query
        return rows
    }

    override suspend fun requestDecodedImage(
        localIdentifier: String,
        request: IosImageRequest,
    ): DecodedRgbaImage? {
        lastDecodeIdentifier = localIdentifier
        lastImageRequest = request
        return decodedImage
    }

    override fun requestOriginalData(
        localIdentifier: String,
        request: IosOriginalRequest,
        onData: (ByteArray) -> Unit,
        onComplete: (Throwable?) -> Unit,
    ): PhotoKitRequest {
        lastOriginalIdentifier = localIdentifier
        lastOriginalRequest = request
        openOriginalRequests++
        if (!holdOriginalOpen) {
            originalChunks.forEach(onData)
            onComplete(originalError)
        }
        return PhotoKitRequest {
            cancelledOriginalRequests++
            openOriginalRequests--
        }
    }

    override fun observeImageChanges(onChange: (Set<String>?) -> Unit): PhotoKitObservation {
        observers += onChange
        activeObservers++
        return PhotoKitObservation {
            if (observers.remove(onChange)) {
                activeObservers--
                closedObservers++
            }
        }
    }

    fun emitChange(assetIds: Set<String>?) {
        observers.toList().forEach { it(assetIds) }
    }
}

private fun imageRow(
    identifier: String,
    width: Int = 4_032,
    height: Int = 3_024,
    byteCount: Long? = 1_024,
    capturedAtMillis: Long? = 1_000,
    modifiedAtMillis: Long? = 2_000,
    isFavorite: Boolean = false,
    isEdited: Boolean? = false,
) = PhotoKitImageRow(
    localIdentifier = identifier,
    width = width,
    height = height,
    byteCount = byteCount,
    capturedAtEpochMillis = capturedAtMillis,
    modifiedAtEpochMillis = modifiedAtMillis,
    isFavorite = isFavorite,
    isEdited = isEdited,
)
