package me.abuzaid.lensift.quality

import me.abuzaid.lensift.analysis.BlurAnalyzer
import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.analysis.BlurVerdict
import me.abuzaid.lensift.analysis.CandidateBucketer
import me.abuzaid.lensift.analysis.CandidatePair
import me.abuzaid.lensift.analysis.PerceptualCandidate
import me.abuzaid.lensift.analysis.PerceptualHash
import me.abuzaid.lensift.analysis.Sha256
import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.BlurPolicy
import me.abuzaid.lensift.domain.Sensitivity

data class BinaryMetrics(val truePositive: Int, val falsePositive: Int, val falseNegative: Int) {
    val precision: Double get() = if (truePositive + falsePositive == 0) 1.0 else truePositive.toDouble() / (truePositive + falsePositive)
    val recall: Double get() = if (truePositive + falseNegative == 0) 1.0 else truePositive.toDouble() / (truePositive + falseNegative)
}

data class QualityMetrics(
    val exact: BinaryMetrics,
    val near: BinaryMetrics,
    val blur: BinaryMetrics,
)

/** Keeps prediction construction separate from source-family labels. */
object QualityEvaluator {
    fun evaluate(corpus: DevelopmentCorpus, policy: AnalysisPolicy): QualityMetrics = PreparedCorpus(corpus).evaluate(policy)
    fun exactPredictions(corpus: DevelopmentCorpus): Set<CandidatePair> = PreparedCorpus(corpus).exactPredictions
    fun nearPredictions(corpus: DevelopmentCorpus, policy: AnalysisPolicy): Set<CandidatePair> =
        PreparedCorpus(corpus).nearPredictions(policy)
}

data class PolicySelection(
    val conservative: AnalysisPolicy,
    val balanced: AnalysisPolicy,
    val broad: AnalysisPolicy,
    val conservativeMetrics: QualityMetrics,
    val balancedMetrics: QualityMetrics,
    val broadMetrics: QualityMetrics,
    val broadEqualsBalanced: Boolean,
)

object PolicySelector {
    val perceptualDistances: IntRange = 0..20
    val captureWindowsSeconds: List<Int> = listOf(15, 30, 60, 90, 120, 180)
    val aspectDeltas: List<Double> = listOf(0.005, 0.01, 0.02, 0.04)

    fun select(corpus: DevelopmentCorpus): PolicySelection {
        val prepared = PreparedCorpus(corpus)
        val blurCutPoints = prepared.observedBlurCutPoints()
        val candidates = buildList {
            perceptualDistances.forEachIndexed { distanceIndex, distance ->
                captureWindowsSeconds.forEachIndexed { windowIndex, seconds ->
                    aspectDeltas.forEachIndexed { aspectIndex, aspectDelta ->
                        val base = AnalysisPolicy(
                            sensitivity = Sensitivity.Balanced,
                            maxPerceptualDistance = distance,
                            maxCaptureGapMillis = seconds * 1_000L,
                            maxAspectRatioDelta = aspectDelta,
                            blur = BlurPolicy(0.0, 0.0),
                        )
                        val exact = prepared.exactMetrics
                        val near = prepared.nearMetrics(base)
                        blurCutPoints.forEachIndexed { blurIndex, blur ->
                            val policy = base.copy(blur = blur)
                            add(
                                ScoredCandidate(
                                    policy = policy,
                                    metrics = QualityMetrics(exact, near, prepared.blurMetrics(blur)),
                                    breadth = Breadth(distanceIndex, windowIndex, aspectIndex, blurIndex),
                                ),
                            )
                        }
                    }
                }
            }
        }

        val strict = candidates.filter(ScoredCandidate::meetsBalancedGate)
        require(strict.isNotEmpty()) {
            val best = candidates.maxWithOrNull(candidateComparator())!!
            "No development-grid candidate meets the balanced quality gate; broadest=$best; missedNear=${prepared.missedNear(best.policy)}"
        }
        val balanced = strict.maxWithOrNull(candidateComparator())!!
        val conservative = strict
            .filter { it != balanced && it.noWiderThan(balanced) }
            .maxWithOrNull(candidateComparator())
            ?: balanced
        val broad = candidates
            .filter { it != balanced && it.noTighterThan(balanced) && it.preservesPrecisionGate() }
            .minWithOrNull(candidateComparator())
            ?: balanced

        return PolicySelection(
            conservative = conservative.policy.withSensitivity(Sensitivity.Conservative),
            balanced = balanced.policy.withSensitivity(Sensitivity.Balanced),
            broad = broad.policy.withSensitivity(Sensitivity.Broad),
            conservativeMetrics = conservative.metrics,
            balancedMetrics = balanced.metrics,
            broadMetrics = broad.metrics,
            broadEqualsBalanced = broad == balanced,
        )
    }

    private fun candidateComparator(): Comparator<ScoredCandidate> = compareBy<ScoredCandidate> { it.breadth.total }
        .thenBy { it.policy.maxPerceptualDistance }
        .thenBy { it.policy.maxCaptureGapMillis }
        .thenBy { it.policy.maxAspectRatioDelta }
        .thenBy { it.policy.blur.laplacianVarianceCeiling }
        .thenBy { it.policy.blur.edgeDensityCeiling }

    /** A broadness rank is only a deterministic tie breaker after gate satisfaction. */
    private data class Breadth(val distance: Int, val window: Int, val aspect: Int, val blur: Int) {
        val total: Int get() = distance + window + aspect + blur
    }

    private data class ScoredCandidate(val policy: AnalysisPolicy, val metrics: QualityMetrics, val breadth: Breadth) {
        fun meetsBalancedGate(): Boolean =
            metrics.exact.precision == 1.0 && metrics.exact.recall == 1.0 &&
                metrics.near.precision >= 0.90 && metrics.near.recall >= 0.85 && metrics.blur.precision >= 0.85

