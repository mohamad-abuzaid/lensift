package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.LumaFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerceptualHashTest {
    @Test
    fun identicalFramesHaveIdenticalHashes() {
        val frame = gradientFrame(48, 37)

        assertEquals(PerceptualHash.compute(frame), PerceptualHash.compute(frame))
    }

    @Test
    fun brightnessShiftChangesAtMostFourHashBits() {
        val original = gradientFrame(48, 37, maximumSample = 200)
        val brighter = LumaFrame(
            original.width,
            original.height,
            original.pixels.map { sample -> (sample.toInt() and 0xff).plus(20).coerceAtMost(255).toByte() }.toByteArray(),
        )

        val distance = PerceptualHash.distance(PerceptualHash.compute(original), PerceptualHash.compute(brighter))

        assertTrue(distance <= 4, "brightness-shift distance was $distance")
    }

    @Test
    fun checkerboardDiffersFromGradientByAtLeastTwentyBits() {
        val gradient = gradientFrame(32, 32)
        val checkerboard = LumaFrame(32, 32, ByteArray(32 * 32) { index ->
            val x = index % 32
            val y = index / 32
            if ((x / 3 + y / 5) % 2 == 0) 0 else 255.toByte()
        })

        val distance = PerceptualHash.distance(PerceptualHash.compute(gradient), PerceptualHash.compute(checkerboard))

        assertTrue(distance >= 20, "checkerboard distance was $distance")
    }

    @Test
    fun normalizesFramesSmallerThanTheHashGrid() {
        val singlePixel = LumaFrame(1, 1, byteArrayOf(127))

        assertEquals(0, PerceptualHash.distance(PerceptualHash.compute(singlePixel), PerceptualHash.compute(singlePixel)))
    }

    @Test
    fun packsTheUnmodifiedDctDcComparison() {
        val uniformFrame = LumaFrame(1, 1, byteArrayOf(127))

        assertTrue(PerceptualHash.compute(uniformFrame) < 0L)
    }

    private fun gradientFrame(width: Int, height: Int, maximumSample: Int = 255): LumaFrame = LumaFrame(
        width,
        height,
        ByteArray(width * height) { index -> ((index % width) * maximumSample / (width - 1)).toByte() },
    )
}
