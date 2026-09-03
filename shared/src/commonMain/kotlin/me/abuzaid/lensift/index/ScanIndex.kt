package me.abuzaid.lensift.index

import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.PhotoDescriptor

interface ScanIndex {
    suspend fun partitionChanged(
        descriptors: List<PhotoDescriptor>,
        analyzerVersion: Int,
    ): IndexPartition

    suspend fun saveAnalysis(record: AnalysisRecord)

    suspend fun saveExactHash(assetId: AssetId, sha256: String)

    suspend fun currentRecords(): List<AnalysisRecord>

    suspend fun purgeExcept(accessibleIds: Set<AssetId>)

    suspend fun invalidate(assetIds: Set<AssetId>)

    suspend fun recordCleanup(summary: CleanupSummary)

    suspend fun cleanupHistory(): List<CleanupSummary>
}
