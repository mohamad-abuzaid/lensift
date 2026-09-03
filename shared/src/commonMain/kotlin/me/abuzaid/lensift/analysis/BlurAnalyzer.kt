package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.LumaFrame
import kotlin.math.sqrt

data class BlurEvidence(
    val laplacianVariance: Double,
    val edgeDensity: Double,
    val localTextureSupport: Double,
    val verdict: BlurVerdict,
)

enum class BlurVerdict { PossiblyBlurred, Inconclusive }

/**
 * Calculates complementary blur signals over a bounded, downscaled luminance frame.
 *
 * Low scores are evidence of reduced detail, not proof of blur: frames without
 * sufficient local texture support are therefore [BlurVerdict.Inconclusive].
 */
object BlurAnalyzer {
    private const val MAX_ANALYSIS_DIMENSION = 256

    /** A 3x3 neighborhood supports texture only when its normalized luma span reaches this value. */
    private const val NORMALIZED_LOCAL_CONTRAST_THRESHOLD = 0.10

    /** At least this fraction of analyzed neighborhoods must support texture before a blur verdict is allowed. */
    private const val MIN_LOCAL_TEXTURE_SUPPORT = 0.01

    /**
     * A Sobel magnitude normalized to [0, 1] is an edge when it reaches this threshold.
     * The normalization divides by the largest 3x3 Sobel magnitude possible for normalized luma,
     * keeping edge density comparable across frames.
     */
    private const val NORMALIZED_SOBEL_EDGE_MAGNITUDE_THRESHOLD = 0.125
    private const val MAX_NORMALIZED_SOBEL_MAGNITUDE = 4.0 * 1.4142135623730951

    fun analyze(frame: LumaFrame, policy: AnalysisPolicy): BlurEvidence {
        val samples = downscale(frame)
        if (samples.width < 3 || samples.height < 3) return inconclusiveEvidence()

        val signals = calculateSignals(samples)
        val rawEvidence = BlurEvidence(
            laplacianVariance = signals.laplacianVariance,
            edgeDensity = signals.edgeDensity,
            localTextureSupport = signals.localTextureSupport,
            verdict = BlurVerdict.Inconclusive,
        )
        return rawEvidence.copy(verdict = classify(rawEvidence, policy))
    }

    /** Reapplies current policy thresholds to policy-independent, persistable evidence. */
    fun classify(evidence: BlurEvidence, policy: AnalysisPolicy): BlurVerdict {
        val hasEnoughTexture = evidence.localTextureSupport >= MIN_LOCAL_TEXTURE_SUPPORT
        val possiblyBlurred = hasEnoughTexture &&
            evidence.laplacianVariance <= policy.blur.laplacianVarianceCeiling &&
            evidence.edgeDensity <= policy.blur.edgeDensityCeiling
        return if (possiblyBlurred) BlurVerdict.PossiblyBlurred else BlurVerdict.Inconclusive
    }

    private fun inconclusiveEvidence(): BlurEvidence = BlurEvidence(
        laplacianVariance = 0.0,
        edgeDensity = 0.0,
        localTextureSupport = 0.0,
        verdict = BlurVerdict.Inconclusive,
    )

