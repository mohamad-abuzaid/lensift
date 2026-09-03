package me.abuzaid.lensift.scan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.analysis.BlurVerdict
import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.BlurPolicy
import me.abuzaid.lensift.domain.LumaFrame
import me.abuzaid.lensift.domain.PhotoDescriptor
import me.abuzaid.lensift.domain.Sensitivity
import me.abuzaid.lensift.findings.FindingAssembler
import me.abuzaid.lensift.gateway.AccessState
import me.abuzaid.lensift.gateway.LibraryChange
import me.abuzaid.lensift.index.AnalysisRecord
import me.abuzaid.lensift.test.FakePhotoLibraryGateway
import me.abuzaid.lensift.test.InMemoryScanIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryReconcilerTest {
    @Test
    fun burstIsDebouncedAndChangedIdsAreReanalyzedDespiteStaleSignatures() = runTest {
        val changedA = descriptor("changed-a")
        val changedB = descriptor("changed-b")
        val added = descriptor("added")
        val removed = descriptor("removed")
        val library = FakePhotoLibraryGateway(descriptors = listOf(changedA, changedB, added))
        val index = InMemoryScanIndex(
            listOf(record(changedA), record(changedB), record(removed)),
        )
        val coordinator = coordinator(library, index)
        val reconciler = LibraryReconciler(library, index, coordinator, this) { policy() }

        reconciler.start()
        runCurrent()
        library.emitChange(LibraryChange.Changed(setOf(changedA.id)))
        advanceTimeBy(299)
        runCurrent()
        assertEquals(0, library.accessCalls)

        library.emitChange(LibraryChange.Changed(setOf(changedB.id)))
        advanceTimeBy(299)
        runCurrent()
        assertEquals(0, library.accessCalls)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(
            setOf(changedA.id, changedB.id, added.id),
            index.currentRecords().mapTo(mutableSetOf()) { it.descriptor.id },
        )
        assertEquals(listOf(added.id, changedA.id, changedB.id), library.decodedAssetIds.sortedBy(AssetId::value))
        assertEquals(2, library.accessCalls)
        assertEquals(2, library.enumerationCalls)
        reconciler.stop()
    }

    @Test
    fun partialAccessShrinkPurgesHiddenRowsAndLaterExpansionAnalyzesAddedRows() = runTest {
        val visible = descriptor("visible")
        val hidden = descriptor("hidden")
        val added = descriptor("added")
        val library = FakePhotoLibraryGateway(AccessState.Partial, listOf(visible))
        val index = InMemoryScanIndex(listOf(record(visible), record(hidden)))
        val reconciler = LibraryReconciler(library, index, coordinator(library, index), this) { policy() }
        reconciler.start()
        runCurrent()

        library.emitChange(LibraryChange.AccessMayHaveChanged)
        advanceTimeBy(300)
        runCurrent()

        assertEquals(setOf(visible.id), index.currentRecords().mapTo(mutableSetOf()) { it.descriptor.id })
        assertEquals(emptyList(), library.decodedAssetIds)

        library.descriptors = listOf(visible, added)
        library.emitChange(LibraryChange.AccessMayHaveChanged)
        advanceTimeBy(300)
        runCurrent()

        assertEquals(setOf(visible.id, added.id), index.currentRecords().mapTo(mutableSetOf()) { it.descriptor.id })
        assertEquals(listOf(added.id), library.decodedAssetIds)
        reconciler.stop()
    }

    @Test
    fun unreadableAccessStatesPurgePersistenceAndObservableFindingsAndFailClosed() = runTest {
        for (access in listOf(AccessState.Denied, AccessState.Restricted, AccessState.NotDetermined)) {
            val first = descriptor("first-$access")
            val second = descriptor("second-$access")
            val records = listOf(
                record(first).copy(perceptualHash = 1, sha256 = "same"),
                record(second).copy(perceptualHash = 1, sha256 = "same"),
            )
            val library = FakePhotoLibraryGateway(descriptors = listOf(first, second))
            val index = InMemoryScanIndex(records)
            val coordinator = coordinator(library, index)
            coordinator.start(policy())
            runCurrent()
            assertEquals(1, coordinator.findings.value.exactGroups.size)

            val reconciler = LibraryReconciler(library, index, coordinator, this) { policy() }
            reconciler.start()
            runCurrent()
            library.access = access
            library.emitChange(LibraryChange.AccessMayHaveChanged)
            advanceTimeBy(300)
            runCurrent()

            assertEquals(emptyList(), index.currentRecords())
            assertEquals(emptyList(), coordinator.findings.value.exactGroups)
            assertEquals(emptyList(), coordinator.findings.value.nearGroups)
            assertEquals(emptyList(), coordinator.findings.value.blurItems)
            assertEquals(
                ScanFailureReason.AccessUnavailable,
                assertIs<ScanState.RecoverableFailure>(coordinator.state.value).reason,
            )
            reconciler.stop()
        }
    }

    @Test
    fun changeDuringActiveAnalysisWaitsForQuiescenceThenRunsOneFollowUp() = runTest {
        val asset = descriptor("asset")
        val firstDecodeStarted = CompletableDeferred<Unit>()
        val releaseFirstDecode = CompletableDeferred<Unit>()
        var decodeOrdinal = 0
        val library = FakePhotoLibraryGateway(descriptors = listOf(asset)).apply {
            decodeBehavior = {
                decodeOrdinal += 1
                if (decodeOrdinal == 1) {
                    firstDecodeStarted.complete(Unit)
                    releaseFirstDecode.await()
                }
                LumaFrame(1, 1, byteArrayOf(1))
            }
        }
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index)
        val reconciler = LibraryReconciler(library, index, coordinator, this) { policy() }
        reconciler.start()
        runCurrent()

        coordinator.start(policy())
        firstDecodeStarted.await()
        library.emitChange(LibraryChange.Changed(setOf(asset.id)))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, library.accessCalls)

        releaseFirstDecode.complete(Unit)
        runCurrent()

        assertEquals(2, library.decodeCalls)
        assertEquals(3, library.accessCalls)
        assertEquals(3, library.enumerationCalls)
        assertEquals(1, library.maximumActiveDecodes)
        reconciler.stop()
    }

    @Test
    fun changesThroughoutAnActiveScanCollapseIntoOneDirtyReconciliation() = runTest {
        val asset = descriptor("asset")
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(descriptors = listOf(asset)).apply {
            decodeBehavior = {
                decodeStarted.complete(Unit)
                releaseDecode.await()
                LumaFrame(1, 1, byteArrayOf(1))
            }
        }
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index)
        val reconciler = LibraryReconciler(library, index, coordinator, this) { policy() }
        reconciler.start()
        runCurrent()

        coordinator.start(policy())
        decodeStarted.await()
        library.emitChange(LibraryChange.AccessMayHaveChanged)
        advanceTimeBy(300)
        runCurrent()
        library.emitChange(LibraryChange.AccessMayHaveChanged)
        advanceTimeBy(500)
        runCurrent()

        releaseDecode.complete(Unit)
        runCurrent()
        advanceTimeBy(300)
        runCurrent()

        assertEquals(3, library.accessCalls)
        assertEquals(3, library.enumerationCalls)
        assertEquals(1, library.decodeCalls)
        reconciler.stop()
    }

    @Test
    fun pausedScanStormUsesOneWakeSlotAndLosslesslyInvalidatesEveryChangedId() = runTest {
        val stormAssets = List(128) { index ->
            descriptor("storm-$index").copy(byteCount = index.toLong() + 1)
        }
        val blocker = descriptor("blocker").copy(byteCount = 10_000)
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(descriptors = stormAssets + blocker).apply {
            decodeBehavior = { assetId ->
                if (assetId == blocker.id && !decodeStarted.isCompleted) {
                    decodeStarted.complete(Unit)
                    releaseDecode.await()
                }
                LumaFrame(1, 1, byteArrayOf((assetId.value.hashCode() and 0xff).toByte()))
            }
        }
        val index = InMemoryScanIndex(stormAssets.map(::record))
        val coordinator = coordinator(library, index)
        val reconciler = LibraryReconciler(library, index, coordinator, this) { policy() }
        reconciler.start()
        runCurrent()

        coordinator.start(policy())
        decodeStarted.await()
        coordinator.pause()
        releaseDecode.complete(Unit)
        runCurrent()
        assertIs<ScanState.Paused>(coordinator.state.value)

        repeat(STORM_EVENT_COUNT) { ordinal ->
            if (ordinal % 17 == 0) {
                library.emitChange(LibraryChange.AccessMayHaveChanged)
            } else {
                library.emitChange(
                    LibraryChange.Changed(setOf(stormAssets[ordinal % stormAssets.size].id)),
                )
            }
        }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, reconciler.wakeBufferCapacity)
        assertEquals(1, library.accessCalls)

        coordinator.resume()
        runCurrent()
        advanceTimeBy(300)
        runCurrent()

        assertEquals(stormAssets.mapTo(mutableSetOf()) { it.id }, index.invalidatedAssetIds)
        assertEquals(4, library.accessCalls)
        assertEquals(3, library.enumerationCalls)
        assertEquals(stormAssets.size + 1, library.decodeCalls)
        assertTrue(library.maximumActiveDecodes <= 2)
        reconciler.stop()
    }

    @Test
    fun changeDuringPausedScanWaitsUntilResumeAndNeverOverlapsTheScan() = runTest {
        val asset = descriptor("paused")
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        var decodeOrdinal = 0
        val library = FakePhotoLibraryGateway(descriptors = listOf(asset)).apply {
            decodeBehavior = {
                decodeOrdinal += 1
                if (decodeOrdinal == 1) {
                    decodeStarted.complete(Unit)
                    releaseDecode.await()
                }
                LumaFrame(1, 1, byteArrayOf(1))
            }
        }
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index)
        val reconciler = LibraryReconciler(library, index, coordinator, this) { policy() }
        reconciler.start()
        runCurrent()

        coordinator.start(policy())
        decodeStarted.await()
        coordinator.pause()
        library.emitChange(LibraryChange.Changed(setOf(asset.id)))
        advanceTimeBy(300)
        releaseDecode.complete(Unit)
        runCurrent()
        assertIs<ScanState.Paused>(coordinator.state.value)
        assertEquals(1, library.accessCalls)

        coordinator.resume()
        runCurrent()

        assertIs<ScanState.Ready>(coordinator.state.value)
        assertEquals(2, library.decodeCalls)
        assertEquals(1, library.maximumActiveDecodes)
        reconciler.stop()
    }

    @Test
    fun changeDuringReconciliationIsPreservedForOneAdditionalPass() = runTest {
        val first = descriptor("first")
        val second = descriptor("second")
        val enumerationStarted = CompletableDeferred<Unit>()
        val releaseEnumeration = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(descriptors = listOf(first)).apply {
            beforeEnumeration = {
                if (enumerationCalls == 1) {
                    enumerationStarted.complete(Unit)
                    releaseEnumeration.await()
                }
            }
        }
        val index = InMemoryScanIndex(listOf(record(first)))
        val reconciler = LibraryReconciler(library, index, coordinator(library, index), this) { policy() }
        reconciler.start()
        runCurrent()

        library.emitChange(LibraryChange.Changed(setOf(first.id)))
        advanceTimeBy(300)
        enumerationStarted.await()
        library.descriptors = listOf(first, second)
        library.emitChange(LibraryChange.Changed(setOf(second.id)))
        releaseEnumeration.complete(Unit)
        runCurrent()
        advanceTimeBy(300)
        runCurrent()

        assertEquals(4, library.accessCalls)
        assertEquals(4, library.enumerationCalls)
        assertEquals(listOf(first.id, second.id, second.id), library.decodedAssetIds)
        reconciler.stop()
    }

    @Test
    fun changesDuringFollowUpScanAreCoalescedIntoExactlyOneAdditionalFollowUp() = runTest {
        val first = descriptor("first")
        val second = descriptor("second")
        val firstDecodeStarted = CompletableDeferred<Unit>()
        val releaseFirstDecode = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(descriptors = listOf(first)).apply {
            decodeBehavior = { assetId ->
                if (assetId == first.id && !firstDecodeStarted.isCompleted) {
                    firstDecodeStarted.complete(Unit)
                    releaseFirstDecode.await()
                }
                LumaFrame(1, 1, byteArrayOf(1))
            }
        }
        val index = InMemoryScanIndex(listOf(record(first)))
        val reconciler = LibraryReconciler(library, index, coordinator(library, index), this) { policy() }
        reconciler.start()
        runCurrent()

        library.emitChange(LibraryChange.Changed(setOf(first.id)))
        advanceTimeBy(300)
        firstDecodeStarted.await()
        library.descriptors = listOf(first, second)
        library.emitChange(LibraryChange.Changed(setOf(second.id)))
        library.emitChange(LibraryChange.AccessMayHaveChanged)
        releaseFirstDecode.complete(Unit)
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertEquals(2, library.accessCalls)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(4, library.accessCalls)
        assertEquals(4, library.enumerationCalls)
        assertEquals(listOf(first.id, second.id), library.decodedAssetIds)
        reconciler.stop()
    }

    @Test
    fun followUpUsesPolicyReadAtAcceptedStart() = runTest {
        val first = descriptor("first")
        val second = descriptor("second")
        val index = InMemoryScanIndex(
            listOf(
                record(first).copy(perceptualHash = 0, sha256 = "first"),
                record(second).copy(perceptualHash = 1, sha256 = "second"),
            ),
        )
        val library = FakePhotoLibraryGateway(descriptors = listOf(first, second))
        val coordinator = coordinator(library, index)
        var currentPolicy = policy(maxDistance = 0)
        val reconciler = LibraryReconciler(library, index, coordinator, this) { currentPolicy }
        reconciler.start()
        runCurrent()

        library.emitChange(LibraryChange.AccessMayHaveChanged)
        advanceTimeBy(300)
        currentPolicy = policy(maxDistance = 1)
        runCurrent()

        assertEquals(1, coordinator.findings.value.nearGroups.size)
        reconciler.stop()
    }

    @Test
    fun terminalStateStartGateRaceCannotDropTheFollowUpRequest() = runTest {
        val asset = descriptor("asset")
        val library = FakePhotoLibraryGateway(descriptors = listOf(asset))
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index)
        val reconciler = LibraryReconciler(library, index, coordinator, this) { policy() }
        reconciler.start()
        runCurrent()
        coordinator.start(policy())

        coordinator.state.first { it is ScanState.Ready }
        library.emitChange(LibraryChange.Changed(setOf(asset.id)))
        advanceTimeBy(300)
        runCurrent()

        assertEquals(2, library.decodeCalls)
        assertEquals(3, library.enumerationCalls)
        reconciler.stop()
    }

    @Test
    fun stopAndOwnerCancellationUnregisterObserverAndLeaveNoLifecycleJobs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob()
        val lifecycleScope = CoroutineScope(owner + dispatcher)
        val library = FakePhotoLibraryGateway()
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index, lifecycleScope)
        val reconciler = LibraryReconciler(library, index, coordinator, lifecycleScope) { policy() }

        reconciler.start()
        runCurrent()
        assertEquals(1, library.observerCount)
        reconciler.stop()
        assertEquals(0, library.observerCount)
        assertTrue(owner.children.none())

        reconciler.start()
        runCurrent()
        assertEquals(1, library.observerCount)
        owner.cancel()
        runCurrent()
        assertEquals(0, library.observerCount)
        assertTrue(owner.children.none())
    }

    @Test
    fun stopDuringOwnedFollowUpCancelsScanAndObserverWithoutLeakingJobs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = SupervisorJob()
        val lifecycleScope = CoroutineScope(owner + dispatcher)
        val asset = descriptor("asset")
        val decodeStarted = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(descriptors = listOf(asset)).apply {
            decodeBehavior = {
                decodeStarted.complete(Unit)
                neverRelease.await()
                LumaFrame(1, 1, byteArrayOf(1))
            }
        }
        val index = InMemoryScanIndex(listOf(record(asset)))
        val coordinator = coordinator(library, index, lifecycleScope)
        val reconciler = LibraryReconciler(library, index, coordinator, lifecycleScope) { policy() }
        reconciler.start()
        runCurrent()

        library.emitChange(LibraryChange.Changed(setOf(asset.id)))
        advanceTimeBy(300)
        decodeStarted.await()
        reconciler.stop()

        assertEquals(0, library.observerCount)
        assertIs<ScanState.Cancelled>(coordinator.state.value)
        assertTrue(owner.children.none())
    }

    private fun TestScope.coordinator(
        library: FakePhotoLibraryGateway,
        index: InMemoryScanIndex,
        scope: CoroutineScope = this,
    ): ScanCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ScanCoordinator(
            library = library,
            index = index,
            analyzer = AssetAnalyzer(
                nowEpochMillis = { 123L },
                perceptualHash = { frame -> (frame.pixels.single().toInt() and 0xff).toLong() },
            ),
            assembler = FindingAssembler(),
            scope = scope,
            dispatchers = LensiftDispatchers(dispatcher, dispatcher),
            analyzerVersion = 1,
        )
    }

    private fun descriptor(id: String): PhotoDescriptor = PhotoDescriptor(
        id = AssetId(id),
        contentSignature = "stale-signature-$id",
        width = 100,
        height = 100,
        byteCount = 100,
        capturedAtEpochMillis = 1,
        isFavorite = false,
        isEdited = false,
    )

    private fun record(descriptor: PhotoDescriptor): AnalysisRecord = AnalysisRecord(
        descriptor = descriptor,
        analyzerVersion = 1,
        perceptualHash = 7,
        sha256 = "old-${descriptor.id.value}",
        blurEvidence = BlurEvidence(1.0, 1.0, 1.0, BlurVerdict.Inconclusive),
        analyzedAtEpochMillis = 1,
    )

    private fun policy(maxDistance: Int = 0): AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Balanced,
        maxPerceptualDistance = maxDistance,
        maxCaptureGapMillis = Long.MAX_VALUE,
        maxAspectRatioDelta = 1.0,
        blur = BlurPolicy(laplacianVarianceCeiling = 0.0, edgeDensityCeiling = 0.0),
    )

    private companion object {
        const val STORM_EVENT_COUNT = 50_000
    }
}
