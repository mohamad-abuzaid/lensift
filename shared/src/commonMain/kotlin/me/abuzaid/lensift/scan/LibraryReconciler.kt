package me.abuzaid.lensift.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.gateway.AccessState
import me.abuzaid.lensift.gateway.LibraryChange
import me.abuzaid.lensift.gateway.PhotoLibraryGateway
import me.abuzaid.lensift.index.ScanIndex

class LibraryReconciler(
    private val library: PhotoLibraryGateway,
    private val index: ScanIndex,
    private val coordinator: ScanCoordinator,
    private val scope: CoroutineScope,
    private val currentPolicy: () -> AnalysisPolicy,
) {
    private var lifecycleJob: Job? = null
    internal val wakeBufferCapacity: Int
        get() = WAKE_BUFFER_CAPACITY

    fun start() {
        if (lifecycleJob?.isActive == true) return
        lifecycleJob = scope.launch { collectChanges() }
    }

    suspend fun stop() {
        lifecycleJob?.cancelAndJoin()
        lifecycleJob = null
    }

    private suspend fun collectChanges() = coroutineScope {
        val pending = PendingState()
        val wakeSignals = Channel<Unit>(
            capacity = WAKE_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        launch {
            try {
                library.observeChanges().collect { change ->
                    pending.merge(change)
                    wakeSignals.send(Unit)
                }
            } finally {
                wakeSignals.close()
            }
        }

        while (true) {
            val firstWake = wakeSignals.receiveCatching()
            if (firstWake.isClosed) break
            val batch = pending.awaitDebouncedSnapshot(wakeSignals)
            if (!batch.hasSignals) continue

            coordinator.startAfterQuiescentAndAwait(currentPolicy) {
                batch.merge(pending.take())
                reconcile(batch)
            }
        }
    }

    private suspend fun reconcile(batch: PendingChange) {
        check(batch.hasSignals)
        val accessibleIds = try {
            when (library.currentAccess()) {
                AccessState.Full, AccessState.Partial ->
                    library.enumerateAccessibleImages().toList().mapTo(linkedSetOf()) { it.id }
                AccessState.Denied, AccessState.Restricted, AccessState.NotDetermined -> emptySet()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptySet()
        }

        index.purgeExcept(accessibleIds)
        index.invalidate(batch.changedAssetIds.intersect(accessibleIds))
    }

    private class PendingChange {
        val changedAssetIds = linkedSetOf<AssetId>()
        var accessMayHaveChanged: Boolean = false
            private set
        val hasSignals: Boolean
            get() = accessMayHaveChanged || changedAssetIds.isNotEmpty()

        fun merge(change: LibraryChange) {
            when (change) {
                is LibraryChange.Changed -> changedAssetIds += change.assetIds
                LibraryChange.AccessMayHaveChanged -> accessMayHaveChanged = true
            }
        }

        fun merge(other: PendingChange) {
            changedAssetIds += other.changedAssetIds
            accessMayHaveChanged = accessMayHaveChanged || other.accessMayHaveChanged
        }
    }

    private class PendingState {
        private val mutex = Mutex()
        private var change = PendingChange()
        private var revision = 0L

        suspend fun merge(next: LibraryChange) {
            mutex.withLock {
                change.merge(next)
                revision += 1
            }
        }

        suspend fun take(): PendingChange = mutex.withLock { takeLocked() }

        suspend fun awaitDebouncedSnapshot(wakeSignals: Channel<Unit>): PendingChange {
            var observedRevision = mutex.withLock { revision }
            while (true) {
                val nextWake = withTimeoutOrNull(DEBOUNCE_MILLIS) {
                    wakeSignals.receiveCatching()
                }
                if (nextWake?.isSuccess == true) {
                    observedRevision = mutex.withLock { revision }
                    continue
                }
                if (nextWake?.isClosed == true) return take()

                val snapshot = mutex.withLock {
                    if (revision == observedRevision) takeLocked() else null
                }
                if (snapshot != null) return snapshot
                observedRevision = mutex.withLock { revision }
            }
        }

        private fun takeLocked(): PendingChange = change.also { change = PendingChange() }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 300L
        const val WAKE_BUFFER_CAPACITY = 1
    }
}
