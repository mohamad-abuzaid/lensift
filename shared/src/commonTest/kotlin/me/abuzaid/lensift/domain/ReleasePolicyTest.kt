package me.abuzaid.lensift.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleasePolicyTest {
    @Test
    fun releasePoliciesLockTheSelectedCorpusAndAnalyzerVersions() {
        assertEquals("synthetic-corpus-v2", ReleasePolicies.corpusVersion)
        assertEquals("shared-analysis-v1", ReleasePolicies.analyzerVersion)
    }

    @Test
    fun releasePoliciesLockTheSelectedNumericValues() {
        assertPolicy(ReleasePolicies.conservative, Sensitivity.Conservative, 19, 180_000L, 0.04, 5.223295514542475E-4, 0.0)
        assertPolicy(ReleasePolicies.balanced, Sensitivity.Balanced, 19, 180_000L, 0.04, 5.341212412216976E-4, 0.0)
        assertPolicy(ReleasePolicies.broad, Sensitivity.Broad, 19, 180_000L, 0.04, 5.341212412216976E-4, 0.0)
    }

    @Test
    fun sensitivityPoliciesAreMonotonicFromConservativeToBroad() {
        assertTrue(ReleasePolicies.conservative.maxPerceptualDistance <= ReleasePolicies.balanced.maxPerceptualDistance)
        assertTrue(ReleasePolicies.balanced.maxPerceptualDistance <= ReleasePolicies.broad.maxPerceptualDistance)
        assertTrue(ReleasePolicies.conservative.maxCaptureGapMillis <= ReleasePolicies.balanced.maxCaptureGapMillis)
        assertTrue(ReleasePolicies.balanced.maxCaptureGapMillis <= ReleasePolicies.broad.maxCaptureGapMillis)
        assertTrue(ReleasePolicies.conservative.maxAspectRatioDelta <= ReleasePolicies.balanced.maxAspectRatioDelta)
        assertTrue(ReleasePolicies.balanced.maxAspectRatioDelta <= ReleasePolicies.broad.maxAspectRatioDelta)
        assertTrue(ReleasePolicies.conservative.blur.laplacianVarianceCeiling <= ReleasePolicies.balanced.blur.laplacianVarianceCeiling)
        assertTrue(ReleasePolicies.balanced.blur.laplacianVarianceCeiling <= ReleasePolicies.broad.blur.laplacianVarianceCeiling)
        assertTrue(ReleasePolicies.conservative.blur.edgeDensityCeiling <= ReleasePolicies.balanced.blur.edgeDensityCeiling)
        assertTrue(ReleasePolicies.balanced.blur.edgeDensityCeiling <= ReleasePolicies.broad.blur.edgeDensityCeiling)
    }

    private fun assertPolicy(
        actual: AnalysisPolicy,
        sensitivity: Sensitivity,
        perceptualDistance: Int,
        captureGapMillis: Long,
        aspectDelta: Double,
        laplacianCeiling: Double,
        edgeCeiling: Double,
    ) {
        assertEquals(sensitivity, actual.sensitivity)
        assertEquals(perceptualDistance, actual.maxPerceptualDistance)
        assertEquals(captureGapMillis, actual.maxCaptureGapMillis)
        assertEquals(aspectDelta, actual.maxAspectRatioDelta)
        assertEquals(laplacianCeiling, actual.blur.laplacianVarianceCeiling)
        assertEquals(edgeCeiling, actual.blur.edgeDensityCeiling)
    }
}
