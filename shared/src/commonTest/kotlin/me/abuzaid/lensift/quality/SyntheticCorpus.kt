package me.abuzaid.lensift.quality

import me.abuzaid.lensift.analysis.CandidatePair
import me.abuzaid.lensift.analysis.PerceptualHash
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.LumaFrame
import kotlin.math.abs

/** Synthetic-only corpus metadata and labels. No user images, decoders, or platform APIs are used. */
data class CorpusAsset(
    val id: AssetId,
    val sourceFamilyId: String,
    val rawBytes: ByteArray,
    val frame: LumaFrame,
    val capturedAtEpochMillis: Long,
)

/** The only corpus shape accepted by [PolicySelector]; its test counterpart is deliberately opaque. */
class DevelopmentCorpus internal constructor(
    val seed: Long,
    val assets: List<CorpusAsset>,
    val sourceFamilyIds: Set<String>,
    val exactLabels: Set<CandidatePair>,
    val nearLabels: Set<CandidatePair>,
    val blurLabels: Set<AssetId>,
    val exactNegativeAssetIds: Set<AssetId>,
    val nearHardNegativePairs: Set<CandidatePair>,
) {
    val sourceFamilyCount: Int get() = sourceFamilyIds.size
}

/** Test-family identities are visible for split auditing, while samples and labels remain embargoed until Plan 05. */
class EmbargoedTestPartition internal constructor(
    val sourceFamilyIds: Set<String>,
    val variantCount: Int,
) {
    val sourceFamilyCount: Int get() = sourceFamilyIds.size
}

object SyntheticCorpus {
    const val SEED: Long = 2_026_090_201L
    const val VERSION: String = "synthetic-corpus-v2"
    private const val WIDTH = 64
    private const val HEIGHT = 64
    private const val DEVELOPMENT_FAMILY_COUNT = 40
    private const val TOTAL_FAMILY_COUNT = 48

    val development: DevelopmentCorpus = generate(SEED).development
    val test: EmbargoedTestPartition = generate(SEED).test

    fun regenerateDevelopment(): DevelopmentCorpus = generate(SEED).development

    private fun generate(seed: Long): GeneratedCorpus {
        val developmentAssets = mutableListOf<CorpusAsset>()
        val developmentExact = linkedSetOf<CandidatePair>()
        val developmentNear = linkedSetOf<CandidatePair>()
        val developmentBlur = linkedSetOf<AssetId>()
        val developmentExactNegatives = linkedSetOf<AssetId>()
        val developmentNearHardNegatives = linkedSetOf<CandidatePair>()
        val developmentFamilies = linkedSetOf<String>()
        val testFamilies = linkedSetOf<String>()
        var testVariantCount = 0

        repeat(TOTAL_FAMILY_COUNT) { familyIndex ->
            val familyId = "family-${(familyIndex + 1).toString().padStart(2, '0')}"
            val generated = generateFamily(familyId, familyIndex, seed)
            if (familyIndex < DEVELOPMENT_FAMILY_COUNT) {
                developmentFamilies += familyId
                developmentAssets += generated.assets
                developmentExact += generated.exactLabels
                developmentNear += generated.nearLabels
                developmentBlur += generated.blurLabels
                developmentExactNegatives += generated.exactNegativeAssetIds
                developmentNearHardNegatives += generated.nearHardNegativePairs
            } else {
                testFamilies += familyId
                testVariantCount += generated.assets.size
            }
        }

        return GeneratedCorpus(
            DevelopmentCorpus(
                seed = SEED,
                assets = developmentAssets.sortedBy { it.id.value },
                sourceFamilyIds = developmentFamilies,
                exactLabels = developmentExact,
                nearLabels = developmentNear,
                blurLabels = developmentBlur,
                exactNegativeAssetIds = developmentExactNegatives,
                nearHardNegativePairs = developmentNearHardNegatives,
            ),
            EmbargoedTestPartition(testFamilies, testVariantCount),
        )
    }

