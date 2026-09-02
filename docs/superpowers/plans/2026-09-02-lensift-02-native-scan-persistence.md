# Lensift Native Scan and Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enumerate authorized photos on Android and iOS, analyze only new or changed assets, persist results locally, stream stable findings, and support pause, cancellation, invalidation, and warm resume.

**Architecture:** `commonMain` defines value-only gateway contracts, a SQLDelight-backed index, and a staged scan state machine. `androidMain` and `iosMain` translate MediaStore/PhotoKit data into common descriptors and bounded luminance/byte streams. Native application code still owns permission presentation and deletion; this plan only derives access state and reads authorized assets.

**Tech Stack:** Kotlin Multiplatform 2.4.10, kotlinx-coroutines 1.11.0, SQLDelight 2.3.2, Android MediaStore/ImageDecoder, iOS PhotoKit/ImageIO, Android SQLite driver, Native SQLite driver.

**Spec:** `docs/superpowers/specs/2026-09-02-lensift-product-design.md`

## Global Constraints

- Complete Plan 01 first and reuse its exact domain types.
- Never persist permission state as truth; query the platform every time a scan starts or the app resumes.
- Never pass `Uri`, `Bitmap`, `PHAsset`, `UIImage`, `Context`, or view-controller types into `commonMain`.
- Decode oriented 8-bit luma only, longest edge at most 512 px, never upscale.
- Use at most two concurrent decodes and one original-byte stream by default.
- A cancellation request stops scheduling immediately and becomes observable within 500 ms after the current atomic platform decode/hash call returns.
- SQL rows may contain identifiers and analysis values, but never full pixels, thumbnails, filenames, or location metadata.

---

## Task 1: Define the photo-library and scan contracts

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/gateway/PhotoLibraryGateway.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/scan/ScanState.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/scan/ScanModels.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/gateway/PhotoLibraryGatewayContractTest.kt`

- [ ] **Step 1: Write a failing fake-gateway contract test**

Create a fake that returns two descriptors and a 512 px luma frame. Assert descriptor enumeration preserves opaque IDs and that access can change from partial to full without reconstructing the gateway.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :shared:allTests --tests '*PhotoLibraryGatewayContractTest'`

Expected: FAIL because the contracts are absent.

- [ ] **Step 3: Implement the value-only boundary**

```kotlin
enum class AccessState { Full, Partial, Denied, Restricted, NotDetermined }

sealed interface LibraryChange {
    data class Changed(val assetIds: Set<AssetId>) : LibraryChange
    data object AccessMayHaveChanged : LibraryChange
}

interface PhotoLibraryGateway {
    suspend fun currentAccess(): AccessState
    fun enumerateAccessibleImages(): Flow<PhotoDescriptor>
    suspend fun decodeLuma(assetId: AssetId, targetLongestEdge: Int = 512): LumaFrame
    fun originalByteChunks(assetId: AssetId): Flow<ByteArray>
    fun observeChanges(): Flow<LibraryChange>
}
```

Define the sealed `ScanState` hierarchy exactly as `Idle`, `Indexing`, `Analyzing`, `Grouping`, `Pausing`, `Paused`, `Ready`, `RecoverableFailure`, and `Cancelled`. Every active state carries completed/total counts; `Ready` carries review totals and estimated recoverable bytes.

- [ ] **Step 4: Verify common and native compilation**

Run: `./gradlew :shared:allTests :shared:compileAndroidMain :shared:linkDebugFrameworkIosSimulatorArm64`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/gateway shared/src/commonMain/kotlin/me/abuzaid/lensift/scan shared/src/commonTest/kotlin/me/abuzaid/lensift/gateway
git commit -m "feat: define photo library and scan boundaries"
```

## Task 2: Create the SQLDelight scan index

**Files:**

- Modify: `shared/build.gradle.kts`
- Create: `shared/src/commonMain/sqldelight/me/abuzaid/lensift/db/Lensift.sq`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/index/ScanIndex.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/index/SqlDelightScanIndex.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/index/IndexModels.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/index/SqlDelightScanIndexTest.kt`

- [ ] **Step 1: Write failing reuse/invalidation/history tests**

Test that an analysis is reusable only when `contentSignature` and `analyzerVersion` both match, inaccessible assets are purged, sensitivity changes reuse stored metrics, and cleanup history stores only aggregate counts/bytes/timestamp.

- [ ] **Step 2: Run the index test**

Run: `./gradlew :shared:allTests --tests '*SqlDelightScanIndexTest'`

Expected: FAIL because the schema and implementation are missing.

- [ ] **Step 3: Add the schema**

Apply `app.cash.sqldelight`, create a database named `LensiftDatabase` with package `me.abuzaid.lensift.db`, set a schema output directory, and enable migration verification. Create these tables and generated queries:

```sql
CREATE TABLE asset_analysis (
  asset_id TEXT NOT NULL PRIMARY KEY,
  source_signature TEXT NOT NULL,
  analyzer_version INTEGER NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  byte_count INTEGER,
  captured_at_ms INTEGER,
  is_favorite INTEGER AS Boolean NOT NULL,
  is_edited INTEGER AS Boolean NOT NULL,
  perceptual_hash INTEGER NOT NULL,
  sha256 TEXT,
  laplacian_variance REAL NOT NULL,
  edge_density REAL NOT NULL,
  analyzed_at_ms INTEGER NOT NULL
);

CREATE TABLE cleanup_history (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  completed_at_ms INTEGER NOT NULL,
  exact_count INTEGER NOT NULL,
  near_count INTEGER NOT NULL,
  blur_count INTEGER NOT NULL,
  confirmed_estimated_bytes INTEGER NOT NULL
);

CREATE TABLE finding_group (
  group_id TEXT NOT NULL PRIMARY KEY,
  kind TEXT NOT NULL,
  policy_key TEXT NOT NULL,
  estimated_recoverable_bytes INTEGER NOT NULL,
  reviewed INTEGER AS Boolean NOT NULL DEFAULT 0
);

CREATE TABLE finding_member (
  group_id TEXT NOT NULL REFERENCES finding_group(group_id) ON DELETE CASCADE,
  asset_id TEXT NOT NULL REFERENCES asset_analysis(asset_id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  is_keeper INTEGER AS Boolean NOT NULL,
  selected_for_removal INTEGER AS Boolean NOT NULL,
  PRIMARY KEY (group_id, asset_id)
);
```

Add indices for `(perceptual_hash)`, `(captured_at_ms)`, and `(source_signature, analyzer_version)`. Add queries to upsert analyses transactionally, attach SHA-256 after exact-candidate verification, list reusable records, delete missing IDs, clear asset-specific review state, and read aggregate history.

- [ ] **Step 4: Implement `ScanIndex` over generated queries**

```kotlin
interface ScanIndex {
    suspend fun partitionChanged(
        descriptors: List<PhotoDescriptor>,
        analyzerVersion: Int,
    ): IndexPartition
    suspend fun saveAnalysis(record: AnalysisRecord)
    suspend fun saveExactHash(assetId: AssetId, sha256: String)
    suspend fun currentRecords(): List<AnalysisRecord>
    suspend fun purgeExcept(accessibleIds: Set<AssetId>)
    suspend fun recordCleanup(summary: CleanupSummary)
}

data class AnalysisRecord(
    val descriptor: PhotoDescriptor,
    val analyzerVersion: Int,
    val perceptualHash: Long,
    val sha256: String?,
    val blurEvidence: BlurEvidence,
    val analyzedAtEpochMillis: Long,
)

data class IndexPartition(
    val reusable: List<AnalysisRecord>,
    val changed: List<PhotoDescriptor>,
)

data class CleanupSummary(
    val completedAtEpochMillis: Long,
    val exactCount: Int,
    val nearCount: Int,
    val blurCount: Int,
    val confirmedEstimatedBytes: Long,
)
```

Inject a `Clock` and SQLDelight driver; keep transaction calls inside the implementation.

- [ ] **Step 5: Run schema verification and tests**

Run: `./gradlew :shared:verifySqlDelightMigration :shared:allTests --tests '*SqlDelightScanIndexTest'`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add shared/build.gradle.kts shared/src/commonMain/sqldelight shared/src/commonMain/kotlin/me/abuzaid/lensift/index shared/src/commonTest/kotlin/me/abuzaid/lensift/index
git commit -m "feat: persist incremental photo analysis"
```

## Task 3: Implement progressive finding assembly

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/findings/FindingAssembler.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/findings/FindingModels.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/findings/FindingAssemblerTest.kt`

- [ ] **Step 1: Write failing exact/near/blur assembly tests**

Assert that exact labels require equal non-null SHA-256 values, near groups exclude exact duplicates, blurry items remain single-item findings, estimates ignore unknown byte counts, and repeated assembly is byte-for-byte stable.

- [ ] **Step 2: Confirm failure**

Run: `./gradlew :shared:allTests --tests '*FindingAssemblerTest'`

Expected: FAIL because `FindingAssembler` is absent.

- [ ] **Step 3: Implement a two-pass assembler**

First generate exact candidates only from equal normalized dimensions and perceptual hashes, refine with equal known byte counts when available, then request SHA-256 for those candidate IDs through a returned `ExactHashWork` list. After hashes are available, assemble exact groups; exclude their IDs before complete-linkage near clustering and blur findings.

