# iOS Phase 2 — iPhone-Native Layout (Design)

**Date:** 2026-06-30
**Status:** Approved (brainstorm) — pending spec review
**Base:** `main` @ `0d9468a` (iOS Phase 1 merged: launchable iPhone build, macOS+iOS at 190/190)

## Problem

iOS Phase 1 produced a launchable iPhone build, but the UI is the macOS layout shown
unchanged on a phone: hard `minWidth: 560` floors force the content wider than the
~402 pt iPhone screen, so it overflows both edges, the tab pills crush into vertical
letters, and mouse-only affordances (hover-reveal row buttons, `.help()` tooltips, a
right-side account drawer) don't translate to touch.

This phase adapts the presentation layer so the iPhone app feels native — while the
macOS app stays **visually identical**.

## Goal

A native-feeling iPhone experience across all three sections — **Watchlist, Portfolio,
and News** — with the same gold-on-black identity and full feature parity, navigated by a
bottom tab bar. macOS renders the exact same pixels it does today; all 190 tests stay
green on both platforms.

## Non-Goals (YAGNI)

- No iPad-specific layout (iPhone only; iPad can inherit the compact layout for now).
- No new features, no data/Application/Domain/ViewModel changes — presentation only.
- No macOS visual changes of any kind.
- No full navigation rewrite beyond what's described here.

## Core Principle — macOS Stays Identical

Every change is gated `#if os(iOS)` / `#else`, with the `#else` (macOS) branch kept
byte-for-byte as it is today. Concretely:

- The `minWidth: 560` / `minHeight` floors in `RootView` and `WatchlistView` become
  macOS-only.
- The top pill `switcher` in `RootView` stays on macOS; iOS uses the bottom tab bar.
- Hover-reveal row buttons and `.help()` tooltips stay on macOS; iOS adds swipe actions.
- The right-side account drawer stays on macOS; iOS presents the same content as a sheet.

Because macOS code paths are untouched, the macOS app and the macOS test runs are
unaffected.

## Design

### 1. Navigation Shell

On iOS, `RootView` renders a SwiftUI `TabView` with three tabs:

| Tab | Symbol | Content view (reused) |
|-----|--------|-----------------------|
| Watchlist | `eye` | `WatchlistView` |
| Portfolio | `chart.pie` | `PortfolioView` |
| News | `newspaper` | `NewsView` |

- Each tab wraps its content in a `NavigationStack` so detail pushes (e.g.
  `AssetDetailView`) work natively.
- The top pill `switcher` is **not** rendered on iOS (the tab bar replaces it). The
  content views currently take a `switcher: AnyView` they render in their header; on iOS
  they omit it.
- macOS continues to use the existing single-window layout with the top switcher,
  unchanged.

### 2. Account / Settings — Drawer → Sheet

The account "…" menu (currently a right-side drawer in `RootView`) is presented on iOS as
a native sheet, reached from a top-right toolbar button (`ellipsis`). The existing
sub-pages (profile, account settings, notifications, appearance, language, security, help,
about, plus the export action) render inside a `NavigationStack` in the sheet. The macOS
drawer presentation is preserved.

Export: the macOS `NSSavePanel` path is unchanged; iOS keeps the existing Phase-0 stub
(full `.fileExporter` is out of scope for this phase).

### 3. Search (Command Palette)

The ⌘K command palette stays available on iOS via a top-right toolbar search button.
The keyboard shortcut itself remains macOS-only (harmless no-op concept on iPhone).

### 4. Per-Screen Compact Layout

For all three tabs, fixed/min widths are dropped on iOS and wide horizontal header
clusters restack vertically to fit a narrow screen.

- **Watchlist** — the header row (kind-toggle + switcher + live badge designed to span a
  desktop window) restacks vertically: a full-width `KindToggle` (Stocks / ETFs / Crypto),
  then the hero (avg-day-change % + sparkline + advancing/declining legend), then the
  search/add bar. The list rows keep their name/symbol + sparkline + price/change-pill
  layout, which already fits.
- **Portfolio** — drop fixed widths; any wide header cluster (e.g. value + reset menu)
  restacks; sections flow in the existing vertical scroll.
- **News** — the same compact treatment: the General/Crypto/Merger category control and
  any filter/search header fit the narrow width; article rows flow in the vertical scroll.
  Per-symbol company news on `AssetDetailView` is verified to fit as well.
- **AssetDetailView** — already pushed via `NavigationStack`; verify chart and sections
  fit the compact width with no horizontal overflow.

The global brand wordmark shrinks from its 108 pt desktop height to a phone-appropriate
size (or moves into the per-tab navigation chrome).

### 5. Touch Affordances (replacing mouse-only ones)

- **Row actions** — remove/alert currently appear on `.onHover`, invisible on touch. On
  iOS: SwiftUI `.swipeActions` — trailing swipe = remove (destructive), leading swipe =
  set alert — plus the existing long-press context menu (already works on iOS). macOS
  keeps hover-reveal.
- **Tooltips** — `.help()` is a no-op on iOS; left as-is.

## Components & Boundaries

- `RootView` gains an iOS shell branch (`TabView` + per-tab `NavigationStack` + account
  sheet) while its macOS body is unchanged.
- `WatchlistView`, `PortfolioView`, `NewsView`, `AssetDetailView` gain `#if os(iOS)`
  compact-layout branches in their header/frame code; shared row/section subviews gain iOS
  swipe actions where applicable.
- No changes below the presentation layer: ViewModels, use cases, repositories, and domain
  types are untouched.

## Testing & Verification

1. **macOS** `swift test` → **190/190** (must stay green; macOS paths unchanged).
2. **iOS** `xcodebuild test -scheme APTradeLite-Package -destination
   'platform=iOS Simulator,name=iPhone 17 Pro' -skipPackagePluginValidation` → **190/190**.
   (Note: park the generated `APTrade.xcodeproj` aside so the package scheme is visible —
   see project memory `ios-package-test-scheme`.)
3. **Visual** — from-source iOS build, install + launch on iPhone 17 Pro, screenshot each
   tab (Watchlist, Portfolio, News) and the account sheet to confirm no horizontal
   overflow and a native feel. Visual quality judgment is the user's.
4. **Regression** — confirm the macOS app still builds (`APTradeMac`) and looks identical.

## Risks

- SwiftUI layout differences between platforms can be subtle; mitigated by isolating iOS
  branches and keeping macOS code paths literally unchanged.
- `TabView` + `NavigationStack` interactions with existing sheets (trade, alert, palette,
  export) must be verified so presentations don't conflict.
