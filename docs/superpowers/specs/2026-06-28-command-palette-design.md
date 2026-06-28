# Command Palette (⌘K) — Design

**Date:** 2026-06-28
**Status:** Approved (design); pending implementation plan
**Feature 2 of 3** in the "elevate the experience" arc (Risk/Perf done → Command Palette → News).

## Goal

Add a universal ⌘K command palette to APTrade: fuzzy-search any asset and jump straight to
its detail view, plus a couple of static navigation shortcuts. The single biggest "felt"
upgrade available right now — the kind of thing that makes a desktop app feel like
Linear/Raycast — built almost entirely on infrastructure (`SearchAssetsUseCase`, the
existing overlay-panel convention) that already exists.

## Scope

In scope:

- Fuzzy-search stocks/ETFs/crypto by symbol or name, reusing the existing
  `SearchAssetsUseCase` (already powers watchlist autocomplete).
- Selecting an asset result navigates straight to its `AssetDetailView` — no side effect
  (does **not** add it to the watchlist; this is the key behavioral difference from the
  existing watchlist search-and-add flow).
- Two static navigation shortcuts: "Go to Watchlist", "Go to Portfolio".
- Invocation: ⌘K (global within the window) and a visible search-icon button in the header.
- Empty query shows the two nav shortcuts; typing replaces the list with fuzzy-matched
  results (nav shortcuts stay matchable by their label too).
- Full keyboard interaction: ↑/↓ to move selection, Return to activate, Escape to close.

Explicitly out of scope (decided during scoping, not oversights):

- **Mutating action commands** (Buy/Sell, Export, Reset, Theme toggle). Deliberately
  deferred — v1 is search-and-navigate only, the smallest safe slice. Could be a v2 if this
  proves useful.
- **Recent searches / persistence.** Ephemeral tool; no state survives a close.
- **Unifying navigation architecture.** See "Key architectural decision" below — the palette
  works around the existing per-tab `NavigationStack`s rather than replacing them.

## Key architectural decision: isolated modal navigation

`RootView` does not own a shared `NavigationStack` — `WatchlistView` and `PortfolioView`
each own their own, with their own private `selectedAsset: Asset?` +
`.navigationDestination(item:)`. The palette lives at the `RootView` level, so it cannot
push onto either tab's stack directly without new plumbing.

**Decision:** the palette does not integrate with either tab's stack. Selecting an asset
closes the palette and presents `AssetDetailView` wrapped in a **fresh, independent**
`NavigationStack`, via `.sheet(item:)` on `RootView`. Selecting a nav shortcut just flips
`RootView`'s existing `tab` state. This requires **zero changes** to `WatchlistView.swift`
or `PortfolioView.swift`.

Rejected alternative: promoting a single shared `NavigationStack` to `RootView` and
threading `selectedAsset` down as a binding into both tabs. More architecturally "correct"
long-term (one nav stack per window is the typical SwiftUI recommendation), but it's a real
refactor of two working, tested files for a feature that doesn't require it — disproportionate
scope creep for this slice. Worth revisiting only if a future feature needs the tabs to share
navigation state for some other reason.

## Architecture

### Presentation layer only — no Domain or Application changes

This feature adds zero business logic. It orchestrates one existing use case
(`SearchAssetsUseCase`) and two pieces of static, app-local data (the nav shortcuts). No new
Domain entities, no new Application use cases, no new Infrastructure code.

- **`Sources/APTradeApp/CommandPaletteViewModel.swift`** (new) — `@MainActor @Observable`:
  - State: `query: String`, `results: [PaletteResult]`, `selectedIndex: Int`.
  - `private let searchAssets: SearchAssetsUseCase` (injected, existing type — no new port).
  - `updateQuery(_:)`: debounces 250ms (mirrors `WatchlistViewModel.updateQuery`'s existing
    pattern), cancels any in-flight search task on each keystroke. Empty query → `results` =
    the two static nav results. Non-empty query → static nav results whose label
    case-insensitively contains the query, followed by `searchAssets(query:)`'s asset
    results, combined into one list. While a debounced fetch is in flight, the previous
    `results` stay visible (no flicker).
  - `moveSelection(_ delta: Int)`: clamps `selectedIndex` into `results.indices` (no
    wraparound).
  - `activateSelected() -> PaletteResult?`: returns `results[selectedIndex]` if in range.
  - `reset()`: clears `query`, `results`, `selectedIndex` — called on close so the next open
    starts fresh.

