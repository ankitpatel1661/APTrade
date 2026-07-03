# KMP Increment 6a.5 — Windows Desktop Fidelity Pass (Design)

**Date:** 2026-07-03
**Status:** Approved (design presented and approved in-session; spec is its transcription)
**Predecessor:** `2026-07-03-kmp-windows-6a-design.md` (merged to main at 7889208).

## Context

The user's side-by-side check of the shipped 6a desktop app against the macOS app produced
three change requests before 6b (portfolio) begins:

1. Only the Watchlist tab renders in the shell — Portfolio/News are missing from the tab row.
2. The right-hand detail pane is not how macOS presents detail.
3. A general "make it read closer to macOS" sweep.

Investigation results that shape this spec:

- **Tab bug root cause (diagnosed):** in `AppShell.kt`'s `TabRow`, each tab's underline `Box`
  uses `fillMaxWidth()` inside an unweighted `Row` child; the first tab column expands to the
  full row width and pushes Portfolio/News off-screen.
- **macOS ground truth has TWO "open" behaviors** (from `WatchlistView.swift`):
  a row click sets `selectedAsset` → `navigationDestination` pushes `AssetDetailView`
  **full-window**; separately, the header's average-change sparkline click expands an
  **inline `ExpandedValueCard`** in the content flow (full width, hover crosshair, close
  button, `.move(edge: .top) + .opacity` transition). The user chose **both, exactly like
  macOS**.

## Goal

The desktop app's Watchlist tab reads and behaves like the macOS app: correct three-tab
shell, full-window detail navigation, and the macOS header (average day change +
click-to-expand inline chart) — verified live by the user side by side.

## Scope decisions (locked)

1. **Tab row fixed**, all three tabs visible; Portfolio/News still open their placeholder
   panes (their content is 6b/6c).
2. **Full-window detail navigation replaces the master–detail split.** Screen state in the
   shell: `Watchlist | Detail(symbol)` — plain Compose state, no navigation library for a
   one-level push. Row click and palette-Enter route through one `onOpenDetail(symbol)`;
   back chevron and Esc return. The existing `DetailPane` content is rehosted as the
   full-window `DetailScreen`, keeping its per-selection ViewModel + scope-cancelled-on-
   dispose lifecycle unchanged. The watchlist list takes the full window width.
3. **Header parity:** 34sp average-change figure in `DK.changeColor` + "Avg day change"
   label (13sp `textSecondary`), `PulseBar` beneath, 140×36 sparkline right-aligned.
   Sparkline click expands an inline **ExpandedValueCard** (DesignKit component,
   extraction-ready): full-width line chart with hover crosshair; the crosshair index
   drives a headline value plus its delta from period start (percentage-points style,
   matching macOS `changeStyle: .percentagePoints`); close button collapses it. Animated
   expand/collapse (top-slide + fade).
4. **ViewModel additions:** `WatchlistUiState` gains `averageChange: Double?` (mean change%
   across ALL entries, null when no quotes) and `averageSpark: List<Double>` (per-index mean
   of row sparklines, tolerant of missing/short series: average over the series that have a
   value at that index; empty when fewer than 2 usable points). Computed in `publish()`.
5. **Sweep items folded in:** header horizontal padding 24dp, list insets 16dp, kind-switch
   and expand animations, empty-state parity where cheap. Nothing needing new domain logic.
6. **Out of scope:** Portfolio/News content, alerts (bells/sheet), account panel, light
   theme, localization, Android/Swift changes, any `:shared` change.

## Architecture notes

- No `:shared` changes at all this increment — everything is `:desktopApp`.
- Crosshair math is a pure function (index picking from pointer x / chart width + value/delta
  formatting) so it is unit-testable without UI.
- `ExpandedValueCard` lives in `designkit` (no `com.aptrade.desktop.*` imports beyond
  designkit itself), consistent with the extraction-ready rule.
- Navigation state lives in the shell (`AppShell`/`Main`), not in ViewModels; DetailViewModel
  lifecycle rules from 6a are preserved verbatim.

## Testing

- TDD for: `averageChange`/`averageSpark` derivations (mismatched spark lengths, empty,
  single-symbol cases) in `WatchlistViewModelTest`; crosshair index/delta pure functions in
  a designkit test.
- Existing suites stay green: shared 51, androidApp 13, Swift 193; desktopApp grows from 40.
- Live verification: user runs the app, checks tabs, row→full-window detail→back (chevron
  and Esc), header expand/collapse with crosshair, palette navigation.
- CI `windows-desktop` stays green on merge.

## Risks

- Hover/crosshair uses Compose desktop pointer-move events — well supported on JVM; keep the
  math pure and the composable thin.
- The 6a final review noted the add-field Enter/selection-highlight Minor; NOT expanded here
  (still deferred) to keep this increment small.
