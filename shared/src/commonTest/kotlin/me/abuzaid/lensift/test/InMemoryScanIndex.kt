package me.abuzaid.lensift.test

import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.PhotoDescriptor
import me.abuzaid.lensift.index.AnalysisRecord
import me.abuzaid.lensift.index.CleanupSummary
import me.abuzaid.lensift.index.IndexPartition
import me.abuzaid.lensift.index.ScanIndex

class InMemoryScanIndex(initialRecords: List<AnalysisRecord> = emptyList()) : ScanIndex {
    private val records = initialRecords.associateByTo(linkedMapOf()) { it.descriptor.id }
    private val cleanup = mutableListOf<CleanupSummary>()

    var failOnPartition: Throwable? = null
    var failOnSave: Throwable? = null
    var failOnCurrentRecords: Throwable? = null
    var afterAnalysisCommitted: suspend (AnalysisRecord) -> Unit = {}
    var saveAnalysisCalls: Int = 0
        private set
    var saveExactHashCalls: Int = 0
        private set
    val analysisWritesByAsset: MutableMap<AssetId, Int> = linkedMapOf()
    var lastPurgedAccessibleIds: Set<AssetId>? = null
        private set

    override suspend fun partitionChanged(
        descriptors: List<PhotoDescriptor>,
        analyzerVersion: Int,
    ): IndexPartition {
        failOnPartition?.let { throw it }
        val reusable = mutableListOf<AnalysisRecord>()
        val changed = mutableListOf<PhotoDescriptor>()
        descriptors.forEach { descriptor ->
            val record = records[descriptor.id]
            if (
                record != null &&
                record.descriptor.contentSignature == descriptor.contentSignature &&
                record.analyzerVersion == analyzerVersion
            ) {
                reusable += record
            } else {
                changed += descriptor
            }
        }
        return IndexPartition(reusable, changed)
    }

    override suspend fun saveAnalysis(record: AnalysisRecord) {
        failOnSave?.let { throw it }
        saveAnalysisCalls += 1
        analysisWritesByAsset[record.descriptor.id] = analysisWritesByAsset.getOrElse(record.descriptor.id) { 0 } + 1
        records[record.descriptor.id] = record
        afterAnalysisCommitted(record)
    }

    override suspend fun saveExactHash(assetId: AssetId, sha256: String) {
        val record = checkNotNull(records[assetId])
        saveExactHashCalls += 1
        records[assetId] = record.copy(sha256 = sha256)
    }

    override suspend fun currentRecords(): List<AnalysisRecord> {
        failOnCurrentRecords?.let { throw it }
        return records.values.toList()
    }

    override suspend fun purgeExcept(accessibleIds: Set<AssetId>) {
        lastPurgedAccessibleIds = accessibleIds.toSet()
        records.keys.retainAll(accessibleIds)
    }

    override suspend fun recordCleanup(summary: CleanupSummary) {
        cleanup += summary
    }

    override suspend fun cleanupHistory(): List<CleanupSummary> = cleanup.toList()

    fun record(assetId: AssetId): AnalysisRecord? = records[assetId]
}
