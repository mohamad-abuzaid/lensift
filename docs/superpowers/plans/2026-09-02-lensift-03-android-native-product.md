# Lensift Android Native Product Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the complete Android 11+ Lensift experience in native Jetpack Compose, from permission education through scan, review, system-confirmed deletion, history, and settings.

**Architecture:** A single Android application module depends on `shared`. Android ViewModels map shared flows and reason enums to immutable UI state. Compose owns presentation and navigation; Android-only controllers own permission launchers, thumbnail loading, settings intents, and `MediaStore.createDeleteRequest`. UI tests use fake shared gateways and controllers rather than a real personal gallery.

**Tech Stack:** AGP 9.1.0, compile/target SDK 36, min SDK 30, Jetpack Compose BOM 2026.06.00, Material 3, Activity Compose 1.13.0, Lifecycle 2.11.0, Navigation Compose 2.9.8, AndroidX Test, Espresso, Robolectric.

**Spec:** `docs/superpowers/specs/2026-09-02-lensift-product-design.md`

## Global Constraints

- Complete Plans 01 and 02 first.
- The Android manifest must not declare `android.permission.INTERNET`.
- Request photo access only after the user taps **Choose photo access**.
- Distinguish full, selected-only, denied, and not-yet-requested access; never render partial access as an empty full library.
- Use real thumbnails in product surfaces and synthetic/consented fixtures in tests and screenshots.
- Exact groups may preselect redundant non-favorites. Near and blurry findings start with zero selected removals.
- Only an explicit tap on **Continue to system confirmation** may launch `MediaStore.createDeleteRequest`.
- Green/mint is never the destructive color.

---

## Task 1: Create the Android app shell and dependency graph

**Files:**

- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `androidApp/build.gradle.kts`
- Create: `androidApp/src/main/AndroidManifest.xml`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/LensiftApplication.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/MainActivity.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/di/AppGraph.kt`
- Create: `androidApp/src/test/kotlin/me/abuzaid/lensift/android/ManifestContractTest.kt`

- [ ] **Step 1: Write a failing manifest privacy test**

```kotlin
@Test fun manifestHasNoInternetPermission() {
    val xml = File("src/main/AndroidManifest.xml").readText()
    assertFalse(xml.contains("android.permission.INTERNET"))
    assertTrue(xml.contains("android.permission.READ_MEDIA_IMAGES"))
}
```

- [ ] **Step 2: Run before the module exists**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*ManifestContractTest'`

Expected: FAIL because `androidApp` is absent.

- [ ] **Step 3: Add the application module**

Apply `com.android.application` and `org.jetbrains.kotlin.plugin.compose` `2.4.10`; AGP 9 has built-in Kotlin and must not also apply `org.jetbrains.kotlin.android`. Set namespace/application ID `me.abuzaid.lensift`, min 30, target/compile 36, JDK 17, and Compose. Depend on `project(":shared")`, the Compose BOM, Material 3, Activity, Lifecycle ViewModel Compose, and Navigation Compose.

The manifest declares API-appropriate read permissions, `READ_MEDIA_VISUAL_USER_SELECTED`, no location permission, and no network permission. `AppGraph` constructs the Android photo gateway, SQLDelight index, coordinator, and native repositories from application context.

