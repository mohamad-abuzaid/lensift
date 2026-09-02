package me.abuzaid.lensift.quality

import me.abuzaid.lensift.domain.ReleasePolicies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisQualityTest {
    @Test
    fun developmentCorpusIsDeterministicAndPartitionsWholeSourceFamilies() {
        val development = SyntheticCorpus.development
        val test = SyntheticCorpus.test

        assertEquals(48, development.sourceFamilyCount + test.sourceFamilyCount)
        assertTrue(development.sourceFamilyIds.intersect(test.sourceFamilyIds).isEmpty())
        assertTrue(development.sourceFamilyCount >= 40)
        val regenerated = SyntheticCorpus.regenerateDevelopment()
        assertEquals(SyntheticCorpus.SEED, regenerated.seed)
        assertEquals(development.sourceFamilyIds, regenerated.sourceFamilyIds)
        assertEquals(
            development.assets.map { asset -> listOf(asset.id.value, asset.sourceFamilyId, asset.contentSignature, asset.frame.pixels.contentHashCode()) },
            regenerated.assets.map { asset -> listOf(asset.id.value, asset.sourceFamilyId, asset.contentSignature, asset.frame.pixels.contentHashCode()) },
        )
        assertEquals(development.sourceFamilyIds, development.assets.map(CorpusAsset::sourceFamilyId).toSet())
    }

    @Test
    fun balancedReleasePolicyMeetsDevelopmentQualityGatesFromIndependentPredictionsAndLabels() {
        val metrics = QualityEvaluator.evaluate(SyntheticCorpus.development, ReleasePolicies.balanced)

        assertEquals(BinaryMetrics(truePositive = 4, falsePositive = 0, falseNegative = 0), metrics.exact)
        assertEquals(BinaryMetrics(truePositive = 23, falsePositive = 0, falseNegative = 1), metrics.near)
        assertEquals(BinaryMetrics(truePositive = 4, falsePositive = 0, falseNegative = 0), metrics.blur)
        assertEquals(1.0, metrics.exact.precision)
        assertEquals(1.0, metrics.exact.recall)
        assertTrue(metrics.near.precision >= 0.90, "near=$metrics")
        assertTrue(metrics.near.recall >= 0.85, "near=$metrics")
        assertTrue(metrics.blur.precision >= 0.85, "blur=$metrics")
    }

    @Test
    fun policySearchAcceptsOnlyDevelopmentCorpus() {
        val result = PolicySelector.select(SyntheticCorpus.development)

        assertEquals(ReleasePolicies.balanced, result.balanced, result.toString())
    }
}
