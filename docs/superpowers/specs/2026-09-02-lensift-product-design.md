# Lensift Product and Architecture Design

**Date:** 2026-09-02

**Status:** Approved

**Implementation roadmap:** `docs/superpowers/plans/2026-09-02-lensift-implementation-roadmap.md`

**Delivery target:** Portfolio-ready release in 6–8 weeks

## 1. Purpose

Lensift is a privacy-first Android and iOS application that helps people reclaim photo-library storage by finding:

- exact duplicate images;
- near-duplicate images; and
- images that are probably blurred or out of focus.

Lensift analyzes authorized, locally available still images entirely on the device. It explains what it found, recommends which image to keep when the available evidence supports a recommendation, and requires the user to review and confirm every deletion through the operating system.

The project is also a flagship portfolio artifact for Senior/Lead Kotlin Multiplatform and Mobile Platform roles. Its value is not the number of features. Its value is a visible, well-defended platform architecture, native user experiences, measurable image-analysis behavior, safe destructive operations, performance evidence, and honest documentation of trade-offs.

## 2. Product Positioning

**Name:** Lensift

**Descriptor:** Private, on-device photo cleanup.

**Product promise:** Lensift suggests. You always decide.

Lensift must feel calm and trustworthy rather than aggressive or gamified. It must not claim that every recommendation is correct, describe classical image analysis as artificial intelligence, or imply that estimated storage has already been reclaimed.

## 3. Success Criteria

### User success

1. A new user can understand why photo-library access is needed before the system permission prompt appears.
2. A user with full or limited access can scan all currently accessible, locally available still images.
3. Exact duplicates, near-duplicates, and possibly blurry photos are clearly separated because they carry different confidence and deletion risks.
4. Every recommendation includes understandable evidence such as “identical file,” “visually similar,” “sharper,” “higher resolution,” or “favorite.”
5. No image is deleted without an in-app review action followed by the platform-native confirmation flow.
6. Interrupted scans resume without starting from zero, and unchanged assets are not decoded again.

### Engineering and portfolio success

1. The repository README explains the product, architecture, privacy boundary, key trade-offs, and measured results in under three minutes of reading.
2. A 60–90 second recorded demo shows both native applications, a scan, explainable findings, review, and native deletion confirmation without requiring a recruiter to install the apps.
3. CI builds and tests the shared module and both platform applications.
4. A labeled test corpus reports precision and recall separately for exact duplicates, near-duplicates, and blur candidates.
5. A 10,000-image benchmark completes on at least one documented representative device per platform without crashing and with peak process memory below 200 MB.
6. A warm rescan of an unchanged 10,000-image library performs no pixel decodes and completes within five seconds on the documented benchmark devices.
7. Cooperative cancellation is observed within 500 ms after the current platform decode or hash operation returns.
8. Architecture decision records and one documented failed or rejected approach show how decisions were made, not merely the final code.

## 4. Release Scope

### Included

- Android 11 (API 30) and later; iOS 16 and later. The implementation pins and documents exact compile/target toolchain versions.
- Android application using Jetpack Compose.
- iOS application using SwiftUI.
- Kotlin Multiplatform shared domain, orchestration, analysis, and persistence logic.
- Android photo access through MediaStore, including full and selected-photo access states.
- iOS photo access through PhotoKit, including full and limited-library access states.
- Exact content fingerprinting.
- Perceptual fingerprinting for near-duplicate candidates.
- Classical blur scoring for possibly blurred or out-of-focus images.
- Explainable grouping and keeper recommendations.
- Incremental, resumable scanning with progressive results.
- Conservative, review-first deletion flow using native operating-system confirmation.
- Adjustable Conservative, Balanced, and Broad sensitivity presets.
- Dark-first visual identity with a complete accessible light theme.
- Dynamic type/font scaling, VoiceOver/TalkBack support, sufficient contrast, non-color status cues, reduced-motion support, and native minimum touch targets.
- Synthetic and consented benchmark/test corpus that contains no private gallery content.
- CI, architecture decision records, benchmark report, demo media, and technical README.
- A public Apache-2.0 GitHub repository and tagged source release. App Store and Play Store publication are not required for the portfolio release.

### Explicitly excluded from the first release

