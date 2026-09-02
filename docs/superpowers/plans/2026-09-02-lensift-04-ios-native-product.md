# Lensift iOS Native Product Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the complete iOS 16+ Lensift experience in native SwiftUI, matching Android’s product semantics while respecting PhotoKit, Swift concurrency, accessibility, and iOS interaction conventions.

**Architecture:** An Xcode application target integrates the shared KMP framework directly. A small exported Kotlin observer façade turns shared flows into snapshot callbacks; `@MainActor` Swift state holders own screen state and cancel observation explicitly. SwiftUI owns presentation/navigation. iOS-only controllers own authorization prompts, limited-library management, thumbnails, and `PHPhotoLibrary.performChanges` deletion.

**Tech Stack:** Xcode 26.4, Swift 6, iOS 16 deployment target, SwiftUI, Combine `ObservableObject`, PhotoKit, XCTest, KMP direct framework integration.

**Spec:** `docs/superpowers/specs/2026-09-02-lensift-product-design.md`

## Global Constraints

- Complete Plans 01 and 02 first.
- Keep all Swift-observable mutation on `@MainActor`.
- Query PhotoKit authorization at use time; never persist it as truth.
- Configure PhotoKit image/data requests with network access disabled for v1.
- Never add URLSession, third-party analytics, telemetry, or networking entitlements.
- Exact groups may preselect redundant non-favorites. Near and blurry findings start with zero selected removals.
- Only an explicit tap on **Continue to system confirmation** may invoke `PHPhotoLibrary.performChanges`.
- Describe reclaimed bytes as confirmed estimates and explain that iOS may retain items in Recently Deleted.

---

## Task 1: Export a lifecycle-safe Swift observation façade

**Files:**

- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/client/LensiftClient.kt`
- Create: `shared/src/commonMain/kotlin/me/abuzaid/lensift/client/LensiftObserver.kt`
- Create: `shared/src/commonTest/kotlin/me/abuzaid/lensift/client/LensiftClientTest.kt`

- [ ] **Step 1: Write failing observation tests**

Assert a new observer immediately receives state/findings snapshots, receives later updates in order, cancellation is idempotent, and no callback occurs after cancellation.

- [ ] **Step 2: Run the focused test**

Run: `./gradlew :shared:allTests --tests '*LensiftClientTest'`

Expected: FAIL because the client façade does not exist.

- [ ] **Step 3: Implement an Objective-C-exportable interface**

```kotlin
interface LensiftObserver {
    fun onScanStateChanged(state: ScanState)
    fun onFindingsChanged(findings: FindingSnapshot)
}

interface ObservationToken { fun cancel() }

class LensiftClient(private val coordinator: ScanCoordinator) {
    fun observe(observer: LensiftObserver): ObservationToken
    fun start(policy: AnalysisPolicy)
    fun pause()
    fun resume()
    fun cancel()
}
```

Back `observe` with child jobs in a supervisor scope. The token cancels those jobs once. Keep callbacks as snapshots rather than exposing `Flow` or coroutines directly to Swift.

- [ ] **Step 4: Verify common and exported iOS APIs**

Run: `./gradlew :shared:allTests :shared:linkDebugFrameworkIosSimulatorArm64`

Expected: PASS and generated headers contain `LensiftClient`, `LensiftObserver`, and `ObservationToken`.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/me/abuzaid/lensift/client shared/src/commonTest/kotlin/me/abuzaid/lensift/client
git commit -m "feat: expose lifecycle-safe shared observation"
```

## Task 2: Create the iOS app and direct KMP integration

**Files:**

- Create: `iosApp/Lensift.xcodeproj/project.pbxproj`
- Create: `iosApp/Lensift.xcodeproj/xcshareddata/xcschemes/Lensift.xcscheme`
- Create: `iosApp/Lensift/Info.plist`
- Create: `iosApp/Lensift/LensiftApp.swift`
- Create: `iosApp/Lensift/AppContainer.swift`
- Create: `iosApp/Lensift/RootView.swift`
- Create: `iosApp/LensiftTests/InfoPlistPrivacyTests.swift`
- Modify: `.gitignore`

- [ ] **Step 1: Write a failing privacy/configuration test**

```swift
func testPhotoUsageCopyAndNetworkFreeConfiguration() throws {
    let info = try XCTUnwrap(Bundle.main.infoDictionary)
    XCTAssertNotNil(info["NSPhotoLibraryUsageDescription"])
    XCTAssertNil(info["NSAppTransportSecurity"])
}
```

