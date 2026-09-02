package me.abuzaid.lensift.domain

enum class Sensitivity { Conservative, Balanced, Broad }

data class AnalysisPolicy(
    val sensitivity: Sensitivity,
    val maxPerceptualDistance: Int,
    val maxCaptureGapMillis: Long,
    val maxAspectRatioDelta: Double,
    val blur: BlurPolicy,
) {
    init {
        require(maxPerceptualDistance in 0..64) { "Perceptual distance must be between 0 and 64" }
        require(maxCaptureGapMillis >= 0) { "Capture gap must not be negative" }
        require(maxAspectRatioDelta.isFinite() && maxAspectRatioDelta >= 0.0) {
            "Aspect-ratio delta must be finite and nonnegative"
        }
    }
}

data class BlurPolicy(
    val laplacianVarianceCeiling: Double,
    val edgeDensityCeiling: Double,
) {
    init {
        require(laplacianVarianceCeiling.isFinite() && laplacianVarianceCeiling >= 0.0) {
            "Laplacian variance ceiling must be finite and nonnegative"
        }
        require(edgeDensityCeiling.isFinite() && edgeDensityCeiling >= 0.0) {
            "Edge density ceiling must be finite and nonnegative"
        }
    }
}
