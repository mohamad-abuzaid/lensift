package me.abuzaid.lensift.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.abuzaid.lensift.analysis.CandidateGenerationStatus
import me.abuzaid.lensift.analysis.Sha256
import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.PhotoDescriptor
import me.abuzaid.lensift.findings.FindingAssembler
import me.abuzaid.lensift.findings.FindingSnapshot
import me.abuzaid.lensift.gateway.AccessState
import me.abuzaid.lensift.gateway.PhotoLibraryGateway
import me.abuzaid.lensift.index.AnalysisRecord
import me.abuzaid.lensift.index.IndexPartition
import me.abuzaid.lensift.index.ScanIndex

class ScanCoordinator(
    private val library: PhotoLibraryGateway,
    private val index: ScanIndex,
    private val analyzer: AssetAnalyzer,
    private val assembler: FindingAssembler,
    private val scope: CoroutineScope,
    private val dispatchers: LensiftDispatchers,
    private val analyzerVersion: Int,
) : ScanControl {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _findings = MutableStateFlow(emptyFindingSnapshot())
    val findings: StateFlow<FindingSnapshot> = _findings.asStateFlow()

    private val _diagnostics = MutableStateFlow(ScanDiagnostics.Empty)
    val diagnostics: StateFlow<ScanDiagnostics> = _diagnostics.asStateFlow()

    private val startGate = Mutex()
    private val control = MutableStateFlow(Control.Running)
    private val lumaDecodePermits = Semaphore(LUMA_CONCURRENCY)
    private val originalStreamPermits = Semaphore(ORIGINAL_CONCURRENCY)
    private val progressMutex = Mutex()
    private val snapshotMutex = Mutex()
    private var currentProgress = ScanProgress(0, 0)

    init {
        require(analyzerVersion >= 0) { "Analyzer version must not be negative" }
    }

    override fun start(policy: AnalysisPolicy) {
        if (!startGate.tryLock()) return

        control.value = Control.Running
        currentProgress = ScanProgress(0, 0)
        _state.value = ScanState.Indexing(currentProgress)
        _findings.value = emptyFindingSnapshot()
        _diagnostics.value = ScanDiagnostics.Empty

        val job = scope.launch {
            runScan(policy)
        }
        job.invokeOnCompletion { startGate.unlock() }
    }

    override fun pause() {
        val active = _state.value
        if (active is ScanState.Active && active !is ScanState.Pausing) {
            control.value = Control.PauseRequested
            _state.value = ScanState.Pausing(active.progress)
        }
    }

    override fun resume() {
        if (control.value == Control.PauseRequested) control.value = Control.Running
    }

    override fun cancel() {
        if (_state.value is ScanState.Active || _state.value is ScanState.Paused) {
            control.value = Control.CancelRequested
        }
    }

    private suspend fun runScan(policy: AnalysisPolicy) {
        val skips = mutableListOf<AssetScanSkip>()
        val skipsMutex = Mutex()
        try {
            val access = platformAccess()
            if (!access.canRead) throw HardScanFailure(ScanFailureReason.AccessUnavailable)

            val descriptors = enumerateDescriptors()
            val accessibleIds = descriptors.mapTo(linkedSetOf(), PhotoDescriptor::id)
            val partition = databaseCall {
                index.purgeExcept(accessibleIds)
                index.partitionChanged(descriptors, analyzerVersion)
            }

            val records = partition.reusable.toMutableList()
            val recordsMutex = Mutex()
            currentProgress = ScanProgress(partition.reusable.size, descriptors.size)
            _state.value = ScanState.Analyzing(currentProgress)

            analyzeChanged(
                partition = partition,
                records = records,
                recordsMutex = recordsMutex,
                skips = skips,
                skipsMutex = skipsMutex,
                policy = policy,
            )

            checkpoint(::analyzingState)
            if (partition.changed.isEmpty()) emitSnapshot(records, recordsMutex, policy)

            val exactWork = computationCall { assembler.exactHashWork(recordsMutex.withLock { records.toList() }) }
            checkpoint(::analyzingState)
            currentProgress = ScanProgress(0, exactWork.assetIds.size)
            _state.value = ScanState.Grouping(currentProgress)
            exactWork.assetIds.forEach { assetId ->
                checkpoint(::groupingState)
                hashOriginal(assetId, records, recordsMutex, skips, skipsMutex)
                checkpoint(::groupingState)
            }

            val persisted = databaseCall { index.currentRecords() }
            checkpoint(::groupingState)
            val finalSnapshot = computationCall { assembler.assemble(persisted, policy) }
            checkpoint(::groupingState)
            _findings.value = finalSnapshot
            val diagnostics = diagnostics(skipsMutex, skips)
            _diagnostics.value = diagnostics
            _state.value = ScanState.Ready(
                reviewTotals = ReviewTotals(
                    exactCount = finalSnapshot.exactGroups.size,
                    nearCount = finalSnapshot.nearGroups.size,
                    blurCount = finalSnapshot.blurItems.size,
                ),
                estimatedRecoverableBytes = finalSnapshot.estimatedRecoverableBytes,
                diagnostics = diagnostics,
            )
        } catch (cancelled: UserCancelled) {
            val diagnostics = diagnostics(skipsMutex, skips)
            _diagnostics.value = diagnostics
            _state.value = ScanState.Cancelled(currentProgress, diagnostics)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: HardScanFailure) {
            val diagnostics = diagnostics(skipsMutex, skips)
            _diagnostics.value = diagnostics
            _state.value = ScanState.RecoverableFailure(currentProgress, failure.reason, diagnostics)
        } catch (_: Throwable) {
            val diagnostics = diagnostics(skipsMutex, skips)
            _diagnostics.value = diagnostics
            _state.value = ScanState.RecoverableFailure(currentProgress, ScanFailureReason.Invariant, diagnostics)
        }
    }

    private suspend fun enumerateDescriptors(): List<PhotoDescriptor> {
        val descriptors = mutableListOf<PhotoDescriptor>()
        val ids = mutableSetOf<AssetId>()
        try {
            library.enumerateAccessibleImages().collect { descriptor ->
                checkpoint(::indexingState)
                if (!ids.add(descriptor.id)) throw HardScanFailure(ScanFailureReason.Invariant)
                descriptors += descriptor
                currentProgress = ScanProgress(descriptors.size, descriptors.size)
                _state.value = ScanState.Indexing(currentProgress)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: UserCancelled) {
            throw cancelled
        } catch (failure: HardScanFailure) {
            throw failure
        } catch (_: Throwable) {
            throw HardScanFailure(ScanFailureReason.AccessUnavailable)
        }
        checkpoint(::indexingState)
        return descriptors
    }

    private suspend fun analyzeChanged(
        partition: IndexPartition,
        records: MutableList<AnalysisRecord>,
        recordsMutex: Mutex,
        skips: MutableList<AssetScanSkip>,
        skipsMutex: Mutex,
        policy: AnalysisPolicy,
    ) = coroutineScope {
        val queue = Channel<PhotoDescriptor>(capacity = LUMA_QUEUE_CAPACITY)
        launch {
            try {
                partition.changed.forEach { descriptor ->
                    checkpoint(::analyzingState)
                    queue.send(descriptor)
                }
            } finally {
                queue.close()
            }
        }
        repeat(LUMA_CONCURRENCY) {
            launch {
                for (descriptor in queue) {
                    checkpoint(::analyzingState)
                    analyzeOne(descriptor, records, recordsMutex, skips, skipsMutex, policy)
                }
            }
        }
    }

    private suspend fun analyzeOne(
        descriptor: PhotoDescriptor,
        records: MutableList<AnalysisRecord>,
        recordsMutex: Mutex,
        skips: MutableList<AssetScanSkip>,
        skipsMutex: Mutex,
        policy: AnalysisPolicy,
    ) {
        val frame = try {
            lumaDecodePermits.withPermit { library.decodeLuma(descriptor.id, TARGET_LONGEST_EDGE) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ensureAccessAfterPlatformFailure()
            addSkip(skipsMutex, skips, AssetScanSkip(descriptor.id, ScanSkipStage.LumaDecode))
            completeAnalysisUnit(records, recordsMutex, policy)
            return
        }

        checkpoint(::analyzingState)
        val record = try {
            computationCall { analyzer.analyze(descriptor, frame, policy, analyzerVersion) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw HardScanFailure(ScanFailureReason.Invariant)
        }
        checkpoint(::analyzingState)
        databaseCall { index.saveAnalysis(record) }
        recordsMutex.withLock {
            records.removeAll { it.descriptor.id == descriptor.id }
            records += record
        }
        completeAnalysisUnit(records, recordsMutex, policy)
        checkpoint(::analyzingState)
    }

    private suspend fun completeAnalysisUnit(
        records: MutableList<AnalysisRecord>,
        recordsMutex: Mutex,
        policy: AnalysisPolicy,
    ) {
        val shouldEmit = progressMutex.withLock {
            val completed = (currentProgress.completed + 1).coerceAtMost(currentProgress.total)
            currentProgress = ScanProgress(completed, currentProgress.total)
            if (control.value == Control.Running) _state.value = ScanState.Analyzing(currentProgress)

            val cadence = maxOf(
                MIN_PROGRESSIVE_RECORD_CADENCE,
                (currentProgress.total / MAX_PROGRESSIVE_SNAPSHOTS).coerceAtLeast(1),
            )
            currentProgress.total <= MAX_PROGRESSIVE_ASSEMBLY_RECORDS &&
                (completed == currentProgress.total || completed % cadence == 0)
        }
        if (shouldEmit) {
            emitSnapshot(records, recordsMutex, policy)
        }
    }

    private suspend fun hashOriginal(
        assetId: AssetId,
        records: MutableList<AnalysisRecord>,
        recordsMutex: Mutex,
        skips: MutableList<AssetScanSkip>,
        skipsMutex: Mutex,
    ) {
        val digest = try {
            originalStreamPermits.withPermit {
                val sha256 = Sha256()
                library.originalByteChunks(assetId).collect { chunk ->
                    checkpoint(::groupingState)
                    computationCall { sha256.update(chunk) }
                    checkpoint(::groupingState)
                }
                computationCall { sha256.digestHex() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: UserCancelled) {
            throw cancelled
        } catch (_: Throwable) {
            ensureAccessAfterPlatformFailure()
            addSkip(skipsMutex, skips, AssetScanSkip(assetId, ScanSkipStage.OriginalBytes))
            completeGroupingUnit()
            return
        }

        checkpoint(::groupingState)
        databaseCall { index.saveExactHash(assetId, digest) }
        recordsMutex.withLock {
            val recordIndex = records.indexOfFirst { it.descriptor.id == assetId }
            if (recordIndex < 0) throw HardScanFailure(ScanFailureReason.Invariant)
            records[recordIndex] = records[recordIndex].copy(sha256 = digest)
        }
        completeGroupingUnit()
        checkpoint(::groupingState)
    }

    private suspend fun completeGroupingUnit() {
        progressMutex.withLock {
            currentProgress = ScanProgress(
                completed = (currentProgress.completed + 1).coerceAtMost(currentProgress.total),
                total = currentProgress.total,
            )
            if (control.value == Control.Running) _state.value = ScanState.Grouping(currentProgress)
        }
    }

    private suspend fun emitSnapshot(
        records: MutableList<AnalysisRecord>,
        recordsMutex: Mutex,
        policy: AnalysisPolicy,
    ) {
        snapshotMutex.withLock {
            checkpoint(::analyzingState)
            val ownedRecords = recordsMutex.withLock { records.toList() }
            val snapshot = computationCall { assembler.assemble(ownedRecords, policy) }
            checkpoint(::analyzingState)
            _findings.value = snapshot
        }
    }

    private suspend fun checkpoint(activeState: (ScanProgress) -> ScanState.Active) {
        currentCoroutineContext().ensureActive()
        while (true) {
            when (control.value) {
                Control.CancelRequested -> throw UserCancelled()
                Control.PauseRequested -> {
                    _state.value = ScanState.Paused(currentProgress)
                    control.first { it != Control.PauseRequested }
                    currentCoroutineContext().ensureActive()
                }
                Control.Running -> {
                    _state.value = activeState(currentProgress)
                    if (control.value == Control.Running) return
                }
            }
        }
    }

    private suspend fun platformAccess(): AccessState = try {
        library.currentAccess()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        throw HardScanFailure(ScanFailureReason.AccessUnavailable)
    }

    private suspend fun ensureAccessAfterPlatformFailure() {
        if (!platformAccess().canRead) throw HardScanFailure(ScanFailureReason.AccessUnavailable)
    }

    private suspend fun <T> databaseCall(block: suspend () -> T): T = try {
        withContext(dispatchers.database) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: HardScanFailure) {
        throw failure
    } catch (_: Throwable) {
        throw HardScanFailure(ScanFailureReason.Database)
    }

    private suspend fun <T> computationCall(block: suspend () -> T): T =
        withContext(dispatchers.computation) { block() }

    private suspend fun addSkip(
        mutex: Mutex,
        skips: MutableList<AssetScanSkip>,
        skip: AssetScanSkip,
    ) {
        mutex.withLock {
            if (skip !in skips) skips += skip
        }
    }

    private suspend fun diagnostics(mutex: Mutex, skips: MutableList<AssetScanSkip>): ScanDiagnostics =
        mutex.withLock {
            ScanDiagnostics(skips.sortedWith(compareBy({ it.assetId.value }, { it.stage.ordinal })))
        }

    private fun indexingState(progress: ScanProgress): ScanState.Active = ScanState.Indexing(progress)

    private fun analyzingState(progress: ScanProgress): ScanState.Active = ScanState.Analyzing(progress)

    private fun groupingState(progress: ScanProgress): ScanState.Active = ScanState.Grouping(progress)

    private enum class Control { Running, PauseRequested, CancelRequested }

    private class UserCancelled : RuntimeException()

    private class HardScanFailure(val reason: ScanFailureReason) : RuntimeException()

    private companion object {
        const val TARGET_LONGEST_EDGE = 512
        const val LUMA_CONCURRENCY = 2
        const val ORIGINAL_CONCURRENCY = 1
        const val LUMA_QUEUE_CAPACITY = 2
        const val MIN_PROGRESSIVE_RECORD_CADENCE = 2
        const val MAX_PROGRESSIVE_SNAPSHOTS = 2
        const val MAX_PROGRESSIVE_ASSEMBLY_RECORDS = 1_000
    }
}

private val AccessState.canRead: Boolean
    get() = this == AccessState.Full || this == AccessState.Partial

private fun emptyFindingSnapshot(): FindingSnapshot = FindingSnapshot(
    exactGroups = emptyList(),
    nearGroups = emptyList(),
    blurItems = emptyList(),
    candidateGenerationStatus = CandidateGenerationStatus.Complete,
    estimatedRecoverableBytes = 0,
)
