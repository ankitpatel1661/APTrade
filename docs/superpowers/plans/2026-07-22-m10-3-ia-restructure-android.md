# M10.3 — IA Restructure: Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the IA restructure on Android — the final M10 increment, bringing all four platforms to the new IA: four bottom tabs (Home · Markets · Portfolio · Invest), phone Home dashboard, Alerts center, and tool re-homing.

**Architecture:** Transcribe from the **desktop as-built on `main` (91c74a1)** phone-adapted per the mockup's Phone frame (`docs/superpowers/specs/2026-07-22-ia-restructure-mockup.html`) and Swift's iOS variant as layout cross-reference. The shared `HomeFeedAssembler` (commonMain, 18 tests) is ready — Android adds VM + screens + wiring. The M10.3 carry-notes from the M10.2 final review (ledger) are binding and woven into the tasks below. DO NOT touch desktop/Swift except where a task explicitly says so (T1's shared hoist refactors desktop's AppGraph seam).

**Tech Stack:** Kotlin, Jetpack Compose, androidx ViewModel, JUnit (`./gradlew :androidApp:testDebugUnitTest`).

## Global Constraints (carry-notes, binding)

1. **StrictMode I/O seam:** every assembler source closure that does file I/O (alerts load, screener snapshot, settings, portfolio store) must be IO-dispatched at the AppGraph seam; the assembler is NEVER called from init on main. The Home refresh loop = ONE lifecycle-aware coroutine (`repeatOnLifecycle(STARTED) { while(isActive) { vm.refresh(); delay(15_000) } }`) — sequential, refresh-before-delay, no overlap possible.
2. **Four recorded divergences to consciously carry** (each comment-documented at its code site, matching desktop's comments): armed-only alert count; hero tap → Portfolio·Performance; hero chart via THE shared activity-scoped PortfolioViewModel (not a second instance); selection persistence is **N/A on push-nav** — decide and COMMENT the back-stack behavior (default: pop returns to list, nothing persists — state it).
3. **Lazy-mount lesson (Swift I-1):** if any tab→section handoff uses a request pattern, it must consume on INITIAL composition too. Prefer writing hoisted section state directly from AppNavHost.
4. Conditional master–detail is **N/A on phone** — keep push navigation; do not transcribe ✕/click-again.
5. **Traps:** no `continue`/`break` inside composable loop bodies (Compose runtime crash); feed rows use index-based keys (HomeFeedItem has no stable identity); single-source preset display titles (one mapping, reuse the screener's); `-USD` → crypto fallback (transcribe desktop's 10 alerts-VM tests); `QuickEarningsWeekFmt` gets its FIRST consumer in the Home quick cards — use it; after DRIP re-homes, SettingsDrip/SettingsDripFooter usage is deleted from SettingsScreen and both keys join the dead-key chip (keys stay in the catalog).
6. House rules: tr()/trf() only (keys all exist from M10.2 T1 — no new keys, NEEDS_CONTEXT if missing); theme tokens; green/red = price direction only; 48dp touch targets (M9.3 lesson); UI composition ships without unit tests (standing waiver); every task ends with FULL `./gradlew :androidApp:testDebugUnitTest` green + `assembleDebug` clean; branch `feature/m10-3-ia-restructure-android`.

---

### Task 1: Shared income-summary hoist + Android HomeViewModel

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/IncomeSummaryMath.kt` — hoist the receivedYTD + first-upcoming-dividend orchestration (currently duplicated in desktop `AppGraph.buildHomeIncomeSummary` and privately in desktop/android IncomeViewModels) into shared pure functions over the existing income pipeline (wrap `DividendMath.nextProjected`, UTC-year filter via the existing civil-date helper family — this CLOSES the recorded "equivalence unguarded" chip)
- Create: `shared/src/commonTest/kotlin/com/aptrade/shared/application/IncomeSummaryMathTest.kt` (≥4: receivedYTD year filter/boundary; next-dividend selection + tie-break; non-crypto filter; empty cases)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt` (buildHomeIncomeSummary delegates to the shared functions — behavior identical, its KDoc updated)
- Create: `androidApp/src/main/kotlin/com/aptrade/android/home/HomeViewModel.kt` (androidx ViewModel sibling of desktop `home/HomeViewModel.kt`: StateFlow<HomeState?>, suspend refresh() delegating to the assembler, CE-first, error keeps previous state)
- Create: `androidApp/src/test/kotlin/com/aptrade/android/home/HomeViewModelTest.kt` (transcribe desktop's 5 VM tests, androidx dispatcher idiom per PlansViewModelTest)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt` (assembler wired from EXISTING graph pieces per the desktop recipe: ownSymbols union closure, quotes fetch, income via the NEW shared functions, earnings fetch, marketCalendar + clock, screener snapshot store, alert store — ALL file-I/O closures IO-dispatched per constraint 1; epoch→LocalDate once via calendar.tradingDay)

- [ ] **Step 1:** Failing tests RED (shared + android filtered). **Step 2:** Implement; `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest` ALL green (desktop must stay 359 — the hoist is behavior-identical); `./scripts/build-shared.sh` (commonMain changed). **Step 3: Commit** `feat(shared+android): income-summary hoist + Android HomeViewModel over the shared assembler`

---

### Task 2: Android 4-tab shell regroup

**Files:**
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppShell.kt` (`ShellTab` → Home("home", HomeTab) / Markets("markets", MarketsTab) / Portfolio / Invest("invest", InvestTab); icons: Home filled-house; Markets/Invest closest core analogs per the file's documented substitution policy, KDoc updated)
- Create: `androidApp/src/main/kotlin/com/aptrade/android/markets/MarketsScreen.kt` (host: search field opening the existing search route + pill row [Watchlist · Screener · Calendar · News] wrapping the EXISTING screens unchanged, pill idiom per PortfolioScreen's section switcher)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/portfolio/PortfolioScreen.kt` (PortfolioSection → Holdings, Allocation, Activity, Performance — Plans/Income OUT; same order as the other platforms)
- Create: `androidApp/src/main/kotlin/com/aptrade/android/invest/InvestScreen.kt` (pills [Plans · Income] hosting the existing Plans/Income screens' content — move instantiation, not internals)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/MainActivity.kt` (AppNavHost: `when (tab)` gains Home (placeholder "// Task 3") + Markets/Invest; section state HOISTED in AppNavHost and passed down so Home/deep-links write it directly — constraint 3; back-stack behavior comment per constraint 2 divergence #4)
- Test: update tests pinning ShellTab/PortfolioSection (intent-preserving; list them)

- [ ] **Step 1:** Implement. **Step 2:** FULL android suite green; `assembleDebug` clean. **Step 3: Commit** `feat(android): four-tab shell — Markets and Invest hosts, Portfolio slimmed`

---

### Task 3: HomeScreen

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/home/HomeScreen.kt`
- Modify: `MainActivity.kt` (Home tab hosts it; bell in the top bar badged when alertCount > 0 → Task 4's sheet, "// Task 4" no-op for now; feed-row navigation writes the hoisted tab+section state)

**References:** mockup Phone frame Home + Swift `HomeView.swift` iOS body as-built (post-UAT: rich P&L hero chart). Hero = microlabel + total + change pill + THE rich P&L chart (reuse Android's existing performance chart components — find PortfolioScreen's Performance section chart family — fed by THE shared activity-scoped PortfolioViewModel per constraint 2 divergence #3); hero tap → Portfolio·Performance (divergence #2); quick stats trio; Today card (feed rows per the desktop HomePane row treatment, index-keyed per constraint 5, each row navigates); quick cards 2×2 (Screener w/ single-source preset title, Alerts w/ AlertsActiveFmt, Calendar w/ QuickEarningsWeekFmt — its first consumer, News static label); refresh loop per constraint 1 (repeatOnLifecycle).

- [ ] **Step 1:** Implement (UI waiver). **Step 2:** FULL suite + assembleDebug green. **Step 3: Commit** `feat(android): Home dashboard — hero chart, Today feed, quick cards`

---

### Task 4: Alerts center sheet

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/alerts/AlertsCenterViewModel.kt` + `AlertsCenterSheet.kt` (ModalBottomSheet per PieWizardSheet idiom)
- Create: `androidApp/src/test/kotlin/com/aptrade/android/alerts/AlertsCenterViewModelTest.kt` (transcribe desktop's 10 tests incl. -USD both directions)
- Modify: Android's alert-condition summary source (find it in `watchlist/PriceAlertSheet.kt`) — extract ONE shared helper per the desktop `AlertConditionSummary.kt` pattern; `AppGraph.kt` factory (IO-dispatched load/remove); `HomeScreen.kt`/`MainActivity.kt` (bell + Alerts quick card open the sheet; row tap → navigate `detail/{symbol}` and dismiss)

- [ ] **Step 1:** Failing tests RED. **Step 2:** Implement; FULL suite + assembleDebug green. **Step 3: Commit** `feat(android): Alerts center sheet — all alerts listed, removable, tap-through`

---

### Task 5: Re-homing + polish twins

**Files:**
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/settings/SettingsScreen.kt` (REMOVE the DRIP toggle (SettingsDrip usage deleted — keys join the dead-key chip) and any Export entry if present; reorder groups per the other platforms: Appearance, Language, Notifications first; fix any Default-Tab-style static row → Home)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/portfolio/PortfolioScreen.kt` (Export entry in the Holdings/summary header via the existing ExportShare flow), allocation ~30/70 with tightened by-class rows (Swift U4/desktop twin — check current layout, adjust minimally)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/income/IncomeScreen.kt` (DRIP card at top bound to the SAME settings field/store path SettingsScreen used; monthly-bar TAP tooltips (month + Money.formatted) if absent — phone = tap not hover)
- Test: update any pinning settings rows (intent-preserving)

- [ ] **Step 1:** Implement. **Step 2:** FULL suite + assembleDebug green. **Step 3: Commit** `feat(android): re-home Export and DRIP; settings order; allocation ratio; income tooltips`

---

### Task 6: README + gates + M10 close-out

- Modify: `README.md` (Android shipped; **PRUNE M10 from the Roadmap** — milestone complete; Android section IA prose; observed counts)
- [ ] **Step 1:** README. **Step 2: Gates:** `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest` ALL green; `assembleDebug` clean; `DEVELOPER_DIR=/Applications/Xcode.app swift test` (585) vs current xcframework; any failure → BLOCKED verbatim. **Step 3: Commit** `feat: M10.3 close-out — IA restructure on Android; M10 complete`

---

## Self-Review Notes

- Carry-note coverage: §1-2 (T1), §3+phone adaptation (T2/T3), §4 alerts sheet (T4), §5 divergences+traps woven into T2/T3/T5 constraints, §6 dead keys (T5), close-out (T6). The shared income hoist in T1 deliberately closes the M10.2 kept chip on BOTH platforms.
- Type consistency: IncomeSummaryMath (T1) ← desktop AppGraph + android AppGraph; HomeState/assembler → T3 screen; hoisted tab+section state (T2) ← T3 navigation + T4 tap-through.
- At M10 close (post-UAT): prune roadmap, push ALL repos, milestone arc complete.
