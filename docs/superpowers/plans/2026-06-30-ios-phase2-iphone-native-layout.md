# iOS Phase 2 — iPhone-Native Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adapt the APTrade presentation layer so the iPhone app feels native — a bottom tab bar for Watchlist / Portfolio / News, compact-width reflow, account drawer→sheet, hover→swipe row actions — while the macOS app stays visually identical.

**Architecture:** Every change is gated `#if os(iOS)` / `#else`, with the macOS (`#else`) branch kept byte-for-byte as today. On iOS, `RootView` renders a `TabView` whose tabs reuse the existing `WatchlistView` / `PortfolioView` / `NewsView` (each keeps its own `NavigationStack`). The desktop-only `minWidth: 560` floors, the top pill switcher, the right-side account drawer, and hover-reveal row buttons all become macOS-only; iOS gets a bottom tab bar, compact stacked headers, a sheet-based account screen, and swipe actions.

**Tech Stack:** SwiftUI, Swift 6, SwiftPM (`APTradeLite` package), XcodeGen (`APTradeiOS` app target), iOS 17 simulator (iPhone 17 Pro), macOS 14.

**Spec:** `docs/superpowers/specs/2026-06-30-ios-phase2-iphone-native-layout-design.md`

## Global Constraints

- **macOS stays visually identical.** Every edit keeps the existing macOS code path byte-for-byte; all iOS behavior is added under `#if os(iOS)`. No macOS-visible change is acceptable.
- **Presentation layer only.** No changes to ViewModels, use cases, repositories, or domain types. (Adding optional `var` parameters with defaults to existing `View` structs is allowed; changing their existing behavior is not.)
- **Toolchain:** prefix every `swift`/`xcodebuild`/`xcrun` command with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- **macOS verification:** `DEVELOPER_DIR=… swift build` (produces the `APTradeMac` binary) and `DEVELOPER_DIR=… swift test` = **190/190**, after every task.
- **iOS package test:** scheme is `APTradeLite-Package` (NOT `APTradeLite`). The generated `APTrade.xcodeproj` **shadows** the package scheme, so park it first:
  ```bash
  mv APTrade.xcodeproj /tmp/APTrade.xcodeproj.parked
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test \
    -scheme APTradeLite-Package \
    -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
    -skipPackagePluginValidation 2>&1 | tail -6
  mv /tmp/APTrade.xcodeproj.parked APTrade.xcodeproj
  ```
  Expect `** TEST SUCCEEDED **` (190 tests).
- **iOS app build + run** (for visual checks):
  ```bash
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodegen generate
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild \
    -project APTrade.xcodeproj -scheme APTradeiOS \
    -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
    -derivedDataPath .build/ios-derived build
  APP=.build/ios-derived/Build/Products/Debug-iphonesimulator/APTradeiOS.app
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun simctl boot "iPhone 17 Pro" 2>/dev/null; true
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun simctl install booted "$APP"
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun simctl launch booted com.aptrade.ios
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun simctl io booted screenshot /tmp/aptrade-<tab>.png
  ```
- **No fabricated tests.** These are view-layout changes with no business logic and no existing UI snapshot tests. Verification per task = builds on both platforms + `swift test` 190/190 + (for visual tasks) simulator screenshot. Do not invent hollow unit tests.
- **Commit after every task** with a `ios(phase2):` prefix.

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `Sources/APTradeApp/PlatformLayout.swift` | iOS-only shared chrome: a `.iosTopChrome(onSearch:onAccount:)` view modifier (brand mark + search + account toolbar). | Create |
| `Sources/APTradeApp/RootView.swift` | App shell. Add iOS `TabView` body + account/palette sheets; gate macOS body + `minWidth` unchanged. | Modify |
| `Sources/APTradeApp/WatchlistView.swift` | Watchlist tab. Optional `switcher`/chrome callbacks; iOS compact header + frame gating + swipe actions. | Modify |
| `Sources/APTradeApp/PortfolioView.swift` | Portfolio tab. Optional `switcher`/chrome callbacks; iOS compact header + frame gating. | Modify |
| `Sources/APTradeApp/NewsView.swift` | News tab. Optional `switcher`/chrome callbacks; iOS compact header + frame gating. | Modify |
| `Sources/APTradeApp/AssetDetailView.swift` | Asset detail (pushed). Verify compact-width fit; gate any `minWidth` if present. | Modify (verify) |

