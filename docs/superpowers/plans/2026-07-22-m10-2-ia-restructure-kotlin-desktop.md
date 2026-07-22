# M10.2 — IA Restructure: Kotlin Windows Desktop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the IA restructure on Windows desktop — Home dashboard, sidebar rail with the four destinations, Alerts center, conditional master–detail for Watchlist/Screener, and the tool re-homing — transcribed from the **Swift as-built on `main` (950372f), INCLUDING the UAT polish wave** (conditional split, rich hero chart, palette without navigate rows, allocation ratio, income tooltips, invest-nav teardown are all part of the reference).

**Architecture:** Spec = `docs/superpowers/specs/2026-07-22-ia-restructure-design.md`; mockup = `2026-07-22-ia-restructure-mockup.html` (Desktop frame). Per the M10.2 carry-notes (final-review §1–6, recorded in `.superpowers/sdd/progress.md`): the HomeFeed aggregation moves to **shared commonMain** so M10.3 Android reuses it; desktop adds ViewModel + panes + shell. Swift stays platform-local reference. DO NOT touch Swift/`Sources/`; `shared/` only where a task explicitly says so.

**Tech Stack:** Kotlin, Compose Desktop, kotlinx-coroutines, JUnit (`./gradlew :shared:jvmTest :desktopApp:test`).

## Global Constraints (carry-notes, binding)