```kotlin
data class FindingSnapshot(
    val exactGroups: List<DuplicateGroup>,
    val nearGroups: List<DuplicateGroup>,
    val blurItems: List<BlurFinding>,
    val estimatedRecoverableBytes: Long,
)
```

- [ ] **Step 4: Verify progressive snapshots remain stable**

Run: `./gradlew :shared:allTests --tests '*FindingAssemblerTest'`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/findings shared/src/commonTest/kotlin/me/abuzaid/lensift/findings
git commit -m "feat: assemble progressive cleanup findings"
```

## Task 4: Build the resumable scan coordinator

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/scan/ScanCoordinator.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/scan/ScanControl.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/scan/AssetAnalyzer.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/scan/LensiftDispatchers.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/scan/ScanCoordinatorTest.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/test/FakePhotoLibraryGateway.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/test/InMemoryScanIndex.kt`

- [ ] **Step 1: Write failing state-machine tests**

Cover: empty library, partial access, progressive findings, two-decode cap, pause after current atomic call, resume without duplicate writes, cancellation, recoverable decode failure, and a warm unchanged scan with zero luma/original-byte calls.

- [ ] **Step 2: Run the coordinator suite**

Run: `./gradlew :shared:allTests --tests '*ScanCoordinatorTest'`

Expected: FAIL because the coordinator is absent.

- [ ] **Step 3: Implement structured concurrency and explicit control**

```kotlin
interface ScanControl {
    fun start(policy: AnalysisPolicy)
    fun pause()
    fun resume()
    fun cancel()
}

class ScanCoordinator(
    private val library: PhotoLibraryGateway,
    private val index: ScanIndex,
    private val analyzer: AssetAnalyzer,
    private val assembler: FindingAssembler,
    private val scope: CoroutineScope,
    private val dispatchers: LensiftDispatchers,
    private val analyzerVersion: Int,
) : ScanControl {
    val state: StateFlow<ScanState>
    val findings: StateFlow<FindingSnapshot>
}
```

Use a `Semaphore(2)` for luma decodes and `Semaphore(1)` for original streams. Check pause/cancel before scheduling, after decode, after analysis, and after each persisted record. Treat per-asset decode errors as recorded recoverable skips; fail the run only for access loss, database failure, or an invariant violation.

`AssetAnalyzer` composes `PerceptualHash` and `BlurAnalyzer` into one `AnalysisRecord`; `LensiftDispatchers` provides injected computation and database dispatchers. After the luma pass, the coordinator asks `FindingAssembler` for exact-hash work, streams only those originals through `Sha256`, persists the digests, then assembles final groups.

- [ ] **Step 4: Verify state transitions and warm rescan**

Run: `./gradlew :shared:allTests --tests '*ScanCoordinatorTest'`

Expected: PASS; the warm test asserts exactly zero calls to `decodeLuma` and `originalByteChunks`.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/scan shared/src/commonTest/kotlin/me/abuzaid/lensift/scan shared/src/commonTest/kotlin/me/abuzaid/lensift/test
git commit -m "feat: orchestrate resumable progressive scans"
```

## Task 5: Implement Android MediaStore reading and database driver

**Files:**

- Create: `shared/src/androidMain/kotlin/me/abuzaid/lensift/platform/AndroidPhotoLibraryGateway.kt`
- Create: `shared/src/androidMain/kotlin/me/abuzaid/lensift/platform/AndroidLumaDecoder.kt`
- Create: `shared/src/androidMain/kotlin/me/abuzaid/lensift/index/AndroidDatabaseDriver.kt`
- Create: `shared/src/androidHostTest/kotlin/me/abuzaid/lensift/platform/AndroidPhotoLibraryGatewayTest.kt`
- Modify: `shared/build.gradle.kts`

- [ ] **Step 1: Write failing cursor/access/signature tests**

Use Robolectric or a narrow fake `ContentResolverFacade` to cover Android 13 full access, Android 14 selected-photo access, denied access, null size metadata, stable sort order, and source-signature changes.

- [ ] **Step 2: Run Android unit tests**

Run: `./gradlew :shared:testAndroidHostTest --tests '*AndroidPhotoLibraryGatewayTest'`

Expected: FAIL because the Android adapter is absent.

- [ ] **Step 3: Implement MediaStore enumeration and bounded decode**

Query still images only with `_ID`, width, height, size, date taken, date modified, orientation, and favorite where available. Build content URIs internally. Decode through `ImageDecoder` with sample sizing, apply orientation, convert rows directly to `PhotoDescriptor`, close every cursor/source/stream, and emit original data in reusable 64 KiB chunks with cancellation checks.

Derive `AccessState.Partial` on Android 14+ when selected-photos access is granted without full visual-media access. Register a `ContentObserver` and emit coarse `LibraryChange` values; never expose a `Uri` to common code.

- [ ] **Step 4: Implement the Android SQL driver and run tests**

Use `AndroidSqliteDriver(LensiftDatabase.Schema, context, "lensift.db")` through a small factory and enable `PRAGMA foreign_keys=ON` when opening the connection.

Run: `./gradlew :shared:testAndroidHostTest :shared:compileAndroidMain`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/build.gradle.kts shared/src/androidMain shared/src/androidHostTest
git commit -m "feat: read Android photos through MediaStore"
```

