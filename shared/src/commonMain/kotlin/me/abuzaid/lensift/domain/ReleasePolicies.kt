package me.abuzaid.lensift.domain

/** Versioned policies selected from the deterministic development corpus. */
object ReleasePolicies {
    const val corpusVersion: String = "synthetic-corpus-v1"
    const val analyzerVersion: String = "shared-analysis-v1"

    val conservative: AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Conservative,
        maxPerceptualDistance = 20,
        maxCaptureGapMillis = 180_000,
        maxAspectRatioDelta = 0.04,
        blur = BlurPolicy(laplacianVarianceCeiling = 5.615785965522273E-4, edgeDensityCeiling = 0.0),
    )
    val balanced: AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Balanced,
        maxPerceptualDistance = 20,
        maxCaptureGapMillis = 180_000,
        maxAspectRatioDelta = 0.04,
        blur = BlurPolicy(laplacianVarianceCeiling = 5.753471953795763E-4, edgeDensityCeiling = 0.0),
    )
    val broad: AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Broad,
        maxPerceptualDistance = 20,
        maxCaptureGapMillis = 180_000,
        maxAspectRatioDelta = 0.04,
        blur = BlurPolicy(laplacianVarianceCeiling = 5.753471953795763E-4, edgeDensityCeiling = 0.0),
    )
}
