package me.abuzaid.lensift.domain

/** Versioned policies selected from the deterministic development corpus. */
object ReleasePolicies {
    const val corpusVersion: String = "synthetic-corpus-v2"
    const val analyzerVersion: String = "shared-analysis-v1"

    val conservative: AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Conservative,
        maxPerceptualDistance = 19,
        maxCaptureGapMillis = 180_000,
        maxAspectRatioDelta = 0.04,
        blur = BlurPolicy(laplacianVarianceCeiling = 5.223295514542475E-4, edgeDensityCeiling = 0.0),
    )
    val balanced: AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Balanced,
        maxPerceptualDistance = 19,
        maxCaptureGapMillis = 180_000,
        maxAspectRatioDelta = 0.04,
        blur = BlurPolicy(laplacianVarianceCeiling = 5.341212412216976E-4, edgeDensityCeiling = 0.0),
    )
    val broad: AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Broad,
        maxPerceptualDistance = 19,
        maxCaptureGapMillis = 180_000,
        maxAspectRatioDelta = 0.04,
        blur = BlurPolicy(laplacianVarianceCeiling = 5.341212412216976E-4, edgeDensityCeiling = 0.0),
    )
}
