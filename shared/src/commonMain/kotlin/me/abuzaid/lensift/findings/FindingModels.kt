package me.abuzaid.lensift.findings

import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.analysis.BlurVerdict
import me.abuzaid.lensift.analysis.CandidateGenerationStatus
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.recommendation.KeeperReason

enum class DuplicateKind { Exact, Near }

class ExactHashWork(assetIds: List<AssetId>) {
    private val ownedAssetIds = assetIds.toList()

    val assetIds: List<AssetId>
        get() = ownedAssetIds.toList()

    init {
        require(ownedAssetIds == ownedAssetIds.sortedBy(AssetId::value)) { "Exact-hash work must be ordered by asset ID" }
        require(ownedAssetIds.toSet().size == ownedAssetIds.size) { "Exact-hash work must contain distinct assets" }
    }

    override fun equals(other: Any?): Boolean = other is ExactHashWork && ownedAssetIds == other.ownedAssetIds

    override fun hashCode(): Int = ownedAssetIds.hashCode()

    override fun toString(): String = "ExactHashWork(assetIds=$ownedAssetIds)"
}

class DuplicateGroup(
    val id: String,
    val kind: DuplicateKind,
    assetIds: List<AssetId>,
    val keeper: AssetId,
    keeperReasons: List<KeeperReason>,
    selectedForRemoval: List<AssetId>,
    val estimatedRecoverableBytes: Long,
) {
    private val ownedAssetIds = assetIds.toList()
    private val ownedKeeperReasons = keeperReasons.toList()
    private val ownedSelectedForRemoval = selectedForRemoval.toList()

    val assetIds: List<AssetId>
        get() = ownedAssetIds.toList()

    val keeperReasons: List<KeeperReason>
        get() = ownedKeeperReasons.toList()

    val selectedForRemoval: List<AssetId>
        get() = ownedSelectedForRemoval.toList()

    init {
        require(id.isNotBlank()) { "Finding group ID must not be blank" }
        require(ownedAssetIds.size >= 2) { "Duplicate groups require at least two assets" }
        require(ownedAssetIds == ownedAssetIds.sortedBy(AssetId::value)) { "Duplicate group members must be ordered by asset ID" }
        val assetIdSet = ownedAssetIds.toSet()
        require(assetIdSet.size == ownedAssetIds.size) { "Duplicate group members must be distinct" }
        require(id == stableFindingId(kind.idPrefix, ownedAssetIds)) { "Finding group ID must be derived from its kind and members" }
        require(keeper in ownedAssetIds) { "The recommended keeper must belong to its duplicate group" }
        require(ownedKeeperReasons.toSet().size == ownedKeeperReasons.size) { "Keeper reasons must be distinct" }
        require(ownedSelectedForRemoval == ownedSelectedForRemoval.sortedBy(AssetId::value)) {
            "Selected assets must be ordered by asset ID"
        }
        require(ownedSelectedForRemoval.toSet().size == ownedSelectedForRemoval.size) { "Selected assets must be distinct" }
        require(ownedSelectedForRemoval.all(assetIdSet::contains)) { "Selected assets must belong to their duplicate group" }
        require(keeper !in ownedSelectedForRemoval) { "The recommended keeper must not be selected for removal" }
        require(kind != DuplicateKind.Near || ownedSelectedForRemoval.isEmpty()) {
            "Near-duplicate groups must not preselect removals"
        }
        require(estimatedRecoverableBytes >= 0) { "Estimated recoverable bytes must not be negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is DuplicateGroup &&
            id == other.id &&
            kind == other.kind &&
            ownedAssetIds == other.ownedAssetIds &&
            keeper == other.keeper &&
            ownedKeeperReasons == other.ownedKeeperReasons &&
            ownedSelectedForRemoval == other.ownedSelectedForRemoval &&
            estimatedRecoverableBytes == other.estimatedRecoverableBytes

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + ownedAssetIds.hashCode()
        result = 31 * result + keeper.hashCode()
        result = 31 * result + ownedKeeperReasons.hashCode()
        result = 31 * result + ownedSelectedForRemoval.hashCode()
        return 31 * result + estimatedRecoverableBytes.hashCode()
    }

    override fun toString(): String =
        "DuplicateGroup(id=$id, kind=$kind, assetIds=$ownedAssetIds, keeper=$keeper, " +
            "keeperReasons=$ownedKeeperReasons, selectedForRemoval=$ownedSelectedForRemoval, " +
            "estimatedRecoverableBytes=$estimatedRecoverableBytes)"
}

class BlurFinding(
    val id: String,
    val assetId: AssetId,
    val evidence: BlurEvidence,
) {
    val selectedForRemoval: List<AssetId> = emptyList()

    init {
        require(id == stableFindingId("blur", listOf(assetId))) { "Blur finding ID must be derived from its asset" }
        require(evidence.verdict == BlurVerdict.PossiblyBlurred) { "Blur findings require a current possibly-blurred verdict" }
    }

    override fun equals(other: Any?): Boolean =
        other is BlurFinding && id == other.id && assetId == other.assetId && evidence == other.evidence

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + assetId.hashCode()) + evidence.hashCode()

    override fun toString(): String = "BlurFinding(id=$id, assetId=$assetId, evidence=$evidence, selectedForRemoval=[])"
}

