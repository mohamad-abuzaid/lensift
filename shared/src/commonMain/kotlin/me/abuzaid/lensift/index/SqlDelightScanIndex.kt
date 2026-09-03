package me.abuzaid.lensift.index

import app.cash.sqldelight.db.SqlDriver
import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.analysis.BlurVerdict
import me.abuzaid.lensift.db.Asset_analysis
import me.abuzaid.lensift.db.LensiftDatabase
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.PhotoDescriptor

class SqlDelightScanIndex(driver: SqlDriver) : ScanIndex {
    private val database = createLensiftDatabase(driver)
    private val queries = database.lensiftQueries

    override suspend fun partitionChanged(
        descriptors: List<PhotoDescriptor>,
        analyzerVersion: Int,
    ): IndexPartition {
        require(analyzerVersion >= 0) { "Analyzer version must not be negative" }
        require(descriptors.map(PhotoDescriptor::id).toSet().size == descriptors.size) {
            "Photo descriptors must have unique asset IDs"
        }

        val storedById = queries.selectAllAnalyses().executeAsList().associateBy { it.asset_id }
        val reusable = ArrayList<AnalysisRecord>(descriptors.size)
        val changed = ArrayList<PhotoDescriptor>(descriptors.size)
        descriptors.forEach { descriptor ->
            val stored = storedById[descriptor.id.value]
            if (
                stored != null &&
                stored.source_signature == descriptor.contentSignature &&
                stored.analyzer_version == analyzerVersion.toLong()
            ) {
                reusable += stored.toRecord()
            } else {
                changed += descriptor
            }
        }
        return IndexPartition(reusable = reusable, changed = changed)
    }

    override suspend fun saveAnalysis(record: AnalysisRecord) {
        database.transaction {
            queries.insertAnalysisIfAbsent(
                asset_id = record.descriptor.id.value,
                source_signature = record.descriptor.contentSignature,
                analyzer_version = record.analyzerVersion.toLong(),
                width = record.descriptor.width.toLong(),
                height = record.descriptor.height.toLong(),
                byte_count = record.descriptor.byteCount,
                captured_at_ms = record.descriptor.capturedAtEpochMillis,
                is_favorite = record.descriptor.isFavorite,
                is_edited = record.descriptor.isEdited,
                perceptual_hash = record.perceptualHash,
                sha256 = record.sha256,
                laplacian_variance = record.blurEvidence.laplacianVariance,
                edge_density = record.blurEvidence.edgeDensity,
                local_texture_support = record.blurEvidence.localTextureSupport,
                analyzed_at_ms = record.analyzedAtEpochMillis,
            )
            queries.updateAnalysis(
                source_signature = record.descriptor.contentSignature,
                analyzer_version = record.analyzerVersion.toLong(),
                width = record.descriptor.width.toLong(),
                height = record.descriptor.height.toLong(),
                byte_count = record.descriptor.byteCount,
                captured_at_ms = record.descriptor.capturedAtEpochMillis,
                is_favorite = record.descriptor.isFavorite,
                is_edited = record.descriptor.isEdited,
                perceptual_hash = record.perceptualHash,
                sha256 = record.sha256,
                laplacian_variance = record.blurEvidence.laplacianVariance,
                edge_density = record.blurEvidence.edgeDensity,
                local_texture_support = record.blurEvidence.localTextureSupport,
                analyzed_at_ms = record.analyzedAtEpochMillis,
                asset_id = record.descriptor.id.value,
            )
        }
    }

    override suspend fun saveExactHash(assetId: AssetId, sha256: String) {
        require(sha256.isNotBlank()) { "SHA-256 must not be blank" }
        database.transaction {
            check(queries.selectAnalysisById(assetId.value).executeAsOneOrNull() != null) {
                "Cannot attach SHA-256 to an analysis that is not stored"
            }
            queries.attachExactHash(sha256 = sha256, asset_id = assetId.value)
        }
    }

    override suspend fun currentRecords(): List<AnalysisRecord> =
        queries.selectAllAnalyses().executeAsList().map(Asset_analysis::toRecord)

    override suspend fun purgeExcept(accessibleIds: Set<AssetId>) {
        val accessibleValues = accessibleIds.mapTo(HashSet(accessibleIds.size), AssetId::value)
        database.transaction {
            queries.selectAllAnalyses().executeAsList()
                .asSequence()
                .map(Asset_analysis::asset_id)
                .filterNot(accessibleValues::contains)
                .forEach { inaccessibleId ->
                    queries.deleteFindingGroupsForAsset(inaccessibleId)
                    queries.deleteAnalysisById(inaccessibleId)
                }
        }
    }

    override suspend fun invalidate(assetIds: Set<AssetId>) {
        database.transaction {
            assetIds.asSequence()
                .map(AssetId::value)
                .distinct()
                .forEach { assetId ->
                    queries.deleteFindingGroupsForAsset(assetId)
                    queries.deleteAnalysisById(assetId)
                }
        }
    }

    override suspend fun recordCleanup(summary: CleanupSummary) {
        database.transaction {
            queries.insertCleanupHistory(
                completed_at_ms = summary.completedAtEpochMillis,
                exact_count = summary.exactCount.toLong(),
                near_count = summary.nearCount.toLong(),
                blur_count = summary.blurCount.toLong(),
                confirmed_estimated_bytes = summary.confirmedEstimatedBytes,
            )
        }
    }

    override suspend fun cleanupHistory(): List<CleanupSummary> =
        queries.selectCleanupHistory().executeAsList().map { row ->
            CleanupSummary(
                completedAtEpochMillis = row.completed_at_ms,
                exactCount = row.exact_count.toIntExact("exact cleanup count"),
                nearCount = row.near_count.toIntExact("near cleanup count"),
                blurCount = row.blur_count.toIntExact("blur cleanup count"),
                confirmedEstimatedBytes = row.confirmed_estimated_bytes,
            )
        }
}

internal fun createLensiftDatabase(driver: SqlDriver): LensiftDatabase = LensiftDatabase(driver)

private fun Asset_analysis.toRecord(): AnalysisRecord = AnalysisRecord(
    descriptor = PhotoDescriptor(
        id = AssetId(asset_id),
        contentSignature = source_signature,
        width = width.toIntExact("photo width"),
        height = height.toIntExact("photo height"),
        byteCount = byte_count,
        capturedAtEpochMillis = captured_at_ms,
        isFavorite = is_favorite,
        isEdited = is_edited,
    ),
    analyzerVersion = analyzer_version.toIntExact("analyzer version"),
    perceptualHash = perceptual_hash,
    sha256 = sha256,
    blurEvidence = BlurEvidence(
        laplacianVariance = laplacian_variance,
        edgeDensity = edge_density,
        localTextureSupport = local_texture_support,
        verdict = BlurVerdict.Inconclusive,
    ),
    analyzedAtEpochMillis = analyzed_at_ms,
)

private fun Long.toIntExact(label: String): Int {
    require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$label is outside the Int range" }
    return toInt()
}