---

### Task 1: iOS navigation shell (bottom tab bar + account/palette sheets)

Add the iOS `TabView` shell to `RootView` and make the three content views accept optional `switcher` and chrome callbacks so the shell can drive them, all without changing the macOS path.

**Files:**
- Modify: `Sources/APTradeApp/RootView.swift`
- Modify: `Sources/APTradeApp/WatchlistView.swift:5` (`var switcher: AnyView`)
- Modify: `Sources/APTradeApp/PortfolioView.swift:20` (`var switcher: AnyView`)
- Modify: `Sources/APTradeApp/NewsView.swift:6` (`var switcher: AnyView`)

**Interfaces:**
- Produces: each content view gains `var switcher: AnyView? = nil`, `var onOpenSearch: (() -> Void)? = nil`, `var onOpenAccount: (() -> Void)? = nil` (all defaulted — existing macOS call sites stay valid).
- Consumes: existing `RootView` state (`tab`, `showAccountPanel`, `panelRoute`, `showPalette`, `paletteVM`), the existing `accountPanel` computed view, and `CommandPaletteView`.

- [ ] **Step 1: Make `switcher` optional + add chrome callbacks in the three views**

In each of `WatchlistView`, `PortfolioView`, `NewsView`, change the stored property:
```swift
// was: var switcher: AnyView
var switcher: AnyView? = nil
var onOpenSearch: (() -> Void)? = nil
var onOpenAccount: (() -> Void)? = nil
```
Then guard every existing use of `switcher` in their headers so a nil switcher renders nothing:
- `WatchlistView.swift` header (`switcher` at line ~68): wrap as `if let switcher { switcher }`.
- `PortfolioView.swift` header (`switcher` at line ~138): wrap as `if let switcher { switcher }`.
- `NewsView.swift` body (`switcher` at line ~15): wrap as `if let switcher { switcher.padding(.horizontal, 24).padding(.top, 8) }`.

- [ ] **Step 2: Build macOS to confirm the optional change is source-compatible**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build`
Expected: `Build complete!` (macOS `RootView` still calls `WatchlistView(switcher: AnyView(switcher))` etc. — valid against the optional param).

- [ ] **Step 3: Add the iOS shell to `RootView`**

Add `tr`-based tab titles and SF Symbols. Wrap the existing `body` so macOS is unchanged and iOS gets the TabView. Replace the `public var body: some View { … }` with:
```swift
public var body: some View {
    #if os(iOS)
    iosBody
    #else
    macBody   // the exact previous body, renamed (see Step 4)
    #endif
}

