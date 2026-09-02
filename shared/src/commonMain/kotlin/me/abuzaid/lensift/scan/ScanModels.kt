package me.abuzaid.lensift.scan

/** Completed work within a known scan phase. */
data class ScanProgress(
    val completed: Int,
    val total: Int,
) {
    init {
        require(completed >= 0) { "Completed scan work must not be negative" }
        require(total >= 0) { "Total scan work must not be negative" }
        require(completed <= total) { "Completed scan work must not exceed total work" }
    }
}

/** Counts of non-destructive findings presented for review. */
data class ReviewTotals(
    val exactCount: Int,
    val nearCount: Int,
    val blurCount: Int,
) {
    init {
        require(exactCount >= 0) { "Exact review count must not be negative" }
        require(nearCount >= 0) { "Near review count must not be negative" }
        require(blurCount >= 0) { "Blur review count must not be negative" }
    }

    val totalCount: Int
        get() = exactCount + nearCount + blurCount
}
