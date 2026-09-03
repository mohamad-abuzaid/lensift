package me.abuzaid.lensift.findings

import me.abuzaid.lensift.analysis.BlurAnalyzer
import me.abuzaid.lensift.analysis.BlurVerdict
import me.abuzaid.lensift.analysis.NearDuplicateClusterer
import me.abuzaid.lensift.analysis.PerceptualCandidate
import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.BlurCandidate
import me.abuzaid.lensift.domain.ExactDuplicate
import me.abuzaid.lensift.domain.NearDuplicate
import me.abuzaid.lensift.index.AnalysisRecord
import me.abuzaid.lensift.recommendation.KeeperRecommender
import me.abuzaid.lensift.recommendation.ReviewSelection

class FindingAssembler {
    fun exactHashWork(records: Iterable<AnalysisRecord>): ExactHashWork {
        val ordered = ownedRecords(records)
        val candidateIds = exactBuckets(ordered)
            .values
            .flatMap(::exactCandidateRecords)
            .asSequence()
            .filter { it.sha256 == null }
            .map { it.descriptor.id }
            .distinct()
            .sortedBy(AssetId::value)
            .toList()
        return ExactHashWork(candidateIds)
    }

    fun assemble(records: Iterable<AnalysisRecord>, policy: AnalysisPolicy): FindingSnapshot {
        val ordered = ownedRecords(records)
        val exactGroups = assembleExactGroups(ordered)
        val exactAssetIds = exactGroups.flatMap(DuplicateGroup::assetIds).toSet()
        val nonExactRecords = ordered.filterNot { it.descriptor.id in exactAssetIds }

        val clustering = NearDuplicateClusterer.cluster(
            nonExactRecords.map { record ->
                PerceptualCandidate(
                    assetId = record.descriptor.id,
                    hash = record.perceptualHash,
                    width = record.descriptor.width,
                    height = record.descriptor.height,
                    capturedAtEpochMillis = record.descriptor.capturedAtEpochMillis,
                )
            },
            policy,
        )
        val byId = nonExactRecords.associateBy { it.descriptor.id }
        val nearGroups = clustering.clusters
            .filter { it.assetIds.size >= 2 }
            .map { cluster -> duplicateGroup(DuplicateKind.Near, cluster.assetIds.map(byId::getValue)) }
            .sortedBy(DuplicateGroup::id)
        val blurItems = nonExactRecords.mapNotNull { record ->
            val verdict = BlurAnalyzer.classify(record.blurEvidence, policy)
            if (verdict != BlurVerdict.PossiblyBlurred) {
                null
            } else {
                BlurFinding(
                    id = stableFindingId("blur", listOf(record.descriptor.id)),
                    assetId = record.descriptor.id,
                    evidence = record.blurEvidence.copy(verdict = verdict),
                )
            }
        }.sortedBy { it.assetId.value }
        val estimate = saturatingSum((exactGroups + nearGroups).map(DuplicateGroup::estimatedRecoverableBytes))

        return FindingSnapshot(
            exactGroups = exactGroups,
            nearGroups = nearGroups,
            blurItems = blurItems,
            candidateGenerationStatus = clustering.candidateGenerationStatus,
            estimatedRecoverableBytes = estimate,
        )
    }

    private fun assembleExactGroups(records: List<AnalysisRecord>): List<DuplicateGroup> = exactBuckets(records)
        .values
        .flatMap { bucket ->
            bucket.filter { it.sha256 != null }
                .groupBy(AnalysisRecord::sha256)
                .values
                .flatMap(::partitionExactShaGroup)
        }
        .filter { it.size >= 2 }
        .map { duplicateGroup(DuplicateKind.Exact, it) }
        .sortedBy(DuplicateGroup::id)

    private fun duplicateGroup(kind: DuplicateKind, records: List<AnalysisRecord>): DuplicateGroup {
        val ordered = records.sortedBy { it.descriptor.id.value }
        val assetIds = ordered.map { it.descriptor.id }
        val descriptors = ordered.map(AnalysisRecord::descriptor)
        val descriptorsById = descriptors.associateBy { it.id }
        val evidenceById = ordered.associate { it.descriptor.id to it.blurEvidence }
        val recommendation = KeeperRecommender.recommend(descriptors, evidenceById)
        val selected = when (kind) {
            DuplicateKind.Exact -> ReviewSelection.initialFor(
                ExactDuplicate(assetIds),
                recommendation.keeper,
                descriptorsById,
            )
            DuplicateKind.Near -> ReviewSelection.initialFor(NearDuplicate(assetIds))
        }.sortedBy(AssetId::value)
        val estimate = saturatingSum(selected.mapNotNull { descriptorsById[it]?.byteCount })

        return DuplicateGroup(
            id = stableFindingId(kind.idPrefix, assetIds),
            kind = kind,
            assetIds = assetIds,
            keeper = recommendation.keeper,
            keeperReasons = recommendation.reasons,
            selectedForRemoval = selected,
            estimatedRecoverableBytes = estimate,
        )
    }

    private fun exactBuckets(records: List<AnalysisRecord>): Map<ExactBucket, List<AnalysisRecord>> = records.groupBy { record ->
        ExactBucket(
            shortEdge = minOf(record.descriptor.width, record.descriptor.height),
            longEdge = maxOf(record.descriptor.width, record.descriptor.height),
            perceptualHash = record.perceptualHash,
        )
    }

    private fun exactCandidateRecords(bucket: List<AnalysisRecord>): List<AnalysisRecord> {
        if (bucket.size < 2) return emptyList()
        if (bucket.any { it.descriptor.byteCount == null }) return bucket
        return bucket.groupBy { it.descriptor.byteCount!! }
            .values
            .filter { it.size >= 2 }
            .flatten()
    }

    /**
     * A verified SHA normally implies one byte count. If persisted known sizes contradict that,
     * each known-size partition remains isolated and unknown-size records form their own fail-safe
     * partition so they cannot bridge corrupt metadata into one exact group.
     */
    private fun partitionExactShaGroup(records: List<AnalysisRecord>): List<List<AnalysisRecord>> {
        val knownSizeGroups = records.filter { it.descriptor.byteCount != null }
            .groupBy { it.descriptor.byteCount!! }
        if (knownSizeGroups.size <= 1) return listOf(records)

        val unknownSizeGroup = records.filter { it.descriptor.byteCount == null }
        return buildList {
            addAll(knownSizeGroups.values)
            if (unknownSizeGroup.isNotEmpty()) add(unknownSizeGroup)
        }
    }

    private fun ownedRecords(records: Iterable<AnalysisRecord>): List<AnalysisRecord> = records.toList()
        .sortedBy { it.descriptor.id.value }
        .also { ordered ->
            require(ordered.map { it.descriptor.id }.toSet().size == ordered.size) {
                "Finding assembly requires distinct asset IDs"
            }
        }

    private data class ExactBucket(
        val shortEdge: Int,
        val longEdge: Int,
        val perceptualHash: Long,
    )
}
