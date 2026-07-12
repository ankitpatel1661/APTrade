# iOS Universal App — Design Spec (Workstream B)

**Date:** 2026-07-12
**Status:** Approved design — ready for implementation planning
**Owner workstream:** B (iOS) of the cross-platform catch-up program
**Related:** `docs/ios-port-readiness.md` (Phase 0 audit, 2026-06-29)

---

## Context

APTrade exists as four platform builds across two codebases sharing one repo:

| Platform | Stack | ~LOC | State |
|---|---|---|---|
| Windows (`desktopApp`) | Kotlin / Compose Desktop | 15,065 | Most complete — reference feature set |
| macOS (`Sources`, SwiftUI) | Swift / SwiftUI | 9,653 | Mature original |
| Android (`androidApp`) | Kotlin / Compose | 3,227 | Partial (~40%) — Workstream A |
| iOS (`APTradeiOS`) | Swift / SwiftUI | ~15 | Shell — **this spec** |

Decision (locked with user): **native per platform** — macOS/iOS stay Swift/SwiftUI, Windows/Android stay Kotlin/Compose. No stack consolidation.

The Swift Domain / Application / Infrastructure layers and all ViewModels are already shared between macOS and iOS. The `APTradeiOS` Xcode target already builds and renders the macOS `RootView()`. Therefore iOS parity is a **presentation-layer** effort, not a business-logic one.

## Goal

Ship a native iPhone experience at feature parity with macOS (watchlist, portfolio, detail, news, alerts, command palette, settings, localization), reusing the Swift core verbatim. **macOS must keep working unchanged throughout** — every change is either an `#if os(iOS)` shim or additive responsive layout.

## Scope

**Device target (locked):** iPhone only, portrait only, iOS 17.0 — matches current `project.yml` (`TARGETED_DEVICE_FAMILY: 1`, `UIInterfaceOrientationPortrait`).

**In scope:** presentation-layer shims + responsive iPhone layout + touch interactions.

**Out of scope:**
- iPad and landscape (explicitly deferred).
- Any change to Domain / Application / Infrastructure logic.
- Workstream A (Android → Windows parity) — separate spec.
- Workstream C (macOS gap-audit vs newest Windows features) — separate spec.

## Current state (measured on `main`, 2026-07-12)

**Correction to the 2026-06-29 audit:** the code has advanced far past that audit. Direct inspection of `main` shows iOS is ~85% complete, not a shell. The nav shell and per-screen narrow layouts are already implemented.

- **Xcode wrapper: done.** `project.yml` defines the `APTradeiOS` application target (iOS 17, iPhone-only, portrait) consuming the `APTradeApp` SwiftPM product; `AppIcon` asset present. iOS cannot run as a bare SwiftPM executable, so this xcodegen project is the run/build vehicle.
- **Navigation shell: DONE.** `RootView.swift:42-87` has a full `iosBody`: `TabView(selection:)` over Watchlist/Portfolio/News with `NavigationStack`s, account panel as a `.sheet`, command palette as a `.sheet`, asset detail as a `.sheet(item:)`, export as a `.confirmationDialog`. `WatchlistView`/`PortfolioView`/`NewsView` accept `onOpenSearch`/`onOpenAccount` closures on iOS.
- **Per-screen narrow layouts: substantially DONE.** `WatchlistView`, `PortfolioView`, `NewsView`, `AssetDetailView` gate `minWidth: 560` under `#if os(macOS)` only and carry real `#if os(iOS)` layout branches (e.g. `AssetDetailView.swift:361` pins the chart to a fixed `height: 260` on iOS to stop the area fill flooding the ScrollView). Remaining `minWidth: 104` usages are per-column numeric alignments, correct on iPhone.
- **PDF export renderer: DONE for iOS.** `PortfolioExportRenderer.swift` defines `PlatformFont`/`PlatformColor` typealiases (`NSFont`/`NSColor` on macOS, `UIFont`/`UIColor` on iOS) with full iOS color/font paths. macOS keeps calibrated-RGB P&L colors; iOS uses sRGB — intentional, must not be "unified."
- **Remaining raw AppKit in shared presentation: one site.** `RootView.swift:204` uses `NSSavePanel` with an iOS **stub** at `:218` (`// iOS Phase 0 stub — full export via .fileExporter is Phase 1`). The `NSApp`/`AppDelegate` references in `APTradeMac/APTradeMacApp.swift` are in the macOS-only target and are correct as-is.
- **Touch crosshair: NOT done.** `AssetDetailView.swift:332` and `ExpandableValueChart.swift:193` drive the crosshair from `.onContinuousHover` with no touch fallback, so chart scrubbing does not work on iPhone.
- **Test baseline:** 190 tests reported passing on both macOS and the iPhone simulator as of the Phase 0 audit; must be re-confirmed on current `main` before changes (Task 1).

