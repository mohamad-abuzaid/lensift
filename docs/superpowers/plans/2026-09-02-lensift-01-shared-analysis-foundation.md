# Lensift Shared Analysis Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bootstrap the Lensift monorepo and deliver a deterministic, platform-neutral engine that fingerprints, scores, clusters, and explains photo-cleanup candidates.

**Architecture:** The `shared` KMP module owns immutable domain models and pure analysis policies. Algorithms accept byte/luma inputs and return evidence-rich results; they never query a photo library, access a database, invoke a UI, or delete content. Common tests use synthetic fixtures and golden vectors so Android and iOS receive identical decisions.

**Tech Stack:** Kotlin 2.4.10, Kotlin Multiplatform, Gradle 9.3.1, AGP 9.1.0, kotlinx-coroutines 1.11.0, kotlin-test, JDK 17.

**Spec:** `docs/superpowers/specs/2026-09-02-lensift-product-design.md`

## Global Constraints

- Package all shared code under `me.abuzaid.lensift`.
- Represent perceptual hashes as signed `Long` bit patterns so SQLDelight can persist them later; compare with `(left xor right).countOneBits()`.
- Treat every threshold as named policy data. Do not scatter numeric cutoffs through algorithms.
- Validate pixel dimensions and buffer lengths at constructors/boundaries.
- Preserve stable ordering by asset ID whenever scores tie; nondeterministic groups are test failures.
- Use only synthetic or explicitly consented fixtures.

---

## Task 1: Bootstrap the Gradle monorepo

**Files:**

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `shared/build.gradle.kts`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/LensiftCore.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/LensiftCoreTest.kt`
- Create with Gradle wrapper: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Modify: `.gitignore`

- [ ] **Step 1: Write the failing smoke test**

```kotlin
package me.abuzaid.lensift

import kotlin.test.Test
import kotlin.test.assertEquals

class LensiftCoreTest {
    @Test
    fun exposesBuildIdentity() {
        assertEquals("Lensift shared core", LensiftCore.identity)
    }
}
```

- [ ] **Step 2: Create the pinned build files and verify the test cannot compile yet**

Use version-catalog entries for Kotlin `2.4.10`, the Kotlin Compose compiler plugin `2.4.10`, AGP `9.1.0`, coroutines `1.11.0`, SQLDelight `2.3.2`, Compose BOM `2026.06.00`, and Activity Compose `1.13.0`. Apply `org.jetbrains.kotlin.multiplatform` plus `com.android.kotlin.multiplatform.library` to `shared`; configure `android`, `iosArm64`, `iosSimulatorArm64`, and `iosX64`; set Android minimum SDK 30 and iOS deployment target 16. Configure every iOS target with a static framework named `Shared`, required by the later direct Xcode integration. Opt into Android host tests with `kotlin.android.withHostTest { isIncludeAndroidResources = true }`, which creates `src/androidHostTest` and the `testAndroidHostTest` task.

Run: `./gradlew :shared:allTests`

Expected: FAIL because `LensiftCore` does not exist.

- [ ] **Step 3: Add the minimum implementation**

```kotlin
package me.abuzaid.lensift

public object LensiftCore {
    public const val identity: String = "Lensift shared core"
}
```

- [ ] **Step 4: Generate/lock the wrapper and run the complete shared matrix**

Run: `gradle wrapper --gradle-version 9.3.1`

Run: `./gradlew :shared:allTests :shared:compileAndroidMain :shared:linkDebugFrameworkIosSimulatorArm64`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle shared
git commit -m "build: bootstrap Lensift KMP core"
```

## Task 2: Define the domain contract and policy presets

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/domain/PhotoModels.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/domain/AnalysisPolicy.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/domain/AnalysisPolicyTest.kt`

- [ ] **Step 1: Write failing policy and invariant tests**

```kotlin
class AnalysisPolicyTest {
    @Test fun rejectsInvalidThresholds() {
        assertFailsWith<IllegalArgumentException> {
            AnalysisPolicy(Sensitivity.Balanced, -1, 90_000, 0.02, BlurPolicy(85.0, 0.075))
        }
    }

