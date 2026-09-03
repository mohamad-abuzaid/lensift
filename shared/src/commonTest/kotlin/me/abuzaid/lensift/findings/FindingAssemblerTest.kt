package me.abuzaid.lensift.findings

import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.analysis.BlurVerdict
import me.abuzaid.lensift.analysis.CandidateGenerationStatus
import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.BlurPolicy
import me.abuzaid.lensift.domain.PhotoDescriptor
import me.abuzaid.lensift.domain.Sensitivity
import me.abuzaid.lensift.index.AnalysisRecord
import me.abuzaid.lensift.recommendation.KeeperReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FindingAssemblerTest {
    private val assembler = FindingAssembler()

    @Test
    fun exactHashWorkNarrowsToCompatibleCandidatesThatStillNeedDigests() {
        val records = listOf(
            record("a", width = 400, height = 300, byteCount = 100, hash = 7, sha256 = null),
            record("b", width = 300, height = 400, byteCount = 100, hash = 7, sha256 = "known"),
            record("different-bytes", width = 400, height = 300, byteCount = 200, hash = 7, sha256 = null),
            record("different-dimensions", width = 401, height = 300, byteCount = 100, hash = 7, sha256 = null),
            record("different-phash", width = 400, height = 300, byteCount = 100, hash = 8, sha256 = null),
        )

        assertEquals(listOf(AssetId("a")), assembler.exactHashWork(records).assetIds)
    }

    @Test
    fun exactGroupsRequireEqualNonNullSha256Values() {
        val snapshot = assembler.assemble(
            listOf(
                record("a", hash = 0, sha256 = "same"),
                record("b", hash = 0, sha256 = "same"),
                record("different", hash = 0, sha256 = "different"),
                record("missing", hash = 0, sha256 = null),
            ),
            policy(maxDistance = 0),
        )

        assertEquals(listOf(listOf(AssetId("a"), AssetId("b"))), snapshot.exactGroups.map { it.assetIds })
    }

    @Test
    fun exactCandidateRefinementRejectsConflictingKnownByteCounts() {
        val snapshot = assembler.assemble(
            listOf(
                record("a", byteCount = 100, hash = 0, sha256 = "same"),
                record("b", byteCount = 101, hash = 0, sha256 = "same"),
            ),
            policy(maxDistance = 0),
        )

        assertEquals(emptyList(), snapshot.exactGroups)
    }

    @Test
    fun contradictoryKnownSizesKeepUnknownMembersInTheirOwnFailSafeGroup() {
        val snapshot = assembler.assemble(
            listOf(
                record("a-100", byteCount = 100, hash = 0, sha256 = "same"),
                record("b-100", byteCount = 100, hash = 0, sha256 = "same"),
                record("c-200", byteCount = 200, hash = 0, sha256 = "same"),
                record("d-200", byteCount = 200, hash = 0, sha256 = "same"),
                record("e-unknown", byteCount = null, hash = 0, sha256 = "same"),
                record("f-unknown", byteCount = null, hash = 0, sha256 = "same"),
            ),
            policy(maxDistance = 0),
        )

        assertEquals(
            listOf(
                listOf(AssetId("a-100"), AssetId("b-100")),
                listOf(AssetId("c-200"), AssetId("d-200")),
                listOf(AssetId("e-unknown"), AssetId("f-unknown")),
            ),
            snapshot.exactGroups.map { it.assetIds },
        )
    }

    @Test
    fun assemblesTenThousandVerifiedExactMembersAsOneGroup() {
        val records = List(10_000) { index ->
            record(
                id = index.toString().padStart(5, '0'),
                byteCount = 10,
                hash = 7,
                sha256 = "same",
            )
        }

        val snapshot = assembler.assemble(records, policy(maxDistance = 0))
        val group = snapshot.exactGroups.single()

        assertEquals(10_000, group.assetIds.size)
        assertEquals(AssetId("00000"), group.assetIds.first())
        assertEquals(AssetId("09999"), group.assetIds.last())
        assertEquals(9_999, group.selectedForRemoval.size)
        assertEquals(99_990, snapshot.estimatedRecoverableBytes)
    }

    @Test
    fun tenThousandDistinctKnownSizesInOneCollisionBucketProduceNoHashWork() {
        val records = List(10_000) { index ->
            record(
                id = index.toString().padStart(5, '0'),
                byteCount = index.toLong(),
                hash = 7,
                sha256 = null,
            )
        }

        assertEquals(emptyList(), assembler.exactHashWork(records).assetIds)
    }

    @Test
    fun exactAssetsAreExcludedFromNearAndBlurFindings() {
        val snapshot = assembler.assemble(
            listOf(
                record("exact-a", hash = 0, sha256 = "same", blur = blurredEvidence()),
                record("exact-b", hash = 0, sha256 = "same", blur = blurredEvidence()),
                record("near-singleton", hash = 1, sha256 = "other", blur = blurredEvidence()),
            ),
            policy(maxDistance = 1),
        )

        assertEquals(listOf(listOf(AssetId("exact-a"), AssetId("exact-b"))), snapshot.exactGroups.map { it.assetIds })
        assertEquals(emptyList(), snapshot.nearGroups)
        assertEquals(listOf(AssetId("near-singleton")), snapshot.blurItems.map { it.assetId })
    }

    @Test
    fun nearGroupsReuseCompleteLinkageAndOmitSingletonClusters() {
        val snapshot = assembler.assemble(
            listOf(
                record("a", hash = 0b000, capturedAt = 0),
                record("b", hash = 0b001, capturedAt = 0),
                record("c", hash = 0b011, capturedAt = 0),
                record("isolated", hash = -1, capturedAt = 0),
            ),
            policy(maxDistance = 1),
        )

        assertEquals(listOf(listOf(AssetId("a"), AssetId("b"))), snapshot.nearGroups.map { it.assetIds })
        assertEquals(CandidateGenerationStatus.Complete, snapshot.candidateGenerationStatus)
    }

    @Test
    fun pairLimitReachedRemainsVisibleOnThePublicSnapshot() {
        val records = List(448) { index ->
            record(
                id = index.toString().padStart(3, '0'),
                hash = 0,
                sha256 = "digest-$index",
                capturedAt = index.toLong(),
            )
        }

        val snapshot = assembler.assemble(
            records,
            policy(maxDistance = 64, maxCaptureGapMillis = 0),
        )

        assertEquals(CandidateGenerationStatus.PairLimitReached, snapshot.candidateGenerationStatus)
        assertEquals(emptyList(), snapshot.nearGroups)
    }

    @Test
    fun blurFindingsUseRawEvidenceWithTheCurrentPolicyAndNeverPreselectDeletion() {
        val snapshot = assembler.assemble(
            listOf(
                record(
                    "low-texture",
                    blur = blurredEvidence(localTextureSupport = 0.0, verdict = BlurVerdict.PossiblyBlurred),
                ),
                record(
                    "reclassified",
                    blur = blurredEvidence(localTextureSupport = 1.0, verdict = BlurVerdict.Inconclusive),
                ),
            ),
            policy(maxDistance = 0),
        )

        assertEquals(listOf(AssetId("reclassified")), snapshot.blurItems.map { it.assetId })
        assertEquals(BlurVerdict.PossiblyBlurred, snapshot.blurItems.single().evidence.verdict)
        assertEquals(emptyList(), snapshot.blurItems.single().selectedForRemoval)
    }

    @Test
    fun estimatesSumOnlySelectedKnownBytesAndSaturateOnOverflow() {
        val snapshot = assembler.assemble(
            listOf(
                record("a", byteCount = null, hash = 0, sha256 = "same"),
                record("b", byteCount = null, hash = 0, sha256 = "same"),
                record("c", byteCount = Long.MAX_VALUE, hash = 0, sha256 = "same"),
            ),
            policy(maxDistance = 0),
        )

        assertEquals(listOf(AssetId("b"), AssetId("c")), snapshot.exactGroups.single().selectedForRemoval)
        assertEquals(Long.MAX_VALUE, snapshot.exactGroups.single().estimatedRecoverableBytes)
        assertEquals(Long.MAX_VALUE, snapshot.estimatedRecoverableBytes)
    }

    @Test
    fun favoriteKeeperAndOtherFavoritesAreNeverPreselectedForRemoval() {
        val snapshot = assembler.assemble(
            listOf(
                record("a-favorite", favorite = true, hash = 0, sha256 = "same"),
                record("b-favorite", favorite = true, hash = 0, sha256 = "same"),
                record("c-redundant", hash = 0, sha256 = "same"),
            ),
            policy(maxDistance = 0),
        )

        val group = snapshot.exactGroups.single()
        assertEquals(AssetId("a-favorite"), group.keeper)
        assertEquals(listOf(KeeperReason.Favorite, KeeperReason.StableTieBreak), group.keeperReasons)
        assertEquals(listOf(AssetId("c-redundant")), group.selectedForRemoval)
    }

    @Test
    fun shuffledRepeatedAssemblyHasStableIdsMemberOrderAndRendering() {
        val records = mutableListOf(
            record("near-b", hash = 1, capturedAt = 0),
            record("exact-b", hash = 8, sha256 = "same"),
            record("blur", hash = -1, blur = blurredEvidence()),
            record("exact-a", hash = 8, sha256 = "same"),
            record("near-a", hash = 0, capturedAt = 0),
        )
        val expected = assembler.assemble(records, policy(maxDistance = 1))

        records.reverse()
        val actual = assembler.assemble(records, policy(maxDistance = 1))

        assertEquals(expected, actual)
        assertEquals(expected.toString(), actual.toString())
        assertEquals("exact:7:exact-a7:exact-b", actual.exactGroups.single().id)
        assertEquals("near:6:near-a6:near-b", actual.nearGroups.single().id)
    }

    @Test
    fun publicModelsOwnCollectionsAndRejectInvalidMembership() {
        val members = mutableListOf(AssetId("a"), AssetId("b"))
        val selected = mutableListOf(AssetId("b"))
        val reasons = mutableListOf(KeeperReason.StableTieBreak)
        val group = DuplicateGroup(
            id = "exact:1:a1:b",
            kind = DuplicateKind.Exact,
            assetIds = members,
            keeper = AssetId("a"),
            keeperReasons = reasons,
            selectedForRemoval = selected,
            estimatedRecoverableBytes = 1,
        )

        members += AssetId("c")
        selected.clear()
        reasons.clear()

        assertEquals(listOf(AssetId("a"), AssetId("b")), group.assetIds)
        assertEquals(listOf(AssetId("b")), group.selectedForRemoval)
        assertEquals(listOf(KeeperReason.StableTieBreak), group.keeperReasons)
        assertFailsWith<IllegalArgumentException> {
            DuplicateGroup(
                id = "invalid",
                kind = DuplicateKind.Near,
                assetIds = listOf(AssetId("b"), AssetId("a")),
                keeper = AssetId("a"),
                keeperReasons = emptyList(),
                selectedForRemoval = emptyList(),
                estimatedRecoverableBytes = 0,
            )
        }
    }

    private fun record(
        id: String,
        width: Int = 100,
        height: Int = 100,
        byteCount: Long? = 10,
        hash: Long = id.hashCode().toLong(),
        sha256: String? = null,
        capturedAt: Long? = null,
        favorite: Boolean = false,
        edited: Boolean = false,
        blur: BlurEvidence = sharpEvidence(),
    ): AnalysisRecord = AnalysisRecord(
        descriptor = PhotoDescriptor(
            id = AssetId(id),
            contentSignature = "signature-$id",
            width = width,
            height = height,
            byteCount = byteCount,
            capturedAtEpochMillis = capturedAt,
            isFavorite = favorite,
            isEdited = edited,
        ),
        analyzerVersion = 1,
        perceptualHash = hash,
        sha256 = sha256,
        blurEvidence = blur,
        analyzedAtEpochMillis = 1,
    )

    private fun policy(
        maxDistance: Int,
        maxCaptureGapMillis: Long = Long.MAX_VALUE,
    ): AnalysisPolicy = AnalysisPolicy(
        sensitivity = Sensitivity.Balanced,
        maxPerceptualDistance = maxDistance,
        maxCaptureGapMillis = maxCaptureGapMillis,
        maxAspectRatioDelta = 1.0,
        blur = BlurPolicy(laplacianVarianceCeiling = 0.1, edgeDensityCeiling = 0.1),
    )

    private fun sharpEvidence(): BlurEvidence = BlurEvidence(
        laplacianVariance = 1.0,
        edgeDensity = 1.0,
        localTextureSupport = 1.0,
        verdict = BlurVerdict.Inconclusive,
    )

    private fun blurredEvidence(
        localTextureSupport: Double = 1.0,
        verdict: BlurVerdict = BlurVerdict.PossiblyBlurred,
    ): BlurEvidence = BlurEvidence(
        laplacianVariance = 0.01,
        edgeDensity = 0.01,
        localTextureSupport = localTextureSupport,
        verdict = verdict,
    )
}
