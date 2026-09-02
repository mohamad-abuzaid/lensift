# Lensift Hardening and Portfolio Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the working Android/iOS applications into defensible Senior/Lead portfolio evidence through measured accuracy, large-library performance, privacy/accessibility verification, architecture records, a concise demo, and a release-ready public repository.

**Architecture:** A reproducible JVM corpus/benchmark module generates licensed synthetic derivatives and machine-readable reports. Device harnesses measure the real native pipelines without shipping test media in the product. CI independently gates shared, Android, iOS, privacy, and documentation contracts. Portfolio artifacts summarize measured facts and link to raw reports rather than making unbounded claims.

**Tech Stack:** Kotlin/JVM benchmark CLI, kotlinx-serialization, JUnit, Android instrumentation, XCTest/XCUI, GitHub Actions, Mermaid, ffmpeg, Apache-2.0.

**Spec:** `docs/superpowers/specs/2026-09-02-lensift-product-design.md`

## Global Constraints

- Complete Plans 01–04 first.
- Never use private personal-gallery images in commits, CI artifacts, demos, or reports.
- Keep a source/license/consent record for every non-synthetic corpus image.
- Do not tune on the untouched test split. Evaluate it once after locking policies; any subsequent tuning creates and documents a new holdout split.
- Report device-specific measurements as measurements, not universal guarantees.
- If a release gate fails, publish the limitation only after trying a bounded evidence-based fix; do not massage labels or silently remove hard cases.
- Do not push, tag, publish a GitHub release, or change the public profile without explicit user approval at the final gate.

---

## Task 1: Build the reproducible corpus and metrics CLI

**Files:**

- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `benchmark/build.gradle.kts`
- Create: `benchmark/src/main/kotlin/me/abuzaid/lensift/benchmark/Main.kt`
- Create: `benchmark/src/main/kotlin/me/abuzaid/lensift/benchmark/CorpusGenerator.kt`
- Create: `benchmark/src/main/kotlin/me/abuzaid/lensift/benchmark/MetricsEvaluator.kt`
- Create: `benchmark/src/main/kotlin/me/abuzaid/lensift/benchmark/WilsonInterval.kt`
- Create: `benchmark/src/main/kotlin/me/abuzaid/lensift/benchmark/ReportModels.kt`
- Create: `benchmark/src/test/kotlin/me/abuzaid/lensift/benchmark/MetricsEvaluatorTest.kt`
- Create: `benchmark/corpus/sources.json`
- Create: `benchmark/corpus/labels.schema.json`
- Create: `benchmark/.gitignore`

- [ ] **Step 1: Write failing confusion-matrix and interval tests**

Use a tiny labeled set with known TP/FP/FN/TN counts. Assert precision, recall, Wilson 95% intervals, per-category isolation, and division-by-zero behavior.

- [ ] **Step 2: Run before the module exists**

Run: `./gradlew :benchmark:test --tests '*MetricsEvaluatorTest'`

Expected: FAIL because `benchmark` is absent.

- [ ] **Step 3: Implement generation and evaluation commands**

Provide these deterministic commands:

```text
generate --seed 82912045 --sources benchmark/corpus/sources.json --out benchmark/build/corpus
evaluate --labels benchmark/build/corpus/test-labels.json --out benchmark/build/reports/quality.json
```

Generate exact byte copies, metadata rewrites, resize, JPEG recompression, small crop, exposure change, defocus/Gaussian blur, motion blur, and hard negatives. Partition by source family—not variant—into development and test splits to prevent leakage. `sources.json` records source ID, author/owner, license or consent, original URL when applicable, SHA-256, and redistribution status. Generated files remain build artifacts unless their source licenses permit redistribution.

- [ ] **Step 4: Verify reproducibility**

Run the generator twice into separate build directories and compare manifest SHA-256 values.

Run: `./gradlew :benchmark:test :benchmark:run --args='generate --seed 82912045 --sources benchmark/corpus/sources.json --out benchmark/build/corpus'`

