package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId
import kotlin.math.floor

/** The value-only input used to generate near-duplicate candidates. */
data class PerceptualCandidate(
    val assetId: AssetId,
    val hash: Long,
    val width: Int,
    val height: Int,
    val capturedAtEpochMillis: Long?,
) {
    init {
        require(width > 0 && height > 0) { "Candidate dimensions must be positive" }
    }
}

/** A pair whose asset IDs are in strict lexicographic order. */
data class CandidatePair(
    val first: AssetId,
    val second: AssetId,
) {
    init {
        require(first.value < second.value) { "Candidate pair IDs must be ordered and distinct" }
    }
}

internal data class HashBand(
    val startBit: Int,
    val bitCount: Int,
    val value: Long,
)

/**
 * Narrows near-duplicate comparisons through metadata neighborhoods and pHash bands.
 * Exact metadata and Hamming checks always follow coarse bucketing, so a boundary bucket
 * cannot change the decision.
 */
object CandidateBucketer {
    fun find(inputs: Iterable<PerceptualCandidate>, policy: AnalysisPolicy): List<CandidatePair> {
        val candidates = inputs.sortedBy { it.assetId.value }
        require(candidates.map { it.assetId }.toSet().size == candidates.size) { "Candidate asset IDs must be unique" }

        if (policy.maxPerceptualDistance == 64) {
            return allMetadataCompatiblePairs(candidates, policy)
        }

        val hits = linkedSetOf<CandidatePair>()
        val datedCandidates = candidates.filter { it.capturedAtEpochMillis != null }
        val partitions = mutableMapOf<MetadataPartition, MutableList<PerceptualCandidate>>()
        datedCandidates.forEach { candidate ->
            val aspectCells = neighboringCells(aspectCell(candidate, policy))
            val timeCells = neighboringCells(timeCell(candidate.capturedAtEpochMillis!!, policy))
            aspectCells.forEach { aspect ->
                timeCells.forEach { time ->
                    partitions.getOrPut(MetadataPartition(aspect, time)) { mutableListOf() }.add(candidate)
                }
            }
        }

        partitions.values.forEach { partition ->
            val bands = mutableMapOf<HashBand, MutableList<PerceptualCandidate>>()
            partition.forEach { candidate ->
                bandsFor(candidate.hash, policy.maxPerceptualDistance).forEach { band ->
                    bands.getOrPut(band) { mutableListOf() }.add(candidate)
                }
            }
            bands.values.forEach { bandMembers ->
                for (leftIndex in 0 until bandMembers.lastIndex) {
                    for (rightIndex in leftIndex + 1 until bandMembers.size) {
                        addIfVerified(hits, bandMembers[leftIndex], bandMembers[rightIndex], policy)
                    }
                }
            }
        }

        candidates.filter { it.capturedAtEpochMillis == null }.forEach { unknownTime ->
            candidates.forEach { other ->
                if (unknownTime != other && sharesBand(unknownTime.hash, other.hash, policy.maxPerceptualDistance)) {
                    addIfVerified(hits, unknownTime, other, policy)
                }
            }
        }

        return hits.sortedWith(compareBy<CandidatePair> { it.first.value }.thenBy { it.second.value })
    }

    internal fun bandsFor(hash: Long, maxPerceptualDistance: Int): List<HashBand> {
        require(maxPerceptualDistance in 0..64) { "Perceptual distance must be between 0 and 64" }
        if (maxPerceptualDistance == 64) return emptyList()

        val bandCount = maxPerceptualDistance + 1
        val baseBitCount = Long.SIZE_BITS / bandCount
        val largerBandCount = Long.SIZE_BITS % bandCount
        var startBit = 0
        return buildList(bandCount) {
            repeat(bandCount) { bandIndex ->
                val bitCount = baseBitCount + if (bandIndex < largerBandCount) 1 else 0
                val value = if (bitCount == Long.SIZE_BITS) hash else hash ushr startBit and ((1L shl bitCount) - 1L)
                add(HashBand(startBit, bitCount, value))
                startBit += bitCount
            }
        }
    }

    private fun allMetadataCompatiblePairs(
        candidates: List<PerceptualCandidate>,
        policy: AnalysisPolicy,
    ): List<CandidatePair> {
        val pairs = mutableListOf<CandidatePair>()
        for (leftIndex in 0 until candidates.lastIndex) {
            for (rightIndex in leftIndex + 1 until candidates.size) {
                if (metadataCompatible(candidates[leftIndex], candidates[rightIndex], policy)) {
                    pairs += canonicalPair(candidates[leftIndex], candidates[rightIndex])
                }
            }
        }
        return pairs
    }

    private fun addIfVerified(
        hits: MutableSet<CandidatePair>,
        left: PerceptualCandidate,
        right: PerceptualCandidate,
        policy: AnalysisPolicy,
    ) {
        if (metadataCompatible(left, right, policy) && PerceptualHash.distance(left.hash, right.hash) <= policy.maxPerceptualDistance) {
            hits += canonicalPair(left, right)
        }
    }

    private fun metadataCompatible(
        left: PerceptualCandidate,
        right: PerceptualCandidate,
        policy: AnalysisPolicy,
    ): Boolean = normalizedAspectRatioDelta(left, right) <= policy.maxAspectRatioDelta &&
        captureTimeCompatible(left.capturedAtEpochMillis, right.capturedAtEpochMillis, policy.maxCaptureGapMillis)

    private fun normalizedAspectRatioDelta(left: PerceptualCandidate, right: PerceptualCandidate): Double =
        kotlin.math.abs(normalizedAspectRatio(left) - normalizedAspectRatio(right))

    private fun normalizedAspectRatio(candidate: PerceptualCandidate): Double =
        minOf(candidate.width, candidate.height).toDouble() / maxOf(candidate.width, candidate.height)

    private fun captureTimeCompatible(left: Long?, right: Long?, maxGapMillis: Long): Boolean {
        if (left == null || right == null) return true
        val lower = minOf(left, right)
        val upper = maxOf(left, right)
        return if (upper < Long.MIN_VALUE + maxGapMillis) true else lower >= upper - maxGapMillis
    }

    private fun aspectCell(candidate: PerceptualCandidate, policy: AnalysisPolicy): Long {
        val cellWidth = policy.maxAspectRatioDelta.takeIf { it > 0.0 } ?: 1.0
        return floor(normalizedAspectRatio(candidate) / cellWidth).toLong()
    }

    private fun timeCell(capturedAtEpochMillis: Long, policy: AnalysisPolicy): Long =
        if (policy.maxCaptureGapMillis == 0L) capturedAtEpochMillis else capturedAtEpochMillis.floorDiv(policy.maxCaptureGapMillis)

    private fun neighboringCells(cell: Long): List<Long> = buildList(3) {
        if (cell != Long.MIN_VALUE) add(cell - 1)
        add(cell)
        if (cell != Long.MAX_VALUE) add(cell + 1)
    }

    private fun sharesBand(left: Long, right: Long, maxPerceptualDistance: Int): Boolean =
        bandsFor(left, maxPerceptualDistance).toSet().intersect(bandsFor(right, maxPerceptualDistance).toSet()).isNotEmpty()

    private fun canonicalPair(left: PerceptualCandidate, right: PerceptualCandidate): CandidatePair =
        if (left.assetId.value < right.assetId.value) CandidatePair(left.assetId, right.assetId) else CandidatePair(right.assetId, left.assetId)

    private data class MetadataPartition(val aspectCell: Long, val timeCell: Long)
}
