# M8.3 — Dividend & Income Engine: Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the dividend & income engine on Android — the final M8 increment, bringing all four platforms to parity: Income screen, asset-detail dividend card, settings toggles, live coordinator crediting with notifications, and the display-polish twins.

**Architecture:** The shared Kotlin core already carries everything (ProcessDueDividends, DividendMath, planner event, settings fields, L10n keys — M8.2). Android adds ViewModels + Compose screens + coordinator/notifier wiring, transcribed from the **desktop as-built on `main` (31f9f82)** — the same twin-transcription discipline as M7.3 (desktop→android, sed-normalizable ViewModels). DO NOT touch `shared/` except where a task explicitly says so.

**Tech Stack:** Kotlin, Jetpack Compose, androidx ViewModel/viewModelScope, JUnit (`./gradlew :androidApp:testDebugUnitTest`).

## Global Constraints

- Desktop reference on `main` at 31f9f82; transcribe as-built semantics AND tests name-for-name (M7.3 precedent: androidx `ViewModel`/`viewModelScope` normalization is the only sanctioned delta for VMs).
- All portfolio-mutating wiring holds THE PortfolioGraph/AppGraph mutex (ProcessDueDividends is its 10th co-holder, established M8.2).
- Crediting ungated; notifications gated by `dividendNotifications`; backfill outcomes collapse into ONE summary notification; carry-note 4 (launch catch-up marks `lastDividendDay`) transcribes from the desktop coordinator INCLUDING its latency-trade-off KDoc.
- All user-visible strings via the shared L10n catalog (keys exist from M8.2 — Income*, AssetDividend*, Settings*, NotifDividend*, ActivityDividend); android's hardcoded "Dividend" sideLabel must switch to `ActivityDividend`.
- Share quantities DISPLAY at max 4 decimals via an android twin of desktop's `formatShares` (HALF_AWAY_FROM_ZERO, trailing zeros trimmed; ledger precision untouched).
- Phone layouts: Income's Upcoming/Income-by-Holding stay STACKED (narrow screen — mirrors the Swift iOS decision); projected bars keep the dashed-outline treatment; Allocation stays stacked (no side-by-side on phones).
- Crypto excluded from every dividend path; stale projections filtered (`> asOf`) in both months and upcoming AND the detail card's next-ex-date.
- Test command: `./gradlew :androidApp:testDebugUnitTest` (baseline 195); JUnit XML is the count authority. Shared suite `./gradlew :shared:jvmTest` (538) and desktop (`:desktopApp:test`, 295) must stay green whenever their compile surface is touched.
- Commit per task, conventional messages, explicit paths (NEVER `git add -A`). No pushes.

---

### Task 1: Android IncomeViewModel

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/income/IncomeViewModel.kt`
- Create: `androidApp/src/test/kotlin/com/aptrade/android/income/IncomeViewModelTest.kt`

**Desktop reference:** `desktopApp/src/main/kotlin/com/aptrade/desktop/income/IncomeViewModel.kt` + all 6 tests in `desktopApp/src/test/kotlin/com/aptrade/desktop/income/IncomeViewModelTest.kt` — byte-identical after the M7.3 normalization (class extends `ViewModel`, injected scope becomes `viewModelScope`-driven per the android plans-VM precedent in `androidApp/.../plans/PlansViewModel.kt`; test scaffolding per `androidApp/.../plans/PlansViewModelTest.kt`'s dispatcher idiom). State shape (`IncomeState` alias caveat), 730-day lookback, stale-projection guards, per-symbol failure isolation, crypto exclusion — all as-built.

- [ ] **Step 1: Failing tests** — 6 transcribed name-for-name, byte-equal fixtures. Run: `./gradlew :androidApp:testDebugUnitTest --tests "*IncomeViewModel*"` — FAILS.
- [ ] **Step 2: Implement.** Filtered PASS; full android suite PASS.
- [ ] **Step 3: Commit** `feat(android): IncomeViewModel — cards, monthly bars, upcoming, history`

---

### Task 2: Android Income screen + section + share formatting

**Files:**
- Create: `androidApp/src/main/kotlin/com/aptrade/android/income/IncomeScreen.kt`
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/portfolio/PortfolioScreen.kt` (`PortfolioSection` gains `Income` after `Plans`; title `tr(L10n.Key.IncomeSection)`)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt` (or PortfolioGraph — wherever plans VMs are wired; follow that precedent)
- Create: android `formatShares` twin (put it beside the existing android formatting helpers — find them: `grep -rn "formatMoney\|formatPercent" androidApp/src/main/kotlin --include="*.kt" -l`; same file family)
- Modify: every android SHARE-quantity display site (audit the 7 `toStringExpanded` hits in androidApp — route the share ones through `formatShares`; money sites stay untouched)
- Test: `androidApp/src/test/kotlin/com/aptrade/android/ui/ShareFormattingTest.kt` (transcribe desktop's 5 cases)

**Desktop reference:** `IncomePane.kt` for subsection structure/badges/empty state — BUT phone layout: single-column scroll (cards 2×2 grid is fine; Upcoming then Income-by-Holding STACKED; history feed below); dashed projected bars identical (Canvas dash [3,2], 0.12 fill / 0.6 stroke); `formatShares` from desktop `designkit/Formatting.kt` + `ShareFormattingTest.kt`.

- [ ] **Step 1: Failing test** — ShareFormattingTest (5 cases). RED, implement helper, GREEN.
- [ ] **Step 2: Implement screen + section + wiring + share-site sweep.** Full android suite PASS; `./gradlew :androidApp:assembleDebug` clean.
- [ ] **Step 3: Commit** `feat(android): Income screen — cards, monthly chart, upcoming, history; 4dp share display`

---

### Task 3: Android detail dividend card + settings toggles + sideLabel

**Files:**
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/detail/DetailViewModel.kt`, `DetailScreen.kt`
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/settings/SettingsScreen.kt`, `SettingsViewModel.kt`
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/portfolio/PortfolioViewModel.kt` (sideLabel "Dividend" hardcode → `tr(L10n.Key.ActivityDividend)`-routed, following how desktop's PortfolioViewModel did it in M8.2 T5)
- Test: `androidApp/src/test/kotlin/com/aptrade/android/detail/DetailViewModelTest.kt` (extend)

**Desktop reference:** `DetailViewModel.kt`/`DetailPane.kt` as-built (DividendInfo, crypto-skip-without-fetch spy test, null-on-empty/failure, future-only guard, last-8 mini chart) + its 5 dividend tests, PLUS the M8.2-flagged edge as a NEW 6th test here: profile fetch fails for a crypto symbol → dividend fetch proceeds (documented behavior) — pin it so a refactor can't silently flip it. Settings: DRIP toggle in the account/trading group with `SettingsDripFooter` subtitle; dividend-notifications toggle beside the pie one (desktop AccountPanel precedent adapted to SettingsScreen's idioms; persistence through the existing settings path — no new plumbing).

