package me.abuzaid.lensift.scan

/**
 * Observable lifecycle of a scan.
 *
 * Only [Indexing], [Analyzing], [Grouping], and [Pausing] are active: each represents work that
 * is still in flight. [Paused] preserves progress but performs no work until resumed.
 */
sealed interface ScanState {
    val isActive: Boolean

    data object Idle : ScanState {
        override val isActive: Boolean = false
    }

    sealed interface Active : ScanState {
        val progress: ScanProgress
        override val isActive: Boolean
            get() = true
    }

    data class Indexing(override val progress: ScanProgress) : Active

    data class Analyzing(override val progress: ScanProgress) : Active

    data class Grouping(override val progress: ScanProgress) : Active

    data class Pausing(override val progress: ScanProgress) : Active

    data class Paused(val progress: ScanProgress) : ScanState {
        override val isActive: Boolean = false
    }

    data class Ready(
        val reviewTotals: ReviewTotals,
        val estimatedRecoverableBytes: Long,
    ) : ScanState {
        init {
            require(estimatedRecoverableBytes >= 0) { "Estimated recoverable bytes must not be negative" }
        }

        override val isActive: Boolean = false
    }

    data class RecoverableFailure(val progress: ScanProgress) : ScanState {
        override val isActive: Boolean = false
    }

    data class Cancelled(val progress: ScanProgress) : ScanState {
        override val isActive: Boolean = false
    }
}