    private fun generateFamily(familyId: String, familyIndex: Int, seed: Long): GeneratedFamily {
        val familySeed = familySeed(seed, familyIndex)
        val source = baseFrame(familySeed)
        val capturedAt = 1_700_000_000_000L + familyIndex * 1_000_000L
        val first = asset(familyId, "source", source, capturedAt)
        val kind = FamilyKind.entries[familyIndex % FamilyKind.entries.size]

        return when (kind) {
            FamilyKind.ExactCopy -> {
                val copy = asset(familyId, "copy", source, capturedAt + 5_000L)
                GeneratedFamily(listOf(first, copy), exactLabels = setOf(pair(first, copy)))
            }
            FamilyKind.Recompression -> nearFamily(first, recompress(source), capturedAt + 12_000L)
            FamilyKind.Resize -> nearFamily(first, resize(source, 63, HEIGHT), capturedAt + 30_000L)
            FamilyKind.Brightness -> nearFamily(first, adjustBrightness(source, 18), capturedAt + 60_000L)
            FamilyKind.Crop -> nearFamily(first, crop(source, 1, 0, 63, HEIGHT), capturedAt + 90_000L)
            FamilyKind.Burst -> nearFamily(first, translate(source, 1, 0), capturedAt + burstGapMillis(familyIndex))
            FamilyKind.SharpMotion -> nearFamily(first, sharpMotion(source), capturedAt + 45_000L)
            FamilyKind.IntentionalBokeh -> GeneratedFamily(
                listOf(asset(familyId, "bokeh", bokehFrame(familySeed), capturedAt)),
            )
            FamilyKind.UniformWall -> GeneratedFamily(
                listOf(asset(familyId, "wall", uniformWall(familySeed), capturedAt)),
            )
            FamilyKind.SyntheticBlur -> {
                val blurred = asset(familyId, "blurred", boxBlur(source, 3), capturedAt)
                GeneratedFamily(listOf(blurred), blurLabels = setOf(blurred.id))
            }
            FamilyKind.NearHardNegative -> {
                val negative = asset(familyId, "hard-negative", hardNegative(source, familySeed), capturedAt + 60_000L)
                GeneratedFamily(
                    assets = listOf(first, negative),
                    exactNegativeAssetIds = setOf(first.id, negative.id),
                    nearHardNegativePairs = setOf(pair(first, negative)),
                )
            }
        }
    }

    private fun nearFamily(source: CorpusAsset, frame: LumaFrame, capturedAt: Long): GeneratedFamily {
        val variant = asset(
            source.sourceFamilyId,
            "variant",
            frame,
            capturedAt,
        )
        return GeneratedFamily(listOf(source, variant), nearLabels = setOf(pair(source, variant)))
    }

    private fun asset(
        familyId: String,
        suffix: String,
        frame: LumaFrame,
        capturedAt: Long,
    ): CorpusAsset = CorpusAsset(
        id = AssetId("$familyId-$suffix"),
        sourceFamilyId = familyId,
        rawBytes = frame.pixels,
        frame = frame,
        capturedAtEpochMillis = capturedAt,
    )

    private fun pair(left: CorpusAsset, right: CorpusAsset): CandidatePair =
        if (left.id.value < right.id.value) CandidatePair(left.id, right.id) else CandidatePair(right.id, left.id)

    private fun baseFrame(seed: Int): LumaFrame = LumaFrame(
        WIDTH,
        HEIGHT,
        ByteArray(WIDTH * HEIGHT) { index ->
            val x = index % WIDTH
            val y = index / WIDTH
            val checker = if (((x / 6) + (y / 5) + seed) % 2 == 0) 38 else -38
            val diagonal = ((x * 7 + y * 11 + seed * 17) % 67) - 33
            val ripple = ((x * x + y * 3 + seed * 13) % 41) - 20
            (128 + checker + diagonal + ripple).coerceIn(0, 255).toByte()
        },
    )

    private fun recompress(frame: LumaFrame): LumaFrame = map(frame) { value, _, _ -> (value / 16) * 16 + 7 }
    private fun adjustBrightness(frame: LumaFrame, offset: Int): LumaFrame = map(frame) { value, _, _ -> value + offset }

    private fun translate(frame: LumaFrame, shiftX: Int, shiftY: Int): LumaFrame = LumaFrame(
        frame.width,
        frame.height,
        ByteArray(frame.width * frame.height) { index ->
            val x = index % frame.width
            val y = index / frame.width
            val sourceX = (x - shiftX).coerceIn(0, frame.width - 1)
            val sourceY = (y - shiftY).coerceIn(0, frame.height - 1)
            frame.pixels[sourceY * frame.width + sourceX]
        },
    )

    private fun sharpMotion(frame: LumaFrame): LumaFrame = map(frame) { value, x, _ ->
        if (x % 16 == 0) value + 8 else value
    }