#if os(iOS)
private var iosBody: some View {
    TabView(selection: $tab) {
        WatchlistView(onOpenSearch: { showPalette = true },
                      onOpenAccount: { showAccountPanel = true })
            .tabItem { Label(tr(.watchlist), systemImage: "eye") }
            .tag(Tab.watchlist)
        PortfolioView(onOpenSearch: { showPalette = true },
                      onOpenAccount: { showAccountPanel = true })
            .tabItem { Label(tr(.portfolio), systemImage: "chart.pie") }
            .tag(Tab.portfolio)
        NewsView(onOpenSearch: { showPalette = true },
                 onOpenAccount: { showAccountPanel = true })
            .tabItem { Label(tr(.news), systemImage: "newspaper") }
            .tag(Tab.news)
    }
    .tint(Theme.gold)
    .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
    .task { await scheduler.run() }
    .sheet(isPresented: $showAccountPanel, onDismiss: { panelRoute = .menu }) {
        NavigationStack { accountPanel.background(Theme.surface.ignoresSafeArea()) }
            .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
    }
    .sheet(isPresented: $showPalette, onDismiss: { paletteVM.reset() }) {
        CommandPaletteView(viewModel: paletteVM,
                           onSelect: { handlePaletteSelection($0) },
                           onClose: { closePalette() })
    }
    .confirmationDialog(tr(.exportPortfolioData), isPresented: $showExportDialog, titleVisibility: .visible) {
        ForEach(PortfolioExportFormat.allCases, id: \.self) { format in
            Button(format.displayName) { beginExport(format) }
        }
        Button(tr(.cancel), role: .cancel) {}
    } message: { Text(tr(.exportFormatPrompt)) }
    .alert(tr(.exportFailed), isPresented: Binding(
        get: { exportError != nil }, set: { if !$0 { exportError = nil } })) {
        Button(tr(.ok), role: .cancel) { exportError = nil }
    } message: { Text(exportError ?? "") }
    .sheet(item: $paletteAsset) { asset in
        NavigationStack {
            AssetDetailView(asset: asset)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(tr(.done)) { paletteAsset = nil } } }
        }
    }
}
#endif
```
Notes: the `accountPanel` close `×` buttons already set `showAccountPanel = false`, which dismisses the sheet; that is acceptable for this task. The macOS-only `.frame(minWidth: 560, minHeight: 680)` stays attached to `macBody` only.

- [ ] **Step 4: Rename the existing body to `macBody` and keep its modifiers macOS-only**

Rename the current `public var body: some View { GeometryReader { … } … .frame(minWidth: 560, minHeight: 680) … .sheet(item: $paletteAsset) {…} }` to:
```swift
#if os(macOS)
private var macBody: some View {
    GeometryReader { geo in … }   // unchanged
    .frame(minWidth: 560, minHeight: 680)
    .preferredColorScheme(…)
    .task { await scheduler.run() }
    .confirmationDialog(…) …
    .alert(…) …
    .sheet(item: $paletteAsset) { … }
}
#endif
```
Do not alter any line inside `macBody` other than the rename + `#if os(macOS)` wrapper.

- [ ] **Step 5: Build macOS + run macOS tests**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test 2>&1 | tail -3`
Expected: `Build complete!` and `Executed 190 tests, with 0 failures`.

- [ ] **Step 6: Build + launch iOS, screenshot to confirm the tab bar**

Run the iOS app build + run block from Global Constraints (screenshot to `/tmp/aptrade-task1.png`).
Expected: `BUILD SUCCEEDED`; a real PID; the bottom tab bar shows Watchlist / Portfolio / News. (Content may still overflow inside a tab — fixed in Tasks 2–3. This task's deliverable is the shell.)

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/RootView.swift Sources/APTradeApp/WatchlistView.swift Sources/APTradeApp/PortfolioView.swift Sources/APTradeApp/NewsView.swift
git commit -m "ios(phase2): bottom tab-bar shell + account/palette sheets (iOS); macOS unchanged"
```

---

### Task 2: Watchlist compact layout (frame gating, stacked header, top chrome, swipe actions)

Make the Watchlist tab fit and feel native on iPhone.

**Files:**
- Create: `Sources/APTradeApp/PlatformLayout.swift`
- Modify: `Sources/APTradeApp/WatchlistView.swift` (`.frame(minWidth: 560, minHeight: 640)` at line ~56; `header` at lines ~62-89; `list` rows at lines ~142-165)

**Interfaces:**
- Produces: `extension View { func iosTopChrome(onSearch:@escaping () -> Void, onAccount:@escaping () -> Void) -> some View }` (iOS only).
- Consumes: `onOpenSearch` / `onOpenAccount` callbacks added in Task 1; `BrandMark`, `Theme`, `tr`.

- [ ] **Step 1: Create the shared iOS top-chrome modifier**

`Sources/APTradeApp/PlatformLayout.swift`:
```swift
import SwiftUI

#if os(iOS)
/// iOS navigation-bar chrome shared by every tab: brand mark on the left,
/// search + account buttons on the right. Applied INSIDE each tab's NavigationStack.
struct IOSTopChrome: ViewModifier {
    let onSearch: () -> Void
    let onAccount: () -> Void

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    BrandMark(size: 17, showsMark: true)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onSearch) {
                        Image(systemName: "magnifyingglass").foregroundStyle(Theme.gold)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onAccount) {
                        Image(systemName: "ellipsis").foregroundStyle(Theme.gold)
                    }
                }
            }
            .toolbarBackground(Theme.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
    }
}

extension View {
    func iosTopChrome(onSearch: @escaping () -> Void, onAccount: @escaping () -> Void) -> some View {
        modifier(IOSTopChrome(onSearch: onSearch, onAccount: onAccount))
    }
}
#endif
```

