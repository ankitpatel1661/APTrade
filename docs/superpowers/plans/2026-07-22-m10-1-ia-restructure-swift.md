# M10.1 — IA Restructure: Swift (macOS + iPhone) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the approved IA restructure on macOS and iPhone — Home dashboard (new opening screen), the Home · Markets · Portfolio · Invest regroup, the Alerts center, desktop sidebar + master–detail, and the tool re-homing — as the reference implementation M10.2/M10.3 will transcribe.

**Architecture:** Spec = `docs/superpowers/specs/2026-07-22-ia-restructure-design.md`; visual reference = `docs/superpowers/specs/2026-07-22-ia-restructure-mockup.html` (open it — every destination is clickable; it is authoritative for STRUCTURE, existing components stay authoritative for rendering). No visual-token changes, no engine changes — new ViewModels are pure aggregation over existing use cases wired in `CompositionRoot`. Existing feature views (Watchlist, Screener, Calendar, News, Plans, Income, …) are re-hosted, not rewritten.

**Tech Stack:** Swift 6, SwiftUI, `@Observable` MVVM. Tests: `DEVELOPER_DIR=/Applications/Xcode.app swift test` (560 at branch start). iOS sanity builds: `xcodebuild -scheme APTradeiOS ARCHS=arm64` (park `APTrade.xcodeproj` if package tests are needed via scheme `APTradeLite-Package`).

## Global Constraints