- Videos and Live Photo video components.
- Screenshot or document classification.
- Faces, people grouping, sensitive-photo detection, or private vaults.
- Aesthetic or composition scoring.
- Custom machine-learning models.
- Cloud accounts, synchronization, uploads, backend services, or web hosting.
- Analytics, advertising, user accounts, subscriptions, or payment flows.
- Compression or modification of retained photos.
- Automatic deletion.
- Long-running background scanning. The app checkpoints when it leaves the foreground and resumes when active.

## 5. Product Principles

1. **Review before removal.** Deletion is a workflow, not a side effect of scanning.
2. **Evidence over authority.** The app states why items were grouped or recommended and distinguishes certainty from suspicion.
3. **Private by construction.** Runtime builds contain no networking feature and Android does not request the `INTERNET` permission.
4. **Shared decisions, native experiences.** The rules that must agree are Kotlin; the interfaces and system integrations remain native.
5. **Progressive and resumable.** Large libraries produce useful progress and partial results without holding the library in memory.
6. **Metrics must be honest.** The circular dashboard indicator represents scan or review completion, never an invented “library health” score.

## 6. Core User Journey

### 6.1 Onboarding and authorization

1. Lensift explains that analysis occurs on the device, which images it needs, and that deletion always requires confirmation.
2. The user taps **Choose photo access**; only then does Lensift request the native read/write level needed for library review and deletion.
3. The app derives the current access state at runtime rather than persisting permission state.
4. Full access begins a whole-accessible-library scan. Partial or limited access begins a scan of the accessible subset and keeps a persistent **Manage access** affordance.
5. A denied or restricted state remains useful: it explains the limitation and offers the appropriate Settings action without showing an empty-library success state.

Android 14 and later may provide access only to user-selected visual media, so Lensift must distinguish full, partial, and denied access and allow reselection. iOS similarly exposes authorized, limited, denied, and restricted PhotoKit states. The platform adapters own these differences.

### 6.2 Scan

1. The platform enumerates accessible still-image descriptors without loading full image objects into shared code.
2. The shared coordinator compares each descriptor’s source signature with the local index and schedules only new or changed assets.
3. Platform workers decode bounded, normalized luminance frames and stream original bytes only when an exact fingerprint is required.
4. Shared analyzers calculate fingerprints and blur evidence, persist records, and progressively produce stable finding groups.
5. The dashboard ring reports scan progress while work remains.
6. When the scan completes, the same ring transitions to review progress. The state label and denominator change explicitly, so the meaning is never inferred from color alone.

### 6.3 Review

The dashboard uses the approved **Review First** hierarchy:

1. Circular workflow-progress and recoverable-space card.
2. Duplicate and blurry finding cards with real thumbnails and counts.
3. One **Up next** card that resumes the next unreviewed group.
4. Bottom navigation for Home, Review, and History.

Exact-duplicate groups begin with all redundant copies selected for removal except the recommended keeper. Favorite assets are never preselected for removal. The user can change every selection.

Near-duplicate and blurry findings begin with no removals selected. Lensift may highlight a recommended keeper or explain why an image was flagged, but the user must actively select anything to remove.

The duplicate-review screen presents the recommended keeper, alternate images, recoverable bytes, and reason chips. The blur-review screen provides a large preview, zoom, the measured evidence in plain language, and explicit Keep or Select for removal actions.

### 6.4 Confirmation and deletion

1. Lensift presents a final in-app summary containing item count, estimated bytes, and affected finding types.
2. The user taps **Continue to system confirmation**.
3. The native adapter requests deletion using MediaStore on Android or PhotoKit on iOS.
4. The operating system presents its confirmation UI.
5. Lensift treats the native completion result as provisional and re-enumerates the affected asset identifiers before reporting success.
6. Completed asset-specific selections are purged. History retains only timestamp, category counts, and the confirmed estimated bytes—never thumbnails, filenames, hashes, or deleted identifiers.

Android deletion is permanent when approved through `MediaStore.createDeleteRequest`. PhotoKit controls iOS deletion and may retain items in Recently Deleted according to system behavior. Lensift must describe storage as an estimate and must not promise immediate iOS reclamation.

## 7. Visual Identity

### 7.1 Logo