    private fun hardNegative(frame: LumaFrame, seed: Int): LumaFrame {
        val sourceHash = PerceptualHash.compute(frame)
        return (1..48)
            .map { trial -> independentScene(frame, seed, trial) }
            .minWithOrNull(
                compareBy<LumaFrame> { candidate ->
                    if (PerceptualHash.distance(sourceHash, PerceptualHash.compute(candidate)) in 15..20) 0 else 1
                }.thenBy { candidate -> abs(PerceptualHash.distance(sourceHash, PerceptualHash.compute(candidate)) - 18) },
            )!!
    }

    /** A deliberately separate synthetic subject that can collide with the source's low-frequency pHash. */
    private fun independentScene(frame: LumaFrame, seed: Int, trial: Int): LumaFrame = map(frame) { value, x, y ->
        val centerX = 8 + (seed ushr (trial % 13) and 31)
        val centerY = 8 + (seed ushr ((trial + 7) % 13) and 31)
        val radius = 4 + trial % 14
        val subject = if ((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY) <= radius * radius) 30 + trial * 3 else 0
        val texture = if ((x * (trial + 3) + y * (trial + 5) + seed) % 5 == 0) 18 else -6
        value + subject + texture
    }

    private fun crop(frame: LumaFrame, left: Int, top: Int, width: Int, height: Int): LumaFrame = LumaFrame(
        width,
        height,
        ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            frame.pixels[(y + top) * frame.width + x + left]
        },
    )

    private fun resize(frame: LumaFrame, width: Int, height: Int): LumaFrame = LumaFrame(
        width,
        height,
        ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val sourceX = x * frame.width / width
            val sourceY = y * frame.height / height
            frame.pixels[sourceY * frame.width + sourceX]
        },
    )

    private fun boxBlur(frame: LumaFrame, radius: Int): LumaFrame = LumaFrame(
        frame.width,
        frame.height,
        ByteArray(frame.width * frame.height) { index ->
            val x = index % frame.width
            val y = index / frame.width
            var sum = 0
            var count = 0
            for (offsetY in -radius..radius) for (offsetX in -radius..radius) {
                val sourceX = (x + offsetX).coerceIn(0, frame.width - 1)
                val sourceY = (y + offsetY).coerceIn(0, frame.height - 1)
                sum += frame.pixels[sourceY * frame.width + sourceX].toInt() and 0xff
                count += 1
            }
            (sum / count).toByte()
        },
    )

    private fun bokehFrame(seed: Int): LumaFrame = LumaFrame(
        WIDTH,
        HEIGHT,
        ByteArray(WIDTH * HEIGHT) { index ->
            val x = index % WIDTH - WIDTH / 2
            val y = index / WIDTH - HEIGHT / 2
            val distance = x * x + y * y
            val ring = if (abs(distance - (150 + seed % 5 * 90)) < 45) 105 else 0
            (70 + ring + (x * 3 + y * 5 + seed) % 20).coerceIn(0, 255).toByte()
        },
    )

    private fun uniformWall(seed: Int): LumaFrame = LumaFrame(
        WIDTH,
        HEIGHT,
        ByteArray(WIDTH * HEIGHT) { index -> (115 + seed % 7 + (index % WIDTH) / 24).toByte() },
    )

    private fun map(frame: LumaFrame, transform: (Int, Int, Int) -> Int): LumaFrame = LumaFrame(
        frame.width,
        frame.height,
        ByteArray(frame.width * frame.height) { index ->
            transform(frame.pixels[index].toInt() and 0xff, index % frame.width, index / frame.width).coerceIn(0, 255).toByte()
        },
    )

    private fun burstGapMillis(familyIndex: Int): Long = listOf(10_000L, 25_000L, 55_000L, 85_000L, 115_000L, 175_000L)[familyIndex % 6]

    private fun familySeed(seed: Long, familyIndex: Int): Int {
        val mixed = (seed + (familyIndex + 1L) * 1_103_515_245L) xor (seed ushr 17)
        return (mixed xor (mixed ushr 32)).toInt()
    }

    private enum class FamilyKind {
        ExactCopy, Recompression, Resize, Brightness, Crop, Burst, SharpMotion, IntentionalBokeh, UniformWall, SyntheticBlur, NearHardNegative,
    }

    private data class GeneratedFamily(
        val assets: List<CorpusAsset>,
        val exactLabels: Set<CandidatePair> = emptySet(),
        val nearLabels: Set<CandidatePair> = emptySet(),
        val blurLabels: Set<AssetId> = emptySet(),
        val exactNegativeAssetIds: Set<AssetId> = emptySet(),
        val nearHardNegativePairs: Set<CandidatePair> = emptySet(),
    )

    private data class GeneratedCorpus(val development: DevelopmentCorpus, val test: EmbargoedTestPartition)
}
