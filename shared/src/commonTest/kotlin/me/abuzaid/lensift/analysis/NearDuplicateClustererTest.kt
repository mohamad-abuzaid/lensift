package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.BlurPolicy
import me.abuzaid.lensift.domain.Sensitivity
import kotlin.test.Test
import kotlin.test.assertEquals

class NearDuplicateClustererTest {
    @Test
    fun clusterOwnsItsMembershipAfterConstruction() {
        val source = mutableListOf(AssetId("a"), AssetId("b"))
        val cluster = NearDuplicateCluster(AssetId("a"), source)

        source += AssetId("mutated")

        assertEquals(listOf(AssetId("a"), AssetId("b")), cluster.assetIds)
    }

    @Test
    fun completeLinkageKeepsChainedEndpointsInSeparateClusters() {
        val inputs = listOf(candidate("a", 0b000L), candidate("b", 0b001L), candidate("c", 0b011L))
        val result = NearDuplicateClusterer.cluster(inputs, policy())

        assertEquals(
            listOf(cluster("a", "a", "b"), cluster("c", "c")),
            result.clusters,
        )
        assertEquals(CandidateGenerationStatus.Complete, result.candidateGenerationStatus)
    }

    @Test
    fun shufflingAChainKeepsItsClusterIdsAndMemberOrderStable() {
        val inputs = listOf(candidate("a", 0b000L), candidate("b", 0b001L), candidate("c", 0b011L))
        val expected = NearDuplicateClusterer.cluster(inputs, policy()).clusters

        assertEquals(expected, NearDuplicateClusterer.cluster(listOf(inputs[2], inputs[0], inputs[1]), policy()).clusters)
        assertEquals(expected, NearDuplicateClusterer.cluster(listOf(inputs[1], inputs[2], inputs[0]), policy()).clusters)
    }

    @Test
    fun retainsIsolatedAssetsAsSingletonClusters() {
        val inputs = listOf(candidate("match-a", 0L), candidate("isolated", -1L), candidate("match-b", 1L))

        assertEquals(
            listOf(cluster("isolated", "isolated"), cluster("match-a", "match-a", "match-b")),
            NearDuplicateClusterer.cluster(inputs, policy()).clusters,
        )
    }

    @Test
    fun breaksEqualDistanceMergeTiesBySmallestAssetId() {
        val inputs = listOf(candidate("c", 0b010L), candidate("b", 0b001L), candidate("a", 0b000L))

        assertEquals(
            listOf(cluster("a", "a", "b"), cluster("c", "c")),
            NearDuplicateClusterer.cluster(inputs, policy()).clusters,
        )
    }

    @Test
    fun doesNotMergePairsRejectedByTheActiveMetadataPolicy() {
        val inputs = listOf(
            candidate("early", hash = 0L, capturedAtEpochMillis = 0L),
            candidate("late", hash = 0L, capturedAtEpochMillis = 1_001L),
        )

        assertEquals(
            listOf(cluster("early", "early"), cluster("late", "late")),
            NearDuplicateClusterer.cluster(inputs, policy(maxCaptureGapMillis = 1_000L)).clusters,
        )
    }

    private fun cluster(id: String, vararg members: String): NearDuplicateCluster = NearDuplicateCluster(
        id = AssetId(id),
        assetIds = members.map(::AssetId),
    )

    private fun candidate(
        id: String,
        hash: Long,
        capturedAtEpochMillis: Long? = 0L,
    ): PerceptualCandidate = PerceptualCandidate(
        assetId = AssetId(id),
        hash = hash,
        width = 100,
        height = 100,
        capturedAtEpochMillis = capturedAtEpochMillis,
    )

    private fun policy(maxCaptureGapMillis: Long = Long.MAX_VALUE): AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Balanced,
        maxPerceptualDistance = 1,
        maxCaptureGapMillis = maxCaptureGapMillis,
        maxAspectRatioDelta = 1.0,
        blur = BlurPolicy(1.0, 1.0),
    )
}
