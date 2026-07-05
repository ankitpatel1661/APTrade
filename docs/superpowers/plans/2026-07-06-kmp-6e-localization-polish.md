# Plan: Increment 6e — Localization + chart/UX polish

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> to implement this plan task-by-task.

Branch: kmp-6e-localization-polish off main @ bba2a28 (incl. the 6e spec). Spec:
docs/superpowers/specs/2026-07-06-kmp-6e-localization-polish-design.md.
Baselines: shared 225 / android 34 / desktop 255 / Swift 200 / 3 slices. Parity source:
the macOS Swift app — implementers READ THE SWIFT FILES named per task and transcribe
exactly, as all prior shared work did.

**Goal:** Ship the desktop language switcher (EN/DE/IT/ES, full retrofit) plus three
polish fixes — the performance chart's flat-line artifacts, the indicator warm-up gap,
and the invisible-until-hover BUY/SELL buttons. Roadmap's final planned increment.

## Global Constraints
- Money amountText/BigDecimal; MONEY_MATH DecimalMode(38, HALF_AWAY_FROM_ZERO) on any
  division; CancellationException-first in every catch; Main-confined VMs (plain vars);
  Esc ownership chain; comment policy (contracts/divergences kept).
- commonMain framework-free apart from sanctioned Ktor; no java.time/AWT in :shared.
- Suites --rerun-tasks XML-counted (measured counts govern); DEVELOPER_DIR for Apple;
  the final task re-proves xcframework/Swift/iOS (:shared CHANGES — Tasks 1, 2, 4).
- JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; ./gradlew.
- HARD RULE: implementers work directly; no Agent-tool delegation; run commands in the
  plain foreground and read the output yourself.
- STANDING WAIVER (6a user ruling): UI composition ships without composable tests; pure
  logic still gets unit tests.

## Scope decisions (recorded)
- Perf chart: daily EOD resample, WEEKENDS SHOWN, for OneWeek/OneMonth/OneYear; OneDay
  keeps its intraday grid. 1W becomes ~7 daily points (accepted tradeoff).
- Indicator fix: keep the shared MarketDataRepository port UNBROADENED — move the
  window-clamp from the repository into the application layer, add a padded chart-window
  fetch; Android is untouched (still window-clamped via FetchCandles).
- BUY/SELL always visible at ~0.35 alpha → 1.0 on hover (small divergence from macOS
  hover-reveal, recorded).
- Language: FULL retrofit; catalog lives in :desktopApp only this increment (Android
  localization out of scope). English raw value is the fallback.

---

### Task 1: Performance chart daily resample (Workstream A)

**Files:** Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PerformanceResample.kt`;
Modify `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchPortfolioPerformance.kt`;
Test `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PerformanceResampleTest.kt` +
extend `shared/src/commonTest/.../application/FetchPortfolioPerformanceTest.kt`.

Read `domain/PortfolioPerformance.kt` (the `performanceSeries` union-grid + forward-fill)
and `application/FetchPerformanceReport.kt` (the benchmark twin uses `points.map { it.epochSeconds }`
as `curveDates` — so resampling `points` propagates to the twin automatically).

1. `resampledDaily(): List<PortfolioPerformancePoint>` extension: group points by UTC day
   `(epochSeconds / 86_400)`, keep the MAX-epochSeconds point in each day bucket (the day's
   closing value), return sorted ascending by epochSeconds. Pure, no clock access. Empty →
   empty; already-daily input unchanged; weekends kept (no trading-calendar filtering).
   KDoc why (kills the intraday overnight/weekend forward-fill flats + the benchmark
   staircase, since the twin rides the same dates).
2. In `FetchPortfolioPerformance.execute`, after building `series` (and after the
   sinceInception trim), apply `if (timeframe != Timeframe.OneDay) series = series.resampledDaily()`.
   OneDay keeps intraday. This is the single insertion point that fixes both the portfolio
   curve and the benchmark twin (verify via FetchPerformanceReport that the twin's
   curveDates come from these points).
3. Tests: resampledDaily — multi-intraday-points-one-day → that day's last value; multi-day
   → one point/day sorted; empty → empty; single-point day preserved; two points same day
   different values → later kept. FetchPortfolioPerformance — OneWeek input with intraday
   points collapses to one/day; OneDay input returns intraday untouched (fake repository
   returning known intraday PricePoints).

### Task 2: Indicator warm-up window (Workstream B)

**Files:** Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/ChartWindow.kt`,
`shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchChartWindow.kt`; Modify
`shared/.../infrastructure/YahooMarketDataRepository.kt` (candles: drop internal clamp),
`shared/.../application/FetchCandles.kt` (clamp here instead),
`shared/.../application/MarketDataRepository.kt` (KDoc the new candles contract — NO
signature change), desktop `detail/DetailViewModel.kt`, `detail/IndicatorOverlays.kt`
(PriceChartWithOverlays + CandleChartWithOverlays gain a visible-window clip),
`desktop/AppGraph.kt` (wire FetchChartWindow); Test shared
`FetchChartWindowTest.kt` + adjust `YahooMarketDataRepository`/`FetchCandles` candle tests
for the moved clamp.

