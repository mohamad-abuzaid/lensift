package me.abuzaid.lensift.index

import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.domain.PhotoDescriptor

data class AnalysisRecord(
    val descriptor: PhotoDescriptor,
    val analyzerVersion: Int,
    val perceptualHash: Long,
    val sha256: String?,
    val blurEvidence: BlurEvidence,
    val analyzedAtEpochMillis: Long,
) {
    init {
        require(descriptor.contentSignature.isNotBlank()) { "Content signature must not be blank" }
        require(analyzerVersion >= 0) { "Analyzer version must not be negative" }
        require(sha256 == null || sha256.isNotBlank()) { "SHA-256 must not be blank" }
        require(blurEvidence.laplacianVariance.isFinite() && blurEvidence.laplacianVariance >= 0.0) {
            "Laplacian variance must be finite and nonnegative"
        }
        require(blurEvidence.edgeDensity.isFinite() && blurEvidence.edgeDensity >= 0.0) {
            "Edge density must be finite and nonnegative"
        }
        require(blurEvidence.localTextureSupport.isFinite() && blurEvidence.localTextureSupport in 0.0..1.0) {
            "Local texture support must be finite and between 0 and 1"
        }
        require(analyzedAtEpochMillis >= 0) { "Analysis timestamp must not be negative" }
    }
}

class IndexPartition(reusable: List<AnalysisRecord>, changed: List<PhotoDescriptor>) {
    private val ownedReusable = reusable.toList()
    private val ownedChanged = changed.toList()

    val reusable: List<AnalysisRecord>
        get() = ownedReusable.toList()

    val changed: List<PhotoDescriptor>
        get() = ownedChanged.toList()

    override fun equals(other: Any?): Boolean =
        other is IndexPartition && ownedReusable == other.ownedReusable && ownedChanged == other.ownedChanged

    override fun hashCode(): Int = 31 * ownedReusable.hashCode() + ownedChanged.hashCode()

    override fun toString(): String = "IndexPartition(reusable=$ownedReusable, changed=$ownedChanged)"
}

data class CleanupSummary(
    val completedAtEpochMillis: Long,
    val exactCount: Int,
    val nearCount: Int,
    val blurCount: Int,
    val confirmedEstimatedBytes: Long,
) {
    init {
        require(completedAtEpochMillis >= 0) { "Cleanup timestamp must not be negative" }
        require(exactCount >= 0) { "Exact cleanup count must not be negative" }
        require(nearCount >= 0) { "Near cleanup count must not be negative" }
        require(blurCount >= 0) { "Blur cleanup count must not be negative" }
        require(confirmedEstimatedBytes >= 0) { "Confirmed estimated bytes must not be negative" }
    }
}
