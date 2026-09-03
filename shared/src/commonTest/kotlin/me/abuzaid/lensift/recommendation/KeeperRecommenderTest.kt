package me.abuzaid.lensift.recommendation

import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.analysis.BlurVerdict
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.PhotoDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

class KeeperRecommenderTest {
    @Test
    fun favoriteOutranksEveryLowerPrioritySignal() {
        val favorite = photo("favorite", favorite = true)
        val editedSharperLarger = photo("other", edited = true, width = 4_000, height = 3_000)

        assertEquals(
            KeeperRecommendation(favorite.id, listOf(KeeperReason.Favorite)),
            KeeperRecommender.recommend(
                listOf(favorite, editedSharperLarger),
                mapOf(editedSharperLarger.id to evidence(20.0, 0.8)),
            ),
        )
    }

    @Test
    fun editedVersionOutranksSharpnessAndPixelCount() {
        val edited = photo("edited", edited = true)
        val sharperLarger = photo("other", width = 4_000, height = 3_000)

        assertEquals(
            KeeperRecommendation(edited.id, listOf(KeeperReason.Edited)),
            KeeperRecommender.recommend(
                listOf(edited, sharperLarger),
                mapOf(sharperLarger.id to evidence(20.0, 0.8)),
            ),
        )
    }

    @Test
    fun strongerBlurEvidenceOutranksPixelCount() {
        val sharper = photo("sharper")
        val larger = photo("larger", width = 4_000, height = 3_000)

        assertEquals(
            KeeperRecommendation(sharper.id, listOf(KeeperReason.Sharper)),
            KeeperRecommender.recommend(
                listOf(sharper, larger),
                mapOf(
                    sharper.id to evidence(20.0, 0.8),
                    larger.id to evidence(10.0, 0.4),
                ),
            ),
        )
    }

    @Test
    fun missingBlurEvidenceFallsThroughToPixelCount() {
        val finiteButSmaller = photo("a-finite")
        val missingButLarger = photo("z-missing", width = 4_000, height = 3_000)

        assertEquals(
            KeeperRecommendation(missingButLarger.id, listOf(KeeperReason.HigherResolution)),
            KeeperRecommender.recommend(
                listOf(finiteButSmaller, missingButLarger),
                mapOf(finiteButSmaller.id to evidence(20.0, 0.8)),
            ),
        )
    }

    @Test
    fun nonFiniteBlurEvidenceFallsThroughToStableIdentifier() {
        val nonFiniteEarlier = photo("a-non-finite")
        val finiteLater = photo("z-finite")

        assertEquals(
            KeeperRecommendation(nonFiniteEarlier.id, listOf(KeeperReason.StableTieBreak)),
            KeeperRecommender.recommend(
                listOf(nonFiniteEarlier, finiteLater),
                mapOf(
                    nonFiniteEarlier.id to evidence(Double.NaN, 0.8),
                    finiteLater.id to evidence(20.0, 0.8),
                ),
            ),
        )
    }

    @Test
    fun greaterPixelCountOutranksStableIdentifier() {
        val larger = photo("z-larger", width = 4_000, height = 3_000)
        val smaller = photo("a-smaller")

        assertEquals(
            KeeperRecommendation(larger.id, listOf(KeeperReason.HigherResolution)),
            KeeperRecommender.recommend(listOf(larger, smaller)),
        )
    }

    @Test
    fun earlierAssetIdBreaksAnOtherwiseEqualTie() {
        val later = photo("z-later")
        val earlier = photo("a-earlier")

        assertEquals(
            KeeperRecommendation(earlier.id, listOf(KeeperReason.StableTieBreak)),
            KeeperRecommender.recommend(listOf(later, earlier)),
        )
    }

    @Test
    fun stableTieBreakExplainsTheTiedRivalEvenWhenAnotherCandidateDiffers() {
        val earlierFavorite = photo("a-earlier", favorite = true)
        val laterFavorite = photo("b-later", favorite = true)
        val nonFavorite = photo("c-non-favorite")

        assertEquals(
            KeeperRecommendation(
                earlierFavorite.id,
                listOf(KeeperReason.Favorite, KeeperReason.StableTieBreak),
            ),
            KeeperRecommender.recommend(listOf(laterFavorite, nonFavorite, earlierFavorite)),
        )
    }

    @Test
    fun listsAllApplicableReasonsInRankingOrder() {
        val winner = photo("winner", favorite = true, edited = true, width = 4_000, height = 3_000)
        val other = photo("other")

        assertEquals(
            KeeperRecommendation(
                winner.id,
                listOf(
                    KeeperReason.Favorite,
                    KeeperReason.Edited,
                    KeeperReason.Sharper,
                    KeeperReason.HigherResolution,
                ),
            ),
            KeeperRecommender.recommend(
                listOf(winner, other),
                mapOf(
                    winner.id to evidence(20.0, 0.8),
                    other.id to evidence(10.0, 0.4),
                ),
            ),
        )
    }

    private fun photo(
        id: String,
        favorite: Boolean = false,
        edited: Boolean = false,
        width: Int = 1_000,
        height: Int = 1_000,
    ): PhotoDescriptor = PhotoDescriptor(
        id = AssetId(id),
        contentSignature = "signature-$id",
        width = width,
        height = height,
        byteCount = null,
        capturedAtEpochMillis = null,
        isFavorite = favorite,
        isEdited = edited,
    )

    private fun evidence(laplacianVariance: Double, edgeDensity: Double): BlurEvidence = BlurEvidence(
        laplacianVariance = laplacianVariance,
        edgeDensity = edgeDensity,
        localTextureSupport = 1.0,
        verdict = BlurVerdict.Inconclusive,
    )
}