The approved logo direction is **Focus Pair**:

- two horizontally overlapping circular lens outlines;
- the left circle uses a subdued green and the right uses the primary mint;
- the overlap forms a crisp, light focal shape with a dark central point;
- the geometry represents near-duplicate comparison and selection of the clearer keeper;
- the mark must remain recognizable at 24 px and inside both iOS and adaptive Android app-icon masks.

The wordmark uses **Lensift** as a single unbroken word in a clean, high-weight sans-serif. The mark must not include a broom, trash can, sparkle, shield, or generic camera outline.

### 7.2 UI direction

- Card-based, dark-first dashboard using deep ink green surfaces and a mint action color.
- Light mode uses the same semantic tokens rather than direct color inversion.
- Real photo thumbnails provide evidence; decorative AI illustrations do not.
- One dominant action per screen.
- Large rounded cards, clear type hierarchy, restrained animation, and generous spacing.
- Destructive actions use native semantics and explicit labels; green is never used for deletion.
- Progress states include text, numerator/denominator, and accessibility values in addition to the circular graphic.

Android and iOS share information architecture, content, tokens, and state semantics. Each platform implements navigation, controls, sheets, permissions, typography behavior, and deletion confirmation with its native conventions.

## 8. Architecture

### 8.1 Repository shape

```text
lensift/
├── shared/                 # Kotlin Multiplatform library
│   └── src/
│       ├── commonMain/     # domain, analysis, orchestration, persistence API
│       ├── commonTest/     # deterministic shared tests
│       ├── androidMain/    # MediaStore/ImageDecoder source adapter + DB driver
│       └── iosMain/        # PhotoKit/ImageIO source adapter + DB driver
├── androidApp/             # Permission/deletion hosts, ViewModels, Compose UI
├── iosApp/                 # Permission/deletion hosts, state holders, SwiftUI UI
├── benchmark/              # corpus tooling, labels, result schemas, reports
├── docs/
│   ├── adr/
│   ├── benchmarks/
│   └── superpowers/specs/
└── .github/workflows/
```

One shared Gradle module is sufficient for the first release. Its internal packages enforce boundaries without adding build complexity solely for appearances.

### 8.2 Shared core responsibilities

- Domain models and reason codes.
- Scan coordinator and explicit scan state machine.
- Candidate bucketing and work scheduling.
- Exact content fingerprint comparison.
- Perceptual fingerprint generation and comparison.
- Blur evidence calculation.
- Near-duplicate clustering.
- Keeper recommendation and explainability policy.
- Sensitivity policy.
- Review-session rules and aggregate history.
- Incremental scan index and migrations through SQLDelight.
- Deterministic clocks, dispatchers, and configuration injected for testing.

SQLDelight is selected because it generates type-safe Kotlin APIs, verifies schema and migrations at compile time, and supports Android and Kotlin/Native SQLite targets.

### 8.3 Platform responsibilities

**Android**

- `shared/androidMain`: MediaStore enumeration, stable content URI mapping, bounded original-byte streaming, luminance decoding, library-change observation, and the SQLDelight Android driver.
- `androidApp`: runtime permission presentation, including Android 14 selected-photo access; `MediaStore.createDeleteRequest` activity-result handling; Android ViewModels; navigation; and Jetpack Compose UI.

**iOS**

- `shared/iosMain`: `PHAsset` enumeration, local-availability checks, bounded original-data streaming, ImageIO luminance decoding, `PHPhotoLibraryChangeObserver` integration, and the SQLDelight native driver.
- `iosApp`: PhotoKit authorization presentation and limited-library management; `PHPhotoLibrary.performChanges` with `PHAssetChangeRequest.deleteAssets`; `@MainActor` observable state holders; navigation; and SwiftUI UI.

JetBrains recommends separating shared logic from UI when one client is written in native Swift. Lensift follows that model: platform state holders adapt shared use cases to native UI state instead of sharing Compose UI or forcing Kotlin platform objects into SwiftUI.

### 8.4 Boundary contracts

The shared module receives values, streams, and byte buffers—not `Bitmap`, `UIImage`, `PHAsset`, `Uri`, `Context`, or view-controller references.