## Task 6: Implement iOS PhotoKit reading and database driver

**Files:**

- Create: `shared/src/iosMain/kotlin/me/abuzaid/lensift/platform/IosPhotoLibraryGateway.kt`
- Create: `shared/src/iosMain/kotlin/me/abuzaid/lensift/platform/IosLumaDecoder.kt`
- Create: `shared/src/iosMain/kotlin/me/abuzaid/lensift/index/IosDatabaseDriver.kt`
- Create: `shared/src/iosTest/kotlin/me/abuzaid/lensift/platform/IosPhotoLibraryGatewayTest.kt`
- Modify: `shared/build.gradle.kts`

- [ ] **Step 1: Write failing authorization/availability/signature tests**

Hide PhotoKit behind `PhotoKitFacade`. Cover authorized, limited, denied, restricted, unavailable-in-iCloud, stable enumeration, and change callbacks without importing PhotoKit in tests.

- [ ] **Step 2: Run iOS simulator tests**

Run: `./gradlew :shared:iosSimulatorArm64Test --tests '*IosPhotoLibraryGatewayTest'`

Expected: FAIL because the iOS adapter is absent.

- [ ] **Step 3: Implement PhotoKit enumeration and local-only decoding**

Fetch `PHAssetMediaTypeImage`, translate `localIdentifier`, dimensions, creation/modification dates, favorite, and edit state to common values. Use request options with network access disabled. If an asset is only in iCloud, emit a recoverable unavailable result and continue. Normalize orientation and luma through ImageIO/vImage-compatible buffers, releasing native objects inside `autoreleasepool` per asset.

Register a `PHPhotoLibraryChangeObserverProtocol`, translate changes to opaque IDs where available, and retain/unregister the observer with gateway lifecycle.

- [ ] **Step 4: Implement the Native SQL driver and run tests**

Use `NativeSqliteDriver(LensiftDatabase.Schema, "lensift.db")` through a factory and enable `PRAGMA foreign_keys=ON` when opening the connection.

Run: `./gradlew :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosSimulatorArm64`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/build.gradle.kts shared/src/iosMain shared/src/iosTest
git commit -m "feat: read iOS photos through PhotoKit"
```

## Task 7: Reconcile live library changes and access changes

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/scan/LibraryReconciler.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/scan/LibraryReconcilerTest.kt`
- Modify: `shared/src/commonMain/kotlin/me/abuzaid/lensift/scan/ScanCoordinator.kt`

- [ ] **Step 1: Write failing reconciliation tests**

Cover added/changed/removed IDs, limited-access shrink/expand, a change arriving during analysis, debounce/coalescing, and ensuring inaccessible IDs disappear from findings and persistence.

- [ ] **Step 2: Run focused tests**

Run: `./gradlew :shared:allTests --tests '*LibraryReconcilerTest'`

Expected: FAIL before the reconciler exists.

- [ ] **Step 3: Implement single-flight reconciliation**

Coalesce changes for 300 ms, re-read access and descriptors, purge inaccessible IDs, invalidate changed signatures, and request one follow-up scan. If a scan is active, mark it dirty and reconcile once it reaches a safe boundary; never run two coordinators concurrently.

- [ ] **Step 4: Run the full shared matrix**

Run: `./gradlew :shared:allTests :shared:testAndroidHostTest :shared:iosSimulatorArm64Test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/scan shared/src/commonTest/kotlin/me/abuzaid/lensift/scan
git commit -m "feat: reconcile photo library changes"
```

## Plan acceptance

- [ ] A fake 10,000-descriptor scan completes with bounded queues and no list of decoded frames retained.
- [ ] A second unchanged fake scan performs zero luma decodes and zero original-byte reads.
- [ ] Android and iOS adapter tests cover full/partial-or-limited/denied states and unavailable assets.
- [ ] `./gradlew :shared:verifySqlDelightMigration :shared:allTests :shared:testAndroidHostTest :shared:iosSimulatorArm64Test` passes.
- [ ] `rg -n "android\.provider|android\.graphics|platform\.Photos|UIKit|SwiftUI" shared/src/commonMain` returns no matches.
- [ ] `git diff --check` passes and all task commits are local only.