- [ ] **Step 2: Run before the Xcode project exists**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -sdk iphonesimulator -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test`

Expected: FAIL because the project is absent.

- [ ] **Step 3: Add the native target and shared-framework build phase**

Set bundle ID `me.abuzaid.lensift`, deployment 16.0, Swift 6 strict concurrency, and shared scheme. Add a pre-compile script phase:

```bash
cd "$SRCROOT/.."
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

Set framework search paths from Gradle’s `BUILT_PRODUCTS_DIR` integration. `AppContainer` constructs the iOS photo gateway, native SQL driver, coordinator, and `LensiftClient`. Info.plist copy must say Lensift analyzes chosen photos on device and requests changes only after review.

- [ ] **Step 4: Build and test on simulator**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -sdk iphonesimulator -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add .gitignore iosApp
git commit -m "build: add native iOS application"
```

## Task 3: Implement the Focus Pair identity and adaptive SwiftUI theme

**Files:**

- Create: `iosApp/Lensift/Design/Theme.swift`
- Create: `iosApp/Lensift/Design/Typography.swift`
- Create: `iosApp/Lensift/Design/FocusPairMark.swift`
- Create: `iosApp/Lensift/Assets.xcassets/AccentColor.colorset/Contents.json`
- Create: `iosApp/Lensift/Assets.xcassets/AppIcon.appiconset/Contents.json`
- Create: `iosApp/LensiftTests/ThemeTests.swift`
- Create: `iosApp/LensiftUITests/ThemeSnapshotTests.swift`

- [ ] **Step 1: Write failing semantic-token tests**

Assert light/dark surfaces and foreground roles differ, destructive uses the error role rather than mint, and the Focus Pair accessibility label is “Lensift.” Capture 24 pt and app-icon-mask snapshots.

- [ ] **Step 2: Run tests**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test`

Expected: FAIL before theme assets exist.

- [ ] **Step 3: Implement native tokens and scalable geometry**

Match the approved semantic palette: deep ink-green surfaces, subdued green, primary mint, neutral text tiers, warning, and destructive. Implement a complete light scheme through asset appearances/environment. Draw two overlapping circle outlines and central focus point in SwiftUI `Canvas`; keep geometry legible at 24 pt and inside every app-icon mask.

- [ ] **Step 4: Verify dark/light and accessibility sizes**

Run the UI suite at default and accessibility-extra-extra-extra-large Dynamic Type.

Expected: PASS without clipped primary actions.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Lensift/Design iosApp/Lensift/Assets.xcassets iosApp/LensiftTests iosApp/LensiftUITests
git commit -m "feat: add iOS Lensift visual identity"
```

## Task 4: Build PhotoKit authorization and limited-library management

**Files:**

- Create: `iosApp/Lensift/Access/PhotoAccessController.swift`
- Create: `iosApp/Lensift/Access/LimitedLibraryPicker.swift`
- Create: `iosApp/Lensift/Onboarding/OnboardingModel.swift`
- Create: `iosApp/Lensift/Onboarding/OnboardingView.swift`
- Create: `iosApp/LensiftTests/OnboardingModelTests.swift`
- Create: `iosApp/LensiftUITests/OnboardingViewTests.swift`

- [ ] **Step 1: Write failing behavior tests**

Assert no system prompt occurs at launch, the explanation includes on-device analysis and review-first deletion, the button requests read/write authorization, limited access exposes **Manage access**, and denied/restricted states show useful settings guidance rather than an empty success state.

- [ ] **Step 2: Run model/UI tests**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test -only-testing:LensiftTests/OnboardingModelTests`

Expected: FAIL before implementation.

- [ ] **Step 3: Implement native authorization flows**

Use `PHPhotoLibrary.authorizationStatus(for: .readWrite)` and request only from the explicit button. Present `PHPhotoLibrary.shared().presentLimitedLibraryPicker(from:)` through a small UIKit bridge. Open app settings for denied access. Re-read authorization whenever the scene becomes active.