## Actual remaining work (this plan)

Only three things remain to ship the iPhone app:
1. **Export save** — replace the `NSSavePanel`/iOS-stub with a cross-platform `.fileExporter` (extract a shared naming/UTType helper first, TDD).
2. **Touch crosshair** — add a `DragGesture` scrub path on iOS at the two chart sites.
3. **Verification pass** — build the `APTradeiOS` scheme, run the 190-test suite on both platforms, and visually walk every screen on the iPhone simulator to catch layout bugs that the already-written (but computer-use-unverifiable) iOS branches may contain.

## Architecture

No architectural change. The dependency rule is preserved: presentation depends inward on Application/Domain; Infrastructure stays behind ports. All iOS work lives in the SwiftUI presentation files and is gated so the macOS render path is byte-for-byte unchanged where it matters (e.g. the PDF renderer's calibrated-RGB colors must be preserved on macOS — a known Phase-0 regression trap).

## Phases

Sequencing: **P1 (baseline) → P2 (export helper) → P3 (iOS export) → P4 (touch crosshair) → P5 (verify).** P2 precedes P3 because P3 consumes the extracted helper.

### P1 — Baseline verify
Confirm the iOS toolchain and regression guard before touching anything.
- `xcodegen generate`, then `xcodebuild` the `APTradeiOS` scheme for an iPhone simulator (iOS 17+).
- Run the SwiftPM test suite on macOS (`DEVELOPER_DIR` set) and on the iPhone simulator.
- **Exit criteria:** clean iOS build; test counts on both platforms recorded as the baseline.

### P2 — Extract export naming/UTType helper (TDD)
Both macOS and iOS need the same filename stem and content-type resolution. Extract the naming currently inlined in `RootView` into a pure, testable helper and repoint macOS at it.
- New pure type `PortfolioExportNaming` (filename stem, `filename(for:)`, `contentType(for:)`).
- macOS `presentSavePanel` uses the helper; behavior unchanged.
- **Exit criteria:** helper unit-tested; macOS export behavior identical; tests green.

### P3 — iOS export via `.fileExporter`
Replace the iOS stub with a real save flow using the P2 helper.
- iOS-gated state + `.fileExporter` wired to `iosBody`; produced `Data` wrapped in a `FileDocument`.
- **Preserve macOS `NSSavePanel` path unchanged.**
- **Exit criteria:** export writes a file from the iPhone sim; macOS unchanged; no `NS*` API remains reachable on iOS.

### P4 — Touch crosshair (`DragGesture`)
Add a touch scrub path at both chart overlays; keep hover on macOS.
- `AssetDetailView.swift:332` and `ExpandableValueChart.swift:193`: `#if os(iOS)` `DragGesture(minimumDistance: 0)` calling the existing `updateHover(at:…)`, `.onEnded` clears the hover point; `#else` keeps `onContinuousHover`.
- **Exit criteria:** crosshair scrubs by touch on iOS; hover path unchanged on macOS; tests green.

### P5 — Full verification pass
Ship-readiness gate for the already-written iOS UI.
- Rebuild `APTradeiOS`; run the full suite on both platforms.
- Visual UAT on the iPhone simulator: walk Watchlist, Portfolio (+ Performance sub-tab), AssetDetail (hero + chart + stat grids + position + news), News, all account/settings subpages, Trade sheet, PriceAlert sheet, command palette, and export — checking for horizontal clipping, unreachable controls, and broken sheets. (Automated computer-use cannot target the dev build, so this is user-performed.)
- **Exit criteria:** no layout defects outstanding; macOS visually unchanged; both test suites green.

## Testing strategy

- The 190-test suite is the regression guard: it must stay green on **both** macOS and the iPhone simulator after every phase.
- Layout and touch behavior are visually verified on the iPhone simulator, per screen. Automated computer-use cannot target the APTrade dev build, so this visual UAT is performed by the user.
- No new business-logic tests are expected (no logic changes); add ViewModel/layout-helper tests only where a phase introduces new presentation logic (e.g. an export-filename helper).

## Risks & mitigations

- **Silent macOS regressions from shared-file edits** (e.g. the Phase-0 PDF color trap). Mitigation: gate iOS branches narrowly; keep macOS branches literally unchanged; rely on the dual-platform test run + visual spot-check.
- **Nav-shell divergence** making macOS and iOS drift. Mitigation: share ViewModels and screen bodies; branch only the shell/container with `#if os`.
- **Toolchain drift** (iOS SDK/simulator availability). Mitigation: B0 verifies before any UI work.

## Definition of done

iPhone app builds and runs from the `APTradeiOS` scheme; all macOS-parity features are reachable and usable in portrait on iPhone; 190/190 tests green on macOS and iOS simulator; macOS app unchanged.
