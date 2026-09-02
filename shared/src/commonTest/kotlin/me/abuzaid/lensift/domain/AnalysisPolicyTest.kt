package me.abuzaid.lensift.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalysisPolicyTest {
    @Test
    fun rejectsInvalidThresholds() {
        assertFailsWith<IllegalArgumentException> {
            AnalysisPolicy(Sensitivity.Balanced, -1, 90_000, 0.02, BlurPolicy(85.0, 0.075))
        }
    }

    @Test
    fun lumaFrameRejectsWrongBufferSize() {
        assertFailsWith<IllegalArgumentException> { LumaFrame(4, 4, ByteArray(15)) }
    }

    @Test
    fun lumaFrameCopiesInputAndOutputBuffers() {
        val input = byteArrayOf(1, 2, 3, 4)
        val frame = LumaFrame(2, 2, input)
        input[0] = 9
        val output = frame.pixels
        output[1] = 9

        assertEquals(1, frame.pixels[0])
        assertEquals(2, frame.pixels[1])
    }

    @Test
    fun rejectsInvalidDescriptorAndPolicyValues() {
        assertFailsWith<IllegalArgumentException> { AssetId(" ") }
        assertFailsWith<IllegalArgumentException> {
            PhotoDescriptor(AssetId("a"), "sig", 0, 10, -1, null, false, false)
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisPolicy(Sensitivity.Balanced, 65, 0, 0.0, BlurPolicy(1.0, 0.1))
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisPolicy(Sensitivity.Balanced, 0, -1, 0.0, BlurPolicy(1.0, 0.1))
        }
        assertFailsWith<IllegalArgumentException> {
            AnalysisPolicy(Sensitivity.Balanced, 0, 0, -0.1, BlurPolicy(1.0, 0.1))
        }
        assertFailsWith<IllegalArgumentException> {
            BlurPolicy(-1.0, 0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            BlurPolicy(Double.POSITIVE_INFINITY, 0.1)
        }
    }
}
