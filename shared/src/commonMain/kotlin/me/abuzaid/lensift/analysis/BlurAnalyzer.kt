package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.LumaFrame
import kotlin.math.sqrt

data class BlurEvidence(
    val laplacianVariance: Double,
    val edgeDensity: Double,
    val verdict: BlurVerdict,
)

enum class BlurVerdict { PossiblyBlurred, Inconclusive }

/**
 * Calculates complementary blur signals over a bounded, downscaled luminance frame.
 *
 * Low scores are evidence of reduced detail, not proof of blur: flat and near-flat
 * scenes are therefore always [BlurVerdict.Inconclusive].
 */
object BlurAnalyzer {
    private const val MAX_ANALYSIS_DIMENSION = 256
    private const val MIN_LUMA_DYNAMIC_RANGE_FOR_BLUR_DECISION = 8.0

    /**
     * A Sobel magnitude normalized to [0, 1] is an edge when it reaches this threshold.
     * The normalization divides by the largest 3x3 Sobel magnitude possible for 8-bit luma,
     * keeping edge density comparable across frames.
     */
    private const val NORMALIZED_SOBEL_EDGE_MAGNITUDE_THRESHOLD = 0.125
    private const val MAX_8_BIT_SOBEL_MAGNITUDE = 1020.0 * 1.4142135623730951

    fun analyze(frame: LumaFrame, policy: AnalysisPolicy): BlurEvidence {
        val samples = downscale(frame)
        if (samples.width < 3 || samples.height < 3) return inconclusiveEvidence()

        val signals = calculateSignals(samples)
        val hasEnoughTexture = samples.dynamicRange() >= MIN_LUMA_DYNAMIC_RANGE_FOR_BLUR_DECISION
        val possiblyBlurred = hasEnoughTexture &&
            signals.laplacianVariance <= policy.blur.laplacianVarianceCeiling &&
            signals.edgeDensity <= policy.blur.edgeDensityCeiling

        return BlurEvidence(
            laplacianVariance = signals.laplacianVariance,
            edgeDensity = signals.edgeDensity,
            verdict = if (possiblyBlurred) BlurVerdict.PossiblyBlurred else BlurVerdict.Inconclusive,
        )
    }

    private fun inconclusiveEvidence(): BlurEvidence = BlurEvidence(0.0, 0.0, BlurVerdict.Inconclusive)

    private fun calculateSignals(samples: Samples): Signals {
        var laplacianSum = 0.0
        var laplacianSquareSum = 0.0
        var edgeCount = 0
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

                val laplacian = top + left - 4.0 * center + right + bottom
                laplacianSum += laplacian
                laplacianSquareSum += laplacian * laplacian

                val horizontalGradient = -topLeft + topRight - 2.0 * left + 2.0 * right - bottomLeft + bottomRight
                val verticalGradient = -topLeft - 2.0 * top - topRight + bottomLeft + 2.0 * bottom + bottomRight
                val normalizedMagnitude = sqrt(
                    horizontalGradient * horizontalGradient + verticalGradient * verticalGradient,
                ) / MAX_8_BIT_SOBEL_MAGNITUDE
                if (normalizedMagnitude >= NORMALIZED_SOBEL_EDGE_MAGNITUDE_THRESHOLD) edgeCount += 1
                sampleCount += 1
            }
        }

        val mean = laplacianSum / sampleCount
        return Signals(
            laplacianVariance = (laplacianSquareSum / sampleCount - mean * mean).coerceAtLeast(0.0),
            edgeDensity = edgeCount.toDouble() / sampleCount,
        )
    }

    private fun downscale(frame: LumaFrame): Samples {
        val scale = maxOf(frame.width, frame.height).toDouble() / MAX_ANALYSIS_DIMENSION
        if (scale <= 1.0) {
            val source = frame.pixels
            return Samples(frame.width, frame.height, DoubleArray(frame.width * frame.height) { index ->
                (source[index].toInt() and 0xff).toDouble()
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
                        weightedSum += (source[sourceY * frame.width + sourceX].toInt() and 0xff) * horizontalOverlap * verticalOverlap
                    }
                }
                downscaled[targetY * width + targetX] = weightedSum / ((right - left) * (bottom - top))
            }
        }
        return Samples(width, height, downscaled)
    }

    private data class Signals(val laplacianVariance: Double, val edgeDensity: Double)

    private class Samples(val width: Int, val height: Int, private val data: DoubleArray) {
        operator fun get(x: Int, y: Int): Double = data[y * width + x]

        fun dynamicRange(): Double = data.maxOrNull()!! - data.minOrNull()!!
    }
}