- **`Sources/APTradeApp/CommandPaletteView.swift`** (new) — the overlay UI:
  - `PaletteResult: Identifiable`: `case navigate(label: String, icon: String, destination: PaletteDestination)`,
    `case asset(Asset)`. `id`: the label for `.navigate`, the symbol for `.asset`.
  - `PaletteDestination: Equatable { case watchlist, portfolio }` — palette-local, so this
    file never references `RootView.Tab` (keeps the unit understandable without reading
    `RootView`'s internals).
  - Dimmed backdrop (`Color.black.opacity(0.45).ignoresSafeArea()`, same convention as
    `RootView`'s existing account-panel overlay) behind a top-anchored floating panel
    (~520pt wide, rounded, `Theme.surface` background) containing a search `TextField` and a
    scrollable result list below it.
  - `.onKeyPress(.upArrow)` / `.onKeyPress(.downArrow)` on the text field call
    `viewModel.moveSelection`; `.onKeyPress(.return)` calls `activateSelected()` and invokes
    `onSelect`; `.onKeyPress(.escape)` calls `onClose`. Clicking the backdrop also calls
    `onClose`. Clicking a row activates that row directly (independent of `selectedIndex`).
  - Each result row: an asset icon/kind chip for `.asset`, a system-symbol icon for
    `.navigate`; the row at `selectedIndex` gets a highlighted background.
  - Zero non-default results on a non-empty query renders a single quiet "No matches" row.
  - Interface: `CommandPaletteView(viewModel:, onSelect: (PaletteResult) -> Void, onClose: () -> Void)`.

### RootView wiring (modified)

- `@State private var showPalette = false`.
- `@State private var paletteVM = CommandPaletteViewModel(searchAssets: ...)` (factory added
  to `CompositionRoot`, mirroring its other `make...` factories).
- `@State private var paletteAsset: Asset?` — separate from either tab's own `selectedAsset`.
- A hidden button carrying `.keyboardShortcut("k", modifiers: .command)` toggles `showPalette`
  (true regardless of which tab is active, since it's a sibling in `RootView`'s view tree,
  not nested inside either tab).
- A visible search-icon button in the header (next to the existing theme-toggle/account-menu
  buttons) does the same.
- `CommandPaletteView` is rendered as a top-level overlay in `RootView`'s existing `ZStack`,
  conditionally on `showPalette`, with:
  - `onSelect`: for `.navigate(_, _, .watchlist)` → `tab = .watchlist`; `.portfolio` →
    `tab = .portfolio`; for `.asset(let asset)` → `paletteAsset = asset`. Either case then
    sets `showPalette = false` and calls `paletteVM.reset()`.
  - `onClose`: `showPalette = false`, `paletteVM.reset()`.
- `.sheet(item: $paletteAsset) { asset in NavigationStack { AssetDetailView(asset: asset) }.toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { paletteAsset = nil } } } }`
  added alongside `RootView`'s existing `.sheet`/`.alert` modifiers.

## Data flow

⌘K or header button → `showPalette = true` → empty-state nav list renders immediately →
user types → `paletteVM.updateQuery` debounces → `SearchAssetsUseCase` call (existing
infrastructure call, existing error handling: failures resolve to an empty asset list,
matching the existing autocomplete's `(try? await ...) ?? []` pattern) → combined results
render → arrow keys / mouse select a row → Return or click → `onSelect` → `RootView` updates
`tab` or `paletteAsset` → palette closes and resets.

## Error handling

- Search failures are swallowed to an empty result set, exactly like the existing watchlist
  autocomplete (`(try? await searchAssets(query:)) ?? []`) — a transient network blip just
  means fewer results, never a crash or visible error state.
- Empty/whitespace-only query never calls the network (matches `SearchAssetsUseCase`'s
  existing trim-and-guard).
- `selectedIndex` is always clamped against `results.indices`; `activateSelected()` returns
  `nil` (no-op) if `results` is empty, so Return on an empty list does nothing.

## Testing

- `CommandPaletteViewModelTests` (new, `Tests/APTradeAppTests/`): empty query yields the two
  nav results; a query matching a nav label's substring includes that nav result; a query
  triggering `searchAssets` includes its returned assets in the combined list; a failing
  search resolves to just the (possibly filtered) nav results, not a crash;
  `moveSelection`/`activateSelected` clamp correctly at both ends and on an empty list;
  `reset()` clears all three state fields. Uses a stub `MarketDataRepository` matching the
  existing `FixedRepo`-style test double already used in `WatchlistViewModel`/
  `PortfolioViewModel` tests.
- `CommandPaletteView`/`RootView` wiring is presentation glue with no independent logic —
  verified by build + manual click-through (⌘K opens it, header button opens it, typing
  filters, arrow keys move selection, Return/click navigates correctly for both an asset and
  a nav shortcut, Escape/backdrop-click closes it), the same verification approach used for
  the previous feature's final UI-wiring task.

## Build/run/test notes

- `swift test` requires `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` (project
  memory) or XCTest is missing.
- `.onKeyPress` requires macOS 14+, which is already this project's deployment target
  (`Package.swift`: `.macOS(.v14)`) — no platform-availability guard needed.

## Out-of-scope follow-ups (tracked for later)

- Action commands (Buy/Sell/Export/Reset/Theme toggle) as a v2 if search-and-navigate proves
  useful.
- Recent/frecency-ranked results if the static two-shortcut list ever grows.
