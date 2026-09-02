package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.LumaFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** A deterministic 64-bit DCT perceptual hash over normalized luminance. */
object PerceptualHash {
    private const val NORMALIZED_SIZE = 32
    private const val HASH_SIZE = 8
    private const val COEFFICIENT_ZERO_EPSILON = 1e-6

    fun compute(frame: LumaFrame): Long {
        val normalized = resizeByAreaAverage(frame)
        val average = normalized.average()
        normalized.indices.forEach { index -> normalized[index] -= average }
        val coefficients = dctLowFrequencyCoefficients(normalized)
        coefficients[0] = 0.0
        coefficients.indices.forEach { index ->
            if (abs(coefficients[index]) < COEFFICIENT_ZERO_EPSILON) coefficients[index] = 0.0
        }
        val median = coefficients.copyOfRange(1, coefficients.size).sorted()[31]

        var hash = 0L
        coefficients.forEach { coefficient ->
            hash = hash shl 1
            if (coefficient > median) hash = hash or 1L
        }
        return hash
    }

    fun distance(left: Long, right: Long): Int = (left xor right).countOneBits()

    private fun resizeByAreaAverage(frame: LumaFrame): DoubleArray {
        val source = frame.pixels
        val normalized = DoubleArray(NORMALIZED_SIZE * NORMALIZED_SIZE)

        for (targetY in 0 until NORMALIZED_SIZE) {
            val top = targetY.toDouble() * frame.height / NORMALIZED_SIZE
            val bottom = (targetY + 1).toDouble() * frame.height / NORMALIZED_SIZE
            for (targetX in 0 until NORMALIZED_SIZE) {
                val left = targetX.toDouble() * frame.width / NORMALIZED_SIZE
                val right = (targetX + 1).toDouble() * frame.width / NORMALIZED_SIZE
                var weightedSum = 0.0

                for (sourceY in top.toInt() until kotlin.math.ceil(bottom).toInt()) {
                    val verticalOverlap = minOf(sourceY + 1.0, bottom) - maxOf(sourceY.toDouble(), top)
                    if (verticalOverlap <= 0.0) continue
                    for (sourceX in left.toInt() until kotlin.math.ceil(right).toInt()) {
                        val horizontalOverlap = minOf(sourceX + 1.0, right) - maxOf(sourceX.toDouble(), left)
                        if (horizontalOverlap <= 0.0) continue
                        weightedSum += (source[sourceY * frame.width + sourceX].toInt() and 0xff) *
                            horizontalOverlap * verticalOverlap
                    }
                }
                normalized[targetY * NORMALIZED_SIZE + targetX] = weightedSum / ((right - left) * (bottom - top))
            }
        }
        return normalized
    }

    private fun dctLowFrequencyCoefficients(samples: DoubleArray): DoubleArray {
        val coefficients = DoubleArray(HASH_SIZE * HASH_SIZE)
        for (verticalFrequency in 0 until HASH_SIZE) {
            val verticalScale = if (verticalFrequency == 0) 1.0 / sqrt(NORMALIZED_SIZE.toDouble()) else sqrt(2.0 / NORMALIZED_SIZE)
            for (horizontalFrequency in 0 until HASH_SIZE) {
                val horizontalScale = if (horizontalFrequency == 0) 1.0 / sqrt(NORMALIZED_SIZE.toDouble()) else sqrt(2.0 / NORMALIZED_SIZE)
                var sum = 0.0
                for (y in 0 until NORMALIZED_SIZE) {
                    val verticalBasis = cos((2 * y + 1) * verticalFrequency * PI / (2 * NORMALIZED_SIZE))
                    for (x in 0 until NORMALIZED_SIZE) {
                        val horizontalBasis = cos((2 * x + 1) * horizontalFrequency * PI / (2 * NORMALIZED_SIZE))
                        sum += samples[y * NORMALIZED_SIZE + x] * verticalBasis * horizontalBasis
                    }
                }
                coefficients[verticalFrequency * HASH_SIZE + horizontalFrequency] = sum * verticalScale * horizontalScale
            }
        }
        return coefficients
    }
}
