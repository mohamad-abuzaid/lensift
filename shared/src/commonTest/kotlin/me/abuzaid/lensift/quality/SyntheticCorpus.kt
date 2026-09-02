package me.abuzaid.lensift.quality

import me.abuzaid.lensift.analysis.CandidatePair
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.LumaFrame
import kotlin.math.abs

/** Synthetic-only corpus metadata and labels. No user images, decoders, or platform APIs are used. */
data class CorpusAsset(
    val id: AssetId,
    val sourceFamilyId: String,
    val contentSignature: String,
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
    const val VERSION: String = "synthetic-corpus-v1"
    private const val WIDTH = 64
    private const val HEIGHT = 64
    private const val DEVELOPMENT_FAMILY_COUNT = 40
    private const val TOTAL_FAMILY_COUNT = 48

    val development: DevelopmentCorpus = generate().development
    val test: EmbargoedTestPartition = generate().test

    fun regenerateDevelopment(): DevelopmentCorpus = generate().development

    private fun generate(): GeneratedCorpus {
        val developmentAssets = mutableListOf<CorpusAsset>()
        val developmentExact = linkedSetOf<CandidatePair>()
        val developmentNear = linkedSetOf<CandidatePair>()
        val developmentBlur = linkedSetOf<AssetId>()
        val developmentFamilies = linkedSetOf<String>()
        val testFamilies = linkedSetOf<String>()
        var testVariantCount = 0

        repeat(TOTAL_FAMILY_COUNT) { familyIndex ->
            val familyId = "family-${(familyIndex + 1).toString().padStart(2, '0')}"
            val generated = generateFamily(familyId, familyIndex)
            if (familyIndex < DEVELOPMENT_FAMILY_COUNT) {
                developmentFamilies += familyId
                developmentAssets += generated.assets
                developmentExact += generated.exactLabels
                developmentNear += generated.nearLabels
                developmentBlur += generated.blurLabels
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
            ),
            EmbargoedTestPartition(testFamilies, testVariantCount),
        )
    }

    private fun generateFamily(familyId: String, familyIndex: Int): GeneratedFamily {
        val source = baseFrame(familyIndex)
        val capturedAt = 1_700_000_000_000L + familyIndex * 1_000_000L
        val first = asset(familyId, "source", "bytes-$familyId-source", source, capturedAt)
        val kind = FamilyKind.entries[familyIndex % FamilyKind.entries.size]

        return when (kind) {
            FamilyKind.ExactCopy -> {
                val copy = asset(familyId, "copy", first.contentSignature, source, capturedAt + 5_000L)
                GeneratedFamily(listOf(first, copy), exactLabels = setOf(pair(first, copy)))
            }
            FamilyKind.Recompression -> nearFamily(first, recompress(source), capturedAt + 12_000L)
            FamilyKind.Resize -> nearFamily(first, resize(source, 63, HEIGHT), capturedAt + 30_000L)
            FamilyKind.Brightness -> nearFamily(first, adjustBrightness(source, 18), capturedAt + 60_000L)
            FamilyKind.Crop -> nearFamily(first, crop(source, 1, 0, 63, HEIGHT), capturedAt + 90_000L)
            FamilyKind.Burst -> nearFamily(first, translate(source, 1, 0), capturedAt + burstGapMillis(familyIndex))
            FamilyKind.SharpMotion -> nearFamily(first, sharpMotion(source), capturedAt + 45_000L)
            FamilyKind.IntentionalBokeh -> GeneratedFamily(
                listOf(asset(familyId, "bokeh", "bytes-$familyId-bokeh", bokehFrame(familyIndex), capturedAt)),
            )
            FamilyKind.UniformWall -> GeneratedFamily(
                listOf(asset(familyId, "wall", "bytes-$familyId-wall", uniformWall(familyIndex), capturedAt)),
            )
            FamilyKind.SyntheticBlur -> {
                val blurred = asset(familyId, "blurred", "bytes-$familyId-blurred", boxBlur(source, 3), capturedAt)
                GeneratedFamily(listOf(blurred), blurLabels = setOf(blurred.id))
            }
        }
    }

    private fun nearFamily(source: CorpusAsset, frame: LumaFrame, capturedAt: Long): GeneratedFamily {
        val variant = asset(
            source.sourceFamilyId,
            "variant",
            "bytes-${source.sourceFamilyId}-variant",
            frame,
            capturedAt,
        )
        return GeneratedFamily(listOf(source, variant), nearLabels = setOf(pair(source, variant)))
    }

    private fun asset(
        familyId: String,
        suffix: String,
        signature: String,
        frame: LumaFrame,
        capturedAt: Long,
    ): CorpusAsset = CorpusAsset(
        id = AssetId("$familyId-$suffix"),
        sourceFamilyId = familyId,
        contentSignature = signature,
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

    private enum class FamilyKind {
        ExactCopy, Recompression, Resize, Brightness, Crop, Burst, SharpMotion, IntentionalBokeh, UniformWall, SyntheticBlur,
    }

    private data class GeneratedFamily(
        val assets: List<CorpusAsset>,
        val exactLabels: Set<CandidatePair> = emptySet(),
        val nearLabels: Set<CandidatePair> = emptySet(),
        val blurLabels: Set<AssetId> = emptySet(),
    )

    private data class GeneratedCorpus(val development: DevelopmentCorpus, val test: EmbargoedTestPartition)
}