    @Test fun lumaFrameRejectsWrongBufferSize() {
        assertFailsWith<IllegalArgumentException> { LumaFrame(4, 4, ByteArray(15)) }
    }
}
```

- [ ] **Step 2: Run the tests to prove the contract is absent**

Run: `./gradlew :shared:allTests --tests '*AnalysisPolicyTest'`

Expected: FAIL with unresolved `AnalysisPolicy` and `LumaFrame`.

- [ ] **Step 3: Implement explicit models and presets**

Define these exact public types:

```kotlin
data class AssetId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class PhotoDescriptor(
    val id: AssetId,
    val contentSignature: String,
    val width: Int,
    val height: Int,
    val byteCount: Long?,
    val capturedAtEpochMillis: Long?,
    val isFavorite: Boolean,
    val isEdited: Boolean,
)

class LumaFrame(val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(width > 0 && height > 0 && pixels.size == width * height) }
}

enum class Sensitivity { Conservative, Balanced, Broad }

data class AnalysisPolicy(
    val sensitivity: Sensitivity,
    val maxPerceptualDistance: Int,
    val maxCaptureGapMillis: Long,
    val maxAspectRatioDelta: Double,
    val blur: BlurPolicy,
) {
    init {
        require(maxPerceptualDistance in 0..64)
        require(maxCaptureGapMillis >= 0)
        require(maxAspectRatioDelta >= 0.0)
    }
}

data class BlurPolicy(
    val laplacianVarianceCeiling: Double,
    val edgeDensityCeiling: Double,
)
```

Add `ExactDuplicate`, `NearDuplicate`, and `BlurCandidate` evidence models with asset IDs and human-readable reason enums, not preformatted UI strings. Do not add release presets yet; Task 8 derives and locks them from the development corpus.

- [ ] **Step 4: Run tests and API compilation**

Run: `./gradlew :shared:allTests :shared:compileAndroidMain`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain shared/src/commonTest
git commit -m "feat: define shared photo analysis contracts"
```

## Task 3: Implement streaming SHA-256 content fingerprints

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/analysis/Sha256.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/analysis/Sha256Test.kt`

- [ ] **Step 1: Write failing NIST-vector and chunk-boundary tests**

Test the empty input and `abc` vectors, then assert that one-shot input and irregular chunks produce the same lowercase hexadecimal digest.

```kotlin
@Test fun hashesAbc() {
    val digest = Sha256().update("abc".encodeToByteArray()).digestHex()
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
}
```

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :shared:allTests --tests '*Sha256Test'`

Expected: FAIL because `Sha256` is missing.

- [ ] **Step 3: Implement a common streaming hasher**

Implement `update(bytes: ByteArray): Sha256`, `digest(): ByteArray`, and `digestHex(): String` in common code. Process 64-byte blocks, use the standard SHA-256 constants, and reject `update` after finalization. Do not buffer the whole file.

- [ ] **Step 4: Verify golden vectors and mutation safety**

Run: `./gradlew :shared:allTests --tests '*Sha256Test'`

Expected: PASS for empty, `abc`, million-`a`, and irregular chunking vectors.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/analysis/Sha256.kt shared/src/commonTest/kotlin/me/abuzaid/lensift/analysis/Sha256Test.kt
git commit -m "feat: add streaming SHA-256 fingerprints"
```

## Task 4: Implement DCT perceptual hashing and candidate bucketing

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/analysis/PerceptualHash.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/analysis/CandidateBucketer.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/analysis/PerceptualHashTest.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/analysis/CandidateBucketerTest.kt`
- Create: `shared/src/commonTest/resources/phash/gradient-32x32.luma`

- [ ] **Step 1: Write failing invariance and pruning tests**

Assert that identical frames produce identical hashes, a brightness-shifted frame stays within four bits, a checkerboard differs by at least 20 bits, and the bucketer refuses pairs outside capture-time/aspect-ratio limits. Add a property test that every generated pair within the active Hamming threshold shares at least one candidate band even when changed bits cross a prefix boundary.

- [ ] **Step 2: Run the focused tests**

