# Increment 6e — Localization + chart/UX polish (design)

**Status:** approved design, pre-plan.
**Date:** 2026-07-06.
**Base:** main @ 7373d23 (post-6d.2; macOS settings parity complete).
**Baselines:** shared 225 / android 34 / desktop 255 / Swift 200 / xcframework 3 slices.

## Goal

Close the roadmap's final planned increment: the desktop **language switcher**
(EN/DE/IT/ES, full retrofit) plus three UI/data polish fixes the user flagged from a
live look at the running app — the portfolio performance chart's flat-line artifacts,
the indicator warm-up gap, and the invisible-until-hover BUY/SELL buttons.

Parity source throughout: the macOS Swift app. Implementers READ the named Swift files
and transcribe exactly, as all prior shared work did.

## Origin

User ran the post-6d.2 build and raised four items (screenshots in-session):
1. Portfolio performance chart shows long horizontal runs and a stair-stepped benchmark.
2. Bollinger/SMA (and other indicator) overlays only render across the right half of
   the chart.
3. Portfolio holding-row BUY/SELL are invisible until the cursor is over the row.
4. Bundle in the deferred desktop language switcher "so everything is fixed in one go."

Two decisions taken during brainstorming:
- Performance chart: **daily end-of-day grid, weekends shown** (for spans wider than 1D).
- Language: **full retrofit** — localize the entire desktop user-facing string surface.

---

## Workstream A — Performance chart daily resample (issue 1)

### Diagnosis (verified in code)
`Portfolio.performanceSeries(histories)`
(`shared/.../domain/PortfolioPerformance.kt`) builds its time axis from the **union of
every held symbol's candle timestamps**, then forward-fills each symbol's last close
between its own candles. `benchmarkTwinSeries` (`.../domain/BenchmarkTwin.kt`) is then
evaluated on those same `curveDates` via `closeAt` (also forward-fill).

For the 1W span, equities are fetched as **hourly** bars that exist only during market
hours (`Timeframe.OneWeek → range "1mo", interval "60m"`), while **crypto (BTC) trades
24/7**. The union grid is therefore dominated by BTC's round-the-clock points; on every
night/weekend slot the equities and the (equity) benchmark have no candle and hold their
last close flat → the long horizontal runs, with a step at each session reopen. SPY is
the worst case (a lone equity) → the staircase. The far-right flat is the current weekend.

1M/1Y already fetch daily candles (`interval "1d"`), so their artifact is milder — only
the weekend forward-fill (equities flat Sat/Sun while BTC moves).

### Fix
Add a pure domain function that **resamples the performance series to one point per UTC
calendar day**, keeping each day's **last** point (its closing value):

```
fun List<PortfolioPerformancePoint>.resampledDaily(): List<PortfolioPerformancePoint>
// group by (epochSeconds / 86_400); keep the max-epoch point in each day-bucket;
// result sorted ascending by epochSeconds. Pure, no clock access.
```

- Applied in `FetchPortfolioPerformance.execute` for **OneWeek / OneMonth / OneYear**
  (and MAX, which is the 1Y backtest); **NOT** for OneDay (intraday grid preserved).
- Resample runs on the performance series BEFORE the benchmark twin is computed, so the
  twin inherits the daily `curveDates` and the SPY/QQQ/VTI staircase disappears with it.
  (Verify the call order at the benchmark's construction site — `FetchPerformanceReport`
  / `PortfolioViewModel` — and ensure the resampled dates feed `benchmarkTwinSeries`.)
- Weekends are **kept** (user decision): a crypto holding's real weekend movement stays
  honest; equities show one flat daily step.
- Consequence to accept: **1W renders ~7 daily points** (coarser than today's hourly
  line, but flat-free). This is the "daily EOD" tradeoff the user chose.

`performanceSeries` itself is unchanged (stays a faithful Swift transcription); resampling
is a separate, independently testable step. macOS adoption of the resample is NOT assumed
— this is a desktop-layer refinement (the benchmark twin is already desktop-first with
macOS adoption undecided; the resample rides at the same layer).

### Tests
Pure-function tests on `resampledDaily`: multiple intraday points on one day collapse to
that day's last value; multi-day input keeps one point per day sorted; empty → empty;
already-daily input is unchanged; a day with a single point is preserved. Plus a test that
`FetchPortfolioPerformance` resamples for OneWeek/Month/Year but returns the intraday grid
untouched for OneDay (fake repository returning intraday points).

---

## Workstream B — Indicator warm-up padding (issue 2)

### Diagnosis (verified in code)
`computeIndicators(candles, selection)` (`desktopApp/.../detail/IndicatorOverlays.kt`)
runs each indicator over exactly the **visible** candle window. SMA 20 / BB 20 emit
`null` for the first 19 indices by definition (correct math), which `drawSeries` correctly
skips — so the bands only begin at bar 20. On a 1W hourly window (~35 in-session bars) the
warm-up eats roughly the left half; same effect on every span.

Key simplification found: the repository **already over-fetches**. `history`/`candles`
request `timeframe.yahooRange` (e.g. "1mo" for a 1W view) then `clampToWindow` to the
visible window BEFORE returning — so a month of hourly lookback is fetched and discarded.

### Fix
Keep a **lookback prefix** instead of discarding it, and render only the visible window:
- Introduce a chart-data carrier that exposes the fuller candle series plus the index
  where the visible window begins, e.g.:
  ```
  data class ChartWindow(val candles: List<Candle>, val visibleStartIndex: Int)
  ```
  `candles` = at least `PadBars` (≥26, covering MACD/BB/SMA warm-up) before the visible
  window through the end; `visibleStartIndex` = first visible bar.
