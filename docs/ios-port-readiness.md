# iOS Port-Readiness Audit — APTrade → Universal (macOS + iPhone)

**Date:** 2026-06-29
**Status:** Audit complete. **Phase 0 DONE** — the package compiles for iOS and all 190 tests pass on the iPhone 17 Pro simulator (iOS 26.5); macOS unchanged at 190/190. See "Phase 0 result" below.
**Bottom line:** Low-risk, mostly-additive UI port. The Domain/Application/Infrastructure layers and all tests are reused verbatim; AppKit coupling is confined to 5 files.

## Phase 0 result (2026-06-29)

Proven: **compiles for iOS + 190/190 tests pass on the iOS simulator**, macOS untouched (190/190). 7 touchpoints gated across 8 files (`#if os(macOS)` / `canImport(AppKit)` with `PlatformFont`/`PlatformColor`/`PlatformImage` typealiases; `NSWorkspace`→`openURL`).

- **The audit's "6 AppKit touchpoints" was off by one:** `AppConfig.swift` uses `FileManager.homeDirectoryForCurrentUser`, which is `API_UNAVAILABLE(ios)` — a *Foundation* incompatibility the AppKit-only grep missed. Gated (macOS home dir / iOS Documents dir).
- **No restructure needed:** the `APTradeApp` executable target compiled *and* tested for iOS as-is — the planned library-split fallback was unnecessary.
- **Two iOS Phase-0 stubs** (compile-only, macOS unaffected): portfolio export save panel (Phase 1 = `.fileExporter`), AppConfig default path.
- **One silent regression caught in review** (not by tests): the PDF P&L colors were ported from `NSColor(calibratedRed:)` (calibrated RGB) to `red:` (sRGB) — different rendered colors on macOS. Re-gated to preserve macOS output exactly.

Implication: the engine + tests genuinely port to iPhone. Remaining work is the responsive-layout UI (Phases 2+), as scoped below.

## Goal & shape

One **universal app**: the same codebase builds for macOS and iPhone. The macOS app keeps working unchanged throughout — iOS support is *added* via `#if os(iOS)` shims plus responsive layout. Nothing built so far is discarded.

## What ports with zero changes

- **APTradeDomain** — pure Swift, no framework imports. 100%.
- **APTradeApplication** — use cases + ports. 100%.
- **APTradeInfrastructure** — URLSession (Yahoo/Finnhub), `UserDefaults` stores, caching, scheduler. ~90% (only the export renderer needs work).
- **~19 of 31 UI files** — every ViewModel, `Theme`, `L10n`, `LocalizationManager`, `Sparkline`, `ArticleRow`, `WatchlistSection`, `CompositionRoot`, `RowState`, `SettingsViewModel`, etc.

## The complete AppKit inventory (the whole list — 5 files)

| File:line | API | Fix | Effort |
|---|---|---|---|
| `APTradeApp.swift` (whole) | `@NSApplicationDelegateAdaptor`, `AppDelegate`, `NSApp.setActivationPolicy`, window styleMask | `#if os(macOS)` the AppDelegate; iOS App needs none of it | S |
| `NewsView.swift:87`, `AssetNewsSection.swift:28` | `NSWorkspace.shared.open(url)` | SwiftUI `openURL` — cross-platform, no `#if` | S |
| `RootView.swift:142` | `NSSavePanel` (portfolio export) | `.fileExporter` — cross-platform | S–M |
| `DesignKit.swift:11–111` | `NSImage` brand-logo gold→accent recoloring | `CGContext` core already cross-platform; only image load + final wrap need `UIImage`/`#if` | M (self-contained) |
| `APTradeInfrastructure/PortfolioExportRenderer.swift` | PDF text via `NSFont`/`NSColor`/`NSAttributedString` | PDF `CGContext` core is cross-platform; swap font/color to UIKit or CoreText | M (self-contained) |

The `.xlsx`/`.docx` OOXML/ZIP writer in the export renderer is pure Swift and ports free.

## Touch & keyboard (small, contained)

- **Chart crosshair**: `onContinuousHover` in exactly 2 places — `AssetDetailView.swift:329`, `ExpandableValueChart.swift:193`. Both already use `chartOverlay`+proxy → swap hover for `DragGesture`. M.
- **⌘K palette** keyboard nav (`CommandPaletteView.swift:54–60`) is additive; the tap path already works ("every result is also clickable"). Add a tap trigger instead of the ⌘K shortcut. S.

## The bulk of the work: responsive layout

macOS assumes a wide window (`minWidth: 560` on Root/Watchlist/Portfolio/News/AssetDetail; fixed-size sheets — Trade 420×460, PriceAlert 360, CommandPalette 520). iPhone is ~390pt wide.

- **Navigation shell** (`RootView`): custom top tab-switcher + overlay account-drawer → iOS `TabView` + `NavigationStack` + settings as a presented/pushed stack. Single biggest piece. **L.**
- **Per-screen narrow passes**: Watchlist, Portfolio sub-switcher, the dense AssetDetail (hero + chart + 2-col stat grids + position panel + news), News, Settings subpages. Drop min-widths, stack panels. **M each.** (`PerformanceSection` already uses `GridItem(.adaptive(minimum: 150))` — already responsive.)
- **Sheets** → iOS sheets with detents. **S–M.**

## Structural caveat: build/run wrapper changes

The Mac app currently runs as a bare SwiftPM executable (`"$(swift build --show-bin-path)/APTradeApp"`). **iOS cannot run as a bare SwiftPM executable** — it needs a real `.app` via an Xcode project. So "universal app" cleanly means: keep Domain/Application/Infrastructure (and the presentation code) as the **same SwiftPM packages**, and introduce a thin **Xcode project** with macOS + iOS targets that consume them. Packages and tests are reused verbatim; only the build/run wrapper is new.

Toolchain prerequisite: iOS SDK + simulator runtimes (ships with Xcode). Phase 0 verifies this is present before any code changes.

## Tests

Domain/Application/Infrastructure tests are platform-agnostic; the `@MainActor` view-model tests are SwiftUI/Foundation. All 190 should run on the iOS simulator unchanged. Low risk.

## Recommended phased path (cautious; macOS app working at every step)

- **Phase 0 — prove it compiles.** Add `.iOS` to package platforms, gate the 5 AppKit touchpoints behind `#if`, get it **building for iOS + 190 tests green on the iOS simulator.** Proves the shared core ports before any layout investment. (~1–2 days.)
- **Phase 1 — platform shims.** `openURL`, `.fileExporter`, app shell, image/export render ports.
- **Phase 2 — navigation shell.** `TabView`/`NavigationStack`; one screen reachable on iPhone.
- **Phase 3+ — per-screen responsive layout**, one independently-shippable screen at a time.
- **Then** touch crosshair, sheet detents, polish.

Each phase via the same spec→plan→subagent-build pipeline. Nothing discarded at any step.

## Effort & risk

Roughly **3–5 weeks** of focused work for a polished universal app, almost entirely additive UI. **Low risk** — the tested financial engine is shared, and every step is independently verifiable with the macOS app intact.