class FindingSnapshot(
    exactGroups: List<DuplicateGroup>,
    nearGroups: List<DuplicateGroup>,
    blurItems: List<BlurFinding>,
    val candidateGenerationStatus: CandidateGenerationStatus,
    val estimatedRecoverableBytes: Long,
) {
    private val ownedExactGroups = exactGroups.toList()
    private val ownedNearGroups = nearGroups.toList()
    private val ownedBlurItems = blurItems.toList()

    val exactGroups: List<DuplicateGroup>
        get() = ownedExactGroups.toList()

    val nearGroups: List<DuplicateGroup>
        get() = ownedNearGroups.toList()

    val blurItems: List<BlurFinding>
        get() = ownedBlurItems.toList()

    init {
        require(ownedExactGroups.all { it.kind == DuplicateKind.Exact }) { "Exact findings must have exact kind" }
        require(ownedNearGroups.all { it.kind == DuplicateKind.Near }) { "Near findings must have near kind" }
        require(ownedExactGroups == ownedExactGroups.sortedBy(DuplicateGroup::id)) { "Exact findings must have stable order" }
        require(ownedNearGroups == ownedNearGroups.sortedBy(DuplicateGroup::id)) { "Near findings must have stable order" }
        require(ownedBlurItems == ownedBlurItems.sortedBy { it.assetId.value }) { "Blur findings must have stable order" }
        require(ownedExactGroups.map(DuplicateGroup::id).toSet().size == ownedExactGroups.size) {
            "Exact finding IDs must be distinct"
        }
        require(ownedNearGroups.map(DuplicateGroup::id).toSet().size == ownedNearGroups.size) {
            "Near finding IDs must be distinct"
        }
        require(ownedBlurItems.map(BlurFinding::id).toSet().size == ownedBlurItems.size) {
            "Blur finding IDs must be distinct"
        }
        val exactAssetIds = ownedExactGroups.flatMap(DuplicateGroup::assetIds)
        require(exactAssetIds.toSet().size == exactAssetIds.size) { "An asset must not belong to multiple exact findings" }
        val nearAssetIds = ownedNearGroups.flatMap(DuplicateGroup::assetIds)
        require(nearAssetIds.toSet().size == nearAssetIds.size) { "An asset must not belong to multiple near findings" }
        require(exactAssetIds.none(nearAssetIds.toSet()::contains)) { "Exact assets must not appear in near findings" }
        require(ownedBlurItems.none { it.assetId in exactAssetIds }) { "Exact assets must not appear in blur findings" }
        require(estimatedRecoverableBytes >= 0) { "Estimated recoverable bytes must not be negative" }
        require(
            estimatedRecoverableBytes == saturatingSum(
                (ownedExactGroups + ownedNearGroups).map(DuplicateGroup::estimatedRecoverableBytes),
            ),
        ) { "Snapshot estimate must equal its selected duplicate estimates" }
    }

    override fun equals(other: Any?): Boolean =
        other is FindingSnapshot &&
            ownedExactGroups == other.ownedExactGroups &&
            ownedNearGroups == other.ownedNearGroups &&
            ownedBlurItems == other.ownedBlurItems &&
            candidateGenerationStatus == other.candidateGenerationStatus &&
            estimatedRecoverableBytes == other.estimatedRecoverableBytes

    override fun hashCode(): Int {
        var result = ownedExactGroups.hashCode()
        result = 31 * result + ownedNearGroups.hashCode()
        result = 31 * result + ownedBlurItems.hashCode()
        result = 31 * result + candidateGenerationStatus.hashCode()
        return 31 * result + estimatedRecoverableBytes.hashCode()
    }

    override fun toString(): String =
        "FindingSnapshot(exactGroups=$ownedExactGroups, nearGroups=$ownedNearGroups, " +
            "blurItems=$ownedBlurItems, candidateGenerationStatus=$candidateGenerationStatus, " +
            "estimatedRecoverableBytes=$estimatedRecoverableBytes)"
}

internal val DuplicateKind.idPrefix: String
    get() = when (this) {
        DuplicateKind.Exact -> "exact"
        DuplicateKind.Near -> "near"
    }

internal fun stableFindingId(prefix: String, assetIds: List<AssetId>): String = buildString {
    append(prefix)
    append(':')
    assetIds.forEach { assetId ->
        append(assetId.value.length)
        append(':')
        append(assetId.value)
    }
}

internal fun saturatingSum(values: Iterable<Long>): Long {
    var total = 0L
    values.forEach { value ->
        require(value >= 0) { "Byte estimates must not be negative" }
        total = if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }
    return total
}