- [ ] **Step 4: Build and run privacy test**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:assembleDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml androidApp
git commit -m "build: add native Android application"
```

## Task 2: Implement the Focus Pair identity and adaptive theme

**Files:**

- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui/theme/Color.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui/theme/Theme.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui/theme/Type.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui/components/FocusPairMark.kt`
- Create: `androidApp/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Create: `androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/ui/ThemeScreenshotTest.kt`

- [ ] **Step 1: Write failing dark/light semantic-token tests**

Render a token gallery in dark and light mode. Assert text/background contrast roles differ, error/destructive uses the error role rather than mint, and the Focus Pair mark exposes the description “Lensift”.

- [ ] **Step 2: Run the instrumentation test**

Run: `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.abuzaid.lensift.android.ui.ThemeScreenshotTest`

Expected: FAIL before theme/components exist.

- [ ] **Step 3: Implement semantic tokens and vector geometry**

Create dark-first deep ink-green surfaces, subdued green, primary mint, neutral text tiers, warning, and destructive tokens with a complete light scheme. Draw Focus Pair as two overlapping stroked circles and a crisp center focal point; verify the vector within adaptive icon safe bounds and at 24 dp.

- [ ] **Step 4: Verify both themes and font scaling**

Run: `./gradlew :androidApp:connectedDebugAndroidTest`

Expected: PASS at default and 200% font scale; no clipped title or button labels.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui androidApp/src/main/res androidApp/src/androidTest
git commit -m "feat: add Android Lensift visual identity"
```

## Task 3: Build permission education and access management

**Files:**

- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/access/AndroidPhotoAccessController.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/onboarding/OnboardingViewModel.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/onboarding/OnboardingScreen.kt`
- Create: `androidApp/src/test/kotlin/me/abuzaid/lensift/android/onboarding/OnboardingViewModelTest.kt`
- Create: `androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/onboarding/OnboardingScreenTest.kt`

- [ ] **Step 1: Write failing behavior tests**

Assert no permission request occurs on launch, the education copy says processing stays on device and deletion needs confirmation, one button requests access, partial access exposes **Manage access**, and denial exposes an app-settings action without a false empty state.

- [ ] **Step 2: Run unit and UI tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*OnboardingViewModelTest' :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.abuzaid.lensift.android.onboarding.OnboardingScreenTest`

Expected: FAIL before implementation.

- [ ] **Step 3: Implement version-aware permission control**

Use `READ_EXTERNAL_STORAGE` on API 30–32, `READ_MEDIA_IMAGES` on API 33+, and request `READ_MEDIA_VISUAL_USER_SELECTED` with image access on API 34+. Derive partial access from actual grants. Use the platform reselection flow where available and `ACTION_APPLICATION_DETAILS_SETTINGS` after denial; never store access as a preference.

- [ ] **Step 4: Verify all access states**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:connectedDebugAndroidTest`

Expected: PASS for full, partial, denied, and not-determined fakes.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/me/abuzaid/lensift/android/access androidApp/src/main/kotlin/me/abuzaid/lensift/android/onboarding androidApp/src/test androidApp/src/androidTest
git commit -m "feat: add Android photo access onboarding"
```

## Task 4: Implement navigation, dashboard, and circular workflow progress

**Files:**

- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/navigation/LensiftNavHost.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/home/DashboardUiState.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/home/DashboardViewModel.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/home/DashboardScreen.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui/components/WorkflowProgressCard.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui/components/FindingCard.kt`
- Create: `androidApp/src/test/kotlin/me/abuzaid/lensift/android/home/DashboardViewModelTest.kt`
- Create: `androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/home/DashboardScreenTest.kt`

- [ ] **Step 1: Write failing scan/review semantics tests**

Assert the ring label is `Scanning 120 of 1000` during analysis and `Reviewed 3 of 12` when ready. Test partial-access banner, exact/near/blur cards, estimated-space wording, **Up next**, pause/resume/cancel actions, and Home/Review/History navigation.

- [ ] **Step 2: Run focused tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*DashboardViewModelTest' :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.abuzaid.lensift.android.home.DashboardScreenTest`

Expected: FAIL before screen/state mapping exists.

- [ ] **Step 3: Implement the approved Review First hierarchy**

Map shared states to one immutable `DashboardUiState`. Use the circular card for real scan completion while active, then explicitly switch label/denominator to review completion. Show estimated recoverable storage, not “freed” storage. Cards include real thumbnails, counts, confidence-safe labels, and one dominant next action.