- [ ] **Step 2: Gate the Watchlist min-width frame to macOS**

In `WatchlistView.swift`, change line ~56:
```swift
// was: .frame(minWidth: 560, minHeight: 640)
#if os(macOS)
.frame(minWidth: 560, minHeight: 640)
#endif
```

- [ ] **Step 3: Apply the iOS top chrome inside the NavigationStack**

In `WatchlistView.body`, attach to the `ZStack` inside `NavigationStack` (alongside `.navigationDestination`), gated:
```swift
#if os(iOS)
.iosTopChrome(onSearch: { onOpenSearch?() }, onAccount: { onOpenAccount?() })
.navigationBarTitleDisplayMode(.inline)
#endif
```

- [ ] **Step 4: Reflow the header for compact width (iOS)**

The current `header` lays out `KindToggle` + `Spacer` + `switcher`/live cluster in one wide `HStack` ([WatchlistView.swift:64-81]). Replace the single `HStack(alignment: .top…)` with a platform branch that, on iOS, stacks a full-width `KindToggle` over the live badge, and on macOS keeps the exact existing HStack:
```swift
#if os(iOS)
VStack(alignment: .leading, spacing: 12) {
    KindToggle(selection: $viewModel.selectedKind, counts: viewModel.counts)
    HStack {
        if viewModel.isRefreshing { ProgressView().controlSize(.small) }
        else if viewModel.isLive { LiveBadge() }
        Spacer()
    }
}
#else
HStack(alignment: .top, spacing: 10) {
    KindToggle(selection: $viewModel.selectedKind, counts: viewModel.counts)
    Spacer()
    HStack(alignment: .center, spacing: 10) {
        if let switcher { switcher }
        HStack(spacing: 10) {
            if viewModel.isRefreshing { ProgressView().controlSize(.small) }
            else if viewModel.isLive { LiveBadge() }
        }
        .frame(width: 60, alignment: .trailing)
        Color.clear.frame(width: 28, height: 1)
    }
}
#endif
```
(The macOS branch is the current code verbatim, with the `switcher` use already made optional in Task 1.)

- [ ] **Step 5: Add swipe actions to watchlist rows (iOS)**

In the `list`'s `ForEach` ([WatchlistView.swift:144-160]), add after the existing row modifiers, gated iOS, using the same callbacks the hover buttons use:
```swift
#if os(iOS)
.swipeActions(edge: .trailing, allowsFullSwipe: true) {
    Button(role: .destructive) { viewModel.remove(symbol: row.asset.symbol) } label: {
        Label(tr(.removeFromWatchlist), systemImage: "trash")
    }
}
.swipeActions(edge: .leading) {
    Button { alertTarget = row.asset } label: {
        Label(tr(.setPriceAlert), systemImage: "bell")
    }.tint(Theme.gold)
}
#endif
```
(The macOS hover-reveal buttons in `WatchlistRow` are untouched; the existing `contextMenu` already provides both actions via long-press on iOS too.)

