# M7.2 — Investment Plans (Pies): Kotlin shared core + Windows desktop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Investment Plans (Pies) to the Kotlin Multiplatform shared core and the Windows Compose Desktop app at full parity with the shipped Swift/macOS reference (M7.1, merged as `b9db748`).

**Architecture:** TRANSCRIPTION of the Swift as-built — NOT of the spec's original algorithm sketches. M7.1's review loops corrected the spec in several places; the Swift files + their tests are the source of truth. Every task names its Swift reference file(s); fixtures transcribe byte-value-equal. Kotlin idioms per the existing shared core (BigDecimal via ionspin, suspend stores, kotlinx.coroutines Mutex, epoch-day MarketCalendar).

**Tech Stack:** Kotlin Multiplatform (`shared/commonMain` + `jvmCommonMain`), Compose Desktop (`desktopApp`), kotlin.test/JUnit, Gradle.

## Global Constraints

- `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"` before ANY gradle command; always `./gradlew`.
- Test commands: `./gradlew :shared:jvmTest`, `./gradlew :desktopApp:test`; verify counts from JUnit XML with `--rerun-tasks` (cached runs lie). Baselines at branch start: shared 366 / desktop 215 / android 146 (android untouched by this plan — M7.3).
- ionspin BigDecimal THROWS on non-terminating division without a mode (existing `Portfolio.kt` documents the convention — follow it; 2-dp money rounding matches the Swift `NSDecimalRound(.plain)` = HALF_EVEN? NO: .plain = half-away-from-zero → use `RoundingMode.ROUND_HALF_AWAY_FROM_ZERO` and PROVE equivalence by transcribing the Swift cent-rounding fixtures).
- MANDATORY M7.1 CORRECTIONS (supersede the spec's sketches — transcribe from Swift as-built):
  1. `distribute` remainder = CLAMPED WALK over weight-desc/symbol-asc order (never unconditional largest-slice add) — Sources/APTradeDomain/PieMath.swift + both regression tests (negative-remainder counterexample A=60/20/20 @ 32.30/0.97/0.97 + $6.53; weight-tie A=35/35/30 + $10.01 → A=3.51).
  2. `drift` = SIGNED actual − target. `rebalancePlan` net-zero fold correction = **−netCash** with clamp/side-flip walk; NO sub-cent filter.
  3. `PieSchedule` window semantics on the ROLLED day (include iff rolled > afterDay && rolled ≤ throughDay; break on rolled > throughDay); `nextDueDay` step-0 anchor judged UNROLLED, steps ≥ 1 judged rolled (doc comment explains why — transcribe it). Regression tests: overrun→[], drop-forever window pair, monthly anti-drift (Jan 31 anchor → Mar 31 not Mar 28).
  4. `ContributionSchedule` has REQUIRED `anchorDay` (immutable original first-due-day) + `nextDueDay` cursor; decode fallback anchorDay = nextDueDay on missing key.
  5. `ExecuteDueContributions`: PER-DAY atomic cursor advancement (each consumed day — executed/missed/skipped — persists cursor with that day's mutation); IN-LOCK fresh pie/schedule reloads per day; stop cleanly if pie/schedule vanished mid-run. Replay regression test (3-phase: fail today's quote → rerun no replay → heal → only today executes).
  6. Toggle semantics (ADJUDICATED): `pieContributions` gates EXECUTION on both paths (planner event + launch catch-up), not just notifications.
  7. Wizard: schedule START-DAY field (anchor = chosen day rolled; default today; past → invalid); schedule PRESERVATION rule on edit (off→nil / new-or-unscheduled→fresh / cadence-unchanged→preserve anchor+cursor swap amount / cadence-changed→fresh); ledger FILTERED to surviving slices on save.
  8. Serialization: ALL pie/portfolio mutating sequences share ONE `Mutex` — the EXISTING `portfolioMutex` from `AppGraph` (extend `BuyAsset.kt`'s doc contract to name the pie use cases). Quote/history fetches OUTSIDE the lock where the loaded state doesn't depend on them (BuyAsset precedent) — EXCEPT the per-day catch-up pricing, which reads in-lock state to distribute (mirror Swift's per-day critical-section shape).
- Store JSON schemas converge with Swift field names (spec §C): `pies.json` array of pies with the same field names Swift's Codable produces (id/name/slices/schedule/createdDay/ledger/activity; slice: symbol/assetKind/targetWeight; schedule: amount/cadence/anchorDay/nextDueDay). Verify against an actual Swift-encoded sample (generate one via a Swift scratch or read UserDefaults plist in a mac test — simplest: transcribe from `UserDefaultsPieStore` + `Pie.swift` Codable shapes by reading the code).
- All desktop strings via shared `L10n` `tr`/`trf`; Kotlin uses `%s`/positional `%1$s` (NOT Swift's `%@` — convert); EN byte-equal to Swift raw values, DE/IT/ES transcribed from the Swift catalog (they exist — copy, don't re-translate).
- NEVER `git add -A` (Screenshots/ is the user's); explicit paths; commit trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; no pushes.
- Branch: `feature/investment-plans-kmp` off main (post-M7.1 merge).

---

### Task 1: Kotlin Pie domain model
**Files:** Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Pie.kt`; Test `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PieTest.kt`
**Transcribe from:** `Sources/APTradeDomain/Pie.swift` (+ `Tests/APTradeDomainTests/PieTests.swift` fixtures).
PieCadence (weekly/biweekly/monthly), ContributionSchedule(amount: Money, cadence, **anchorDay: String**, nextDueDay: String), PieSlice(symbol, assetKind: AssetKind, targetWeightPP: BigDecimal — Kotlin has no Percentage VO; use percent-points BigDecimal, document), PieLedgerEntry, PieActivityKind (contribution/rebalance/missedInsufficientCash/manualAdjustment), PieActivityEntry(id, kind, day, amount: Money?), Pie with `init`-equivalent validated factory (`Pie.create(...): Pie` throwing PieError on emptySlices/duplicateSymbols/invalidWeights sum≠100) + `quantityOf(symbol)`. Ledger⊆slices deliberately NOT validated (doc-comment why — reconcile flow). Tests: transcribe (a)–(f) + invalid-decode-note.
- [ ] RED (transcribed tests fail to compile) → implement → `./gradlew :shared:jvmTest --rerun-tasks` green → commit `feat(shared): Pie domain model with slice/schedule/ledger validation`.

### Task 2: Kotlin PieMath — distribute + drift + rebalancePlan
**Files:** Create `shared/src/commonMain/.../domain/PieMath.kt`; Test `shared/src/commonTest/.../domain/PieMathTest.kt`
**Transcribe from:** `Sources/APTradeDomain/PieMath.swift` AS-BUILT (corrections 1–2 above) + ALL fixtures from `PieMathDistributeTests.swift` (7) and `PieMathRebalanceTests.swift` (8) including every regression case, byte-value-equal.
- [ ] RED → implement → green (15+ transcribed tests) → commit `feat(shared): pie distribution and rebalance math with clamped-walk remainder`.

### Task 3: Kotlin PieSchedule
**Files:** Create `shared/src/commonMain/.../domain/PieSchedule.kt`; Test `shared/src/commonTest/.../domain/PieScheduleTest.kt`
**Transcribe from:** `Sources/APTradeDomain/PieSchedule.swift` AS-BUILT (correction 3). Adapt to the Kotlin `MarketCalendar` epoch-day API: day-string ↔ localEpochDay via existing `dayString`/civil helpers (grep MarketCalendar.kt; add a public `parseDay(day: String): Long?` helper to PieSchedule, NOT to MarketCalendar). Weekly +7 / biweekly +14 epoch-day arithmetic; monthly via civil-date month stepping with day-of-month clamp (transcribe Foundation's clamp behavior: Jan 31 + 1mo = Feb 28; PROVE with the anti-drift fixture). rollToTradingDay skips weekends (isoWeekday 6/7) + fullHoliday; half-days are trading days. All 19 Swift test fixtures transcribed.
- [ ] RED → implement → green → commit `feat(shared): cadence due-day generation with rolled-window semantics`.

### Task 4: Kotlin PieBacktest
**Files:** Create `shared/src/commonMain/.../domain/PieBacktest.kt`; Test `shared/src/commonTest/.../domain/PieBacktestTest.kt`
**Transcribe from:** `Sources/APTradeDomain/PieBacktest.swift` + its 5 fixtures (flat 60/40 → 0.00%; rising → hand-shown arithmetic; lumpSum > DCA; missing-close skip; all-missing → null). Same first-due-day seam (nextDueDay step-0 + dueDays for the rest — transcribe the doc comment).
- [ ] RED → implement → green → commit `feat(shared): historical DCA backtest with lump-sum comparison`.

### Task 5: pieId attribution + FilePortfolioStore DTO
**Files:** Modify `shared/src/commonMain/.../domain/Trade.kt` (+`pieId: String? = null` last), `domain/Portfolio.kt` (buying/selling gain trailing `pieId: String? = null` → into Transaction), `shared/src/jvmCommonMain/.../infrastructure/FilePortfolioStore.kt` (TransactionDTO + optional pieId, absent-key tolerant both directions); Tests: `shared/src/commonTest/.../domain/TransactionPieIdTest.kt` + extend FilePortfolioStore round-trip test (legacy JSON literal WITHOUT pieId decodes; tagged round-trips).
- [ ] RED → implement → green (all existing portfolio tests untouched) → commit `feat(shared): optional pieId attribution on transactions`.

### Task 6: PieStore port + FilePieStore + CRUD
**Files:** Create `shared/src/commonMain/.../application/PieStore.kt` (interface: `suspend fun load(): List<Pie>; suspend fun save(pies: List<Pie>)`) + `application/PieCrudUseCases.kt` (LoadPies/SavePie replace-in-place/DeletePie); Create `shared/src/jvmCommonMain/.../infrastructure/FilePieStore.kt` (`pies.json` in ConfigDir, corrupt → emptyList WITHOUT overwrite — byte-equality test; field names converged with Swift Codable shapes per Global Constraints); Tests both layers (transcribe UserDefaultsPieStore + PieUseCases CRUD fixtures incl. position-pinned replace + no-overwrite byte test).
- [ ] RED → implement → green → commit `feat(shared): PieStore port, CRUD use cases, file adapter`.

### Task 7: ContributeToPie + ExecuteDueContributions
**Files:** Create `shared/src/commonMain/.../application/PieContributionUseCases.kt`; Tests `ContributeToPieTest.kt` + `ExecuteDueContributionsTest.kt`
**Transcribe from:** Swift `PieUseCases.swift` AS-BUILT (corrections 5 + 8): ContributeToPie (quotes → ledger×price values → distribute → cash pre-check → unrounded share/price buys tagged pieId through `Portfolio.buying` → ledger+activity → saves, ALL inside `portfolioMutex.withLock` with quote fetch outside; skip-whole on insufficient cash w/ missed activity; ContributionOutcome sealed class). ExecuteDueContributions (non-throwing per-pie degrade; per-day `withLock` critical sections; in-lock fresh reloads; historical closes via existing FetchHistory/candles indexed by tradingDay — mirror the shared fetchClosesByDay helper; today at live quote; cursor per-day advancement; anchorDay stepping). Tests: transcribe all Swift cases incl. the 3-phase replay regression + anti-drift + racing-contributions lost-update test (mirror `racingBuyAndSellSharingOneMutexBothLandNoLostUpdate`'s controllable-suspension style).
- [ ] RED → implement → green → commit `feat(shared): pie contributions with per-day atomic catch-up under the shared portfolio mutex`.

### Task 8: RebalancePie + ReconcilePieLedgers + SimulateDCA
**Files:** Extend `PieContributionUseCases.kt` (or sibling file, keep focused); Tests `RebalancePieTest.kt` + `SimulateDCATest.kt`
**Transcribe from:** Swift AS-BUILT: preview lock-free vs execute in-lock (sells first — $0-cash ordering-proof fixture; sell clamp min(raw, ledger, held); one rebalance activity; both saves after all orders); ReconcilePieLedgers (largest-clamps-first ascending walk, lexicographic tie, injected clock, only-clamped-pies activity); SimulateDCA (non-throwing null-degrade incl. cancellation→null, per-symbol failure map fixture, .oneYear ceiling doc note). BuyAsset/SellAsset/ResetPortfolio ALREADY use the mutex in Kotlin — no residual to fix; extend BuyAsset.kt's doc block to name the pie use cases as co-holders.
- [ ] RED → implement → green → commit `feat(shared): rebalance, ledger reconciliation, and DCA simulation`.

### Task 9: Planner event + settings + scheduler-state DTOs
**Files:** Modify `shared/src/commonMain/.../application/MarketActivityPlanner.kt` (ContributionCheckDue event + lastContributionDay + `pieContributionsEnabled: Boolean = false` plan() parameter — MIRROR the `earningsReportsEnabled` parameter pattern EXACTLY incl. default-false for old-test compat), `settings/AppSettings.kt` (+`pieContributions: Boolean = true`), jvmCommon `FileSettingsStore`/`FileSchedulerStateStore` DTOs (absent-key tolerant); notifier: desktop `TrayNotifier` gains `notifyPieContribution(title, body)` in Task 11 — here only the shared port if one exists (check AlertPorts.kt for the MarketEventNotifier analog and mirror). Tests: planner fires once-per-day gated on the parameter; settings DTO back-compat.
- [ ] RED → implement → green → commit `feat(shared): contribution-check planner event and settings gate`.

### Task 10: Shared L10n keys
**Files:** Modify `shared/src/commonMain/.../l10n/L10n.kt`
Transcribe ALL Plans keys from Swift `L10n.swift` (~55: section, wizard incl. scheduleStartDay, cadences, drift, backtest, notifications, misses, toggle+subtitle) — EN byte-equal, DE/IT/ES copied from the Swift catalog (fix the recorded DE howler: use "Beitrag zu %s wurde ausgeführt"), `%@`→`%s`/positional conversion. Catalog completeness test updates (count assertion if one exists — grep).
- [ ] Implement → `:shared:jvmTest` + `:desktopApp:test` green → commit `feat(l10n): Plans catalog keys transcribed to shared catalog`.

### Task 11: Desktop ViewModels
**Files:** Create `desktopApp/src/main/kotlin/.../PlansViewModel.kt` + `PieWizardViewModel.kt`; Tests both.
**Transcribe from:** Swift `PlansViewModel.swift`/`PieWizardViewModel.swift` AS-BUILT (correction 7): rows w/ >5pp drift badge, reconcile-before-rows, detail w/ totalValue field, contribute amount>0 guard, stale-preview clearing, VM-owned debounced search w/ exclusion, equalSplit largest-remainder, schedule builder w/ START-DAY + preservation rule, non-throwing backtest. Compose-state idioms per existing desktop VMs (read `PortfolioViewModel.kt`/`CalendarViewModel.kt` for the mutableStateOf/coroutine patterns). Transcribe the Swift VM test fixtures (31+3).
- [ ] RED → implement → green → commit `feat(desktop): Plans and pie-wizard view models`.

### Task 12: Desktop UI + AppGraph + coordinator + settings
**Files:** Modify `desktopApp/.../PortfolioPane.kt` (PortfolioSection.Plans + switcher arm), Create `PlansPane.kt` + `PieWizardDialog.kt`, Modify `AppGraph.kt` (FilePieStore + use cases sharing `portfolioMutex` + VMs), `DesktopMarketActivityCoordinator` (ContributionCheckDue handler + launch catch-up gated on settings — correction 6; notification formatting in Main.kt per the L10n-free-coordinator convention — VERIFY that convention in the ledger/code and mirror), `TrayNotifier` (+notifyPieContribution), `Main.kt` (wiring + plan() parameter pass), `AccountPanel`/notifications page (+pieContributions toggle row), README (desktop Plans coverage — small; full README pass happens at M7.3 close).
UI anatomy mirrors Swift PlansSection/PieWizardView (cards w/ existing DonutChart reuse, drift bars data-colored, rebalance dialog honoring ConfirmTrades via existing TradeConfirm layer, wizard 4 steps w/ start-day field, backtest pane) — desktop Compose idioms (dialogs not sheets).
- [ ] Implement → `:desktopApp:test` green + `./gradlew :desktopApp:run` boots (user visually verifies) → commit `feat(desktop): Plans pane, wizard, rebalance preview, coordinator wiring`.

### Task 13: Suites + ledger close
- [ ] `./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest --rerun-tasks` — ALL green, android untouched at baseline; record exact counts from JUnit XML. Swift suite still green (`DEVELOPER_DIR=/Applications/Xcode.app swift test` — no Swift files touched; sanity). Commit any README count touch: `docs: M7.2 suite counts`.

## Self-Review Notes
- Spec §A–§D Kotlin/desktop coverage: model→T1, math→T2–4, attribution/persistence→T5–6, execution→T7, rebalance/reconcile/simulate→T8, planner/settings→T9, L10n→T10, UI/VMs→T11–12. Android = M7.3.
- Every M7.1 ledgered correction appears in Global Constraints and is named in its owning task.
- Type consistency: PieCadence/ContributionSchedule(anchorDay)/PieSlice(targetWeightPP) names used identically T1→T12.
