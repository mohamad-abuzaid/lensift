package me.abuzaid.lensift.analysis

import me.abuzaid.lensift.domain.AnalysisPolicy
import me.abuzaid.lensift.domain.AssetId

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

/** Whether all candidate collisions were examined within the configured safety budget. */
enum class CandidateGenerationStatus {
    Complete,
    PairLimitReached,
}

/**
 * Candidate pairs plus explicit coverage diagnostics.
 *
 * [PairLimitReached] never adds an unverified pair; it means some possible band collisions were
 * deliberately left unexamined after [attemptedPairCount] distinct pair attempts.
 */
class CandidateGenerationResult(
    pairs: List<CandidatePair>,
    val status: CandidateGenerationStatus,
    val attemptedPairCount: Int,
) {
    val pairs: List<CandidatePair> = pairs.toList()
    val isComplete: Boolean get() = status == CandidateGenerationStatus.Complete
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
    /** Bounds CPU and memory for a single pathological collision set while making reduced recall visible. */
    const val MAX_DISTINCT_PAIR_ATTEMPTS = 100_000

    fun find(inputs: Iterable<PerceptualCandidate>, policy: AnalysisPolicy): CandidateGenerationResult =
        find(inputs, policy, PerceptualHash::distance)

    internal fun find(
        inputs: Iterable<PerceptualCandidate>,
        policy: AnalysisPolicy,
        distance: (Long, Long) -> Int,
    ): CandidateGenerationResult {
        val candidates = inputs.sortedBy { it.assetId.value }
        require(candidates.map { it.assetId }.toSet().size == candidates.size) { "Candidate asset IDs must be unique" }

        if (policy.maxPerceptualDistance == 64) {
            return allMetadataCompatiblePairs(candidates, policy)
        }

        val bandGroups = mutableMapOf<HashBand, MutableList<Int>>()
        candidates.forEachIndexed { index, candidate ->
            bandsFor(candidate.hash, policy.maxPerceptualDistance).forEach { band ->
                bandGroups.getOrPut(band) { mutableListOf() }.add(index)
            }
        }

        return collectPairs(candidates, policy, distance) generator@{ attempt ->
            bandGroups.entries
                .sortedWith(compareBy<Map.Entry<HashBand, MutableList<Int>>> { it.key.startBit }
                    .thenBy { it.key.bitCount }
                    .thenBy { it.key.value })
                .forEach { (_, members) ->
                    for (leftIndex in 0 until members.lastIndex) {
                        for (rightIndex in leftIndex + 1 until members.size) {
                            if (!attempt(members[leftIndex], members[rightIndex])) return@generator false
                        }
                    }
                }
            true
        }
    }

    private fun collectPairs(
        candidates: List<PerceptualCandidate>,
        policy: AnalysisPolicy,
        distance: (Long, Long) -> Int,
        generate: ((Int, Int) -> Boolean) -> Boolean,
    ): CandidateGenerationResult {
        val attempted = mutableSetOf<Long>()
        val qualified = mutableListOf<CandidatePair>()
        val exhausted = generate attempt@{ firstIndex, secondIndex ->
            val leftIndex = minOf(firstIndex, secondIndex)
            val rightIndex = maxOf(firstIndex, secondIndex)
            val key = (leftIndex.toLong() shl Int.SIZE_BITS) or rightIndex.toLong()
            if (!attempted.add(key)) return@attempt true
            if (attempted.size > MAX_DISTINCT_PAIR_ATTEMPTS) {
                attempted.remove(key)
                return@attempt false
            }

            val left = candidates[leftIndex]
            val right = candidates[rightIndex]
            if (metadataCompatible(left, right, policy) && distance(left.hash, right.hash) <= policy.maxPerceptualDistance) {
                qualified += canonicalPair(left, right)
            }
            true
        }

        return CandidateGenerationResult(
            pairs = qualified.sortedWith(compareBy<CandidatePair> { it.first.value }.thenBy { it.second.value }),
            status = if (exhausted) CandidateGenerationStatus.Complete else CandidateGenerationStatus.PairLimitReached,
            attemptedPairCount = attempted.size,
        )
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
    ): CandidateGenerationResult = collectPairs(candidates, policy, distance = { _, _ -> 0 }) generator@{ attempt ->
        for (leftIndex in 0 until candidates.lastIndex) {
            for (rightIndex in leftIndex + 1 until candidates.size) {
                if (!attempt(leftIndex, rightIndex)) return@generator false
            }
        }
        true
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

    private fun canonicalPair(left: PerceptualCandidate, right: PerceptualCandidate): CandidatePair =
        if (left.assetId.value < right.assetId.value) CandidatePair(left.assetId, right.assetId) else CandidatePair(right.assetId, left.assetId)
}
