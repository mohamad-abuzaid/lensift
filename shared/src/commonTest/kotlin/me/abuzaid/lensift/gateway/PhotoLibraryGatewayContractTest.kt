package me.abuzaid.lensift.gateway

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.LumaFrame
import me.abuzaid.lensift.domain.PhotoDescriptor
import me.abuzaid.lensift.scan.ReviewTotals
import me.abuzaid.lensift.scan.ScanProgress
import me.abuzaid.lensift.scan.ScanState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class PhotoLibraryGatewayContractTest {
    @Test
    fun enumerationPreservesOpaqueIdsAndUsesTheDefaultBoundedLumaDecode() = runBlocking {
        val library = FakePhotoLibraryGateway()

        val descriptors = library.enumerateAccessibleImages().toList()
        val frame = library.decodeLuma(descriptors.first().id)

        assertEquals(listOf(AssetId("ph://A/opaque"), AssetId("content://media/external/images/media/42")), descriptors.map(PhotoDescriptor::id))
        assertEquals(512, frame.width)
        assertEquals(1, frame.height)
        assertEquals(512, frame.pixels.size)
    }

    @Test
    fun accessCanExpandFromPartialToFullWithoutReconstructingTheGateway() = runBlocking {
        val library = FakePhotoLibraryGateway()

        assertEquals(AccessState.Partial, library.currentAccess())
        library.grantFullAccess()

        assertEquals(AccessState.Full, library.currentAccess())
    }

    @Test
    fun byteChunksAndLibraryChangesRemainAvailableThroughTheCommonBoundary() = runBlocking {
        val library = FakePhotoLibraryGateway()
        val change = async(start = CoroutineStart.UNDISPATCHED) { library.observeChanges().first() }

        library.emitChange(LibraryChange.Changed(setOf(AssetId("ph://A/opaque"))))

        assertEquals(
            listOf(listOf<Byte>(1, 2), listOf<Byte>(3)),
            library.originalByteChunks(AssetId("ph://A/opaque")).toList().map(ByteArray::toList),
        )
        assertEquals(LibraryChange.Changed(setOf(AssetId("ph://A/opaque"))), change.await())
    }

    @Test
    fun changedIdsAreOwnedAndScanStatesExposeOnlyValidActiveProgress() {
        val mutableIds = mutableSetOf(AssetId("first"))
        val changed = LibraryChange.Changed(mutableIds)
        mutableIds += AssetId("later")

        assertEquals(setOf(AssetId("first")), changed.assetIds)

        val active = listOf(
            ScanState.Indexing(ScanProgress(completed = 0, total = 2)),
            ScanState.Analyzing(ScanProgress(completed = 1, total = 2)),
            ScanState.Grouping(ScanProgress(completed = 2, total = 2)),
            ScanState.Pausing(ScanProgress(completed = 1, total = 2)),
        )

        active.forEach { state ->
            assertTrue(state.isActive)
            assertTrue(state.progress.completed in 0..state.progress.total)
        }
        assertFalse(ScanState.Paused(ScanProgress(completed = 1, total = 2)).isActive)
        assertFalse(ScanState.Ready(ReviewTotals(exactCount = 1, nearCount = 2, blurCount = 3), 4_096).isActive)

        try {
            ScanProgress(completed = 3, total = 2)
            fail("Expected invalid progress to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: every progress-bearing state has valid counts.
        }
    }

    private class FakePhotoLibraryGateway : PhotoLibraryGateway {
        private var access = AccessState.Partial
        private val changes = MutableSharedFlow<LibraryChange>(extraBufferCapacity = 1)
        private val descriptors = listOf(
            descriptor("ph://A/opaque", "ios-signature"),
            descriptor("content://media/external/images/media/42", "android-signature"),
        )

        override suspend fun currentAccess(): AccessState = access

        override fun enumerateAccessibleImages(): Flow<PhotoDescriptor> = flowOf(*descriptors.toTypedArray())

        override suspend fun decodeLuma(assetId: AssetId, targetLongestEdge: Int): LumaFrame {
            require(assetId in descriptors.map(PhotoDescriptor::id))
            require(targetLongestEdge == 512) { "The shared default must bound luma decodes to 512 px" }
            return LumaFrame(width = 512, height = 1, pixels = ByteArray(512) { 127.toByte() })
        }

        override fun originalByteChunks(assetId: AssetId): Flow<ByteArray> {
            require(assetId == AssetId("ph://A/opaque"))
            return flowOf(byteArrayOf(1, 2), byteArrayOf(3))
        }

        override fun observeChanges(): Flow<LibraryChange> = changes.asSharedFlow()

        fun grantFullAccess() {
            access = AccessState.Full
        }

        fun emitChange(change: LibraryChange) {
            check(changes.tryEmit(change))
        }

        private fun descriptor(id: String, signature: String): PhotoDescriptor = PhotoDescriptor(
            id = AssetId(id),
            contentSignature = signature,
            width = 1_024,
            height = 768,
            byteCount = 123,
            capturedAtEpochMillis = 42,
            isFavorite = false,
            isEdited = false,
        )
    }
}
