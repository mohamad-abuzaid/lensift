package me.abuzaid.lensift.scan

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.abuzaid.lensift.analysis.CandidateGenerationStatus
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
import me.abuzaid.lensift.index.AnalysisRecord
import me.abuzaid.lensift.test.FakePhotoLibraryGateway
import me.abuzaid.lensift.test.InMemoryScanIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScanCoordinatorTest {
    @Test
    fun emptyLibraryProducesValidEmptySnapshotAndReadyState() = runTest {
        val library = FakePhotoLibraryGateway()
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index)

        coordinator.start(policy())
        advanceUntilIdle()

        assertEquals(1, library.accessCalls)
        assertEquals(emptySet(), index.lastPurgedAccessibleIds)
        assertEquals(CandidateGenerationStatus.Complete, coordinator.findings.value.candidateGenerationStatus)
        assertEquals(emptyList(), coordinator.findings.value.exactGroups)
        assertEquals(ScanState.Ready(ReviewTotals(0, 0, 0), 0), coordinator.state.value)
    }

    @Test
    fun partialAccessProcessesOnlyEnumeratedAssetsAndPurgesInaccessibleRows() = runTest {
        val accessible = descriptor("visible")
        val hidden = record(descriptor("hidden"), hash = 9)
        val library = FakePhotoLibraryGateway(AccessState.Partial, listOf(accessible))
        val index = InMemoryScanIndex(listOf(hidden))
        val coordinator = coordinator(library, index)

        coordinator.start(policy())
        advanceUntilIdle()

        assertEquals(setOf(accessible.id), index.lastPurgedAccessibleIds)
        assertEquals(1, library.decodeCalls)
        assertEquals(null, index.record(hidden.descriptor.id))
        assertIs<ScanState.Ready>(coordinator.state.value)
    }

    @Test
    fun lumaPipelineNeverExceedsTwoAtomicDecodes() = runTest {
        val release = MutableStateFlow(false)
        val library = FakePhotoLibraryGateway(descriptors = List(8) { descriptor("asset-$it") })
        library.decodeBehavior = { assetId ->
            release.first { it }
            luma(assetId.value.hashCode())
        }
        val coordinator = coordinator(library, InMemoryScanIndex())

        coordinator.start(policy())
        runCurrent()
        assertEquals(2, library.maximumActiveDecodes)

        release.value = true
        advanceUntilIdle()
        assertEquals(2, library.maximumActiveDecodes)
        assertIs<ScanState.Ready>(coordinator.state.value)
    }

    @Test
    fun originalPipelineNeverExceedsOneStream() = runTest {
        val library = FakePhotoLibraryGateway(descriptors = List(4) { descriptor("asset-$it", byteCount = 10) })
        library.decodeBehavior = { luma(7) }
        library.originalBehavior = { assetId ->
            flow {
                kotlinx.coroutines.yield()
                emit(assetId.value.encodeToByteArray())
            }
        }
        val coordinator = coordinator(library, InMemoryScanIndex(), analyzer(hash = 7))

        coordinator.start(policy())
        advanceUntilIdle()

        assertEquals(4, library.originalCalls)
        assertEquals(1, library.maximumActiveOriginalStreams)
        assertIs<ScanState.Ready>(coordinator.state.value)
    }

    @Test
    fun pauseBecomesPausedAfterAtomicDecodeAndResumeDoesNotDuplicateWrites() = runTest {
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(descriptors = listOf(descriptor("asset")))
        library.decodeBehavior = {
            decodeStarted.complete(Unit)
            releaseDecode.await()
            luma(1)
        }
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index)

        coordinator.start(policy())
        decodeStarted.await()
        coordinator.pause()
        assertIs<ScanState.Pausing>(coordinator.state.value)

        releaseDecode.complete(Unit)
        runCurrent()
        assertIs<ScanState.Paused>(coordinator.state.value)
        assertEquals(0, index.saveAnalysisCalls)

        coordinator.resume()
        advanceUntilIdle()
        assertIs<ScanState.Ready>(coordinator.state.value)
        assertEquals(1, index.analysisWritesByAsset[AssetId("asset")])
    }

    @Test
    fun resumeRechecksAccessInsideThePausedRunAndFailsClosedWhenRevoked() = runTest {
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(descriptors = listOf(descriptor("asset")))
        library.decodeBehavior = {
            decodeStarted.complete(Unit)
            releaseDecode.await()
            luma(1)
        }
        val coordinator = coordinator(library, InMemoryScanIndex())

        coordinator.start(policy())
        decodeStarted.await()
        coordinator.pause()
        releaseDecode.complete(Unit)
        runCurrent()
        assertIs<ScanState.Paused>(coordinator.state.value)

        library.access = AccessState.Denied
        coordinator.resume()
        advanceUntilIdle()

        assertEquals(2, library.accessCalls)
        assertEquals(
            ScanState.RecoverableFailure(ScanProgress(0, 1), ScanFailureReason.AccessUnavailable),
            coordinator.state.value,
        )
    }

    @Test
    fun cancelStopsAfterCurrentAtomicDecodeWithoutCommittingItsResult() = runTest {
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(descriptors = List(20) { descriptor("asset-$it") })
        library.decodeBehavior = {
            decodeStarted.complete(Unit)
            releaseDecode.await()
            luma(1)
        }
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index)

        coordinator.start(policy())
        decodeStarted.await()
        coordinator.cancel()
        releaseDecode.complete(Unit)
        advanceUntilIdle()

        assertIs<ScanState.Cancelled>(coordinator.state.value)
        assertTrue(library.decodeCalls <= 2)
        assertEquals(0, index.saveAnalysisCalls)
    }

    @Test
    fun cancelAfterAtomicPersistKeepsTheCommittedRecordReusable() = runTest {
        val persistStarted = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val descriptor = descriptor("asset")
        val library = FakePhotoLibraryGateway(descriptors = listOf(descriptor))
        val index = InMemoryScanIndex().apply {
            afterAnalysisCommitted = {
                persistStarted.complete(Unit)
                releasePersist.await()
            }
        }
        val coordinator = coordinator(library, index)

        coordinator.start(policy())
        persistStarted.await()
        coordinator.cancel()
        releasePersist.complete(Unit)
        advanceUntilIdle()
        assertIs<ScanState.Cancelled>(coordinator.state.value)
        assertEquals(1, index.analysisWritesByAsset[descriptor.id])

        library.clearReadCounts()
        coordinator.start(policy())
        advanceUntilIdle()
        assertEquals(0, library.decodeCalls)
        assertEquals(1, index.analysisWritesByAsset[descriptor.id])
        assertIs<ScanState.Ready>(coordinator.state.value)
    }

    @Test
    fun injectedScopeCancellationPublishesCancelledPreservesCommitAndLeaksNoChild() = runTest {
        val persistStarted = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val descriptor = descriptor("asset")
        val library = FakePhotoLibraryGateway(descriptors = listOf(descriptor))
        val index = InMemoryScanIndex().apply {
            afterAnalysisCommitted = {
                persistStarted.complete(Unit)
                releasePersist.await()
            }
        }
        val owner = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = coordinator(
            library = library,
            index = index,
            scope = CoroutineScope(owner + dispatcher),
            dispatchers = LensiftDispatchers(dispatcher, dispatcher),
        )

        coordinator.start(policy())
        persistStarted.await()
        owner.cancel()
        advanceUntilIdle()

        assertIs<ScanState.Cancelled>(coordinator.state.value)
        assertEquals(1, index.analysisWritesByAsset[descriptor.id])
        assertTrue(owner.children.none())

        coordinator.start(policy())
        assertIs<ScanState.Indexing>(coordinator.state.value)
        advanceUntilIdle()
        assertIs<ScanState.Cancelled>(coordinator.state.value)
        assertTrue(owner.children.none())
        releasePersist.complete(Unit)
    }

    @Test
    fun scopeCancelledBeforeStartImmediatelyPublishesCancelledAndLeavesNoChild() = runTest {
        val owner = SupervisorJob().apply { cancel() }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = coordinator(
            library = FakePhotoLibraryGateway(descriptors = listOf(descriptor("asset"))),
            index = InMemoryScanIndex(),
            scope = CoroutineScope(owner + dispatcher),
            dispatchers = LensiftDispatchers(dispatcher, dispatcher),
        )

        coordinator.start(policy())
        advanceUntilIdle()

        assertEquals(ScanState.Cancelled(ScanProgress(0, 0)), coordinator.state.value)
        assertTrue(owner.children.none())
    }

    @Test
    fun controlledWorkerInterleavingNeverPublishesRegressingAnalyzingProgress() = runTest {
        val firstPersisted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = descriptor("a")
        val second = descriptor("b")
        val index = InMemoryScanIndex().apply {
            afterAnalysisCommitted = { record ->
                if (record.descriptor.id == first.id) {
                    firstPersisted.complete(Unit)
                    releaseFirst.await()
                }
            }
        }
        val coordinator = coordinator(
            FakePhotoLibraryGateway(descriptors = listOf(first, second)),
            index,
        )
        val observed = mutableListOf<Int>()
        val collector = launch {
            coordinator.state.collect { state ->
                if (state is ScanState.Analyzing) observed += state.progress.completed
            }
        }

        coordinator.start(policy())
        firstPersisted.await()
        runCurrent()
        releaseFirst.complete(Unit)
        advanceUntilIdle()
        collector.cancel()

        assertTrue(observed.isNotEmpty())
        assertEquals(observed.sorted(), observed)
        assertEquals(2, observed.last())
        assertIs<ScanState.Ready>(coordinator.state.value)
    }

    @Test
    fun startWhileAScanIsActiveDoesNotOverlapOrRequeryAccess() = runTest {
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseDecode = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(descriptors = listOf(descriptor("asset")))
        library.decodeBehavior = {
            decodeStarted.complete(Unit)
            releaseDecode.await()
            luma(1)
        }
        val coordinator = coordinator(library, InMemoryScanIndex())

        coordinator.start(policy())
        decodeStarted.await()
        coordinator.start(policy(maxDistance = 2))
        runCurrent()

        assertEquals(1, library.accessCalls)
        assertEquals(1, library.decodeCalls)
        releaseDecode.complete(Unit)
        advanceUntilIdle()
        assertIs<ScanState.Ready>(coordinator.state.value)
    }

    @Test
    fun decodeFailureIsAValueOnlySkipAndOtherAssetsContinue() = runTest {
        val library = FakePhotoLibraryGateway(descriptors = listOf(descriptor("bad"), descriptor("good")))
        library.decodeBehavior = { assetId ->
            if (assetId == AssetId("bad")) error("platform details must not escape")
            luma(2)
        }
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index)

        coordinator.start(policy())
        advanceUntilIdle()

        assertIs<ScanState.Ready>(coordinator.state.value)
        assertEquals(listOf(AssetScanSkip(AssetId("bad"), ScanSkipStage.LumaDecode)), coordinator.diagnostics.value.skips)
        assertEquals(1, index.saveAnalysisCalls)
    }

    @Test
    fun originalFailureIsRecordedAndDoesNotDiscardOtherCommittedHashes() = runTest {
        val library = FakePhotoLibraryGateway(descriptors = listOf(descriptor("bad"), descriptor("good")))
        library.decodeBehavior = { luma(3) }
        library.originalBehavior = { assetId ->
            if (assetId == AssetId("bad")) flow { error("private platform failure") }
            else flow { emit(byteArrayOf(1, 2, 3)) }
        }
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index, analyzer(hash = 3))

        coordinator.start(policy())
        advanceUntilIdle()

        assertIs<ScanState.Ready>(coordinator.state.value)
        assertEquals(listOf(AssetScanSkip(AssetId("bad"), ScanSkipStage.OriginalBytes)), coordinator.diagnostics.value.skips)
        assertEquals(1, index.saveExactHashCalls)
    }

    @Test
    fun databaseFailureStopsTheRunWithValueOnlyReason() = runTest {
        val library = FakePhotoLibraryGateway(descriptors = listOf(descriptor("asset")))
        val index = InMemoryScanIndex().apply { failOnSave = IllegalStateException("database path") }
        val coordinator = coordinator(library, index)

        coordinator.start(policy())
        advanceUntilIdle()

        assertEquals(
            ScanState.RecoverableFailure(ScanProgress(0, 1), ScanFailureReason.Database),
            coordinator.state.value,
        )
        assertTrue(coordinator.state.value.toString().contains("database path").not())
    }

    @Test
    fun accessLossDuringDecodeStopsTheRunInsteadOfRecordingAnAssetSkip() = runTest {
        val library = FakePhotoLibraryGateway(descriptors = listOf(descriptor("asset")))
        library.decodeBehavior = {
            library.access = AccessState.Denied
            error("permission revoked")
        }
        val coordinator = coordinator(library, InMemoryScanIndex())

        coordinator.start(policy())
        advanceUntilIdle()

        assertEquals(
            ScanState.RecoverableFailure(ScanProgress(0, 1), ScanFailureReason.AccessUnavailable),
            coordinator.state.value,
        )
        assertEquals(emptyList(), coordinator.diagnostics.value.skips)
    }

    @Test
    fun progressiveSnapshotArrivesBeforeBlockedFinalAssetAndFinalStateIsReady() = runTest {
        val releaseThird = CompletableDeferred<Unit>()
        val library = FakePhotoLibraryGateway(
            descriptors = listOf(descriptor("near-a"), descriptor("near-b"), descriptor("blocked")),
        )
        library.decodeBehavior = { assetId ->
            if (assetId == AssetId("blocked")) releaseThird.await()
            luma(if (assetId == AssetId("near-a")) 0 else 1)
        }
        val coordinator = coordinator(library, InMemoryScanIndex(), analyzerFromFirstPixel())

        coordinator.start(policy(maxDistance = 1))
        runCurrent()

        assertEquals(listOf(AssetId("near-a"), AssetId("near-b")), coordinator.findings.value.nearGroups.single().assetIds)
        assertIs<ScanState.Analyzing>(coordinator.state.value)

        releaseThird.complete(Unit)
        advanceUntilIdle()
        assertIs<ScanState.Ready>(coordinator.state.value)
    }

    @Test
    fun firstRunPersistsExactWorkAndWarmUnchangedRunPerformsZeroLibraryReads() = runTest {
        val descriptors = listOf(descriptor("a", byteCount = 10), descriptor("b", byteCount = 10))
        val library = FakePhotoLibraryGateway(descriptors = descriptors).apply {
            decodeBehavior = { luma(4) }
            originalBehavior = { flow { emit(byteArrayOf(9, 8, 7)) } }
        }
        val index = InMemoryScanIndex()
        val coordinator = coordinator(library, index, analyzer(hash = 4))

        coordinator.start(policy())
        advanceUntilIdle()
        assertEquals(2, index.saveExactHashCalls)
        assertEquals(1, coordinator.findings.value.exactGroups.size)

        library.clearReadCounts()
        coordinator.start(policy())
        advanceUntilIdle()

        assertEquals(2, library.accessCalls)
        assertEquals(0, library.decodeCalls)
        assertEquals(0, library.originalCalls)
        assertEquals(1, coordinator.findings.value.exactGroups.size)
    }

    @Test
    fun boundedNearCandidateGenerationRemainsVisibleInReadyFindings() = runTest {
        val descriptors = List(448) { index -> descriptor(index.toString().padStart(3, '0'), capturedAt = index.toLong()) }
        val records = descriptors.mapIndexed { index, descriptor ->
            record(descriptor, hash = 0).copy(sha256 = "digest-$index")
        }
        val library = FakePhotoLibraryGateway(descriptors = descriptors)
        val coordinator = coordinator(library, InMemoryScanIndex(records))

        coordinator.start(policy(maxDistance = 64, maxCaptureGapMillis = 0))
        advanceUntilIdle()

        assertEquals(CandidateGenerationStatus.PairLimitReached, coordinator.findings.value.candidateGenerationStatus)
        assertIs<ScanState.Ready>(coordinator.state.value)
    }

    @Test
    fun assetAnalyzerComposesInjectedAnalysisAndTimestampIntoARecord() {
        val evidence = BlurEvidence(0.25, 0.5, 0.75, BlurVerdict.Inconclusive)
        val descriptor = descriptor("asset")
        val analyzer = AssetAnalyzer(
            nowEpochMillis = { 987L },
            perceptualHash = { 123L },
            blurAnalysis = { _, _ -> evidence },
        )

        val record = analyzer.analyze(descriptor, luma(1), policy(), analyzerVersion = 7)

        assertEquals(descriptor, record.descriptor)
        assertEquals(7, record.analyzerVersion)
        assertEquals(123L, record.perceptualHash)
        assertEquals(evidence, record.blurEvidence)
        assertEquals(987L, record.analyzedAtEpochMillis)
    }

    @Test
    fun tenThousandDescriptorsUseBoundedWorkersWithoutRetainingDecodedFrames() = runTest {
        val descriptors = List(10_000) { index ->
            descriptor(
                id = index.toString().padStart(5, '0'),
                byteCount = 1,
                capturedAt = 1,
            )
        }
        val library = FakePhotoLibraryGateway(descriptors = descriptors).apply {
            decodeBehavior = { luma(1) }
            originalBehavior = { emptyFlow() }
        }
        val coordinator = coordinator(library, InMemoryScanIndex(), analyzer(hash = 1))

        coordinator.start(policy(maxDistance = 0))
        advanceUntilIdle()

        assertEquals(10_000, library.decodeCalls)
        assertTrue(library.maximumActiveDecodes <= 2)
        assertEquals(10_000, library.originalCalls)
        assertTrue(library.maximumActiveOriginalStreams <= 1)
        assertEquals(10_000, coordinator.findings.value.exactGroups.single().assetIds.size)
        assertIs<ScanState.Ready>(coordinator.state.value)
    }

    private fun TestScope.coordinator(
        library: FakePhotoLibraryGateway,
        index: InMemoryScanIndex,
        analyzer: AssetAnalyzer = analyzerFromFirstPixel(),
        scope: CoroutineScope = this,
        dispatchers: LensiftDispatchers? = null,
    ): ScanCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return ScanCoordinator(
            library = library,
            index = index,
            analyzer = analyzer,
            assembler = FindingAssembler(),
            scope = scope,
            dispatchers = dispatchers ?: LensiftDispatchers(computation = dispatcher, database = dispatcher),
            analyzerVersion = 1,
        )
    }

    private fun analyzer(hash: Long): AssetAnalyzer = AssetAnalyzer(
        nowEpochMillis = { 123L },
        perceptualHash = { hash },
    )

    private fun analyzerFromFirstPixel(): AssetAnalyzer = AssetAnalyzer(
        nowEpochMillis = { 123L },
        perceptualHash = { frame -> (frame.pixels.single().toInt() and 0xff).toLong() },
    )

    private fun descriptor(
        id: String,
        byteCount: Long? = 100,
        capturedAt: Long? = 1,
    ): PhotoDescriptor = PhotoDescriptor(
        id = AssetId(id),
        contentSignature = "signature-$id",
        width = 100,
        height = 100,
        byteCount = byteCount,
        capturedAtEpochMillis = capturedAt,
        isFavorite = false,
        isEdited = false,
    )

    private fun record(descriptor: PhotoDescriptor, hash: Long): AnalysisRecord = AnalysisRecord(
        descriptor = descriptor,
        analyzerVersion = 1,
        perceptualHash = hash,
        sha256 = null,
        blurEvidence = me.abuzaid.lensift.analysis.BlurEvidence(0.0, 0.0, 0.0, me.abuzaid.lensift.analysis.BlurVerdict.Inconclusive),
        analyzedAtEpochMillis = 1,
    )

    private fun luma(value: Int): LumaFrame = LumaFrame(1, 1, byteArrayOf(value.toByte()))

    private fun policy(
        maxDistance: Int = 0,
        maxCaptureGapMillis: Long = Long.MAX_VALUE,
    ): AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Balanced,
        maxPerceptualDistance = maxDistance,
        maxCaptureGapMillis = maxCaptureGapMillis,
        maxAspectRatioDelta = 1.0,
        blur = BlurPolicy(laplacianVarianceCeiling = 0.0, edgeDensityCeiling = 0.0),
    )
}