    private fun calculateSignals(samples: Samples): Signals {
        var laplacianSum = 0.0
        var laplacianSquareSum = 0.0
        var edgeCount = 0
        var texturedNeighborhoodCount = 0
        var sampleCount = 0

        for (y in 1 until samples.height - 1) {
            for (x in 1 until samples.width - 1) {
                val topLeft = samples[x - 1, y - 1]
                val top = samples[x, y - 1]
                val topRight = samples[x + 1, y - 1]
                val left = samples[x - 1, y]
                val center = samples[x, y]
                val right = samples[x + 1, y]
                val bottomLeft = samples[x - 1, y + 1]
                val bottom = samples[x, y + 1]
                val bottomRight = samples[x + 1, y + 1]

                val localMaximum = maxOf(
                    maxOf(topLeft, top, topRight),
                    maxOf(left, center, right),
                    maxOf(bottomLeft, bottom, bottomRight),
                )
                val localMinimum = minOf(
                    minOf(topLeft, top, topRight),
                    minOf(left, center, right),
                    minOf(bottomLeft, bottom, bottomRight),
                )
                if (localMaximum - localMinimum >= NORMALIZED_LOCAL_CONTRAST_THRESHOLD) {
                    texturedNeighborhoodCount += 1
                }

                val laplacian = top + left - 4.0 * center + right + bottom
                laplacianSum += laplacian
                laplacianSquareSum += laplacian * laplacian

                val horizontalGradient = -topLeft + topRight - 2.0 * left + 2.0 * right - bottomLeft + bottomRight
                val verticalGradient = -topLeft - 2.0 * top - topRight + bottomLeft + 2.0 * bottom + bottomRight
                val normalizedMagnitude = sqrt(
                    horizontalGradient * horizontalGradient + verticalGradient * verticalGradient,
                ) / MAX_NORMALIZED_SOBEL_MAGNITUDE
                if (normalizedMagnitude >= NORMALIZED_SOBEL_EDGE_MAGNITUDE_THRESHOLD) edgeCount += 1
                sampleCount += 1
            }
        }

        val mean = laplacianSum / sampleCount
        return Signals(
            laplacianVariance = (laplacianSquareSum / sampleCount - mean * mean).coerceAtLeast(0.0),
            edgeDensity = edgeCount.toDouble() / sampleCount,
            localTextureSupport = texturedNeighborhoodCount.toDouble() / sampleCount,
        )
    }

    /** Returns area-averaged 0.0–1.0 luma so both kernel signals share a portable scale. */
    private fun downscale(frame: LumaFrame): Samples {
        val scale = maxOf(frame.width, frame.height).toDouble() / MAX_ANALYSIS_DIMENSION
        if (scale <= 1.0) {
            val source = frame.pixels
            return Samples(frame.width, frame.height, DoubleArray(frame.width * frame.height) { index ->
                (source[index].toInt() and 0xff) / 255.0
            })
        }

        val width = (frame.width / scale).toInt().coerceAtLeast(1)
        val height = (frame.height / scale).toInt().coerceAtLeast(1)
        val source = frame.pixels
        val downscaled = DoubleArray(width * height)
        for (targetY in 0 until height) {
            val top = targetY.toDouble() * frame.height / height
            val bottom = (targetY + 1).toDouble() * frame.height / height
            for (targetX in 0 until width) {
                val left = targetX.toDouble() * frame.width / width
                val right = (targetX + 1).toDouble() * frame.width / width
                var weightedSum = 0.0
                for (sourceY in top.toInt() until kotlin.math.ceil(bottom).toInt()) {
                    val verticalOverlap = minOf(sourceY + 1.0, bottom) - maxOf(sourceY.toDouble(), top)
                    if (verticalOverlap <= 0.0) continue
                    for (sourceX in left.toInt() until kotlin.math.ceil(right).toInt()) {
                        val horizontalOverlap = minOf(sourceX + 1.0, right) - maxOf(sourceX.toDouble(), left)
                        if (horizontalOverlap <= 0.0) continue
                        weightedSum += ((source[sourceY * frame.width + sourceX].toInt() and 0xff) / 255.0) *
                            horizontalOverlap * verticalOverlap
                    }
                }
                downscaled[targetY * width + targetX] = weightedSum / ((right - left) * (bottom - top))
            }
        }
        return Samples(width, height, downscaled)
    }

    private data class Signals(
        val laplacianVariance: Double,
        val edgeDensity: Double,
        val localTextureSupport: Double,
    )

    private class Samples(val width: Int, val height: Int, private val data: DoubleArray) {
        operator fun get(x: Int, y: Int): Double = data[y * width + x]
    }
}