Expected: PASS and identical manifests for the same seed.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml benchmark
git commit -m "test: add reproducible Lensift corpus tooling"
```

## Task 2: Verify locked policies and evaluate the untouched split

**Files:**

- Create: `docs/benchmarks/quality-report.md`
- Create: `docs/benchmarks/quality-report.json`

- [ ] **Step 1: Write a failing report gate test**

Feed `MetricsEvaluator` a report that misses one release threshold. Assert the verification command exits non-zero and names the failed category/metric without changing labels or policies.

- [ ] **Step 2: Reproduce the locked development result**

Run: `./gradlew :shared:allTests --tests '*ReleasePolicyTest' :benchmark:run --args='evaluate --split development --policy release --out benchmark/build/reports/development-quality.json'`

Expected: PASS and reproduce Plan 01's committed development metrics within exact deterministic equality.

- [ ] **Step 3: Freeze inputs before touching the test split**

Record the current commit, release-policy values, corpus manifest hash, generator seed, and analyzer version in the report metadata. Confirm the test manifest contains source families absent from development. Do not alter `ReleasePolicies.kt` during or after test evaluation.

```bash
git diff --exit-code shared/src/commonMain/kotlin/me/abuzaid/lensift/domain/ReleasePolicies.kt
```

- [ ] **Step 4: Evaluate the untouched test split exactly once**

Run: `./gradlew :benchmark:run --args='evaluate --split test --policy release --out docs/benchmarks/quality-report.json'`

Generate the Markdown report from JSON. It must contain the frozen-input metadata, corpus composition, source-family split, TP/FP/FN, precision/recall with Wilson 95% intervals, exact/near/blur results separately, favorite/preselection safety checks, hard examples, and limitations. If a gate fails, preserve the result and return to design review instead of tuning on this split.

Expected release gates: exact precision/recall 1.00; near precision ≥0.90 and recall ≥0.85; blur precision ≥0.85; blur recall reported with target ≥0.75; no unsafe preselection.

- [ ] **Step 5: Commit the immutable evaluation evidence**

```bash
git add docs/benchmarks/quality-report.md docs/benchmarks/quality-report.json
git commit -m "docs: publish Lensift quality evaluation"
```

## Task 3: Add 10,000-image device benchmark harnesses

**Files:**

- Create: `benchmark/src/main/kotlin/me/abuzaid/lensift/benchmark/LargeLibraryGenerator.kt`
- Create: `benchmark/scripts/run_android_benchmark.sh`
- Create: `benchmark/scripts/run_ios_benchmark.sh`
- Create: `benchmark/report.schema.json`
- Create: `androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/benchmark/LargeLibraryBenchmarkTest.kt`
- Create: `iosApp/LensiftTests/LargeLibraryBenchmarkTests.swift`
- Create: `docs/benchmarks/performance-report.md`
- Create: `docs/benchmarks/android-device.json`
- Create: `docs/benchmarks/ios-device.json`

- [ ] **Step 1: Write failing report-schema and warm-rescan assertions**

Require device model, OS/app/analyzer versions, image count, cold wall time, images/sec, first-finding time, peak memory, database bytes, warm wall time, warm decode count, one-percent-change time, cancellation latency, and thermal/battery notes. Assert `imageCount == 10000`, peak memory `< 200 MiB`, warm decodes `== 0`, and warm time `< 5 s`.

- [ ] **Step 2: Run harness tests with a small fixture**

Run: `./gradlew :benchmark:test :androidApp:connectedDebugAndroidTest -Pbenchmark.imageCount=100`

Expected: FAIL until report capture exists.

- [ ] **Step 3: Implement deterministic large-library setup**

Generate 10,000 varied, compressible JPEG/HEIF-compatible fixtures without copying one identical file 10,000 times. The Android script installs/imports fixtures through `adb`, clears Lensift derived data between cold runs, captures `dumpsys meminfo`, and runs warm/1%-change passes. The iOS script boots a dedicated simulator, imports with `xcrun simctl addmedia`, captures XCTest signposts/metrics, and repeats the same passes. Both write schema-valid JSON and clean only their dedicated generated fixture set.

- [ ] **Step 4: Run on one documented representative device per platform**

Run: `benchmark/scripts/run_android_benchmark.sh --count 10000 --output docs/benchmarks/android-device.json`

Run: `benchmark/scripts/run_ios_benchmark.sh --count 10000 --output docs/benchmarks/ios-device.json`

If simulator-only iOS memory differs from a physical device, label it prominently and do not claim it as a device result. Tune queue/concurrency only from measured evidence, rerun from a clean fixture library, and keep before/after numbers.

- [ ] **Step 5: Generate and commit the performance report**

The Markdown report links raw JSON and states exactly which runs used physical hardware versus simulator/emulator.

```bash
git add benchmark androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/benchmark iosApp/LensiftTests/LargeLibraryBenchmarkTests.swift docs/benchmarks
git commit -m "perf: document 10000-image Lensift benchmarks"
```

## Task 4: Harden failure, backgrounding, and memory-pressure behavior

**Files:**

- Modify: `shared/src/commonMain/kotlin/me/abuzaid/lensift/scan/ScanCoordinator.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/scan/FailureMatrixTest.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/lifecycle/AndroidScanLifecycle.kt`
- Create: `iosApp/Lensift/Lifecycle/IosScanLifecycle.swift`
- Create: `docs/benchmarks/failure-matrix.md`

- [ ] **Step 1: Add failing matrix tests**

Cover corrupt image, unsupported format, access loss, database corruption/recreation, app backgrounding, library mutation during decode, repeated memory warnings, user cancellation, declined deletion, and partial deletion. One failed asset must not fail the scan.

- [ ] **Step 2: Run the matrix before hardening**

Run: `./gradlew :shared:allTests --tests '*FailureMatrixTest'`

Expected: at least one failure exposing the current missing transition or cleanup.

- [ ] **Step 3: Implement the smallest evidence-backed fixes**

Checkpoint after each persisted item. On background, stop scheduling and release frame references. On first memory warning, reduce decode concurrency from two to one; on continued pressure, pause with a recoverable explanation. Recreate only the derived index after corruption. Categorize logs by anonymous error code without IDs, names, paths, or hashes.

- [ ] **Step 4: Run platform lifecycle journeys and document outcomes**

Run: `./gradlew :shared:allTests :androidApp:connectedDebugAndroidTest`

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test`