Read `infrastructure/YahooMarketDataRepository.kt` candles (fetches wide `yahooRange` then
`clampToWindow(..., timeframe.windowDurationSeconds)`), `domain/Timeframe.kt`
(windowDurationSeconds), `detail/IndicatorOverlays.kt` (computeIndicators over the visible
list; drawSeries skips null warm-up prefix — this is why bands start mid-chart), and
whatever file defines `clampToWindow`.

1. MOVE the clamp: `YahooMarketDataRepository.candles` returns the RAW mapped candles (no
   clampToWindow). `FetchCandles.execute` now applies
   `clampToWindow(raw, timeframe.windowDurationSeconds) { it.epochSeconds }` — behavior
   byte-preserved for all its consumers (desktop Line-mode + Android). Move/adjust the
   candle-clamp assertions from the repository test to the FetchCandles test.
2. `ChartWindow(val candles: List<Candle>, val visibleStartIndex: Int)` domain type.
3. `FetchChartWindow(repository)` use case: `execute(symbol, timeframe): ChartWindow` —
   fetch raw candles, clamp to `windowDurationSeconds + PadSeconds` (PadSeconds =
   `26 * intervalSeconds(timeframe)` — 26 covers MACD/BB/SMA; add an `intervalSeconds`
   helper on Timeframe: OneDay 300, OneWeek 3600, OneMonth/OneYear 86_400), compute
   `visibleStartIndex` = count of clamped candles with `epochSeconds < (lastEpoch -
   windowDurationSeconds)` (0 when there aren't enough lookback bars). @Throws mirrors
   FetchCandles. Wire `val fetchChartWindow = FetchChartWindow(repository)` in desktop
   AppGraph.
4. Desktop DetailViewModel: replace the candle fetch with FetchChartWindow; state carries
   the full `candles` plus `visibleStartIndex` (default 0). computeIndicators runs over the
   FULL candles (warm-up satisfied).
5. IndicatorOverlays PriceChartWithOverlays + CandleChartWithOverlays: accept
   `visibleStartIndex`; map x over the VISIBLE range only — `stepX = width / (visibleCount - 1)`
   for line, `slot = width / visibleCount` for candles, with `fun x(i) = (i - visibleStartIndex) * step…`
   so indicator point i (computed over the full series) lands at its visible x; draw candles
   only for `i >= visibleStartIndex`; y-domain computed over the VISIBLE candles (+ visible
   Bollinger extremes) so the scale matches what's shown. Overlays now render fully-formed
   from the left edge. RSI/MACD sub-panes likewise clip to the visible slice.
6. Tests: FetchChartWindow — given a fake repo returning N raw candles, the returned window
   has visibleStartIndex > 0 and `candles.drop(visibleStartIndex)` equals the plain
   window-clamped set; too-few-bars → visibleStartIndex 0; PadSeconds covers ≥26 bars.
   Verify SMA/BB is non-null at visibleStartIndex when enough lookback exists
   (computeIndicators over the full window).

### Task 3: Always-visible BUY/SELL (Workstream C)

**Files:** Modify `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioPane.kt`
(HoldingRow ~357-397).

Read HoldingRow. Replace `if (hovered) { Row { TextButton("BUY"…) TextButton("SELL"…) } Spacer }`
so the BUY/SELL Row is ALWAYS present (space reserved, no price-column shift); drive their
color alpha from hover — `DK.gold.copy(alpha = if (hovered) 1f else 0.35f)`. Keep the
onTrade wiring and TradeSide args identical. RECORDED DIVERGENCE comment (macOS
hover-reveals; desktop shows a faint always-on affordance per user). No new tests
(composable-only, waiver); a launch-alive check confirms it renders.

### Task 4: L10n catalog + LocalizationManager (Workstream D foundation)

**Files:** Create `shared`? NO — desktop only:
`desktopApp/src/main/kotlin/com/aptrade/desktop/l10n/AppLanguage.kt`,
`desktopApp/.../l10n/L10n.kt`, `desktopApp/.../l10n/LocalizationManager.kt`; Test
`desktopApp/src/test/kotlin/com/aptrade/desktop/l10n/L10nCatalogTest.kt`.

Read `Sources/APTradeDomain/AppLanguage.swift` (enum english/german/italian/spanish,
rawValue en/de/it/es, `displayName` endonyms English/Deutsch/Italiano/Español) and
`Sources/APTradeApp/L10n.swift` (the `Key` enum — 205 cases, English raw values — and the
`table: [AppLanguage: [Key: String]]`, plus its catalog-completeness test).

1. `AppLanguage` enum transcribed verbatim (english/german/italian/spanish; `code`
   en/de/it/es; `displayName` endonyms).
2. `L10n` object: `enum class Key(val english: String)` with all 205 cases + English raw
   values VERBATIM from L10n.swift; `private val table: Map<AppLanguage, Map<Key, String>>`
   with the DE/IT/ES strings transcribed VERBATIM from the Swift `table` (English falls back
   to `key.english`). `fun string(key: Key, language: AppLanguage): String =
   table[language]?.get(key)?.takeIf { it.isNotBlank() } ?: key.english`.
3. `LocalizationManager`: object with `val current = mutableStateOf(AppLanguage.English)`
   (the DK.accent/DK.isDark Compose-state precedent — read DK.kt); top-level
   `fun tr(key: L10n.Key): String = L10n.string(key, LocalizationManager.current.value)`
   so every composable reading tr() recomposes on language change. Extraction-ready (no
   app-module imports beyond l10n/designkit).
4. Test (transcribe macOS's completeness test): every Key has a non-blank entry for all
   four languages (English via `.english`, others via `table`); `tr` fallback returns
   `key.english` when a table entry is missing/blank; count == the Swift catalog's count.

### Task 5: language persistence + functional Language page

**Files:** Modify `desktopApp/.../infra/FileSettingsStore.kt` (AppSettings + SettingsDTO
+ `language`), `desktopApp/.../Main.kt` (startup apply + selectLanguage via persistSettings),
`desktopApp/.../ui/AccountPanel.kt` (LanguagePage replaces the placeholder route); Test
`desktopApp/.../infra/FileSettingsStoreTest.kt` (language round-trip + back-compat).

Read FileSettingsStore.kt (AppSettings/SettingsDTO — isDarkMode is the field precedent),
Main.kt (startup LaunchedEffect + selectTheme/selectAccent + persistSettings seam),
AccountPanel.kt (AppearancePage THEME rows are the selection-row precedent; the
PlaceholderPage route for Language).

1. AppSettings + SettingsDTO grow `language: AppLanguage = AppLanguage.English` (serialize
   as its `code`; lenient back-compat — keyless file → English, test-pinned like isDarkMode).
2. Main.kt startup applies `LocalizationManager.current.value = loaded.language`;
   `selectLanguage(lang)` mirrors selectTheme — `LocalizationManager.current.value = lang;
   persistSettings { it.copy(language = lang) }` (the ONE persist seam; no second path).
3. LanguagePage (replaces the placeholder): the four languages listed with the AccentRow/
   ThemeRow selection anatomy, each showing its `displayName` endonym; tap selects +
   persists + live re-renders. Placeholder routing now empty (every account page functional).
4. Tests: settings round-trip incl. language; keyless-JSON back-compat → English (real file
   fixture per Task-2/6d.2 style).

### Task 6: retrofit — navigation, watchlist, portfolio

**Files:** Modify desktop `Main.kt` / `AppShell` (tabs), `watchlist/WatchlistPane.kt` (+ any
watchlist strings), `portfolio/PortfolioPane.kt`, `portfolio/PerformanceSection.kt`,
`portfolio/TradeDialog.kt`, `portfolio/TradeConfirm.kt` (+ related portfolio composables).

Convert every user-facing hardcoded string in these files to `tr(L10n.Key.…)`, matching the
macOS Key for each (read L10n.swift to find the Key whose English rawValue equals the
string). For any desktop-only string with no macOS Key (e.g. a confirm-layer label added in
6d.2), ADD a Key + its four translations to L10n.kt (English verbatim from the current UI;
DE/IT/ES from the macOS equivalent if one exists, else a faithful translation) and note each
addition in the report. Non-user-facing strings (log/test/format specifiers) stay literal.
Verify build green; the catalog test still passes (new keys covered).

### Task 7: retrofit — detail, news, alerts

**Files:** Modify desktop `detail/DetailPane.kt` + detail composables, `detail/IndicatorOverlays.kt`
(indicator chip labels — only if user-facing and macOS has Keys), the News tab composables,
and the alerts UI (`watchlist/PriceAlertSheet.kt`, Notifications page bits, bell tooltips).

Same rule as Task 6: string → `tr(Key)` against the macOS Key; add Keys + 4 translations for
desktop-only strings, reported. Indicator labels (SMA 20 etc.) and MACD legend: localize only
if macOS localizes them — otherwise leave as-is and note the decision. Verify build + catalog
test green.

### Task 8: retrofit — settings/account pages

**Files:** Modify `desktopApp/.../ui/AccountPanel.kt` (all pages: Root nav rows, Appearance,
Notifications, SecurityPrivacy, Profile, AccountSettings, Help, Language, About) + any
section/row labels.

Convert every page's strings to `tr(Key)` (these map cleanly — most macOS Keys came from
these pages: watchlist/portfolio/news/appearance/theme/dark/light/security toggles/profile
fields/help rows/etc.). Confirm the Language page's own header/rows localize. Verify build +
catalog test green; a launch-alive check that switching language re-renders the whole panel.

### Task 9: full regression + docs + live run

Mandatory Apple re-proof (Tasks 1 and 2 changed :shared — the resample fn, ChartWindow, and
the moved candle clamp; Task 4's catalog is desktop-only): xcframework 3 slices + swift test
200 + iOS ARCHS=arm64 + all gradle suites XML-counted (shared/android/desktop). LIVE RUN
≥90s: switch language (whole app re-renders EN→DE→IT→ES, restart persists); confirm the perf
chart is flat-free on 1W/1M; indicator bands span the full visible width; BUY/SELL faintly
visible then bright on hover. README: a localization + polish paragraph (divergences: daily
resample vs intraday, always-on BUY/SELL, catalog desktop-only); roadmap CLOSE-OUT — 6e ships
and "Still to come" becomes EMPTY (macOS parity + localization complete). SKILL.md check.
Docs-only commits beyond what verification requires; any regression failure = BLOCKED.

- Coverage: perf resample → T1; indicator window → T2; buttons → T3; catalog/manager → T4;
  persistence/Language page → T5; retrofit → T6-T8; proof/docs → T9.
- Known unknowns w/ guardrails: exact DE/IT/ES strings (T4 transcribes from L10n.swift
  verbatim, completeness test guards); which strings macOS localizes vs leaves (T6-T8 read
  L10n.swift Keys — if no Key exists, it's a desktop addition, reported); PadSeconds
  sufficiency (T2 test asserts ≥26 bars); benchmark-twin daily propagation (T1 verifies via
  FetchPerformanceReport's curveDates).