- [ ] **Step 4: Verify semantics and navigation**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:connectedDebugAndroidTest`

Expected: PASS; circular progress exposes text and range semantics in addition to color/shape.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/me/abuzaid/lensift/android/navigation androidApp/src/main/kotlin/me/abuzaid/lensift/android/home androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui/components androidApp/src/test androidApp/src/androidTest
git commit -m "feat: build Android review-first dashboard"
```

## Task 5: Load privacy-safe native thumbnails

**Files:**

- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/photos/AndroidAssetLocator.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/photos/AndroidThumbnailLoader.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui/components/AssetThumbnail.kt`
- Create: `androidApp/src/test/kotlin/me/abuzaid/lensift/android/photos/AndroidThumbnailLoaderTest.kt`

- [ ] **Step 1: Write failing bounded-cache tests**

Assert IDs resolve internally to MediaStore URIs, requested size is honored, stale IDs render an unavailable fallback tile, cache entries are memory-bounded, and no disk thumbnail cache is created.

- [ ] **Step 2: Run the test**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*AndroidThumbnailLoaderTest'`

Expected: FAIL before the loader exists.

- [ ] **Step 3: Implement a lifecycle-aware loader**

Use `ContentResolver.loadThumbnail` with a size derived from Compose constraints. Keep a small byte-counted in-memory LRU, cancel work when the composable leaves composition, and render an icon plus “Photo unavailable” semantics when an asset vanishes.

- [ ] **Step 4: Verify loading and cancellation**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:connectedDebugAndroidTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/me/abuzaid/lensift/android/photos androidApp/src/main/kotlin/me/abuzaid/lensift/android/ui/components/AssetThumbnail.kt androidApp/src/test/kotlin/me/abuzaid/lensift/android/photos
git commit -m "feat: load bounded Android photo thumbnails"
```

## Task 6: Build exact and near-duplicate review

**Files:**

- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/review/ReviewViewModel.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/review/ReviewUiState.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/review/DuplicateReviewScreen.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/review/ReasonChip.kt`
- Create: `androidApp/src/test/kotlin/me/abuzaid/lensift/android/review/ReviewViewModelTest.kt`
- Create: `androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/review/DuplicateReviewScreenTest.kt`

- [ ] **Step 1: Write failing safe-selection tests**

Assert exact groups preselect only redundant non-favorites, near groups preselect none, users can change every selection, keeper reasons map to plain language, selection survives rotation, and removed/inaccessible assets disappear.

- [ ] **Step 2: Run focused tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*ReviewViewModelTest' :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.abuzaid.lensift.android.review.DuplicateReviewScreenTest`

Expected: FAIL before the review feature exists.

- [ ] **Step 3: Implement native review interaction**

Show a large recommended keeper, alternate image cards, zoom/full-screen preview, estimated recoverable bytes, and reason chips such as Favorite, Edited, Sharper, and Higher resolution. Label exact and similar groups distinctly. Use checkboxes with full accessible labels; do not encode selection only by tint.

- [ ] **Step 4: Run tests**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:connectedDebugAndroidTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/me/abuzaid/lensift/android/review androidApp/src/test/kotlin/me/abuzaid/lensift/android/review androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/review
git commit -m "feat: review Android duplicate findings"
```

## Task 7: Build blurry-photo review

**Files:**

- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/review/BlurReviewScreen.kt`
- Create: `androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/review/BlurReviewScreenTest.kt`

- [ ] **Step 1: Write a failing uncertainty-first UI test**

Assert the screen says **Possibly blurry**, starts unselected, provides zoom, explains low detail/edge evidence in plain language, and exposes explicit **Keep** and **Select for removal** actions.

- [ ] **Step 2: Run the screen test**

Run: `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.abuzaid.lensift.android.review.BlurReviewScreenTest`

Expected: FAIL before the screen exists.

- [ ] **Step 3: Implement the screen**

Use a large real preview and a collapsible “Why was this flagged?” card. Never show a fake AI confidence percentage or call the photo bad. Respect reduced animation settings.

- [ ] **Step 4: Verify**

