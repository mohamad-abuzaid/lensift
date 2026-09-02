package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId

/** A deterministic near-duplicate cluster, including singleton assets with no matching neighbor. */
class NearDuplicateCluster(
    val id: AssetId,
    assetIds: List<AssetId>,
) {
    val assetIds: List<AssetId> = ClusterAssetIdList(assetIds.toTypedArray())

    init {
        require(this.assetIds.isNotEmpty()) { "Near-duplicate clusters must contain at least one asset" }
        require(this.assetIds == this.assetIds.sortedBy(AssetId::value)) { "Near-duplicate cluster members must be ordered by ID" }
        require(this.assetIds.toSet().size == this.assetIds.size) { "Near-duplicate cluster members must be distinct" }
        require(id == this.assetIds.first()) { "Near-duplicate cluster ID must be its smallest member ID" }
    }

    override fun equals(other: Any?): Boolean =
        other is NearDuplicateCluster && id == other.id && assetIds == other.assetIds

    override fun hashCode(): Int = 31 * id.hashCode() + assetIds.hashCode()

    override fun toString(): String = "NearDuplicateCluster(id=$id, assetIds=$assetIds)"
}

/** Complete-linkage clusters plus the source candidate-generation coverage status. */
data class NearDuplicateClusteringResult(
    val clusters: List<NearDuplicateCluster>,
    val candidateGenerationStatus: CandidateGenerationStatus,
)

/**
 * Forms complete-linkage clusters from policy-qualified perceptual candidates.
 *
 * A merge is allowed only when every cross-cluster pair is a candidate returned by
 * [CandidateBucketer], which means it satisfies the active metadata and perceptual-hash policy.
 * Among allowed merges, the smallest maximum Hamming distance wins; asset IDs resolve every tie.
 */
object NearDuplicateClusterer {
    fun cluster(inputs: Iterable<PerceptualCandidate>, policy: AnalysisPolicy): NearDuplicateClusteringResult {
        val candidates = inputs.sortedBy { it.assetId.value }
        require(candidates.map(PerceptualCandidate::assetId).toSet().size == candidates.size) {
            "Near-duplicate candidate asset IDs must be unique"
        }

        val candidateResult = CandidateBucketer.find(candidates, policy)
        val qualifiedPairs = candidateResult.pairs.toSet()
        var clusters = candidates.map { Cluster(listOf(it)) }

        while (true) {
            val merge = nextMerge(clusters, qualifiedPairs) ?: break
            val mergedMembers = (clusters[merge.leftIndex].members + clusters[merge.rightIndex].members)
                .sortedBy { it.assetId.value }

            clusters = clusters.filterIndexed { index, _ -> index != merge.leftIndex && index != merge.rightIndex } +
                Cluster(mergedMembers)
        }

        return NearDuplicateClusteringResult(
            clusters = clusters
            .map { current ->
                NearDuplicateCluster(
                    id = current.members.first().assetId,
                    assetIds = current.members.map(PerceptualCandidate::assetId),
                )
            }
            .sortedBy { it.id.value },
            candidateGenerationStatus = candidateResult.status,
        )
    }

    private fun nextMerge(clusters: List<Cluster>, qualifiedPairs: Set<CandidatePair>): ClusterMerge? {
        val merges = buildList {
            for (leftIndex in 0 until clusters.lastIndex) {
                for (rightIndex in leftIndex + 1 until clusters.size) {
                    val left = clusters[leftIndex]
                    val right = clusters[rightIndex]
                    if (canMerge(left, right, qualifiedPairs)) {
                        add(
                            ClusterMerge(
                                leftIndex = leftIndex,
                                rightIndex = rightIndex,
                                completeLinkageDistance = completeLinkageDistance(left, right),
                                firstClusterId = left.id,
                                secondClusterId = right.id,
                            ),
                        )
                    }
                }
            }
        }

        return merges.minWithOrNull(
            compareBy<ClusterMerge> { it.completeLinkageDistance }
                .thenBy { it.firstClusterId.value }
                .thenBy { it.secondClusterId.value },
        )
    }

    private fun canMerge(left: Cluster, right: Cluster, qualifiedPairs: Set<CandidatePair>): Boolean =
        left.members.all { leftMember ->
            right.members.all { rightMember -> canonicalPair(leftMember.assetId, rightMember.assetId) in qualifiedPairs }
        }

    private fun completeLinkageDistance(left: Cluster, right: Cluster): Int =
        left.members.maxOf { leftMember ->
            right.members.maxOf { rightMember -> PerceptualHash.distance(leftMember.hash, rightMember.hash) }
        }

    private fun canonicalPair(left: AssetId, right: AssetId): CandidatePair =
        if (left.value < right.value) CandidatePair(left, right) else CandidatePair(right, left)

    private data class Cluster(val members: List<PerceptualCandidate>) {
        val id: AssetId = members.first().assetId
    }

    private data class ClusterMerge(
        val leftIndex: Int,
        val rightIndex: Int,
        val completeLinkageDistance: Int,
        val firstClusterId: AssetId,
        val secondClusterId: AssetId,
    )
}

private class ClusterAssetIdList(private val values: Array<AssetId>) : AbstractList<AssetId>() {
    override val size: Int get() = values.size

    override fun get(index: Int): AssetId = values[index]
}