Expected: PASS; complete `failure-matrix.md` with automated/manual evidence.

- [ ] **Step 5: Commit**

```bash
git add shared androidApp/src/main/kotlin/me/abuzaid/lensift/android/lifecycle iosApp/Lensift/Lifecycle docs/benchmarks/failure-matrix.md
git commit -m "fix: harden Lensift scan lifecycle failures"
```

## Task 5: Add CI and repository policy gates

**Files:**

- Create: `.github/workflows/shared-android.yml`
- Create: `.github/workflows/ios.yml`
- Create: `.github/workflows/repository-policy.yml`
- Create: `scripts/check_privacy_boundary.sh`
- Create: `scripts/check_documentation_links.sh`
- Create: `CODEOWNERS`

- [ ] **Step 1: Run failing policy scripts locally**

The privacy script fails on Android Internet permission, PhotoKit network access enabled, imports/usages of URLSession/network clients/analytics, or image-like assets outside approved fixture/app-icon paths. The documentation script fails on broken local links and missing required reports.

Run: `scripts/check_privacy_boundary.sh`

Run: `scripts/check_documentation_links.sh`

Expected: FAIL until scripts and required paths are complete.

- [ ] **Step 2: Implement separate Linux and macOS workflows**

Linux runs shared tests, Android unit/lint/assemble, SQL migrations, benchmark unit tests, privacy checks, and docs checks on JDK 17. macOS pins Xcode 26.4, builds the shared iOS framework, and runs iOS tests. Cache only Gradle dependencies/build metadata; never cache corpus images or user-like media.