Run: `./gradlew :shared:allTests --tests '*PerceptualHashTest' --tests '*CandidateBucketerTest'`

Expected: FAIL because both analyzers are absent.

- [ ] **Step 3: Implement the minimum deterministic algorithms**

`PerceptualHash.compute(frame)` must resize by area averaging to 32×32, apply a 2D DCT, take the top-left 8×8 coefficients excluding DC when finding the median, and pack 64 comparisons into a `Long`. `CandidateBucketer` must first partition by coarse aspect ratio and capture-time window. Within each partition, split the 64 hash bits into `maxPerceptualDistance + 1` deterministic contiguous bands; the pigeonhole principle guarantees that hashes within the threshold share at least one unchanged band. Deduplicate band hits, then run the exact Hamming comparison.

- [ ] **Step 4: Verify behavior and stable ordering**

Run: `./gradlew :shared:allTests --tests '*PerceptualHashTest' --tests '*CandidateBucketerTest'`

Expected: PASS with candidate IDs sorted lexicographically.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/analysis shared/src/commonTest
git commit -m "feat: add perceptual hashing and candidate bucketing"
```

## Task 5: Implement blur evidence without false precision

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/analysis/BlurAnalyzer.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/analysis/BlurAnalyzerTest.kt`

- [ ] **Step 1: Write failing synthetic-focus tests**

Build a sharp edge grid and a box-blurred copy in memory. Assert the blurred copy has lower Laplacian variance and edge density, and only it is classified as `PossiblyBlurred` under `Balanced.blur`.

- [ ] **Step 2: Confirm failure**

Run: `./gradlew :shared:allTests --tests '*BlurAnalyzerTest'`

Expected: FAIL because `BlurAnalyzer` is missing.

- [ ] **Step 3: Implement two independent signals**

```kotlin
data class BlurEvidence(
    val laplacianVariance: Double,
    val edgeDensity: Double,
    val verdict: BlurVerdict,
)

enum class BlurVerdict { PossiblyBlurred, Inconclusive }
```

Compute variance of a 3×3 Laplacian response and Sobel edge density on the downscaled luma frame. Classify only when both signals cross the selected policy ceiling; expose raw evidence so native UIs can explain uncertainty.

- [ ] **Step 4: Run boundary and uniform-image tests**

Run: `./gradlew :shared:allTests --tests '*BlurAnalyzerTest'`

Expected: PASS, including uniform images returning `Inconclusive` rather than confidently “blurred.”

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/analysis/BlurAnalyzer.kt shared/src/commonTest/kotlin/me/abuzaid/lensift/analysis/BlurAnalyzerTest.kt
git commit -m "feat: add explainable blur evidence"
```

## Task 6: Build complete-linkage near-duplicate clusters

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/analysis/NearDuplicateClusterer.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/analysis/NearDuplicateClustererTest.kt`

- [ ] **Step 1: Write the chaining counterexample**

Create A–B and B–C pairs within the threshold while A–C exceeds it. Assert the result never merges all three, then assert input shuffling returns the same cluster IDs/order.

- [ ] **Step 2: Prove single-linkage behavior would fail**

Run: `./gradlew :shared:allTests --tests '*NearDuplicateClustererTest'`

Expected: FAIL before the clusterer exists.

- [ ] **Step 3: Implement deterministic complete linkage**

Merge two clusters only when every cross-cluster pair passes the active policy. Use the smallest asset ID as the stable cluster ID; order clusters and members by ID after scoring.

- [ ] **Step 4: Run clustering tests**

Run: `./gradlew :shared:allTests --tests '*NearDuplicateClustererTest'`

Expected: PASS for chains, isolated nodes, ties, and shuffled input.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/analysis/NearDuplicateClusterer.kt shared/src/commonTest/kotlin/me/abuzaid/lensift/analysis/NearDuplicateClustererTest.kt
git commit -m "feat: cluster near duplicates with complete linkage"
```

## Task 7: Add explainable keeper recommendations and safe defaults

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/recommendation/KeeperRecommender.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/recommendation/ReviewSelection.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/recommendation/KeeperRecommenderTest.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/recommendation/ReviewSelectionTest.kt`