Run: `./gradlew :androidApp:connectedDebugAndroidTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/me/abuzaid/lensift/android/review/BlurReviewScreen.kt androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/review/BlurReviewScreenTest.kt
git commit -m "feat: review Android blur findings"
```

## Task 8: Add in-app confirmation and MediaStore deletion

**Files:**

- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/deletion/DeletionGateway.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/deletion/AndroidDeletionController.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/deletion/DeletionViewModel.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/deletion/ConfirmDeletionScreen.kt`
- Create: `androidApp/src/test/kotlin/me/abuzaid/lensift/android/deletion/DeletionViewModelTest.kt`
- Create: `androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/deletion/ConfirmDeletionScreenTest.kt`

- [ ] **Step 1: Write failing explicit-intent tests**

Assert no native request is launched on selection, the in-app summary contains item/category counts and estimated bytes, only the continue action launches once, cancellation changes nothing, and an approved result is re-enumerated before recording aggregate history.

- [ ] **Step 2: Run focused tests**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*DeletionViewModelTest' :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.abuzaid.lensift.android.deletion.ConfirmDeletionScreenTest`

Expected: FAIL before deletion flow exists.

- [ ] **Step 3: Implement the native request and provisional result handling**

Resolve opaque IDs to content URIs inside the Android controller, call `MediaStore.createDeleteRequest`, launch its intent sender, and return approved/cancelled. After approval, query each affected ID again; count only missing assets as confirmed, invalidate them, purge selections, and persist aggregate history without IDs. Explain that Android deletion is permanent once system-approved.

- [ ] **Step 4: Verify approved, declined, and partial outcomes**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:connectedDebugAndroidTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add androidApp/src/main/kotlin/me/abuzaid/lensift/android/deletion androidApp/src/test/kotlin/me/abuzaid/lensift/android/deletion androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/deletion
git commit -m "feat: confirm Android deletions through MediaStore"
```

## Task 9: Complete history, settings, and Android accessibility

**Files:**

- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/history/HistoryScreen.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/settings/SettingsScreen.kt`
- Create: `androidApp/src/main/kotlin/me/abuzaid/lensift/android/settings/SettingsViewModel.kt`
- Create: `androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/AccessibilityJourneyTest.kt`
- Create: `androidApp/src/androidTest/kotlin/me/abuzaid/lensift/android/EndToEndFakeJourneyTest.kt`

- [ ] **Step 1: Write failing end-to-end and accessibility tests**

Drive onboarding → scan → exact review → in-app confirmation → declined native deletion, then approved deletion. Assert history is aggregate-only, sensitivity relusters without re-decoding, 48 dp targets, meaningful TalkBack order, 200% font scale, dark/light mode, and reduced motion.

- [ ] **Step 2: Run the journey**

Run: `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.abuzaid.lensift.android.EndToEndFakeJourneyTest`

Expected: FAIL before final destinations are wired.

- [ ] **Step 3: Implement remaining destinations and state restoration**

History shows timestamp, category counts, and confirmed estimated bytes only. Settings provides Conservative/Balanced/Broad sensitivity, theme choice, access management, privacy explanation, and app/version information. Persist non-sensitive preferences with DataStore; keep selected asset IDs in saved UI state only until completion/cancellation.

- [ ] **Step 4: Run Android quality gates**

Run: `./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:connectedDebugAndroidTest :androidApp:assembleDebug`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add androidApp
git commit -m "feat: complete accessible Android Lensift journey"
```

## Plan acceptance

- [ ] Android 11, 13, and 14+ behavior is covered by tests or a documented manual device check.
- [ ] Full, selected-only, denied, cancelled deletion, approved deletion, and partial deletion outcomes work.
- [ ] Near and blurry screens never preselect deletion; favorites are never preselected anywhere.
- [ ] `apkanalyzer manifest permissions androidApp/build/outputs/apk/debug/androidApp-debug.apk` shows no Internet permission.
- [ ] `./gradlew :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:connectedDebugAndroidTest :androidApp:assembleDebug` passes.
- [ ] `git diff --check` passes and all changes remain local.
