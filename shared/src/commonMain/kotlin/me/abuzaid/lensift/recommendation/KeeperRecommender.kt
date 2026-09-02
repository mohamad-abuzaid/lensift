package me.abuzaid.lensift.recommendation

import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.PhotoDescriptor

data class KeeperRecommendation(
    val keeper: AssetId,
    val reasons: List<KeeperReason>,
)

enum class KeeperReason { Favorite, Edited, Sharper, HigherResolution, StableTieBreak }

/** Produces a deterministic keeper choice without attaching presentation text to shared logic. */
object KeeperRecommender {
    fun recommend(
        photos: Iterable<PhotoDescriptor>,
        blurEvidence: Map<AssetId, BlurEvidence> = emptyMap(),
    ): KeeperRecommendation {
        val candidates = photos.toList()
        require(candidates.isNotEmpty()) { "A keeper recommendation requires at least one photo" }
        require(candidates.map(PhotoDescriptor::id).toSet().size == candidates.size) {
            "A keeper recommendation requires distinct asset IDs"
        }

        val keeper = candidates.maxWithOrNull { left, right -> compare(left, right, blurEvidence) }!!
        return KeeperRecommendation(keeper.id, reasonsFor(keeper, candidates, blurEvidence))
    }

    private fun compare(
        left: PhotoDescriptor,
        right: PhotoDescriptor,
        blurEvidence: Map<AssetId, BlurEvidence>,
    ): Int {
        compareValues(left.isFavorite, right.isFavorite).takeIf { it != 0 }?.let { return it }
        compareValues(left.isEdited, right.isEdited).takeIf { it != 0 }?.let { return it }
        compareSharpness(left, right, blurEvidence).takeIf { it != 0 }?.let { return it }
        compareValues(pixelCount(left), pixelCount(right)).takeIf { it != 0 }?.let { return it }
        return right.id.value.compareTo(left.id.value)
    }

    private fun reasonsFor(
        keeper: PhotoDescriptor,
        candidates: List<PhotoDescriptor>,
        blurEvidence: Map<AssetId, BlurEvidence>,
    ): List<KeeperReason> = buildList {
        val alternatives = candidates.filterNot { it.id == keeper.id }
        if (keeper.isFavorite && alternatives.any { !it.isFavorite }) add(KeeperReason.Favorite)
        if (keeper.isEdited && alternatives.any { !it.isEdited }) add(KeeperReason.Edited)
        if (alternatives.any { compareSharpness(keeper, it, blurEvidence) > 0 }) add(KeeperReason.Sharper)
        if (alternatives.any { pixelCount(keeper) > pixelCount(it) }) add(KeeperReason.HigherResolution)
        if (hasStableTieBreak(keeper, alternatives, blurEvidence)) add(KeeperReason.StableTieBreak)
    }

    private fun hasStableTieBreak(
        keeper: PhotoDescriptor,
        alternatives: List<PhotoDescriptor>,
        blurEvidence: Map<AssetId, BlurEvidence>,
    ): Boolean = alternatives.any {
        keeper.id.value < it.id.value &&
            keeper.isFavorite == it.isFavorite &&
            keeper.isEdited == it.isEdited &&
            compareSharpness(keeper, it, blurEvidence) == 0 &&
            pixelCount(keeper) == pixelCount(it)
    }

    private fun compareSharpness(
        left: PhotoDescriptor,
        right: PhotoDescriptor,
        blurEvidence: Map<AssetId, BlurEvidence>,
    ): Int {
        val leftEvidence = validEvidence(blurEvidence[left.id])
        val rightEvidence = validEvidence(blurEvidence[right.id])
        if (leftEvidence == null || rightEvidence == null) return 0
        compareValues(leftEvidence.laplacianVariance, rightEvidence.laplacianVariance)
            .takeIf { it != 0 }
            ?.let { return it }
        return compareValues(leftEvidence.edgeDensity, rightEvidence.edgeDensity)
    }

    private fun validEvidence(evidence: BlurEvidence?): BlurEvidence? = evidence?.takeIf {
        it.laplacianVariance.isFinite() && it.edgeDensity.isFinite()
    }

    private fun pixelCount(photo: PhotoDescriptor): Long = photo.width.toLong() * photo.height.toLong()
}
