package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.BlurPolicy
import me.abuzaid.lensift.domain.Sensitivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandidateBucketerTest {
    @Test
    fun rejectsPairsOutsideCaptureTimeWindowButKeepsUnknownCaptureTimes() {
        val policy = policy(maxCaptureGapMillis = 1_000)
        val first = candidate("a", capturedAtEpochMillis = 0)
        val tooLate = candidate("b", capturedAtEpochMillis = 1_001)
        val unknownTime = candidate("c", capturedAtEpochMillis = null)

        val pairs = CandidateBucketer.find(listOf(tooLate, unknownTime, first), policy)

        assertFalse(CandidatePair(AssetId("a"), AssetId("b")) in pairs)
        assertTrue(CandidatePair(AssetId("a"), AssetId("c")) in pairs)
        assertTrue(CandidatePair(AssetId("b"), AssetId("c")) in pairs)
    }

    @Test
    fun rejectsPairsOutsideNormalizedAspectRatioWindow() {
        val square = candidate("square", width = 100, height = 100)
        val wide = candidate("wide", width = 200, height = 100)

        assertEquals(emptyList(), CandidateBucketer.find(listOf(wide, square), policy(maxAspectRatioDelta = 0.1)))
    }

    @Test
    fun returnsLexicographicallyCanonicalPairsInStableOrder() {
        val expected = listOf(
            CandidatePair(AssetId("a"), AssetId("m")),
            CandidatePair(AssetId("a"), AssetId("z")),
            CandidatePair(AssetId("m"), AssetId("z")),
        )
        val candidates = listOf(candidate("z"), candidate("a"), candidate("m"))

        assertEquals(expected, CandidateBucketer.find(candidates, policy()))
        assertEquals(expected, CandidateBucketer.find(listOf(candidates[1], candidates[2], candidates[0]), policy()))
        assertEquals(expected, CandidateBucketer.find(listOf(candidates[2], candidates[0], candidates[1]), policy()))
    }

    @Test
    fun everyGeneratedHashWithinThresholdSharesABandAndAppearsAsACandidate() {
        val baseHash = 0x1357_9bdf_2468_ace0L

        for (threshold in 0..63) {
            for (changedBitCount in linkedSetOf(0, minOf(8, threshold), threshold)) {
                val changedHash = baseHash xor changesAcrossBandPrefixes(threshold, changedBitCount)
                val firstBands = CandidateBucketer.bandsFor(baseHash, threshold).toSet()
                val secondBands = CandidateBucketer.bandsFor(changedHash, threshold).toSet()
                val expected = CandidatePair(AssetId("a"), AssetId("b"))
                val pairs = CandidateBucketer.find(
                    listOf(candidate("b", hash = changedHash), candidate("a", hash = baseHash)),
                    policy(maxPerceptualDistance = threshold),
                )

                assertEquals(changedBitCount, PerceptualHash.distance(baseHash, changedHash), "threshold=$threshold")
                assertTrue(firstBands.intersect(secondBands).isNotEmpty(), "threshold=$threshold")
                assertTrue(expected in pairs, "threshold=$threshold")
            }
        }
    }

    @Test
    fun verifiesAnOverlappingBandHitOnlyOnce() {
        var distanceCalls = 0
        val pairs = CandidateBucketer.find(
            listOf(candidate("a"), candidate("b")),
            policy(maxPerceptualDistance = 8),
        ) { left, right ->
            distanceCalls += 1
            PerceptualHash.distance(left, right)
        }

        assertEquals(listOf(CandidatePair(AssetId("a"), AssetId("b"))), pairs)
        assertEquals(1, distanceCalls)
    }

    @Test
    fun maximumDistanceQualifiesEveryMetadataCompatiblePairWithoutBands() {
        val pairs = CandidateBucketer.find(
            listOf(candidate("inverse", hash = -1L), candidate("zero", hash = 0L)),
            policy(maxPerceptualDistance = 64),
        )

        assertEquals(listOf(CandidatePair(AssetId("inverse"), AssetId("zero"))), pairs)
        assertEquals(emptyList(), CandidateBucketer.bandsFor(0L, 64))
    }

    private fun changesAcrossBandPrefixes(threshold: Int, changedBitCount: Int): Long =
        CandidateBucketer.bandsFor(0L, threshold).take(changedBitCount).fold(0L) { changes, band ->
            changes or (1L shl band.startBit)
        }

    private fun candidate(
        id: String,
        hash: Long = 0L,
        width: Int = 100,
        height: Int = 100,
        capturedAtEpochMillis: Long? = 0L,
    ): PerceptualCandidate = PerceptualCandidate(AssetId(id), hash, width, height, capturedAtEpochMillis)

    private fun policy(
        maxPerceptualDistance: Int = 8,
        maxCaptureGapMillis: Long = Long.MAX_VALUE,
        maxAspectRatioDelta: Double = 1.0,
    ): AnalysisPolicy = AnalysisPolicy(
        Sensitivity.Balanced,
        maxPerceptualDistance,
        maxCaptureGapMillis,
        maxAspectRatioDelta,
        BlurPolicy(1.0, 1.0),
    )
}