```text
PhotoLibraryGateway
  currentAccess(): AccessState
  enumerateAccessibleImages(): Flow<PhotoDescriptor>
  suspend decodeLuma(assetId, targetLongestEdge): LumaFrame
  originalByteChunks(assetId): Flow<ByteArray>
  observeChanges(): Flow<LibraryChange>

DeletionGateway
  requestDeletion(assetIds, completion)

ScanIndex
  changedSince(descriptors, analyzerVersion): List<PhotoDescriptor>
  saveAnalysis(records)
  loadCurrentFindings()
  invalidate(assetIds)
```

`PhotoLibraryGateway` is implemented in the shared module’s Android and iOS source sets so platform image objects are converted to common values before crossing into `commonMain`. `DeletionGateway` is implemented by each native application because it must host operating-system confirmation UI. `ScanIndex` is implemented in shared code over platform SQLDelight drivers. The deletion gateway is never called by an analyzer; only an explicit native UI command can initiate it.

### 8.5 State ownership

The shared scan coordinator owns domain states:

```text
Idle → Indexing → Analyzing → Grouping → Ready
                     ↘ Pausing → Paused → Analyzing
Any active state → RecoverableFailure or Cancelled
```

Android ViewModels collect shared state and map it to immutable Compose screen models. Swift `@MainActor` observable state holders use a small exported observer interface to receive snapshots and invoke shared suspend use cases. This avoids adding a third-party Flow-to-Swift bridge to the first release.

## 9. Analysis Pipeline

### 9.1 Source signatures and indexing

Each asset has an opaque platform identifier plus a source signature derived from modification time, pixel dimensions, orientation, and an optional byte count. Byte count is nullable because the public platform metadata is not equivalent across Android and iOS. A record is reusable only when both the source signature and `analyzerVersion` match.

Permission changes trigger a fresh enumeration. Assets no longer accessible are hidden and their cached records are purged. The permission value itself is never persisted as truth.

### 9.2 Candidate bucketing

Lensift avoids all-pairs comparison:

- Exact candidates are first narrowed by normalized dimensions and identical perceptual fingerprints, then by byte count where the platform supplies it, and finally verified by streamed SHA-256 content fingerprints. Lensift does not read every original file merely to discover exact candidates.
- Near-duplicate candidates are generated through multiple perceptual-hash bands plus capture-time and aspect-ratio neighborhoods. Multiple bands avoid missing similar hashes that happen to fall on one prefix boundary.
- Blur analysis is per asset and therefore requires no pairwise comparison.

Candidate windows and sensitivity thresholds live in a versioned policy object. Changing sensitivity reclusters stored fingerprints without re-decoding unchanged images.

### 9.3 Normalized image representation

Platform decoders apply orientation, scale the longest edge to 512 pixels without upscaling, and return an 8-bit grayscale `LumaFrame`. Work is bounded to at most two concurrent decodes by default; the policy can reduce this under memory pressure.

The shared core processes one frame at a time and releases it after fingerprints and blur evidence are stored. It never caches full-resolution pixels.

### 9.4 Exact duplicates

SHA-256 equality over original bytes is the only basis for an **Exact duplicate** label. Matching filenames, dates, dimensions, or thumbnails alone never produce that label.

### 9.5 Near-duplicates

Near-duplicate evidence uses a DCT-based 64-bit perceptual hash over the normalized luminance frame. Hamming distance supplies an explainable similarity distance. Metadata bucketing constrains comparisons, and complete-linkage clustering prevents a chain of individually similar images from merging unrelated endpoints into one group.

The Balanced threshold is selected from the labeled development split to meet the release precision gate. The untouched test split is evaluated once for the published report. Conservative tightens the distance and metadata tolerances; Broad widens them. The chosen numeric values and corpus results are committed in the benchmark report rather than hidden in code.

### 9.6 Blur evidence

Blur evidence combines variance of the Laplacian with edge density on the normalized luminance frame. Fixed normalization makes scores comparable across device resolutions. Low-texture scenes can legitimately receive low scores, so the product label is always **Possibly blurry**, never **Bad photo**, and the category never preselects deletion.

Balanced sensitivity is tuned on a labeled corpus containing sharp/blurred pairs plus low-texture negatives such as sky, walls, fog, and intentional depth-of-field. Conservative favors precision; Broad favors recall.