- [ ] **Step 6: Build both platforms + macOS tests**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test 2>&1 | tail -3`
Expected: `Build complete!`, `Executed 190 tests, with 0 failures`.

- [ ] **Step 7: Build + launch iOS, screenshot the Watchlist tab**

Run the iOS app build + run block; screenshot to `/tmp/aptrade-task2-watchlist.png`.
Expected: `BUILD SUCCEEDED`; the Watchlist tab fits the screen — no horizontal overflow, full-width Stocks/ETFs/Crypto toggle, hero + sparkline, search bar, rows, bottom tab bar. (Visual quality is the user's call.)

- [ ] **Step 8: Commit**

```bash
git add Sources/APTradeApp/PlatformLayout.swift Sources/APTradeApp/WatchlistView.swift
git commit -m "ios(phase2): compact Watchlist — stacked header, top chrome, swipe actions"
```

---

### Task 3: Portfolio + News compact layout

Apply the same compact treatment (frame gating, top chrome, header reflow) to the other two tabs.

**Files:**
- Modify: `Sources/APTradeApp/PortfolioView.swift` (`.frame(minWidth: 560, minHeight: 640)` at line ~71; header cluster at lines ~136-160)
- Modify: `Sources/APTradeApp/NewsView.swift` (`.frame(minWidth: 560, minHeight: 640)` at line ~27; `controls` at lines ~34-77)

**Interfaces:**
- Consumes: `iosTopChrome` (Task 2), `onOpenSearch`/`onOpenAccount` (Task 1).

- [ ] **Step 1: Gate Portfolio + News min-width frames to macOS**

In `PortfolioView.swift` (line ~71) and `NewsView.swift` (line ~27), wrap each `.frame(minWidth: 560, minHeight: 640)`:
```swift
#if os(macOS)
.frame(minWidth: 560, minHeight: 640)
#endif
```

- [ ] **Step 2: Apply iOS top chrome inside both NavigationStacks**

In `PortfolioView.body` and `NewsView.body`, attach to the inner `ZStack` (next to `.navigationDestination`/`.task`), gated:
```swift
#if os(iOS)
.iosTopChrome(onSearch: { onOpenSearch?() }, onAccount: { onOpenAccount?() })
.navigationBarTitleDisplayMode(.inline)
#endif
```

- [ ] **Step 3: Reflow the Portfolio header cluster (iOS)**

The Portfolio `summary` header places `switcher` + a reset menu in a wide trailing `HStack` ([PortfolioView.swift:136-160]). Gate it so iOS drops the switcher and lets the value summary use full width, while macOS keeps the existing cluster verbatim:
```swift
#if os(iOS)
// iOS: no top switcher (bottom tab bar); keep the reset control only.
HStack {
    Spacer()
    resetMenu   // the existing reset Menu/Button view used in the macOS cluster
}
#else
// existing macOS trailing cluster verbatim (switcher + live + reset), with
// `if let switcher { switcher }` from Task 1.
#endif
```
If the reset control is currently inline (not a named subview), extract it into a `private var resetMenu: some View` first and reference it from both branches (DRY). Do not change its behavior.

- [ ] **Step 4: Reflow the News controls for compact width (iOS)**

The News `controls` lay the `NewsCategory` pills + Saved button in one `HStack` ([NewsView.swift:36-62]) that can overflow on a narrow screen. Gate the category row so iOS wraps it in a horizontal `ScrollView` while macOS keeps the plain `HStack`:
```swift
#if os(iOS)
ScrollView(.horizontal, showsIndicators: false) {
    HStack(spacing: 4) { /* the existing category buttons + Saved button */ }
        .padding(.horizontal, 2)
}
#else
HStack(spacing: 4) { /* existing category buttons + Saved button verbatim */ }
#endif
```
(The search `TextField` row below already fits; leave it unchanged.)

- [ ] **Step 5: Build both platforms + macOS tests**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test 2>&1 | tail -3`
Expected: `Build complete!`, `Executed 190 tests, with 0 failures`.

- [ ] **Step 6: Build + launch iOS, screenshot Portfolio + News**

Run the iOS build + run block. Switch tabs via `xcrun simctl` is not needed — screenshot after tapping is the user's call; at minimum screenshot the launch tab and confirm build. Capture `/tmp/aptrade-task3-portfolio.png` and `/tmp/aptrade-task3-news.png` if you navigate.
Expected: `BUILD SUCCEEDED`; Portfolio and News tabs fit with no horizontal overflow.

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/PortfolioView.swift Sources/APTradeApp/NewsView.swift
git commit -m "ios(phase2): compact Portfolio + News headers (iOS); macOS unchanged"
```

---

### Task 4: Asset detail + wordmark fit, and full visual sweep

Verify the pushed `AssetDetailView` (reached from a watchlist/portfolio row and from per-symbol company news) fits the compact width, and fix any remaining overflow.

**Files:**
- Modify (verify, gate only if needed): `Sources/APTradeApp/AssetDetailView.swift`

- [ ] **Step 1: Audit `AssetDetailView` for fixed/min widths**

Run: `grep -n "minWidth\|\.frame(width:" Sources/APTradeApp/AssetDetailView.swift`
For any `minWidth: <≥560>` or wide fixed `.frame(width:)` on a top-level container, gate it `#if os(macOS)`. Leave intrinsic small fixed widths (icons, sparklines, pills) alone.

