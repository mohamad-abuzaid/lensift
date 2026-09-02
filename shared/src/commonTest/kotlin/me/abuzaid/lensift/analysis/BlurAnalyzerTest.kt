package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.BlurPolicy
import me.abuzaid.lensift.domain.LumaFrame
import me.abuzaid.lensift.domain.Sensitivity
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlurAnalyzerTest {
    @Test
    fun boxBlurredGridHasLowerSignalsAndIsTheOnlyPossibleBlur() {
        val sharp = gridFrame(64, 64)
        val blurred = boxBlur(sharp, radius = 4)

        val sharpEvidence = BlurAnalyzer.analyze(sharp, balancedPolicy())
        val blurredEvidence = BlurAnalyzer.analyze(blurred, balancedPolicy())

        assertFinite(sharpEvidence)
        assertFinite(blurredEvidence)
        assertTrue(blurredEvidence.laplacianVariance < sharpEvidence.laplacianVariance)
        assertTrue(blurredEvidence.edgeDensity < sharpEvidence.edgeDensity)
        assertEquals(BlurVerdict.Inconclusive, sharpEvidence.verdict, "sharp=$sharpEvidence")
        assertEquals(BlurVerdict.PossiblyBlurred, blurredEvidence.verdict, "blurred=$blurredEvidence")
    }

    @Test
    fun requiresBothSignalsToBeAtOrBelowTheirPolicyCeilings() {
        val evidence = BlurAnalyzer.analyze(gridFrame(64, 64), balancedPolicy())
        val belowEdgeCeiling = max(0.0, evidence.edgeDensity / 2.0)
        val belowLaplacianCeiling = evidence.laplacianVariance / 2.0

        val laplacianOnly = BlurAnalyzer.analyze(
            gridFrame(64, 64),
            balancedPolicy(BlurPolicy(evidence.laplacianVariance + 1.0, belowEdgeCeiling)),
        )
        val edgeOnly = BlurAnalyzer.analyze(
            gridFrame(64, 64),
            balancedPolicy(BlurPolicy(belowLaplacianCeiling, evidence.edgeDensity + 1.0)),
        )

        assertEquals(BlurVerdict.Inconclusive, laplacianOnly.verdict)
        assertEquals(BlurVerdict.Inconclusive, edgeOnly.verdict)
    }

    @Test
    fun signalsExactlyAtTheirPolicyCeilingsArePossiblyBlurred() {
        val frame = gridFrame(64, 64)
        val rawEvidence = BlurAnalyzer.analyze(frame, balancedPolicy())

        val atCeilings = BlurAnalyzer.analyze(
            frame,
            balancedPolicy(BlurPolicy(rawEvidence.laplacianVariance, rawEvidence.edgeDensity)),
        )

        assertEquals(BlurVerdict.PossiblyBlurred, atCeilings.verdict)
    }

    @Test
    fun uniformFrameIsInconclusiveEvenWhenBothRawSignalsAreLow() {
        val evidence = BlurAnalyzer.analyze(LumaFrame(32, 32, ByteArray(32 * 32) { 120.toByte() }), balancedPolicy())

        assertFinite(evidence)
        assertEquals(0.0, evidence.laplacianVariance)
        assertEquals(0.0, evidence.edgeDensity)
        assertEquals(BlurVerdict.Inconclusive, evidence.verdict)
    }

    @Test
    fun nearUniformFrameIsInconclusiveEvenWhenBothRawSignalsAreLow() {
        val evidence = BlurAnalyzer.analyze(
            LumaFrame(32, 32, ByteArray(32 * 32) { index -> (120 + index % 8).toByte() }),
            balancedPolicy(),
        )

        assertFinite(evidence)
        assertEquals(BlurVerdict.Inconclusive, evidence.verdict)
    }

    @Test
    fun framesTooSmallForAThreeByThreeKernelAreInconclusiveWithFiniteScores() {
        listOf(LumaFrame(2, 8, ByteArray(16)), LumaFrame(8, 2, ByteArray(16))).forEach { frame ->
            val evidence = BlurAnalyzer.analyze(frame, balancedPolicy())

            assertFinite(evidence)
            assertEquals(BlurVerdict.Inconclusive, evidence.verdict)
        }
    }

    private fun balancedPolicy(blur: BlurPolicy = BlurPolicy(2_000.0, 0.20)): AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Balanced,
        maxPerceptualDistance = 8,
        maxCaptureGapMillis = 90_000,
        maxAspectRatioDelta = 0.02,
        blur = blur,
    )

    private fun gridFrame(width: Int, height: Int): LumaFrame = LumaFrame(
        width,
        height,
        ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if ((x / 8 + y / 8) % 2 == 0) 0 else 255.toByte()
        },
    )

    private fun boxBlur(frame: LumaFrame, radius: Int): LumaFrame {
        val input = frame.pixels
        val output = ByteArray(input.size)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                var sum = 0
                var count = 0
                for (offsetY in -radius..radius) {
                    for (offsetX in -radius..radius) {
                        val sourceX = (x + offsetX).coerceIn(0, frame.width - 1)
                        val sourceY = (y + offsetY).coerceIn(0, frame.height - 1)
                        sum += input[sourceY * frame.width + sourceX].toInt() and 0xff
                        count += 1
                    }
                }
                output[y * frame.width + x] = (sum / count).toByte()
            }
        }
        return LumaFrame(frame.width, frame.height, output)
    }

    private fun assertFinite(evidence: BlurEvidence) {
        assertTrue(evidence.laplacianVariance.isFinite(), "laplacian variance must be finite")
        assertTrue(evidence.edgeDensity.isFinite(), "edge density must be finite")
    }
}
