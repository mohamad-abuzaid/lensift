package me.abuzaid.lensift.index

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import me.abuzaid.lensift.analysis.BlurEvidence
import me.abuzaid.lensift.analysis.BlurVerdict
import me.abuzaid.lensift.db.LensiftDatabase
import me.abuzaid.lensift.domain.AssetId
import me.abuzaid.lensift.domain.PhotoDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlDelightScanIndexTest {
    @Test
    fun reuseRequiresMatchingContentSignatureAndAnalyzerVersion() = withIndex { index, _ ->
        val saved = record(id = "reusable", signature = "signature-v1", analyzerVersion = 7)
        index.saveAnalysis(saved)
        index.saveAnalysis(record(id = "signature-changed", signature = "signature-v1", analyzerVersion = 7))

        val partition = index.partitionChanged(
            descriptors = listOf(
                descriptor(id = "reusable", signature = "signature-v1"),
                descriptor(id = "signature-changed", signature = "signature-v2"),
                descriptor(id = "new", signature = "signature-v1"),
            ),
            analyzerVersion = 7,
        )

        assertEquals(listOf(saved), partition.reusable)
        assertEquals(listOf("signature-changed", "new"), partition.changed.map { it.id.value })

        val versionMismatch = index.partitionChanged(
            descriptors = listOf(descriptor(id = "reusable", signature = "signature-v1")),
            analyzerVersion = 8,
        )
        assertEquals(emptyList(), versionMismatch.reusable)
        assertEquals(listOf("reusable"), versionMismatch.changed.map { it.id.value })
    }

    @Test
    fun purgingAnInaccessibleAssetClearsItsWholeReviewGroup() = withIndex { index, database ->
        index.saveAnalysis(record(id = "accessible"))
        index.saveAnalysis(record(id = "inaccessible"))
        database.lensiftQueries.insertFindingGroup(
            group_id = "review-group",
            kind = "near",
            policy_key = "balanced-v1",
            estimated_recoverable_bytes = 10,
            reviewed = true,
        )
        database.lensiftQueries.insertFindingMember(
            group_id = "review-group",
            asset_id = "accessible",
            position = 0,
            is_keeper = true,
            selected_for_removal = false,
        )
        database.lensiftQueries.insertFindingMember(
            group_id = "review-group",
            asset_id = "inaccessible",
            position = 1,
            is_keeper = false,
            selected_for_removal = true,
        )

        index.purgeExcept(setOf(AssetId("accessible")))

        assertEquals(listOf("accessible"), index.currentRecords().map { it.descriptor.id.value })
        assertEquals(0, database.lensiftQueries.countFindingGroups().executeAsOne())
        assertEquals(0, database.lensiftQueries.countFindingMembers().executeAsOne())
    }

    @Test
    fun policySensitivityChangesReuseStoredRawMetricsWithoutReanalysis() = withIndex { index, _ ->
        val saved = record(
            id = "policy-independent",
            laplacianVariance = 0.0125,
            edgeDensity = 0.03125,
        )
        index.saveAnalysis(saved)

        val afterSensitivityChange = index.partitionChanged(
            descriptors = listOf(saved.descriptor),
            analyzerVersion = saved.analyzerVersion,
        )

        assertEquals(0.0125, afterSensitivityChange.reusable.single().blurEvidence.laplacianVariance)
        assertEquals(0.03125, afterSensitivityChange.reusable.single().blurEvidence.edgeDensity)
        assertEquals(emptyList(), afterSensitivityChange.changed)
    }

    @Test
    fun exactHashIsAttachedOnlyToTheRequestedStoredAsset() = withIndex { index, _ ->
        index.saveAnalysis(record(id = "candidate-a"))
        index.saveAnalysis(record(id = "candidate-b"))

        index.saveExactHash(AssetId("candidate-b"), "sha256-for-b")

        val byId = index.currentRecords().associateBy { it.descriptor.id.value }
        assertNull(byId.getValue("candidate-a").sha256)
        assertEquals("sha256-for-b", byId.getValue("candidate-b").sha256)
    }

    @Test
    fun cleanupHistoryRoundTripsOnlyAggregateSummaries() = withIndex { index, _ ->
        val first = CleanupSummary(
            completedAtEpochMillis = 1_000,
            exactCount = 2,
            nearCount = 3,
            blurCount = 4,
            confirmedEstimatedBytes = 5_000,
        )
        val second = CleanupSummary(
            completedAtEpochMillis = 2_000,
            exactCount = 0,
            nearCount = 1,
            blurCount = 0,
            confirmedEstimatedBytes = 100,
        )

        index.recordCleanup(first)
        index.recordCleanup(second)

        assertEquals(listOf(second, first), index.cleanupHistory())
    }

    @Test
    fun nullableByteCountCaptureDateAndHashRoundTripThroughSqlite() = withIndex { index, _ ->
        val saved = record(id = "nullable", byteCount = null, capturedAtEpochMillis = null, sha256 = null)

        index.saveAnalysis(saved)

        val loaded = index.currentRecords().single()
        assertNull(loaded.descriptor.byteCount)
        assertNull(loaded.descriptor.capturedAtEpochMillis)
        assertNull(loaded.sha256)
        assertEquals(saved, loaded)
    }

    private fun withIndex(block: suspend (SqlDelightScanIndex, LensiftDatabase) -> Unit) = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            LensiftDatabase.Schema.create(driver)
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            val database = createLensiftDatabase(driver)
            block(SqlDelightScanIndex(driver), database)
        } finally {
            driver.close()
        }
    }

    private fun record(
        id: String,
        signature: String = "signature",
        analyzerVersion: Int = 7,
        byteCount: Long? = 123,
        capturedAtEpochMillis: Long? = 456,
        sha256: String? = null,
        laplacianVariance: Double = 0.25,
        edgeDensity: Double = 0.5,
    ): AnalysisRecord = AnalysisRecord(
        descriptor = descriptor(id, signature, byteCount, capturedAtEpochMillis),
        analyzerVersion = analyzerVersion,
        perceptualHash = 0x1234,
        sha256 = sha256,
        blurEvidence = BlurEvidence(
            laplacianVariance = laplacianVariance,
            edgeDensity = edgeDensity,
            verdict = BlurVerdict.Inconclusive,
        ),
        analyzedAtEpochMillis = 789,
    )

    private fun descriptor(
        id: String,
        signature: String = "signature",
        byteCount: Long? = 123,
        capturedAtEpochMillis: Long? = 456,
    ): PhotoDescriptor = PhotoDescriptor(
        id = AssetId(id),
        contentSignature = signature,
        width = 4_032,
        height = 3_024,
        byteCount = byteCount,
        capturedAtEpochMillis = capturedAtEpochMillis,
        isFavorite = false,
        isEdited = false,
    )
}