- [ ] **Step 4: Verify authorized/limited/denied/restricted fakes**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Lensift/Access iosApp/Lensift/Onboarding iosApp/LensiftTests iosApp/LensiftUITests
git commit -m "feat: add iOS photo access onboarding"
```

## Task 5: Adapt shared state and build the dashboard

**Files:**

- Create: `iosApp/Lensift/Shared/LensiftStore.swift`
- Create: `iosApp/Lensift/Navigation/AppRoute.swift`
- Create: `iosApp/Lensift/Home/DashboardState.swift`
- Create: `iosApp/Lensift/Home/DashboardView.swift`
- Create: `iosApp/Lensift/Components/WorkflowProgressCard.swift`
- Create: `iosApp/Lensift/Components/FindingCard.swift`
- Create: `iosApp/LensiftTests/LensiftStoreTests.swift`
- Create: `iosApp/LensiftUITests/DashboardViewTests.swift`

- [ ] **Step 1: Write failing lifecycle and progress tests**

Assert observation starts once, publishes on the main actor, cancels on teardown, and never mutates after cancellation. Verify the ring reads `Scanning 120 of 1000` during work and `Reviewed 3 of 12` when ready, plus limited-access banner, finding cards, estimate copy, **Up next**, and Home/Review/History tabs.

- [ ] **Step 2: Run tests**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test -only-testing:LensiftTests/LensiftStoreTests`

Expected: FAIL before store/dashboard exist.

- [ ] **Step 3: Implement `@MainActor` state adaptation and Review First UI**

`LensiftStore` implements the generated observer protocol, owns its observation token, and maps Kotlin reason/status values to Swift enums without relying on localized Kotlin strings. Build the card dashboard with `NavigationStack` and native tab navigation. During scan, the ring reflects scan work; after ready, explicitly switch label/denominator to review progress. Show “estimated recoverable,” never “already freed.”

- [ ] **Step 4: Verify UI and cancellation**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Lensift/Shared iosApp/Lensift/Navigation iosApp/Lensift/Home iosApp/Lensift/Components iosApp/LensiftTests iosApp/LensiftUITests
git commit -m "feat: build iOS review-first dashboard"
```

## Task 6: Load local-only PhotoKit thumbnails

**Files:**

- Create: `iosApp/Lensift/Photos/PhotoThumbnailLoader.swift`
- Create: `iosApp/Lensift/Components/AssetThumbnail.swift`
- Create: `iosApp/LensiftTests/PhotoThumbnailLoaderTests.swift`

- [ ] **Step 1: Write failing request/cancellation tests**

Assert opaque IDs resolve internally to `PHAsset`, request sizes honor display scale, `isNetworkAccessAllowed` is false, degraded results do not replace final results incorrectly, request cancellation propagates, and unavailable assets show an accessible fallback tile.

- [ ] **Step 2: Run the focused test**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test -only-testing:LensiftTests/PhotoThumbnailLoaderTests`

Expected: FAIL before loader exists.

- [ ] **Step 3: Implement with `PHCachingImageManager`**

Use opportunistic delivery for visible thumbnails, exact target sizes for previews, disabled network access, explicit request IDs for cancellation, and a byte-bounded `NSCache`. Stop caching items that leave the visible review window.

- [ ] **Step 4: Verify local-only loading**

Run the unit suite and inspect the request options assertion.

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Lensift/Photos iosApp/Lensift/Components/AssetThumbnail.swift iosApp/LensiftTests/PhotoThumbnailLoaderTests.swift
git commit -m "feat: load local-only iOS photo thumbnails"
```

## Task 7: Build duplicate and blurry review

**Files:**

- Create: `iosApp/Lensift/Review/ReviewStore.swift`
- Create: `iosApp/Lensift/Review/DuplicateReviewView.swift`
- Create: `iosApp/Lensift/Review/BlurReviewView.swift`
- Create: `iosApp/Lensift/Review/ReasonChip.swift`
- Create: `iosApp/Lensift/Review/ZoomablePhotoView.swift`
- Create: `iosApp/LensiftTests/ReviewStoreTests.swift`
- Create: `iosApp/LensiftUITests/ReviewJourneyTests.swift`

- [ ] **Step 1: Write failing safety and semantics tests**

Assert exact groups preselect only redundant non-favorites, near groups and blurry items preselect none, every choice is reversible, favorite/edited/sharper/resolution reasons are readable, disappeared assets are removed, and the blur label is **Possibly blurry** with no percentage or “bad photo” claim.

- [ ] **Step 2: Run tests**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test -only-testing:LensiftTests/ReviewStoreTests`

Expected: FAIL before review UI exists.

- [ ] **Step 3: Implement native review interactions**

Use SwiftUI selection controls with explicit VoiceOver values, a large recommended keeper, alternate cards, estimated bytes, structured reason chips, and native full-screen/zoom gestures. Blur review shows measured evidence in plain language and explicit **Keep** / **Select for removal** actions. Respect Reduce Motion.