- [ ] **Step 3: Verify workflows locally where possible**

Run: `./gradlew clean check :androidApp:lintDebug :androidApp:assembleDebug :benchmark:test`

Run: `scripts/check_privacy_boundary.sh && scripts/check_documentation_links.sh`

Expected: PASS.

- [ ] **Step 4: Validate YAML and commit**

Run: `ruby -e 'require "yaml"; Dir[".github/workflows/*.yml"].each { |f| YAML.load_file(f) }'`

Expected: PASS.

```bash
git add .github scripts CODEOWNERS
git commit -m "ci: gate Lensift mobile quality and privacy"
```

## Task 6: Write architecture decisions and the honest engineering narrative

**Files:**

- Create: `docs/adr/001-shared-logic-native-ui.md`
- Create: `docs/adr/002-on-device-classical-analysis.md`
- Create: `docs/adr/003-sqldelight-incremental-index.md`
- Create: `docs/adr/004-review-first-native-deletion.md`
- Create: `docs/adr/005-perceptual-hash-and-blur-method.md`
- Create: `docs/architecture.md`
- Create: `docs/what-failed.md`
- Create: `docs/privacy.md`
- Create: `docs/testing.md`

- [ ] **Step 1: Add a failing documentation contract test**

Extend `scripts/check_documentation_links.sh` to require ADR status/context/decision/consequences sections, one Mermaid system diagram, one scan/deletion sequence, benchmark links, and a “What failed” note containing an experiment, evidence, decision, and remaining limitation.

- [ ] **Step 2: Run the docs gate**

Run: `scripts/check_documentation_links.sh`

Expected: FAIL because the documents do not exist.

- [ ] **Step 3: Write decisions from implemented evidence**

Document why native UIs were retained, why there is no backend/custom ML model, why SQLDelight stores derived data, why deletion remains native-confirmed, and why pHash/blur signals are suggestions. For `what-failed.md`, use a real rejected experiment from implementation—such as single-linkage chaining, full-file hashing, or excessive decode concurrency—and include the measured reproduction rather than inventing drama.

- [ ] **Step 4: Validate diagrams against source names**

Run: `scripts/check_documentation_links.sh`

Run: `rg -n "PhotoLibraryGateway|ScanCoordinator|ScanIndex|DeletionGateway" shared androidApp iosApp docs/architecture.md`

Expected: PASS with matching component names and dependency direction.

- [ ] **Step 5: Commit**

```bash
git add docs/adr docs/architecture.md docs/what-failed.md docs/privacy.md docs/testing.md scripts/check_documentation_links.sh
git commit -m "docs: explain Lensift architecture and tradeoffs"
```

## Task 7: Produce the recruiter-first README and demo package

**Files:**

- Modify: `README.md`
- Create: `docs/media/android-dashboard-dark.png`
- Create: `docs/media/ios-dashboard-light.png`
- Create: `docs/media/duplicate-review.png`
- Create: `docs/media/blur-review.png`
- Create: `docs/media/system-confirmation.png`
- Create: `docs/media/lensift-demo.mp4`
- Create: `docs/media/demo-script.md`
- Create: `CONTRIBUTING.md`
- Create: `SECURITY.md`
- Create: `LICENSE`
- Create: `NOTICE`

- [ ] **Step 1: Write a failing README content check**

Require product promise, 60–90 second demo, Android/iOS screenshots, architecture diagram/link, privacy boundary, measured quality/performance table, build/test commands, trade-offs, roadmap, and license. Fail if claims contain “AI-powered,” “guaranteed,” or unqualified benchmark generalizations.

- [ ] **Step 2: Run the README gate**

Run: `scripts/check_documentation_links.sh`

Expected: FAIL until README/media are present.

