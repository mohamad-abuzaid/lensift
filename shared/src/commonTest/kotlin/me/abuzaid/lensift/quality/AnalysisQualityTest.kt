package me.abuzaid.lensift.quality

import me.abuzaid.lensift.analysis.PerceptualHash
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
            development.assets.map { asset -> listOf(asset.id.value, asset.sourceFamilyId, asset.rawBytes.contentHashCode(), asset.frame.pixels.contentHashCode()) },
            regenerated.assets.map { asset -> listOf(asset.id.value, asset.sourceFamilyId, asset.rawBytes.contentHashCode(), asset.frame.pixels.contentHashCode()) },
        )
        assertEquals(development.sourceFamilyIds, development.assets.map(CorpusAsset::sourceFamilyId).toSet())
    }

    @Test
    fun balancedReleasePolicyMeetsDevelopmentQualityGatesFromIndependentPredictionsAndLabels() {
        val corpus = SyntheticCorpus.development
        val metrics = QualityEvaluator.evaluate(corpus, ReleasePolicies.balanced)

        val exactPredictions = QualityEvaluator.exactPredictions(corpus)
        assertEquals(corpus.exactLabels, exactPredictions)
        assertTrue(corpus.exactNegativeAssetIds.none { id -> exactPredictions.any { id == it.first || id == it.second } })
        assertTrue(corpus.exactNegativeAssetIds.size >= 2)

        val byId = corpus.assets.associateBy(CorpusAsset::id)
        val hardNegativeDistances = corpus.nearHardNegativePairs.map { pair ->
            PerceptualHash.distance(byId.getValue(pair.first).frame.let(PerceptualHash::compute), byId.getValue(pair.second).frame.let(PerceptualHash::compute))
        }
        assertTrue(hardNegativeDistances.all { it in 15..20 }, "hard-negative distances=$hardNegativeDistances")
        val hardNegativeHits = QualityEvaluator.nearPredictions(corpus, ReleasePolicies.balanced).intersect(corpus.nearHardNegativePairs)
        assertTrue(hardNegativeHits.isNotEmpty())

        assertEquals(BinaryMetrics(truePositive = 4, falsePositive = 0, falseNegative = 0), metrics.exact)
        assertEquals(BinaryMetrics(truePositive = 24, falsePositive = 1, falseNegative = 0), metrics.near)
        assertEquals(BinaryMetrics(truePositive = 2, falsePositive = 0, falseNegative = 1), metrics.blur)
        assertEquals(1.0, metrics.exact.precision)
        assertEquals(1.0, metrics.exact.recall)
        assertTrue(metrics.near.precision >= 0.90, "near=$metrics")
        assertTrue(metrics.near.recall >= 0.85, "near=$metrics")
        assertTrue(metrics.blur.precision >= 0.85, "blur=$metrics")
        assertEquals(hardNegativeHits.size, metrics.near.falsePositive)
    }

    @Test
    fun policySearchAcceptsOnlyDevelopmentCorpus() {
        val result = PolicySelector.select(SyntheticCorpus.development)

        assertEquals(ReleasePolicies.conservative, result.conservative, result.toString())
        assertEquals(ReleasePolicies.balanced, result.balanced, result.toString())
        assertEquals(ReleasePolicies.broad, result.broad, result.toString())
        assertEquals(sameThresholds(ReleasePolicies.broad, ReleasePolicies.balanced), result.broadEqualsBalanced, result.toString())
    }

    private fun sameThresholds(left: me.abuzaid.lensift.domain.AnalysisPolicy, right: me.abuzaid.lensift.domain.AnalysisPolicy): Boolean =
        left.maxPerceptualDistance == right.maxPerceptualDistance &&
            left.maxCaptureGapMillis == right.maxCaptureGapMillis &&
            left.maxAspectRatioDelta == right.maxAspectRatioDelta &&
            left.blur == right.blur
}