- [ ] **Step 2: Build both platforms + macOS tests**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test 2>&1 | tail -3`
Expected: `Build complete!`, `Executed 190 tests, with 0 failures`.

- [ ] **Step 3: Build + launch iOS; open a row → detail; screenshot**

Run the iOS build + run block; screenshot the detail screen to `/tmp/aptrade-task4-detail.png`.
Expected: detail header, chart, and sections fit the width with no horizontal overflow; back navigation works.

- [ ] **Step 4: Commit (only if files changed)**

```bash
git add Sources/APTradeApp/AssetDetailView.swift
git commit -m "ios(phase2): fit asset-detail to compact width (iOS); macOS unchanged"
```
If Step 1 found nothing to gate, skip the commit and note "AssetDetailView already compact-safe" in the task report.

---

### Task 5: Final regression + cross-platform proof

**Files:** none (verification only).

- [ ] **Step 1: macOS full regression**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test 2>&1 | tail -3`
Expected: `Executed 190 tests, with 0 failures`.

- [ ] **Step 2: iOS package full regression**

Run the parked-xcodeproj iOS test block from Global Constraints.
Expected: `** TEST SUCCEEDED **` (190 tests).

- [ ] **Step 3: macOS app still builds + launches (identical)**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build` then launch `"$(DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build --show-bin-path)/APTradeMac"`.
Expected: `Build complete!`; the macOS app looks exactly as before this phase (user confirms).

- [ ] **Step 4: iOS full visual sweep**

Build + launch iOS; screenshot each tab (Watchlist, Portfolio, News), the account sheet, the command palette, and an asset-detail push. Save to `/tmp/aptrade-final-*.png`.
Expected: every surface fits the iPhone screen with the gold-on-black identity intact and a native feel. Visual quality judgment is the user's.

- [ ] **Step 5: Report**

Write a short proof note (both regression results, screenshot paths + what's visible, macOS-identical confirmation). No commit (verification only); ensure `git status` is clean apart from the gitignored `APTrade.xcodeproj` / `.build`.

---

## Self-Review

**1. Spec coverage**

| Spec section | Task |
|---|---|
| Navigation shell — iOS `TabView`, 3 tabs, per-tab `NavigationStack` | Task 1 |
| Account drawer → sheet; palette as sheet; search toolbar button | Task 1 (sheets) + Task 2 (`iosTopChrome` search/account buttons) |
| Top switcher omitted on iOS; macOS keeps it | Task 1 (optional `switcher`) + Tasks 2–3 (header branches) |
| Drop `minWidth: 560` floors on iOS | Task 1 (RootView), Task 2 (Watchlist), Task 3 (Portfolio + News), Task 4 (AssetDetail) |
| Watchlist compact header reflow | Task 2 |
| Portfolio compact header reflow | Task 3 |
| News compact treatment (incl. per-symbol company news via AssetDetail) | Task 3 (News) + Task 4 (AssetDetail) |
| AssetDetail fits compact width | Task 4 |
| Hover→swipe row actions | Task 2 |
| Wordmark shrinks / moves into nav chrome | Task 2 (`iosTopChrome` brand mark) |
| macOS visually identical | Global Constraints + every task's `#if os(macOS)` gating |
| 190/190 both platforms; visual sweep | Task 5 |

No gaps. Out-of-scope items (iPad layout, `.fileExporter`, new features) are correctly excluded.

**2. Placeholder scan:** Concrete code shown for every edit (the modifier file, the RootView shell, each gated header branch, swipe actions). The only "find the existing code" instructions (rename `macBody`, extract `resetMenu`, audit AssetDetail) reference exact files/lines and say to preserve behavior verbatim. No TBD/TODO.

**3. Type consistency:** `switcher: AnyView?`, `onOpenSearch`/`onOpenAccount: (() -> Void)?` are defined once in Task 1 and consumed identically in Tasks 2–3. `iosTopChrome(onSearch:onAccount:)` is defined in Task 2 Step 1 and called with the same labels in Tasks 2–3. SF Symbols (`eye`, `chart.pie`, `newspaper`) and `tr(.watchlist)/.portfolio)/.news)` match the existing `Tab`/L10n usage. macOS call sites `WatchlistView(switcher: AnyView(switcher))` remain valid against the new optional param.