- [ ] **Step 3: Capture a truthful demo and screenshots**

Use a synthetic/consented fixture library. In 60–90 seconds show both native apps, scan progress, partial/limited access affordance, exact and near review, uncertain blur explanation, in-app summary, and native deletion confirmation. Do not actually delete a personal photo. Compress to a practical GitHub size while preserving readable UI:

```bash
ffmpeg -i lensift-demo-source.mov -vf "scale=1280:-2" -c:v libx264 -crf 24 -preset slow -an docs/media/lensift-demo.mp4
```

- [ ] **Step 4: Write the three-minute README**

Lead with the real user problem and `Lensift suggests. You always decide.` Then show the demo, evidence table, architecture, privacy, technical decisions, and reproducible commands. Include Apache-2.0 text and accurate third-party notices.

- [ ] **Step 5: Verify media and docs**

Run: `ffprobe -v error -show_entries format=duration,size -of default=noprint_wrappers=1 docs/media/lensift-demo.mp4`

Expected: duration 60–90 seconds and reasonable repository size.

Run: `scripts/check_documentation_links.sh && git diff --check`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/media CONTRIBUTING.md SECURITY.md LICENSE NOTICE
git commit -m "docs: present Lensift as a mobile platform case study"
```

## Task 8: Perform one bounded final review and prepare release

**Files:**

- Create: `docs/release/1.0.0-checklist.md`
- Create: `docs/release/1.0.0-notes.md`
- Modify only if review finds a blocker: files identified by evidence

- [ ] **Step 1: Run the complete clean verification matrix**

Use `superpowers:verification-before-completion` and capture fresh output:

```bash
./gradlew clean check :shared:iosSimulatorArm64Test :androidApp:lintDebug :androidApp:assembleDebug :benchmark:test
xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' clean test analyze
scripts/check_privacy_boundary.sh
scripts/check_documentation_links.sh
git diff --check
```

- [ ] **Step 2: Request one independent code/architecture review**

Use `superpowers:requesting-code-review`. Act on evidence-backed Critical or Important blockers, add a regression test for each code fix, rerun the affected gate, and stop after this single bounded cycle. Record accepted/fixed/deferred findings in the release checklist.

- [ ] **Step 3: Inspect the final repository as a recruiter would**

Time a cold README review to under three minutes. Open every image/video/link, compare diagrams to current packages, confirm raw benchmark JSON is present, and verify no private media, secrets, build artifacts, or absolute local paths are tracked.

Run: `git ls-files | rg '(\.keystore$|\.jks$|GoogleService-Info|google-services|/build/|DerivedData|xcuserdata|\.DS_Store)'`

Expected: no matches.

- [ ] **Step 4: Prepare but do not publish the release**

Write release notes with supported OS versions, measured devices, privacy behavior, known limitations, and reproducible commands. Ensure the worktree is clean and all commits are on the intended local branch.

Stop here and ask the user for explicit approval before any push, tag, GitHub release, store submission, or GitHub-profile update.

- [ ] **Step 5: After explicit publication approval only**

Create a signed/annotated `v1.0.0` tag, push the reviewed branch and tag, create the GitHub release with demo/notes, verify the public repository rendering and CI, then add Lensift to the profile’s featured work. Do not perform this step under implementation approval alone.

## Plan acceptance

- [ ] Exact, near, and blur quality reports meet or honestly explain every approved gate with confidence intervals.
- [ ] 10,000-image cold/warm/1%-change runs are linked to raw device-specific evidence; peak memory is below 200 MiB and unchanged warm scan is under five seconds with zero decodes.
- [ ] Android and iOS full test/build/static-analysis matrices pass from clean state.
- [ ] Privacy scripts find no runtime networking or tracked private media.
- [ ] README, 60–90 second demo, screenshots, diagrams, ADRs, benchmark reports, and a real failed-experiment note render correctly.
- [ ] The final worktree is clean and publication awaits explicit approval.