1. **Invariants to re-earn** (all test-pinned in Swift; your Kotlin tests must pin them too): single **sequential** Home refresh loop (one coroutine, `while (isActive) { refresh(); delay(15_000) }`, overlapping refreshes impossible); day-only values are **LocalDate end-to-end, never round-tripped through Instant** (ET only for market-hours transition instants); per-source isolation with **CancellationException rethrown first**, a failing source drops only its row; `-USD` suffix → crypto kind in the alerts-center asset fallback; screener freshness = same `tradingDay` on the shared calendar with match count from the **first preset** (no last-screen persistence exists); movers over holdings ∪ watchlist deduped, loser suppressed when only one quoted symbol; feed order fixed: status → gainer → loser → earnings → dividend → screener.
2. **HomeIncomeSummary WRAPS the existing income pipeline** (receivedYTD + first upcoming) — never recompute dividend math.
3. **No request/clear navigation dance on desktop** — sidebar clicks and Home-row navigation write the sidebar selection state directly (the Swift I-1 lesson: request patterns miss lazy first mounts).
4. **Hero tap → Portfolio is spec** — do not transcribe its absence (Swift gained it in the fix wave).
5. **Single-source preset display titles** — do not copy Swift's recorded presetDisplayName duplication; desktop already has the mapping in ScreenerPane — hoist and reuse, one definition.
6. **Re-derive the window min-width from REAL desktop column widths** with a derivation comment (Swift's 1120 = 208 + 520 + 1 + ~390 — do not copy the number).
7. Do NOT transcribe Swift's dead keys (goToWatchlist, goToPortfolio, settingsDrip, settingsDripFooter, quickNewsFmt); pick up the **corrected DE strings** ("veröffentlicht Quartalszahlen", "Bruchteilsaktien").
8. Alert-count semantics: the Home alerts entry counts **non-triggered ("armed") alerts only** — the settled answer to Swift's recorded `alertsActiveFmt` mismatch; comment it as the cross-platform decision (Swift backports later).
9. House rules: `tr()`/`trf()` all copy; DK tokens only; gains green/losses red = data only; portfolio mutex untouched (nothing here trades); UI composition ships without unit tests (standing waiver) — ViewModels/assembler carry behavior; every task ends `./gradlew :shared:jvmTest :desktopApp:test` green + `:androidApp:testDebugUnitTest` untouched-green where shared changed; branch `feature/m10-2-ia-restructure-desktop`.

---

### Task 1: Shared HomeFeed core + L10n keys

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/HomeFeed.kt` — sealed `HomeFeedItem` (MarketStatus(open, nextTransition), TopGainer(symbol, pct), TopLoser(symbol, pct), Earnings(symbol, session, day: LocalDate), Dividend(symbol, amount: Money, day: LocalDate), ScreenerFresh(presetName-key-or-id, matches)); `HomeFeedAssembler` with injected suspend sources (portfolio snapshot, quotes fetcher, own+watch symbols, income summary provider, earnings fetcher, market calendar + clock, screener snapshot loader, alert counter) exposing `suspend fun refresh(): HomeState` where `HomeState` carries totalValue/dayChange/dayChangePercent(zero-guarded)/cash/incomeYTD/alertCount/feed — per-source scratch + rebuild pattern for 1:1 isolation semantics
- Create: `shared/src/commonTest/kotlin/com/aptrade/shared/application/HomeFeedAssemblerTest.kt` — transcribe the SEMANTICS of all 16 Swift `Tests/APTradeAppTests/HomeViewModelTests.swift` cases (value sum; day-change sum; gainer/loser dedup across holdings∪watchlist; loser suppressed with 1 quoted symbol; per-source isolation incl. portfolio-failure-still-shows-status; open vs closed; stale-screener absent; no-dividend absent; fixed order; alertCount armed-only; spark/curve concerns stay OUT of the assembler — desktop reads the performance series separately like Swift does)
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/l10n/L10n.kt` — the M10 keys ×4 languages (Swift T1's 25 minus dead-listed, plus SidebarSearch/SidebarSettings; reuse existing keys where the catalog already has an equivalent — audit first) + bump `L10nCatalogTest` pin (365 → observed)

**Swift reference:** `Sources/APTradeApp/HomeViewModel.swift` AS-BUILT + its tests — semantics only; this is a Kotlin-first API, not a transcription of Swift's VM shape. Session labels reuse the existing `SessionAfterClose`/`SessionBeforeOpen` key family.

- [ ] **Step 1:** Failing tests RED (`./gradlew :shared:jvmTest --tests "*HomeFeed*"`). **Step 2:** Implement; filtered + full `:shared:jvmTest` PASS; `:desktopApp:test` + `:androidApp:testDebugUnitTest` untouched-green; `./scripts/build-shared.sh` native gate (commonMain changed → xcframework must still compile). **Step 3: Commit** `feat(shared): HomeFeed assembler — dashboard aggregation with per-source isolation`

---

### Task 2: Desktop HomeViewModel

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/home/HomeViewModel.kt` (thin: holds `MutableStateFlow<HomeState>`, `suspend fun refresh()` delegating to the assembler, injected scope per PlansViewModel shape)
- Create: `desktopApp/src/test/kotlin/com/aptrade/desktop/home/HomeViewModelTest.kt` (≥4: refresh publishes assembler state; error keeps previous state; CancellationException rethrows; sequential-loop helper if the VM owns it)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt` (assembler wired from EXISTING graph pieces: repository, marketCalendar, watchlist+portfolio stores, income pipeline (wrap — constraint 2), earnings fetch with ownSymbols, screenerSnapshotStore, alertStore; factory per makeScreenerViewModel precedent)

- [ ] **Step 1:** Failing tests RED. **Step 2:** Implement; `:desktopApp:test` full PASS. **Step 3: Commit** `feat(desktop): HomeViewModel over the shared assembler`

---

### Task 3: Sidebar shell + routing

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/ui/AppShell.kt` (REPLACE the wordmark header + TabRow with the left rail per the mockup Desktop frame: ~208dp, compact `BrandWordmark`, Home item, MARKETS/PORTFOLIO/INVEST group labels (micro-caps tertiary, tracking) with per-section items, footer `SidebarSearch` row (magnifier + "Ctrl+K" hint) + `SidebarSettings` + theme toggle; selection = DK.surfaceHi fill + 1dp gold(0.35) inset ring, 9dp radius; sealed `SidebarDestination { Home; Markets(MarketsSection); Portfolio(PortfolioSection); Invest(InvestSection) }` with enums `MarketsSection { Watchlist, Screener, Calendar, News }`, `PortfolioSection { Holdings, Allocation, Activity, Performance }`, `InvestSection { Plans, Income }` — label functions follow the file's existing plain-function-not-enum-property tr() idiom)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (selectedTab: AppTab → sidebarSelection: SidebarDestination, default Home; the `when` routes each destination/section to the existing panes — PortfolioPane slims to 4 sections (its private PortfolioSection loses Plans/Income; summary header + section content preserved), PlansPane/IncomePane render under Invest; lazy-start effects for News/Calendar re-keyed to the new selection; Ctrl+K + Esc handling unchanged; window minWidth re-derived per constraint 6 with comment)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioPane.kt` (section enum hoisted/public for the sidebar; Plans/Income cases removed — their pane hosting moves to Main's Invest routing; NOTHING inside PlansPane/IncomePane changes)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/search/PaletteOverlay.kt` + `SearchViewModel.kt` ONLY IF they carry navigate/tab rows — Swift as-built palette is symbol-search only; match it (report what existed)
- Test: extend existing shell/pane tests that pin AppTab (update intent-preserving)

**Trap (constraint 3):** Home-row navigation and palette write `sidebarSelection` directly. **Invest nav teardown:** section content keyed by section (`key(section) { … }`) so a pushed pie detail cannot survive a section switch — the Swift U5 lesson, applied preventively.

- [ ] **Step 1:** Implement. **Step 2:** full `:desktopApp:test` PASS; `./gradlew :desktopApp:run` boots to Home with the rail (kill after). **Step 3: Commit** `feat(desktop): sidebar rail — grouped destinations, corner brand, Ctrl+K footer`

---

### Task 4: HomePane

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/home/HomePane.kt`
- Modify: `Main.kt` (Home destination renders it; navigation closures write sidebarSelection)

**Swift reference:** `Sources/APTradeApp/HomeView.swift` `HomeViewMac` AS-BUILT (post-UAT): hero = microlabel + SuperscriptPrice-equivalent (MoneyText) total + change pill + **the rich P&L chart expanded by default with span bar/axes/hover crosshair — reuse `PerformanceSection`'s existing chart family (Charts.kt/ValueCard.kt), fed by the SAME portfolio performance series PortfolioPane uses**; hero click → Portfolio (constraint 4); quick stats row; two-column grid: Today card (feed rows from HomeState, each row a click-through; LocalDate days formatted directly — never via Instant) left, stats + Alerts card right; sequential 15s LaunchedEffect loop (constraint 1). UI waiver — behavior pinned by T1/T2 tests.

- [ ] **Step 1:** Implement. **Step 2:** full `:desktopApp:test` PASS; `:desktopApp:run` eyeball boot (kill after). **Step 3: Commit** `feat(desktop): Home dashboard — hero chart, Today feed, alerts card`

---

### Task 5: Desktop Alerts center

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/alerts/AlertsCenterDialog.kt` + `AlertsCenterViewModel.kt`
- Create: `desktopApp/src/test/kotlin/com/aptrade/desktop/alerts/AlertsCenterViewModelTest.kt` (transcribe the 10 Swift `AlertsCenterViewModelTests` semantics incl. the `-USD` crypto-kind fallback)
- Modify: the condition-summary source — desktop `watchlist/PriceAlertSheet.kt` has the per-condition text; EXTRACT to one shared internal helper both use (Swift T3 pattern); `AppGraph.kt` factory; `HomePane.kt` (bell/card opens the dialog); alert row click → opens detail via the existing openSymbol path

**Constraint 8 applies:** armed-only count feeding Home.

- [ ] **Step 1:** Failing tests RED. **Step 2:** Implement; full PASS. **Step 3: Commit** `feat(desktop): Alerts center — all alerts listed, removable, tap-through`

---

### Task 6: Conditional master–detail (Watchlist + Screener)

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/watchlist/WatchlistPane.kt`, `screener/ScreenerPane.kt`, `Main.kt` (the openSymbol full-window DetailScreen path is REPLACED for these two panes by an in-pane conditional split: no selection → full-width list/table; row click → list column (Watchlist ~300dp / Screener ~520dp) + `DetailPane` beside it via `key(symbol)`, embedded (no full-window floor), Buy included, ✕ + click-again close per Swift U2/U3 as-built; scan bar/chips stay full-width above the screener split; openSymbol stays for palette-opened detail and any other pane)

**Swift reference:** `WatchlistView.swift`/`ScreenerView.swift` macOS bodies AS-BUILT post-UAT. This dissolves M9.2's recorded "detail carries across tab switch" minor — note it in the report. **Decide and document** (carry-note trap 5): list + detail poll concurrently — desktop VMs share the graph's repository (unlike Swift's per-VM caches), so coalescing is free; verify and state it.

- [ ] **Step 1:** Implement. **Step 2:** full `:desktopApp:test` PASS (extend the pane tests that pinned openSymbol routing — intent-preserving); `:desktopApp:run` eyeball (kill after). **Step 3: Commit** `feat(desktop): conditional master–detail for Watchlist and Screener`

---

### Task 7: Re-homing + polish twins

**Files:**
- Modify: `ui/AccountPanel.kt` (REMOVE Export entry + DRIP toggle; reorder: Appearance, Language, Notifications first, then Profile/Account/Security, then Help/About; fix any Default-Tab-style static row to say Home), `portfolio/PortfolioPane.kt` (Export button in the summary header — existing ExportSave flow, entry relocation only), `income/IncomePane.kt` (DRIP card at top bound to the SAME settings field via FileSettingsStore path; hover tooltips on the monthly bars — month + Money.formatted amount — IF not already present, per Swift U6), allocation section (~30/70 with tightened by-class rows per Swift U4 — desktop allocation lives in PortfolioPane)

- [ ] **Step 1:** Implement. **Step 2:** full `:desktopApp:test` PASS. **Step 3: Commit** `feat(desktop): re-home Export and DRIP; settings order; allocation ratio; income tooltips`

---

### Task 8: README + gates + close-out

- Modify: `README.md` (Windows shipped in the M10 roadmap line; Windows section IA prose updated; observed counts)
- [ ] **Step 1:** README. **Step 2: Gates:** `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest` ALL green; `./scripts/build-shared.sh` (commonMain changed in T1); `DEVELOPER_DIR=/Applications/Xcode.app swift test` (585) vs the rebuilt xcframework; `:desktopApp:run` boots. Any failure → BLOCKED verbatim. **Step 3: Commit** `feat: M10.2 close-out — IA restructure on Windows desktop`

---

## Self-Review Notes

- Carry-note coverage: §1 rail mapping (T3), §2 master–detail (T6), §3 invariants (T1 tests + T4 loop + T5 fallback), §4 commonMain move (T1), §5 keys (T1), §6 traps (constraints 3–8 woven into T3/T4/T5/T6/T7).
- Type consistency: HomeState/HomeFeedItem (T1) → T2 VM → T4 pane; SidebarDestination (T3) → T4 navigation closures → T5 tap-through; the section enums hoisted in T3 are the ones Main routes.
- M10.3 Android carry-notes recorded at final review as usual.
