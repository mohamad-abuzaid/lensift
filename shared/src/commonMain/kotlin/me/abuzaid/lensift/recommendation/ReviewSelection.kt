package me.abuzaid.lensift.recommendation

import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.BlurCandidate
import me.abuzaid.lensift.domain.ExactDuplicate
import me.abuzaid.lensift.domain.NearDuplicate
import me.abuzaid.lensift.domain.PhotoDescriptor

/** Conservative initial review selections. Users remain free to change every selection in platform UI. */
object ReviewSelection {
    fun initialFor(
        finding: ExactDuplicate,
        keeper: AssetId,
        descriptorsById: Map<AssetId, PhotoDescriptor>,
    ): Set<AssetId> {
        require(keeper in finding.assetIds) { "The keeper must belong to the exact duplicate finding" }
        return finding.assetIds.filterTo(linkedSetOf()) { assetId ->
            assetId != keeper && descriptorsById[assetId]?.isFavorite == false
        }
    }

    fun initialFor(finding: NearDuplicate): Set<AssetId> = emptySet()

    fun initialFor(finding: BlurCandidate): Set<AssetId> = emptySet()
}