### 9.7 Keeper recommendation

Keeper ranking is deterministic and emits reason codes. In priority order it favors:

1. an asset marked as favorite;
2. an edited version when edit metadata is reliably available;
3. stronger sharpness evidence;
4. greater pixel area;
5. stable platform identifier as a final tie-breaker.

File size is displayed for storage impact but is not treated as a universal quality signal. Exact byte-identical copies use favorite state and then stable identifier because their image content is equal.

## 10. Persistence and Privacy

The local SQLite index stores:

- opaque asset identifier and source signature;
- analyzer version;
- dimensions, byte count when available, favorite/edit flags, and capture time;
- exact and perceptual fingerprints;
- blur metrics;
- group membership, evidence, review state, and aggregate history.

It does not store thumbnails, full image bytes, filenames, album names, locations, face data, or cloud identifiers. Diagnostic logs use counts, stage names, durations, and anonymous error categories only.

Uninstalling the app removes the index. A **Reset Lensift data** action deletes the index without modifying the photo library. No runtime data leaves the device.

## 11. Failure Handling

- **Access denied or restricted:** show an explanatory state and Settings action; do not present zero findings as success.
- **Partial or limited access:** scan the accessible subset, label its size, and expose **Manage access**.
- **Cloud-only or unavailable asset:** skip it with a retryable reason. Network access is not silently enabled and the scan continues.
- **Corrupt or unsupported image:** record a non-sensitive error code, exclude it from findings, and continue.
- **Library changes during scan:** finish the current atomic item, invalidate changed identifiers, and schedule a reconciliation pass.
- **App backgrounding:** checkpoint completed items, stop scheduling new decodes, release pixel buffers, and resume when active.
- **User cancellation:** stop scheduling, preserve completed records, and publish a Paused/Cancelled snapshot.
- **Database migration or corruption:** back up no image data, recreate the derived index, and explain that a rescan is required.
- **Native deletion declined:** preserve review selections and return to the confirmation screen.
- **Partial or failed deletion:** re-enumerate requested identifiers, report confirmed outcomes, and keep unresolved selections available for retry.
- **Memory pressure:** reduce decode concurrency to one, release the current frame after its atomic analysis, and pause if the platform signals continued pressure.

One failed asset must never fail the whole library scan.

## 12. Testing and Measurement

### 12.1 Shared-core tests

- Exact fingerprint equality and inequality fixtures.
- Perceptual-hash invariance across controlled resize, recompression, brightness, and small-crop transformations.
- Non-match fixtures with similar colors but different content.
- Blur score fixtures covering motion blur, defocus blur, sharp detail, low texture, and intentional shallow depth of field.
- Complete-linkage anti-chaining cases.
- Keeper ranking and explanation precedence.
- Sensitivity-policy boundary tests.
- Scan state-machine, cancellation, resume, invalidation, and idempotence tests.
- SQLDelight schema and migration tests.
- Property tests for deterministic ordering and group stability.

### 12.2 Platform tests

- Fake and contract-test implementations of `PhotoLibraryGateway` and `DeletionGateway` on both platforms.
- Android tests for full/partial/denied access, MediaStore change reconciliation, and approved/declined/partial deletion results.
- iOS tests for full/limited/denied access, PhotoKit changes, unavailable assets, and approved/declined/partial deletion results.
- Compose and SwiftUI tests for onboarding, progress semantics, finding review, empty/error states, font scaling, and accessibility labels.
- Visual regression snapshots for the approved Review First dashboard in light and dark themes.

### 12.3 Corpus and quality gates

The committed corpus tooling derives labeled variants from owned or redistribution-compatible source images. Generated transformations include exact copies, metadata-only copies, resize, recompression, minor crop, exposure changes, Gaussian/defocus blur, and motion blur. A separate negative set contains visually unrelated images and difficult low-texture scenes.

Release gates on the untouched test split:

- exact duplicate precision and recall: 100%;
- near-duplicate precision: at least 90%;
- near-duplicate recall: at least 85%;
- possibly-blurry precision: at least 85%;
- possibly-blurry recall: reported, with a target of at least 75%;
- no favorite asset preselected for removal;
- no near-duplicate or blur candidate preselected for removal.

