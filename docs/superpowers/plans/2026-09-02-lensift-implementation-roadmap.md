# Lensift Implementation Roadmap

> **For agentic workers:** Execute these plans in order. Each plan is independently reviewable and ends in a usable, tested increment.

**Goal:** Deliver Lensift as a portfolio-ready Kotlin Multiplatform photo-cleanup application in five bounded implementation increments.

**Spec:** `docs/superpowers/specs/2026-09-02-lensift-product-design.md`

## Plan order

1. [Shared analysis foundation](2026-09-02-lensift-01-shared-analysis-foundation.md) — bootstrap the monorepo and prove the deterministic analysis engine.
2. [Native scan and persistence](2026-09-02-lensift-02-native-scan-persistence.md) — connect PhotoKit/MediaStore, cache analysis, resume scans, and stream findings.
3. [Android native product](2026-09-02-lensift-03-android-native-product.md) — ship the complete Jetpack Compose experience and Android deletion safety flow.
4. [iOS native product](2026-09-02-lensift-04-ios-native-product.md) — ship the equivalent SwiftUI experience and PhotoKit deletion safety flow.
5. [Hardening and portfolio release](2026-09-02-lensift-05-hardening-portfolio-release.md) — measure quality/performance, complete accessibility and documentation, and prepare the public release.

## Compatibility-first toolchain lock

Pin these versions in the first plan and change them only in a dedicated dependency commit with the full build matrix green:

- Kotlin and Kotlin Multiplatform plugin `2.4.10`.
- Kotlin Compose compiler plugin `2.4.10` for the Android UI module.
- Android Gradle Plugin `9.1.0` with `com.android.kotlin.multiplatform.library` for the shared module.
- Gradle `9.3.1`, JDK `17`, Android compile/target SDK `36`, minimum SDK `30`.
- Jetpack Compose BOM `2026.06.00` and Activity Compose `1.13.0`.
- SQLDelight `2.3.2` and kotlinx-coroutines `1.11.0`.
- Xcode `26.4`, Swift `6`, and iOS deployment target `16.0`.

This deliberately avoids the August 2026 Compose/AGP edge: Kotlin 2.4.10 officially supports AGP only through 9.1.0, while the August Compose release requires a newer AGP. Upgrade after the Kotlin compatibility table catches up and the matrix passes.

Primary compatibility references: [Kotlin Multiplatform compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html), [AGP 9 KMP migration](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html), [Compose compiler plugin](https://kotlinlang.org/docs/compose-compiler-migration-guide.html), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), and [SQLDelight 2.3.2](https://github.com/sqldelight/sqldelight/releases/tag/2.3.2).

## Suggested calendar

- Weeks 1–2: Plan 01.
- Weeks 2–4: Plan 02, overlapping only after shared contracts stabilize.
- Week 5: Plan 03.
- Week 6: Plan 04.
- Weeks 7–8: Plan 05.

## Cross-plan acceptance gates

- `./gradlew check` stays green after every Gradle-side task.
- `xcodebuild test` stays green after the iOS project exists.
- No image bytes, hashes, thumbnails, or analytics leave the device.
- Android declares no `INTERNET` permission; iOS contains no networking framework usage.
- Only the platform adapters touch MediaStore, PhotoKit, ImageDecoder, ImageIO, or native deletion APIs.
- Shared code never owns a native permission prompt, deletion prompt, or UI component.
- Exact duplicates may preselect all but the keeper; favorite assets are never preselected. Near-duplicate and blur reviews start with no removal selected.
- A plan is not complete until its tests, diff checks, and plan-specific acceptance commands pass.

## Planned commit boundary

Each task below ends in one focused local commit. Do not push, tag, publish, or create a GitHub release unless the user separately approves that external action.