- [ ] **Step 1: Failing tests** — 5 transcribed + the edge-pin test. RED.
- [ ] **Step 2: Implement.** Filtered + full android suite PASS.
- [ ] **Step 3: Commit** `feat(android): detail dividend card, DRIP + notification settings, localized dividend chip`

---

### Task 4: Android coordinator + notifier wiring

**Files:**
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/alerts/AndroidMarketActivityCoordinator.kt` (replace the staged `DividendCheckDue -> Unit`), `AndroidAlertNotifier.kt` (dividend notification channel + branded notify, mirroring the pie-contribution channel precedent from M7.3; the Dividend verb branch is already correct from the M8.2 fix wave)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt` (ProcessDueDividends on THE mutex; `isDripEnabled = { settingsStore.load().dripEnabled }` live read)
- Test: the android coordinator test file (extend)

**Desktop reference:** `DesktopMarketActivityCoordinator.kt` as-built — launch catch-up UNGATED after the gated contribution catch-up; tick handler; notification gating; backfill collapse (count + Money-summed total via `NotifDividendBackfillBodyFmt`); carry-note 4 marking + its latency KDoc. Tests (a)–(e) transcribed from `DesktopMarketActivityCoordinatorTest.kt`'s dividend set.

- [ ] **Step 1: Failing tests** — (a)–(e). RED.
- [ ] **Step 2: Implement.** Filtered + full android suite PASS.
- [ ] **Step 3: Commit** `feat(android): dividend check wired into coordinator + notifications`

---

### Task 5: Shared follow-ups + README + full close-out

**Files:**
- Modify: `shared/src/commonTest/kotlin/com/aptrade/shared/application/ProcessDueDividendsTest.kt` — the M8.2-recorded fake-`since` fix: make `FakeDivMarket.dividendEvents` ignore `fromEpochSeconds` (like Swift's fake), restoring test (j)'s pre-buy assertion's bite (the pre-buy event must now reach the engine and be skipped by `sharesHeld == 0`, not filtered by the fake). Verify test (j) still passes for the RIGHT reason (temporarily break the strictly-before comparison to see it fail).
- Modify: `README.md` — Android column shipped; **prune M8 from the Roadmap entirely** (house rule: shipped milestones leave the roadmap at final-increment close; M9 screener stays); four-platform Dividend & Income feature block; suite counts updated to observed.
- Test gates: `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest` ALL green; `./gradlew :androidApp:assembleDebug` clean; macOS `DEVELOPER_DIR=/Applications/Xcode.app swift test` (469) — shared commonTest-only change needs no xcframework rebuild, but run the macOS suite as the cross-platform regression formality.

- [ ] **Step 1: Fake-since fix with the RED-probe verification above.**
- [ ] **Step 2: README + all gates.**
- [ ] **Step 3: Commit** `feat: M8.3 close-out — four-platform dividend parity, README, shared test-fidelity fix`

---

## Self-Review Notes

- Coverage vs the M8.3 carry-note list: coordinator/notifier (T4), Income screen (T1/T2), detail card + T8 edge pin (T3), sideLabel localization (T3), formatShares twin (T2), fake-since fix (T5). NOT included by design: IncomeViewModel QuoteError-catch broadening (cross-platform refactor, stays backlog), isExporting guard (desktop-only backlog), macOS 4dp/allocation parity (user-gated backlog).
- Phone-layout divergences (stacked tables, stacked allocation) are deliberate and documented in Global Constraints.
- Type consistency: IncomeState (T1) consumed by T2; ProcessDueDividends signature (incl. suspend isDripEnabled) is the M8.2 as-built — no shared signature changes anywhere in this plan except the T5 test-fake edit.