The report includes corpus composition and confidence intervals so headline rates are not presented without context.

### 12.4 Performance report

The 10,000-image benchmark records:

- device and OS version;
- cold-scan wall time and images per second;
- time to first stable finding;
- peak process memory;
- database size;
- warm unchanged-rescan time and pixel-decode count;
- one-percent-change rescan time;
- pause and resume behavior; and
- battery/thermal observations available from platform tools.

Results are reported rather than generalized beyond the measured devices.

## 13. Documentation Deliverables

- README with a concise product story, animated/recorded demo, screenshots, architecture diagram, privacy statement, benchmark summary, and build instructions.
- ADR 001: Share domain and analysis logic; keep native UIs.
- ADR 002: On-device classical analysis instead of a backend or custom ML model.
- ADR 003: SQLDelight incremental index.
- ADR 004: Review and native-confirmation deletion policy.
- ADR 005: Perceptual-hash and blur methodology, including known failure modes.
- Full benchmark and labeled-corpus methodology.
- A short “What failed” engineering note based on a real rejected experiment, with evidence and the resulting decision.
- Architecture and data-flow diagrams that match the implemented code.
- Reproducible commands for tests and benchmark generation.

## 14. Delivery Sequence

### Week 1 — Foundation and evidence harness

- Project structure, shared contracts, CI baseline, synthetic corpus tooling, and first ADRs.

### Week 2 — Native library boundaries

- MediaStore and PhotoKit authorization, enumeration, local decode, change observation, and fake adapters.

### Week 3 — Duplicate analysis

- Exact fingerprinting, perceptual fingerprinting, candidate bucketing, clustering, evaluation fixtures, and initial report.

### Week 4 — Blur and persistence

- Blur evidence, sensitivity policies, SQLDelight index, invalidation, cancellation, and resumable scanning.

### Week 5 — Android product flow

- Compose onboarding, dashboard, duplicate/blur review, confirmation, and deletion reconciliation.

### Week 6 — iOS product flow

- SwiftUI parity, limited-library management, PhotoKit deletion, and native state adaptation.

### Week 7 — Reliability and performance

- Large-library benchmark, memory tuning, permission/deletion matrix, accessibility, and visual regression coverage.

### Week 8 — Portfolio release

- README, diagrams, demo recording, benchmark publication, failure note, final CI stabilization, and tagged release.

Weeks 7 and 8 form the buffer if core integration takes longer; optional polish is removed before safety, tests, or evidence.

## 15. Key Risks and Controls

| Risk | Control |
|---|---|
| Perceptual-hash false positives | Metadata bucketing, complete-linkage grouping, precision gate, no preselection |
| Blur false positives on low-texture or artistic images | Two evidence measures, difficult negatives, “Possibly blurry” wording, no preselection |
| Very large libraries exceed memory | Fixed-size luma frames, two-decoder limit, streaming hashes, immediate buffer release |
| Platform permission behavior diverges | Explicit access-state contract and native adapters/tests |
| iCloud-only assets stall scanning | Local-only requests, retryable skip state, no silent network work |
| User assumes estimated bytes are already free | “Ready to reclaim” wording and post-deletion reconciliation |
| Shared code leaks platform concepts | Value-only gateway contracts and architecture tests/reviews |
| Native UIs drift semantically | Shared domain states, reason codes, UI acceptance matrix, paired screenshots |
| Eight-week scope expands | Enforce exclusions and cut optional animation/polish before core evidence |

## 16. Authoritative Platform References

- [Kotlin Multiplatform recommended project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [Kotlin Multiplatform: native UIs with shared logic](https://kotlinlang.org/docs/multiplatform/build-ios-android-app.html)
- [SQLDelight supported platforms](https://sqldelight.github.io/sqldelight/)
- [Android MediaStore and native deletion requests](https://developer.android.com/reference/android/provider/MediaStore)
- [Android selected-photo access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
- [Apple PhotoKit privacy and limited-library access](https://developer.apple.com/documentation/photokit/delivering-an-enhanced-privacy-experience-in-your-photos-app)
- [Apple PhotoKit change requests](https://developer.apple.com/documentation/photokit/requesting-changes-to-the-photo-library)
