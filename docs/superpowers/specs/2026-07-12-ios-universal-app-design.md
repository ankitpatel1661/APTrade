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

- **Xcode wrapper: done.** `project.yml` defines the `APTradeiOS` application target (iOS 17, iPhone-only, portrait) consuming the `APTradeApp` SwiftPM product; `AppIcon` asset present. iOS cannot run as a bare SwiftPM executable, so this xcodegen project is the run/build vehicle.
- **Phase-0 AppKit gating: landed** in 10 files (`#if os` / `canImport(AppKit|UIKit)`): `AssetDetailView`, `DesignKit`, `NewsView`, `PlatformLayout`, `PortfolioView`, `RootView`, `WatchlistView`, `AppConfig`, `PortfolioExportRenderer`, `APTradeMacApp`.
- **Remaining raw AppKit in shared presentation: one site.** `RootView.swift:204` uses `NSSavePanel` with an iOS stub at `:218` (`// iOS Phase 0 stub — full export via .fileExporter is Phase 1`). The `NSApp`/`AppDelegate` references in `APTradeMac/APTradeMacApp.swift` are in the macOS-only target and are correct as-is.
- **Responsive-layout surface is small and bounded:** 9 `minWidth` constraints across 5 files — `RootView`, `WatchlistView`, `PortfolioView`, `AssetDetailView`, `NewsView`.
- **Test baseline:** 190 tests pass on both macOS and the iPhone simulator (per Phase 0 audit).

## Architecture

No architectural change. The dependency rule is preserved: presentation depends inward on Application/Domain; Infrastructure stays behind ports. All iOS work lives in the SwiftUI presentation files and is gated so the macOS render path is byte-for-byte unchanged where it matters (e.g. the PDF renderer's calibrated-RGB colors must be preserved on macOS — a known Phase-0 regression trap).

## Phases

Sequencing: **B0 → B2′ → B3 → B4 → B5.** B3 precedes B4 because per-screen layouts hang off the navigation shell.

### B0 — Baseline verify (S)
Confirm the iOS toolchain and regression guard before touching UI.
- `xcodegen generate`, then `xcodebuild` the `APTradeiOS` scheme for an iPhone simulator (iOS 17+).
- Run the 190-test suite on both macOS and the iPhone simulator; both must be green.
- **Exit criteria:** clean iOS build + 190/190 on both platforms recorded as the baseline.

### B2′ — Finish AppKit shims (S–M)
Close the last non-layout coupling.
- Replace the `RootView.swift:204` `NSSavePanel` path and the `:218` iOS stub with a cross-platform SwiftUI `.fileExporter` for portfolio export.
- Audit the `DesignKit` gold→accent logo recolor and `PortfolioExportRenderer` iOS paths: confirm they are real implementations, not compile-only stubs; implement any that are stubbed.
- **Preserve macOS output exactly** (calibrated-RGB PDF colors; existing save behavior).
- **Exit criteria:** no `NS*` AppKit APIs remain in shared presentation; export works on iOS sim and is unchanged on macOS; tests green.

### B3 — Navigation shell (L) — *biggest single piece*
Convert the wide-window macOS shell into an iPhone-native structure.
- `RootView` → iOS `TabView` + `NavigationStack` (macOS keeps its custom top tab-switcher via `#if os(macOS)`).
- Account drawer overlay → presented sheet / pushed stack on iOS.
- Command palette: add a tap trigger (the ⌘K path stays macOS-only; every palette result is already clickable).
- **Exit criteria:** all top-level destinations reachable on iPhone via native nav; macOS shell unchanged; tests green.

### B4 — Per-screen narrow passes (M per screen)
Make each screen usable at ~390pt.
- Remove/relax the 9 `minWidth`s in `RootView`, `WatchlistView`, `PortfolioView`, `AssetDetailView`, `NewsView`.
- Stack the dense `AssetDetailView` vertically (hero + chart + 2-col stat grids + position panel + news). `PerformanceSection` already uses `GridItem(.adaptive(minimum: 150))` and needs no change.
- Convert fixed-size sheets (Trade 420×460, PriceAlert 360, CommandPalette 520) to iOS sheets with detents.
- **Exit criteria:** every screen renders without horizontal clipping or unreachable controls on the iPhone sim; macOS layouts unchanged; tests green.

### B5 — Touch interactions (S–M)
Replace pointer-only affordances.
- Chart crosshair: `onContinuousHover` → `DragGesture` at `AssetDetailView.swift:329` and `ExpandableValueChart.swift:193` (both already use `chartOverlay` + proxy).
- Ensure the palette tap trigger from B3 is wired on iOS.
- **Exit criteria:** crosshair scrub works by touch on iOS; hover path unchanged on macOS; tests green.

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
