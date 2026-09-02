package me.abuzaid.lensift.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoModelsTest {
    @Test
    fun evidenceReasonsAreFixedByTheirFindingTypes() {
        val assetIds = listOf(AssetId("a"), AssetId("b"))

        assertEquals(EvidenceReason.IdenticalContent, ExactDuplicate(assetIds).reason)
        assertEquals(EvidenceReason.VisuallySimilar, NearDuplicate(assetIds).reason)
        assertEquals(EvidenceReason.LowSharpnessSignals, BlurCandidate(AssetId("c")).reason)
    }

    @Test
    fun duplicateEvidenceOwnsItsMembershipAfterConstruction() {
        val exactSource = mutableListOf(AssetId("a"), AssetId("b"))
        val nearSource = mutableListOf(AssetId("c"), AssetId("d"))
        val exact = ExactDuplicate(exactSource)
        val near = NearDuplicate(nearSource)

        exactSource += AssetId("mutated")
        nearSource.clear()

        assertEquals(listOf(AssetId("a"), AssetId("b")), exact.assetIds)
        assertEquals(listOf(AssetId("c"), AssetId("d")), near.assetIds)
    }
}