- Branch `feature/m10-1-ia-restructure-swift`. Every task ends with the FULL Swift suite green + a clean `swift build`; iOS-touching tasks also build the APTradeiOS scheme.
- Section order is identical on every platform and IS the spec: Markets[Watchlist, Screener, Calendar, News] · Portfolio[Holdings, Allocation, Activity, Performance] · Invest[Plans, Income]. App opens on Home.
- Home feed rows degrade independently — a failing quote/missing key hides that row, never blocks Home (per-source try? isolation, same spirit as IncomeViewModel's per-symbol failure isolation).
- All copy through `tr(.…)`; NEW keys land in Task 1 only (all four languages); later tasks may not invent keys — if one is missing, the task reports NEEDS_CONTEXT.
- Gains green / losses red stays data-only; gold stays the interactive accent; no hard-coded colors — `Theme`/`DesignKit` only.
- UI composition ships without unit tests (standing waiver); ViewModels carry behavior with tests.
- The mutex/serializer discipline is untouched — nothing here trades. The DRIP toggle keeps writing the same settings field through the existing SettingsViewModel.
- Deletions are real: the macOS 108pt wordmark header, the center switcher, Export-in-account-menu, and DRIP-in-Account-Settings are REMOVED, not left behind alongside their replacements.

---

### Task 1: L10n keys for the restructure

**Files:**
- Modify: `Sources/APTradeApp/L10n.swift` (all four languages; find and bump any catalog-count pin test)

**New keys (EN values; DE/IT/ES translated in-task):** `homeTab` "Home", `marketsTab` "Markets", `investTab` "Invest", `todaySection` "Today", `portfolioValue` "Portfolio Value", `marketOpenStatus` "Market open", `marketClosedStatus` "Market closed", `closesAtFmt` "closes %@", `opensAtFmt` "opens %@", `leadsHoldingsFmt` "%@ leads your holdings", `biggestFallerFmt` "%@ biggest faller", `reportsEarningsFmt` "%@ reports earnings", `earningsSessionAfterClose` "after close", `earningsSessionBeforeOpen` "before open", `dividendEstFmt` "%@ dividend · est. %@", `screenerFreshFmt` "Screener: %@ · %@ matches", `alertsCenterTitle` "Alerts", `alertsActiveFmt` "%@ active", `alertsEmpty` "No alerts yet. Set one from any symbol row or detail page.", `alertArmed` "armed", `quickNewsFmt` "%@ new for your symbols", `quickEarningsWeekFmt` "%@ earnings this week", `incomeYtdLabel` "Income YTD", `totalReturnLabel` "Total Return", `cashLabel` "Cash", `dripCardTitle` "Reinvest dividends (DRIP)", `dripCardSubtitle` "Payouts buy fractional shares automatically". Reuse existing keys everywhere else (`watchlist`, `screenerTab`, `calendarTab`, `news`, section keys, `exportPortfolioData`, `settingsDrip*` …). If an equivalent key already exists, reuse it instead of adding a twin.

- [ ] **Step 1:** Add keys ×4 languages; bump the Swift catalog pin test if one exists (grep `catalog` / `keyCount` in Tests).
- [ ] **Step 2:** `DEVELOPER_DIR=/Applications/Xcode.app swift test` — green.
- [ ] **Step 3: Commit** `feat(l10n): M10.1 IA-restructure keys (EN/DE/IT/ES)`

---

### Task 2: HomeViewModel

**Files:**
- Create: `Sources/APTradeApp/HomeViewModel.swift`
- Create: `Tests/APTradeAppTests/HomeViewModelTests.swift`
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (`makeHomeViewModel()` factory wiring EXISTING pieces)

**Shape:** `@Observable` VM, aggregation only. Inputs injected via closures/use cases (test-fakeable, per house pattern): portfolio snapshot (positions + cash — the same source `PortfolioViewModel` reads), live quotes for holdings ∪ watchlist, the Performance equity-curve series (reuse the existing reconstruction — do NOT reimplement; take the last ~30 points for `sparkValues`), income summary (YTD + next upcoming dividend — from the income engine's existing helpers), next earnings among owned+watched (existing earnings calendar fetch), market status (the existing market-hours logic the scheduler uses — expose open/closed + next transition time), screener freshness (`isSnapshotFresh` + match count from the persisted snapshot via the existing stores), alerts count (existing alert store).

**Outputs:** `totalValue: Money`, `dayChange: Money`, `dayChangePercent`, `totalReturnPercent`, `cash: Money`, `incomeYTD: Money`, `sparkValues: [Double]`, `feed: [HomeFeedItem]` (enum with associated values: `.marketStatus`, `.topGainer(symbol, pct)`, `.topLoser(symbol, pct)`, `.earnings(symbol, session, date)`, `.dividend(symbol, amount, date)`, `.screenerFresh(name, matches)`), `alertCount: Int`. `func refresh() async`.

**Tests (≥10):** value = market value + cash; day change summed from holdings' quote changes; top gainer/loser picked across holdings ∪ watchlist and deduped; each feed source failing in isolation drops only its row (portfolio load failure still shows market status); market open vs closed formatting states; screener row absent when snapshot stale; dividend row absent with no upcoming; feed ordering is fixed (status, movers, earnings, dividend, screener); alertCount from store; spark takes at most 30 trailing points.

- [ ] **Step 1:** Failing tests. RED. **Step 2:** Implement; filtered + FULL suite green. **Step 3: Commit** `feat: HomeViewModel — dashboard aggregation with per-source isolation`

---

### Task 3: Alerts center

**Files:**
- Create: `Sources/APTradeApp/AlertsCenterView.swift`, `Sources/APTradeApp/AlertsCenterViewModel.swift`
- Create: `Tests/APTradeAppTests/AlertsCenterViewModelTests.swift`
- Modify: `CompositionRoot.swift` (factory)

**Shape:** VM lists all alerts (existing alert store's load-all), exposes `remove(id)`, groups by symbol, condition text via the SAME condition-description helper `PriceAlertSheet` uses (extract it to a shared internal helper rather than duplicating — `PriceAlertSheet.swift:135` region). View: simple list (symbol bold, condition summary, armed chip, ✕ remove; tap row → present the existing `AssetDetailView` sheet), `alertsEmpty` empty state, works as a sheet on iOS and a pane-hosted sheet on macOS (presented from Home in Task 5). Tests: load/group/remove/empty + condition summary reuse.

- [ ] **Step 1:** Failing VM tests. RED. **Step 2:** Implement VM + view; suite green. **Step 3: Commit** `feat: Alerts center — all alerts listed, removable, tap-through to detail`

---

### Task 4: iPhone shell regroup (4 tabs + section hosts)

**Files:**
- Modify: `Sources/APTradeApp/RootView.swift` (`Tab` enum → `home, markets, portfolio, invest`; `iosBody` TabView items with icons: house / chart.line.uptrend.xyaxis / chart.pie / chart.pie.fill—pick distinct SF Symbols; default `.home`)
- Create: `Sources/APTradeApp/MarketsView.swift` (host: visible search field opening the existing palette + pill row [Watchlist · Screener · Calendar · News] hosting the EXISTING views unchanged; pill idiom copied from PortfolioView's section switcher)
- Modify: `Sources/APTradeApp/PortfolioView.swift` (`Section` enum → holdings, allocation, activity, performance — Plans/Income cases REMOVED; ripple through the pill row and switch)
- Create: `Sources/APTradeApp/InvestView.swift` (host: pill row [Plans · Income] hosting the EXISTING `PlansSection` and `IncomeSection` views that PortfolioView used to host — move their instantiation, not their internals)
- Modify: `Sources/APTradeApp/CommandPaletteViewModel.swift` + `RootView.handlePaletteSelection` (navigate destinations → the four new tabs; keep asset selection behavior)

**Behavior notes:** the views the hosts wrap keep their own view models and lifecycles exactly as today (they were previously instantiated by RootView/PortfolioView switches — same pattern, new location). Watchlist/News/Calendar/Screener receive the same `onOpenSearch`/`onOpenAccount` closures they take today. Nothing about their internals changes.

- [ ] **Step 1:** Implement. **Step 2:** FULL suite green (update any tests pinning the old Tab/Section enums — spec-mandated change, note in report); `swift build` + APTradeiOS scheme build green. **Step 3: Commit** `feat(ios): four-tab shell — Markets and Invest hosts, Portfolio slimmed to four sections`

---

### Task 5: HomeView (both platforms)

**Files:**
- Create: `Sources/APTradeApp/HomeView.swift`
- Modify: `Sources/APTradeApp/RootView.swift` (iOS: Home tab hosts it, bell in the top-right opens AlertsCenterView sheet — badge when `alertCount > 0`)

**Layout per mockup:** hero (microlabel PORTFOLIO VALUE, SuperscriptPrice total, ChangePill day change + "today"), gold area sparkline (existing Sparkline/chart primitives), quick-stat trio (Total return / Cash / Income YTD), Today card (feed rows with leading glyph, trailing time label; each row navigates: movers→Markets, earnings→Markets·Calendar, dividend→Invest·Income, screener→Markets·Screener — navigation via closures injected from RootView), quick cards grid (Screener/Alerts/Calendar/News with live one-liners from the VM). macOS variant: two-column grid per mockup (Today left; stats + Alerts card right), hosted by Task 6's shell. 15s refresh loop via `.task` per the house live-data pattern.

- [ ] **Step 1:** Implement (UI waiver — VM behavior already pinned). **Step 2:** FULL suite + both builds green. **Step 3: Commit** `feat: Home dashboard — hero, Today feed, quick cards, alerts bell`

---

### Task 6: macOS sidebar shell

**Files:**
- Modify: `Sources/APTradeApp/RootView.swift` (`macBody` rebuilt: HStack [sidebar | content]; DELETE the 108pt wordmark block, the top-right button cluster, and the centered `switcher` (lines ~131-166 and the `switcher` builder); sidebar = compact brand mark (~28pt wordmark image or BrandMark), `Home`, group labels MARKETS/PORTFOLIO/INVEST with their section items, footer `Search ⌘K` + `Settings` + the theme toggle relocated beside Settings; selection state = (destination, section) replacing the old `tab`; account panel + palette overlays unchanged, now opened from the footer/sidebar)

**Behavior:** clicking a section item shows that section's existing view full-pane (Portfolio sections render the SAME section views PortfolioView switches over — hoist the section rendering so both the iPhone pill host and the macOS sidebar reuse one section-view builder; same for Markets and Invest). Home renders Task 5's macOS layout. ⌘K keyboard shortcut and palette behavior unchanged. Selection persists per launch only (no new persistence).

- [ ] **Step 1:** Implement. **Step 2:** FULL suite + `swift build` green; launch the binary (`"$(swift build --show-bin-path)/APTradeMac"`), eyeball sidebar + every destination renders, quit. **Step 3: Commit** `feat(macos): sidebar navigation — grouped destinations, corner brand, ~120pt chrome reclaimed`

---

### Task 7: macOS master–detail (Watchlist + Screener)

**Files:**
- Modify: `Sources/APTradeApp/WatchlistView.swift`, `Sources/APTradeApp/ScreenerView.swift` (macOS-only: `#if os(macOS)` list-column + detail-pane layout per the mockup — selecting a row sets a local `selectedSymbol` and renders `AssetDetailView` beside the list (Buy included) instead of the current full-window push; iOS keeps its existing navigation untouched; keep the existing row components)

**Notes:** detail's own back affordance is unnecessary in the pane (selection change replaces it); watchlist live-update loop and screener scan lifecycle are unchanged; the pane detail uses `viewModel(key: symbol)`-equivalent per-selection scoping exactly like today's push destination did (scope teardown on selection change — verify the 6a.5 per-selection lifecycle note still holds).

- [ ] **Step 1:** Implement. **Step 2:** FULL suite + build green; launch binary, eyeball both master–detail panes, quit. **Step 3: Commit** `feat(macos): master–detail for Watchlist and Screener — detail beside the list`

---

### Task 8: Tool re-homing

**Files:**
- Modify: `Sources/APTradeApp/PortfolioView.swift` (Holdings header gains the Export button → `showExportDialog` flow lifted from RootView or invoked via closure; the flow itself is unchanged)
- Modify: `Sources/APTradeApp/RootView.swift` (account panel: REMOVE the Export row and the DRIP toggle from `accountSettingsPage`; reorder `accountMenuPage` rows: Appearance, Language, Notifications first, then Profile, Account Settings, Security, Help, About)
- Modify: `Sources/APTradeApp/IncomeSection.swift` (header card: DRIP toggle bound to the SAME `settingsVM.settings.dripEnabled` via injected SettingsViewModel, with `dripCardTitle`/`dripCardSubtitle` copy)

- [ ] **Step 1:** Implement. **Step 2:** FULL suite + both builds green. **Step 3: Commit** `feat: re-home Export to Portfolio, DRIP to Income; settings honesty pass`

---

### Task 9: README + gates + close-out

**Files:**
- Modify: `README.md` (macOS/iPhone shipped in a new M10 IA-restructure note in the platform/feature sections; M10 stays in Roadmap until M10.3)

- [ ] **Step 1:** README.
- [ ] **Step 2: Gates:** `DEVELOPER_DIR=/Applications/Xcode.app swift test` FULL green; `swift build` clean; APTradeiOS scheme builds (ARCHS=arm64); `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest` untouched-green (587/340/267). Any failure → BLOCKED with verbatim capture.
- [ ] **Step 3: Commit** `feat: M10.1 close-out — IA restructure on macOS + iPhone`

---

## Self-Review Notes

- Spec coverage: IA table (T4 phone, T6 desktop), Home (T2+T5), Alerts center (T3), master–detail (T7), re-homing + chrome deletion (T6+T8), L10n (T1), gates (T9). Non-goals respected: no engine, token, or persistence changes anywhere.
- Type consistency: `HomeFeedItem` (T2) consumed by T5; section-view builder hoisted in T6 is the same one T4's pill hosts use; AlertsCenterView (T3) presented from T5's bell.
- Deliberate sequencing: T1 first (keys), T2/T3 parallel-safe but run serially per house rule, T4 before T5 (Home needs the tab to live in), T6 after T5 (sidebar hosts Home's macOS layout), T7 after T6 (panes live in the new shell), T8 last-but-one (touches files T4/T6 settled).
- M10.2 carry-notes will be recorded at final review as usual (transcription source = this increment's as-built).
