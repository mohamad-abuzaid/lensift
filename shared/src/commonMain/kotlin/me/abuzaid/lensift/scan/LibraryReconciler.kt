package me.abuzaid.lensift.scan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

    fun start() {
        if (lifecycleJob?.isActive == true) return
        lifecycleJob = scope.launch { collectChanges() }
    }

    suspend fun stop() {
        lifecycleJob?.cancelAndJoin()
        lifecycleJob = null
    }

    private suspend fun collectChanges() = coroutineScope {
        val changes = Channel<LibraryChange>(Channel.UNLIMITED)
        launch {
            try {
                library.observeChanges().collect(changes::send)
            } finally {
                changes.close()
            }
        }

        while (true) {
            val first = changes.receiveCatching().getOrNull() ?: break
            val batch = PendingChange().apply { merge(first) }
            while (true) {
                val next = withTimeoutOrNull(DEBOUNCE_MILLIS) {
                    changes.receiveCatching().getOrNull()
                } ?: break
                batch.merge(next)
            }

            coordinator.startAfterQuiescentAndAwait(currentPolicy) {
                drainQueuedChanges(changes, batch)
                reconcile(batch)
            }
        }
    }

    private fun drainQueuedChanges(changes: Channel<LibraryChange>, batch: PendingChange) {
        while (true) {
            val change = changes.tryReceive().getOrNull() ?: return
            batch.merge(change)
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
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 300L
    }
}