- [ ] **Step 4: Run unit and UI journeys**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Lensift/Review iosApp/LensiftTests/ReviewStoreTests.swift iosApp/LensiftUITests/ReviewJourneyTests.swift
git commit -m "feat: review iOS cleanup findings"
```

## Task 8: Add in-app confirmation and PhotoKit deletion

**Files:**

- Create: `iosApp/Lensift/Deletion/DeletionGateway.swift`
- Create: `iosApp/Lensift/Deletion/PhotoKitDeletionController.swift`
- Create: `iosApp/Lensift/Deletion/DeletionStore.swift`
- Create: `iosApp/Lensift/Deletion/ConfirmDeletionView.swift`
- Create: `iosApp/LensiftTests/DeletionStoreTests.swift`
- Create: `iosApp/LensiftUITests/ConfirmDeletionViewTests.swift`

- [ ] **Step 1: Write failing explicit-intent and provisional-result tests**

Assert selection does not invoke PhotoKit, the in-app summary includes count/categories/estimated bytes, only **Continue to system confirmation** calls the gateway, cancellation changes nothing, and reported success is followed by asset re-enumeration before aggregate history is written.

- [ ] **Step 2: Run focused tests**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test -only-testing:LensiftTests/DeletionStoreTests`

Expected: FAIL before deletion flow exists.

- [ ] **Step 3: Implement PhotoKit deletion and reconciliation**

Resolve opaque IDs to `PHAsset` inside the controller and call `PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(...) }`. Treat completion as provisional, re-fetch affected local identifiers, count only missing assets as confirmed, invalidate them, purge selections, and persist aggregate history without identifiers. Copy must explain that iOS may keep photos in Recently Deleted.

- [ ] **Step 4: Verify approved, declined/error, and partial outcomes**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add iosApp/Lensift/Deletion iosApp/LensiftTests/DeletionStoreTests.swift iosApp/LensiftUITests/ConfirmDeletionViewTests.swift
git commit -m "feat: confirm iOS deletions through PhotoKit"
```

## Task 9: Complete history, settings, and iOS accessibility

**Files:**

- Create: `iosApp/Lensift/History/HistoryView.swift`
- Create: `iosApp/Lensift/Settings/SettingsStore.swift`
- Create: `iosApp/Lensift/Settings/SettingsView.swift`
- Create: `iosApp/LensiftUITests/AccessibilityJourneyTests.swift`
- Create: `iosApp/LensiftUITests/EndToEndFakeJourneyTests.swift`

- [ ] **Step 1: Write failing end-to-end/accessibility tests**

Drive onboarding → scan → review → declined confirmation → approved deletion. Assert aggregate-only history, sensitivity reclustering without decode, 44 pt targets, VoiceOver order/labels, accessibility Dynamic Type, dark/light mode, and Reduce Motion.

- [ ] **Step 2: Run the fake journey**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' test -only-testing:LensiftUITests/EndToEndFakeJourneyTests`

Expected: FAIL before remaining destinations exist.

- [ ] **Step 3: Implement remaining destinations and scene restoration**

History displays timestamp, category counts, and confirmed estimated bytes only. Settings provides sensitivity, appearance, limited-access management, privacy explanation, and version. Persist non-sensitive preferences with `UserDefaults`; keep asset selections only in current scene state and clear them after completion/cancellation.

- [ ] **Step 4: Run iOS quality gates**

Run: `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' clean test analyze`

Expected: PASS with no Swift concurrency warnings.

- [ ] **Step 5: Commit**

```bash
git add iosApp
git commit -m "feat: complete accessible iOS Lensift journey"
```

## Plan acceptance

- [ ] Authorized, limited, denied, restricted, unavailable-local-asset, approved deletion, declined deletion, and partial deletion outcomes are covered.
- [ ] The app never enables PhotoKit network access and contains no app networking layer.
- [ ] Near and blurry screens never preselect deletion; favorites are never preselected anywhere.
- [ ] `rg -n "URLSession|Network\.framework|Alamofire|Firebase|analytics" iosApp shared` returns no product-networking implementation.
- [ ] `xcodebuild -project iosApp/Lensift.xcodeproj -scheme Lensift -destination 'platform=iOS Simulator,OS=latest,name=iPhone 17 Pro' clean test analyze` passes.
- [ ] `git diff --check` passes and all changes remain local.
