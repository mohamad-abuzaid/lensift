package me.abuzaid.lensift.scan

import me.abuzaid.lensift.domain.AssetId

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

/** Platform-error details never cross into shared state; only the affected asset and stage do. */
enum class ScanSkipStage { LumaDecode, OriginalBytes }

data class AssetScanSkip(
    val assetId: AssetId,
    val stage: ScanSkipStage,
)

class ScanDiagnostics(skips: List<AssetScanSkip> = emptyList()) {
    private val ownedSkips = skips.toList()

    val skips: List<AssetScanSkip>
        get() = ownedSkips.toList()

    init {
        require(ownedSkips == ownedSkips.sortedWith(compareBy({ it.assetId.value }, { it.stage.ordinal }))) {
            "Scan skips must have stable asset and stage order"
        }
        require(ownedSkips.toSet().size == ownedSkips.size) { "Scan skips must be distinct" }
    }

    override fun equals(other: Any?): Boolean = other is ScanDiagnostics && ownedSkips == other.ownedSkips

    override fun hashCode(): Int = ownedSkips.hashCode()

    override fun toString(): String = "ScanDiagnostics(skips=$ownedSkips)"

    companion object {
        val Empty = ScanDiagnostics()
    }
}

enum class ScanFailureReason { AccessUnavailable, Database, Invariant, Unknown }