- The repository (or a thin application step) produces the padded window: reuse the
  already-fetched wider range; clamp to `windowDuration + PadBars` worth of lookback
  rather than exactly the window. Exact mechanism decided in the plan; no extra network
  call is needed for spans whose `yahooRange` already exceeds the window (1W does).
- `computeIndicators` runs over the full `candles`; the price/candle renderers map the
  x-axis over `[visibleStartIndex, lastIndex]` and draw candles only for the visible
  slice, so overlays are fully warmed at the left edge and candle geometry is unchanged
  for the visible range.
- Verify macOS behavior (`AssetDetailView.swift`) for reference; the fix ships regardless
  since the current desktop behavior is the reported defect.

### Tests
Pure-logic tests: given a padded candle list + `visibleStartIndex`, the indicator series
has non-null SMA/BB values at the first visible index (warm-up satisfied); the visible
candle count equals `candles.size - visibleStartIndex`; `PadBars` sufficiency for the
longest period. Renderer x-mapping is waiver territory (visual), covered by the live run.

---

## Workstream C — Always-visible BUY/SELL (issue 3)

### Diagnosis (verified in code)
`HoldingRow` (`desktopApp/.../portfolio/PortfolioPane.kt:391`) renders the BUY/SELL row
inside `if (hovered) { … }`, so there is zero affordance off-hover and the price column
shifts when they appear.

### Fix
Always render BUY/SELL; drive **opacity** from hover: ~0.35 alpha at rest, 1.0 on hover
(apply to the button content/label colors; keep the row present so layout space is
reserved and the price column no longer shifts). Small, deliberate divergence from macOS
(which hover-reveals) — recorded. No new tests (composable-only, waiver); covered by the
live run.

---

## Workstream D — Language switcher (full retrofit)

### Parity source
`Sources/APTradeApp/L10n.swift` — a complete typed catalog: `enum L10n.Key: String`
(205 keys; each English raw value is the last-resort fallback) + a `table` holding
translations for all four languages, guarded by a catalog-completeness test.
`Sources/APTradeDomain/AppSettings.swift` has `language: AppLanguage` (english/german/
italian/spanish); a `LocalizationManager` singleton holds the current language and drives
live re-render.

### Fix
Faithful transcription + mechanical retrofit:
1. **Catalog** — `L10n` in the desktop app: a `Key` enum (205 cases, English raw values
   verbatim from the Swift source) and a `table: Map<AppLanguage, Map<Key, String>>` with
   the DE/IT/ES strings transcribed verbatim from the Swift `table`. English falls back to
   the key's raw value.
2. **AppLanguage** — enum (English/German/Italian/Spanish) transcribed from the Swift
   `AppLanguage`, including its display-name/rawValue mapping.
3. **LocalizationManager** — a Compose-observable holder modeled on `DK.accent`/`DK.isDark`
   (`mutableStateOf(AppLanguage)`); a top-level `tr(key: L10n.Key): String` reads it, so
   every consumer recomposes on language change. Lives in designkit or a peer localization
   package, extraction-ready (no app-module imports).
4. **Persistence** — `AppSettings`/`SettingsDTO` grow `language: AppLanguage` (default
   English; lenient back-compat, test-pinned like isDarkMode). Startup applies the loaded
   language; the Language page selection persists through the existing `persistSettings`
   seam (mirrors `selectTheme`/`selectAccent`).
5. **Language page** — the current `PlaceholderPage` route becomes functional: the four
   languages listed with the AccentRow/ThemeRow selection anatomy, tap selects + persists
   + live re-renders; native language names per macOS.
6. **Retrofit** — convert **every** user-facing desktop string to `tr(Key)` across
   navigation, watchlist, portfolio, detail, news, alerts, and all settings/account pages.
   Reconcile desktop-only strings (e.g. the theme rows, confirm layer) against the catalog:
   any string not already a macOS `Key` gets a new `Key` + its four translations (English
   verbatim from current UI; DE/IT/ES transcribed if macOS has an equivalent, else added).
7. **Completeness test** — transcribe macOS's catalog-completeness test (every `Key` has a
   non-blank entry for all four languages).

### Tests
Catalog-completeness test (all 205+ keys × 4 languages non-blank); `tr` fallback to
English raw value; AppSettings back-compat (keyless file → English); language round-trip
persistence. UI retrofit is waiver territory, proven by a live run (switch language, whole
app re-renders; restart persists).

---

## Increment shape

- `:shared` grows (resample fn, chart-window carrier, and — decision for the plan —
  whether the L10n catalog lives in `:shared` commonMain for future Android reuse or in
  `:desktopApp` only for now; default **desktopApp-only** this increment, since Android
  localization is out of scope and the catalog is large). The final task re-proves the
  full Apple chain (xcframework 3 slices, Swift 200, iOS arm64).
- Estimated ~8–10 tasks: A (resample) · B (indicator window) · C (buttons, likely folded
  with B or A as a small task) · D split across catalog+manager+persistence, Language page,
  and the retrofit (probably 2–3 retrofit tasks by screen area) · final regression+docs.
- Standing waiver: UI composition without composable tests; pure logic still unit-tested.
- Roadmap: this is the **final planned increment**; on merge the roadmap's "Still to come"
  is empty (macOS parity + localization complete).

## Non-goals
- Android localization (catalog stays desktop-only; Android adoption is a future item).
- macOS adopting the daily resample (desktop-layer refinement; not backported this round).
- Changing the intraday 1D performance grid or the candle intervals themselves.
- Real re-authentication behind confirmTrades / the persisted-unwired security toggles
  (unchanged from 6d.2's honest-parity stance).