        fun preservesPrecisionGate(): Boolean =
            metrics.exact.precision == 1.0 && metrics.near.precision >= 0.90 && metrics.blur.precision >= 0.85

        fun noWiderThan(other: ScoredCandidate): Boolean =
            policy.maxPerceptualDistance <= other.policy.maxPerceptualDistance &&
                policy.maxCaptureGapMillis <= other.policy.maxCaptureGapMillis &&
                policy.maxAspectRatioDelta <= other.policy.maxAspectRatioDelta &&
                policy.blur.laplacianVarianceCeiling <= other.policy.blur.laplacianVarianceCeiling &&
                policy.blur.edgeDensityCeiling <= other.policy.blur.edgeDensityCeiling

        fun noTighterThan(other: ScoredCandidate): Boolean =
            policy.maxPerceptualDistance >= other.policy.maxPerceptualDistance &&
                policy.maxCaptureGapMillis >= other.policy.maxCaptureGapMillis &&
                policy.maxAspectRatioDelta >= other.policy.maxAspectRatioDelta &&
                policy.blur.laplacianVarianceCeiling >= other.policy.blur.laplacianVarianceCeiling &&
                policy.blur.edgeDensityCeiling >= other.policy.blur.edgeDensityCeiling
    }
}

private class PreparedCorpus(private val corpus: DevelopmentCorpus) {
    private val contentSignatures = corpus.assets.associate { asset ->
        asset.id to Sha256().update(asset.rawBytes).digestHex()
    }
    private val candidates = corpus.assets.map { asset ->
        PerceptualCandidate(
            assetId = asset.id,
            hash = PerceptualHash.compute(asset.frame),
            width = asset.frame.width,
            height = asset.frame.height,
            capturedAtEpochMillis = asset.capturedAtEpochMillis,
        )
    }
    private val blurEvidence = corpus.assets.associate { it.id to BlurAnalyzer.analyze(it.frame, widestAnalysisPolicy) }
    private val nearCache = mutableMapOf<NearThresholds, BinaryMetrics>()
    private val blurCache = mutableMapOf<BlurPolicy, BinaryMetrics>()

    val exactMetrics: BinaryMetrics by lazy {
        metrics(exactPredictions, corpus.exactLabels)
    }

    val exactPredictions: Set<CandidatePair> by lazy {
        allPairs(corpus.assets)
            .filter { (first, second) -> contentSignatures.getValue(first) == contentSignatures.getValue(second) }
            .toSet()
    }

    fun evaluate(policy: AnalysisPolicy): QualityMetrics = QualityMetrics(
        exact = exactMetrics,
        near = nearMetrics(policy),
        blur = blurMetrics(policy.blur),
    )

    fun nearMetrics(policy: AnalysisPolicy): BinaryMetrics = nearCache.getOrPut(
        NearThresholds(policy.maxPerceptualDistance, policy.maxCaptureGapMillis, policy.maxAspectRatioDelta),
    ) {
        metrics(nearPredictions(policy), corpus.nearLabels)
    }

    fun nearPredictions(policy: AnalysisPolicy): Set<CandidatePair> = CandidateBucketer.find(candidates, policy).pairs
        .filter { pair -> contentSignatures.getValue(pair.first) != contentSignatures.getValue(pair.second) }
        .toSet()

    fun blurMetrics(policy: BlurPolicy): BinaryMetrics = blurCache.getOrPut(policy) {
        val predicted = blurEvidence
            .filter { (_, evidence) ->
                evidence.laplacianVariance <= policy.laplacianVarianceCeiling &&
                    evidence.edgeDensity <= policy.edgeDensityCeiling &&
                    evidence.verdict == BlurVerdict.PossiblyBlurred
            }
            .keys
        metrics(predicted, corpus.blurLabels)
    }

    fun observedBlurCutPoints(): List<BlurPolicy> = blurEvidence.values
        .map { evidence -> BlurPolicy(evidence.laplacianVariance, evidence.edgeDensity) }
        .distinct()
        .sortedWith(compareBy<BlurPolicy> { it.laplacianVarianceCeiling }.thenBy { it.edgeDensityCeiling })

    fun missedNear(policy: AnalysisPolicy): Set<CandidatePair> {
        return corpus.nearLabels - nearPredictions(policy)
    }

    private fun <T> metrics(predicted: Set<T>, labels: Set<T>): BinaryMetrics = BinaryMetrics(
        truePositive = predicted.intersect(labels).size,
        falsePositive = predicted.minus(labels).size,
        falseNegative = labels.minus(predicted).size,
    )

    private fun allPairs(assets: List<CorpusAsset>): List<CandidatePair> = buildList {
        for (firstIndex in 0 until assets.lastIndex) {
            for (secondIndex in firstIndex + 1 until assets.size) {
                val first = assets[firstIndex].id
                val second = assets[secondIndex].id
                add(if (first.value < second.value) CandidatePair(first, second) else CandidatePair(second, first))
            }
        }
    }

    private data class NearThresholds(val distance: Int, val captureGapMillis: Long, val aspectDelta: Double)

    private companion object {
        val widestBlurPolicy = BlurPolicy(Double.MAX_VALUE, Double.MAX_VALUE)
        val widestAnalysisPolicy = AnalysisPolicy(
            sensitivity = Sensitivity.Broad,
            maxPerceptualDistance = 64,
            maxCaptureGapMillis = Long.MAX_VALUE,
            maxAspectRatioDelta = Double.MAX_VALUE,
            blur = widestBlurPolicy,
        )
    }
}

private fun AnalysisPolicy.withSensitivity(sensitivity: Sensitivity): AnalysisPolicy = copy(sensitivity = sensitivity)