- [ ] **Step 1: Write failing ranking and selection tests**

Assert the ranking order: favorite, edited version, sharper evidence, greater pixel count, earlier stable asset ID. Assert exact groups preselect every non-keeper except favorites, while near and blur findings preselect nothing.

- [ ] **Step 2: Confirm failure**

Run: `./gradlew :shared:allTests --tests '*KeeperRecommenderTest' --tests '*ReviewSelectionTest'`

Expected: FAIL because recommendation classes are missing.

- [ ] **Step 3: Implement reasons as structured data**

```kotlin
data class KeeperRecommendation(
    val keeper: AssetId,
    val reasons: List<KeeperReason>,
)

enum class KeeperReason { Favorite, Edited, Sharper, HigherResolution, StableTieBreak }
```

Return ordered reason enums and keep UI text outside the shared module. Implement `ReviewSelection.initialFor(finding)` so exact groups select redundant non-favorites while near and blur findings select nothing.

- [ ] **Step 4: Run the complete shared suite**

Run: `./gradlew :shared:allTests`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/recommendation shared/src/commonTest/kotlin/me/abuzaid/lensift/recommendation
git commit -m "feat: explain keeper choices and safe review defaults"
```

## Task 8: Add a deterministic development corpus and quality gate

**Files:**

- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/quality/SyntheticCorpus.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/quality/AnalysisQualityTest.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/quality/PolicySelector.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/domain/ReleasePolicies.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/domain/ReleasePolicyTest.kt`
- Create: `docs/quality/development-corpus.md`

- [ ] **Step 1: Write a failing aggregate-quality test**

Generate at least 40 labeled source families covering byte-identical copies, recompression, resize, brightness, crop, burst-like changes, sharp motion, intentional bokeh, uniform walls, and synthetic blur. Calculate TP/FP/FN independently for exact, near, and blur. Partition by source family so variants never cross development/test boundaries.

- [ ] **Step 2: Run the aggregate gate**

Run: `./gradlew :shared:allTests --tests '*AnalysisQualityTest'`

Expected: FAIL until the corpus labels and evaluator exist or until thresholds meet the gate.

- [ ] **Step 3: Implement the evaluator and document corpus provenance**

On the development split, search pHash distances `0..20`, capture windows `[15, 30, 60, 90, 120, 180]` seconds, aspect deltas `[0.005, 0.01, 0.02, 0.04]`, and observed blur-score cut points. Choose Balanced as the broadest candidate meeting exact precision/recall 1.00, near precision at least 0.90 and recall at least 0.85, and blur precision at least 0.85. Choose Conservative as the next tighter qualifying tuple and Broad as the next wider tuple; if no wider tuple preserves the precision gate, Broad equals Balanced and the report says so.

Commit the selected numeric values and corpus/analyzer version in `ReleasePolicies.kt`; make `ReleasePolicyTest` assert those exact values and monotonic ordering. Keep the test split untouched until Plan 05. Record the generator seed, transform parameters, selection algorithm, known blind spots, and the fact that this is a development corpus—not field validation.

- [ ] **Step 4: Run all common and platform compilation checks**

Run: `./gradlew :shared:allTests :shared:compileAndroidMain :shared:linkDebugFrameworkIosSimulatorArm64`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/domain/ReleasePolicies.kt shared/src/commonTest/kotlin/me/abuzaid/lensift/quality shared/src/commonTest/kotlin/me/abuzaid/lensift/domain/ReleasePolicyTest.kt docs/quality/development-corpus.md
git commit -m "test: gate shared analysis quality"
```

## Plan acceptance

- [ ] `./gradlew :shared:allTests` passes twice consecutively.
- [ ] `./gradlew :shared:compileAndroidMain :shared:linkDebugFrameworkIosSimulatorArm64` passes.
- [ ] `rg -n "TODO|FIXME|TBD" shared docs/quality` returns no unresolved implementation markers.
- [ ] `git diff --check` passes and `git status --short` contains only intentional files.
- [ ] The shared module contains no imports from MediaStore, PhotoKit, SwiftUI, Compose, SQLDelight, or networking packages.
