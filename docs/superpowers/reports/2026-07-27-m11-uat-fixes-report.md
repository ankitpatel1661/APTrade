# M11.1 UAT fixes — implementation report

**Branch:** `fix/m11-uat-goal-reset-staleness` (worktree `aptrade-uat-fix`), based on `8c135a7`.
**Plan:** `docs/superpowers/plans/2026-07-27-m11-uat-fixes.md`
**Commits (execution order F3 → F2 → F4 → F1):**

| SHA | Subject |
|---|---|
| `3eefbc6` | fix(ios): reload the whole performance report on every appearance (F3) |
| `afbee5d` | fix(ios): push a portfolio reset to the Performance screen's view model (F2) |
| `48cca23` | fix(ios): render the Value Goal card in every Performance state (F4) |
| `aa84e9f` | fix(shared)!: a portfolio reset no longer clears goals (user ruling) (F1) |

---

## Measured baselines (before any edit, on `8c135a7`)

| Suite | Plan says | Measured | Note |
|---|---|---|---|
| `swift test` | 715 | **715**, 0 failures | matches |
| `:shared:jvmTest` | 711 | **712**, 0 failures | off by one from the plan; nothing on this branch changed tests between `ad6de3b` and `8c135a7`, so the plan's 711 appears to be a stale transcription. Deltas below are measured against 712. |

Baselines not named in the plan but measured because F1 touches them: `:desktopApp:test` **391**, 0 failures;
`:androidApp:testDebugUnitTest` **282**, 0 failures (counts unchanged after the work — see final table).

---

## F3 — drop the `.idle` gate (done first, so F2 became a one-line call)

**Changed:** `Sources/APTradeApp/PerformanceViewModel.swift:69-93` — `onAppear()` now re-reads the goal
(cheap, so the card corrects before the network round trip) and then calls `load()` **unconditionally**;
the `if case .idle = state { await load() }` gate is gone. Doc comment rewritten to record why, including
the deliberate absence of `loadTask` cancellation (see the question below).

Also corrected, same file, `refreshValueProjection()`'s doc (`:132-140`): it claimed reset "can clear the
portfolio (and its goals)" — after F1 only the portfolio is replaced.

### Test added — `Tests/APTradeAppTests/PerformanceViewModelTests.swift:249`

`test_onAppear_afterFirstLoad_reloadsCurrentValue_notJustTheGoal`

**Wrong implementation it rejects:** the shipped
`func onAppear() { valueGoal = …; refreshValueProjection(); if case .idle = state { await load() } }`.
`currentValue` is assigned only inside `load()`. The fixture is an all-cash portfolio, so the first
`onAppear()` leaves `state == .empty` (never `.idle`) — the gated version therefore skips the second load
entirely and `currentValue` stays frozen at $100,000 after the store is swapped to $1,000,000.

RED (against the shipped gate):

```
Test Case '-[APTradeAppTests.PerformanceViewModelTests test_onAppear_afterFirstLoad_reloadsCurrentValue_notJustTheGoal]' started.
.../PerformanceViewModelTests.swift:260: error: -[... test_onAppear_afterFirstLoad_reloadsCurrentValue_notJustTheGoal] : XCTAssertEqual failed: ("Money(amount: 100000, currencyCode: "USD")") is not equal to ("Money(amount: 1000000, currencyCode: "USD")") - re-appearing must reload the report, not only re-read the goal
Test Case '... failed (0.574 seconds).
	 Executed 1 test, with 1 failure (0 unexpected) in 0.574 (0.574) seconds
```

GREEN (gate removed):

```
Test Case '-[APTradeAppTests.PerformanceViewModelTests test_onAppear_afterFirstLoad_reloadsCurrentValue_notJustTheGoal]' started.
Test Case '-[APTradeAppTests.PerformanceViewModelTests test_onAppear_afterFirstLoad_reloadsCurrentValue_notJustTheGoal]' passed (0.001 seconds).
	 Executed 1 test, with 0 failures (0 unexpected) in 0.001 (0.001) seconds
```

### Answer — is `loadTask` cancellation sufficient now that `load()` runs on every appearance?

**No, and it is not what I relied on — I deliberately did *not* add cancellation to `onAppear()`.**

1. `loadTask` (`PerformanceViewModel.swift:32`) is only ever assigned by `reload()`, the `didSet` path for
   `timeframe`/`benchmark`. The `onAppear()` load is owned by SwiftUI's `.task`, which cancels it when the
   view goes away — that, not `loadTask`, is what bounds its lifetime.
2. What makes an overlapping load safe is the requested-vs-current guard inside `load()`
   (`guard requestedTimeframe == timeframe, requestedBenchmark == benchmark else { return }`). Two
   concurrent loads for the *same* selection compute the same report, so the late writer is harmless.
3. Adding `loadTask?.cancel()` to `onAppear()` would have been actively **unsafe**. `load()` never polls
   `Task.isCancelled`, and `ComputePerformanceMetricsUseCase` swallows every failure — including a
   cancellation-induced one — into `PerformanceReport.empty` (`PerformanceUseCases.swift`, `try?` around
   each history fetch). A cancelled same-selection task would therefore resume, pass the guard (its
   selection still matches), and overwrite a good `.loaded` report with `.empty`. Today's cancellation is
   only safe *because* it happens exclusively when the selection changed, which makes the guard reject the
   cancelled result.
4. Redundant-fetch cost: yes, one report recompute per appearance, which is the point of the fix — it is
   the same cadence `IncomeSection` has always run at (`IncomeSection.swift:46`). The only genuinely
   redundant window is "navigate away and back while a timeframe reload is still in flight", which yields
   one extra identical fetch and no incorrect state.

---

## F2 — a reset must reach the Performance screen

**Changed:**
- `Sources/APTradeApp/PortfolioView.swift:240` — new `var onDidReset: (() async -> Void)? = nil` on the
  shared `PortfolioSummaryHeader`.
- `Sources/APTradeApp/PortfolioView.swift:292-305` — the sheet's confirm body moved out of the SwiftUI
  closure into `static func applyReset(amount:viewModel:settingsVM:onDidReset:)`, which persists the new
  default, `await`s `viewModel.reset(...)`, and *then* `await onDidReset?()`. Order is asserted.
- `Sources/APTradeApp/PortfolioView.swift:47-53` (header call at `:51-53`) — iOS host wires `onDidReset: { await performanceVM.onAppear() }`.
- `Sources/APTradeApp/RootView.swift:434-441` — macOS shell wires the identical closure to
  `portfolioPerformanceVM`. **Both hosts are wired**; the plan flagged wiring one as the exact asymmetry
  that produced this bug.

### Test added — `Tests/APTradeAppTests/PortfolioViewModelTests.swift:102`

`test_applyReset_notifiesTheHostAfterTheFreshPortfolioIsPersisted`

It exercises the **reset path itself** — `PortfolioSummaryHeader.applyReset`, the exact function the
confirm button runs — not a hand-called refresh. That is why the confirm body was extracted: a closure
inside a view body is unreachable from tests, and per GC2 a test that calls `performanceVM.onAppear()`
itself proves nothing about whether anything asks.

**Wrong implementation it rejects (#1):** the shipped confirm body
`{ settingsVM.settings.defaultStartingCash = amount; Task { await viewModel.reset(startingCash: amount) } }`
— i.e. resetting without notifying. I introduced exactly that (wrote `applyReset` without the
`await onDidReset?()` line) and ran:

```
Test Case '-[APTradeAppTests.PortfolioViewModelTests test_applyReset_notifiesTheHostAfterTheFreshPortfolioIsPersisted]' started.
.../PortfolioViewModelTests.swift:136: error: ... XCTAssertEqual failed: ("Money(amount: 100000, currencyCode: "USD")") is not equal to ("Money(amount: 1000000, currencyCode: "USD")") - the reset must reach the Performance screen's own view model
Test Case '... failed (0.321 seconds).
	 Executed 1 test, with 1 failure (0 unexpected) in 0.321 (0.321) seconds
```

GREEN after adding `await onDidReset?()`:

```
Test Case '-[APTradeAppTests.PortfolioViewModelTests test_applyReset_notifiesTheHostAfterTheFreshPortfolioIsPersisted]' started.
Test Case '-[APTradeAppTests.PortfolioViewModelTests test_applyReset_notifiesTheHostAfterTheFreshPortfolioIsPersisted]' passed (0.003 seconds).
	 Executed 1 test, with 0 failures (0 unexpected) in 0.003 (0.003) seconds
```

**Wrong implementation it rejects (#2)** — added after F1, so the test also pins the plan's end-to-end
target row (`$1,000,000 / $120,000 · Goal reached`): re-introducing F3's `.idle` gate. I restored the gate
in `PerformanceViewModel.onAppear()` and re-ran this test:

```
.../PortfolioViewModelTests.swift:136: error: ... XCTAssertEqual failed: ("Money(amount: 100000, currencyCode: "USD")") is not equal to ("Money(amount: 1000000, currencyCode: "USD")") - the reset must reach the Performance screen's own view model
.../PortfolioViewModelTests.swift:144: error: ... XCTAssertEqual failed: ("Optional(APTradeDomain.GoalProjection.insufficientHistory)") is not equal to ("Optional(APTradeDomain.GoalProjection.reached)")
	 Executed 1 test, with 2 failures (0 unexpected) in 0.380 (0.381) seconds
```

Reverted (`git checkout` of the source file), GREEN as pasted above.

**What remains untested:** that each of the two *hosts* passes the closure. That is a SwiftUI view-body
construction; with no ViewInspector in this project it is unreachable from unit tests. The extraction
shrinks the untested surface to one argument per host, and both are commented so a reader sees the pair.

---

## F4 — the Value Goal card is now genuinely unconditional

**Changed:** `Sources/APTradeApp/PerformanceSection.swift`
- `:20-24` — new `enum ReportBody { case spinner, empty, report(PerformanceReport) }`.
- `:26-36` — new `static func reportBody(for state:)`, exhaustive over `PerformanceViewModel.State`.
- `:49-59` — `content` has **no state switch at all** now: `ScrollView { goalCard; reportBody }`, matching
  `IncomeSection.swift:62-81`'s shape rather than inventing a third one.
- `:71-91` — `reportBody` switches on the mapping: spinner (`.idle`/`.loading`), `emptyState` (`.empty`),
  the report (`.loaded`). Previously `.idle`/`.loading` replaced the *entire* section with a bare
  `ProgressView()`, so the card the file's own doc comment promised did not exist on a cold launch.

Because F3 makes every appearance re-enter `.loading`, mapping `.loading` to the empty state would now
flash "Not enough history yet" over a healthy portfolio on every visit — that is the specific regression
the tests below guard.

### Tests added — `Tests/APTradeAppTests/PerformanceSectionTests.swift` (4 cases, new file)

`test_idle_drivesSpinner`, `test_loading_drivesSpinner_notTheEmptyState`, `test_empty_drivesEmptyState`,
`test_loaded_drivesReportCarryingThatExactReport`.

**Wrong implementation they reject:** any mapping that sends `.idle`/`.loading` somewhere other than the
spinner (I used `.idle → .empty`, `.loading → .empty`), a `.report` case that drops its associated value,
or collapsing the durable `.empty` state into a permanent spinner.

RED (with `.idle`/`.loading` mapped to `.empty`):

```
.../PerformanceSectionTests.swift:31: error: -[... test_idle_drivesSpinner] : XCTAssertEqual failed: ("empty") is not equal to ("spinner")
.../PerformanceSectionTests.swift:38: error: -[... test_loading_drivesSpinner_notTheEmptyState] : XCTAssertEqual failed: ("empty") is not equal to ("spinner")
.../PerformanceSectionTests.swift:39: error: -[... test_loading_drivesSpinner_notTheEmptyState] : XCTAssertNotEqual failed: ("empty") is equal to ("empty")
	 Executed 4 tests, with 3 failures (0 unexpected) in 0.347 (0.347) seconds
```

GREEN (mapping reverted):

```
Test Case '-[APTradeAppTests.PerformanceSectionTests test_empty_drivesEmptyState]' passed (0.002 seconds).
Test Case '-[APTradeAppTests.PerformanceSectionTests test_idle_drivesSpinner]' passed (0.000 seconds).
Test Case '-[APTradeAppTests.PerformanceSectionTests test_loaded_drivesReportCarryingThatExactReport]' passed (0.000 seconds).
Test Case '-[APTradeAppTests.PerformanceSectionTests test_loading_drivesSpinner_notTheEmptyState]' passed (0.000 seconds).
	 Executed 4 tests, with 0 failures (0 unexpected) in 0.003 (0.004) seconds
```

**Honest limit:** these tests pin the *mapping*, not the goal card's structural placement. Nothing in a
unit test can observe that `goalCard` sits outside the switch. What does defend it: `content` no longer
takes a `PerformanceViewModel.State` at all, and `ReportBody` is exhaustive, so re-introducing the defect
requires deliberately re-adding a state switch above the card rather than doing it by omission.

---

## F1 — goals survive a portfolio reset (user ruling, behaviour change, both platforms)

**Swift:**
- `Sources/APTradeApplication/PortfolioUseCases.swift:53-80` — `ResetPortfolioUseCase` **no longer has a
  `goalStore` property or init parameter**; `goalStore?.save([])` is gone. The optional-defaulting-to-nil
  parameter was removed outright, per the plan, rather than left unused.
- `Sources/APTradeApp/CompositionRoot.swift:137` — construction site updated.
- `Sources/APTradeApp/IncomeViewModel.swift:114-120` — its `NoOpGoalStore` doc claimed to "mirror
  `ResetPortfolioUseCase`'s `goalStore: GoalStore? = nil` rationale"; that referent no longer exists, so
  the comment now states its own (different, legitimate) rationale.

**Kotlin:**
- `shared/.../application/ResetPortfolio.kt:8-36` — `goalStore` constructor parameter and
  `goalStore.save(emptyList())` removed; KDoc rewritten (it previously argued at length for the parameter
  being *required*).
- `desktopApp/.../AppGraph.kt:152`, `androidApp/.../AppGraph.kt:619` — call sites.
- `androidApp/.../AppGraph.kt` — `PortfolioGraph`'s `goalStore` constructor parameter had no other use, so
  it and its argument at the `portfolio` lazy site are gone. `AppGraph.goalStore` (the `FileGoalStore` val,
  `:163`) is **kept**, with a comment saying it is currently unwired and is what M11.3's Android goal
  screens will read. Removing it would delete the file-path/format definition M11.3 needs; keeping it is
  not the hazard the plan warned about (that was a silently-defaulted dependency inside a use case).
- `desktopApp/.../portfolio/PortfolioViewModel.kt:377-402` — `reset()` no longer sets `valueGoal = null`
  nor publishes `valueGoal = null`. It re-reads the goal from the store and calls
  `refreshValueProjection()` after clearing the curve and recomputing `currentValue`. **This was required
  for parity**: with `ResetPortfolio` no longer clearing, the old desktop code would have hidden from the
  screen a goal still sitting intact on disk — the Swift UAT bug in mirror image.
- Test fakes that existed only to satisfy the removed parameter were deleted
  (`shared/.../PortfolioUseCasesTest.kt`'s and `androidApp/.../PortfolioViewModelTest.kt`'s private
  `InMemoryGoalStore`, plus their now-unused imports). The desktop one is still used by the VM fixture.

**Docs:** `docs/superpowers/specs/2026-07-25-m11-carry-notes.md` §2.5 (was: "Reset clears goals explicitly
via the use case") and §3.4 rewritten. §3.4 keeps its conclusion — goal state must re-read on every
appearance — with the real reason: a stale `currentValue` renders a wrong percentage, and `currentValue`
is assigned only in the report load, so re-reading the goal alone is not enough.

### Test 1 (inverted, not deleted) — `Tests/APTradeApplicationTests/GoalUseCasesTests.swift:64`

`test_reset_clearsGoals` → **`test_reset_leavesGoalsIntact`**.

**Wrong implementation it rejects:** `self.goalStore?.save([])` inside `callAsFunction`. RED was produced
by writing the inverted assertions while still constructing the use case the old way
(`ResetPortfolioUseCase(store:serializer:goalStore:)`), i.e. against the shipped implementation:

```
Test Case '-[APTradeApplicationTests.GoalUseCasesTests test_reset_leavesGoalsIntact]' started.
.../GoalUseCasesTests.swift:71: error: -[... test_reset_leavesGoalsIntact] : XCTAssertEqual failed: ("[]") is not equal to ("[APTradeDomain.Money(amount: 120000, currencyCode: "USD"), APTradeDomain.Money(amount: 6000, currencyCode: "USD")]") - a reset changes the starting capital, never the user's plan
Test Case '... failed (0.632 seconds).
	 Executed 1 test, with 1 failure (0 unexpected) in 0.632 (0.632) seconds
```

GREEN after removing the dependency (and dropping the argument from the test):

```
Test Case '-[APTradeApplicationTests.GoalUseCasesTests test_reset_leavesGoalsIntact]' started.
Test Case '-[APTradeApplicationTests.GoalUseCasesTests test_reset_leavesGoalsIntact]' passed (0.002 seconds).
	 Executed 1 test, with 0 failures (0 unexpected) in 0.002 (0.002) seconds
```

The test also asserts the reset still happened (`result.cash` and the persisted portfolio both
`$10,000`), so it cannot pass by the use case doing nothing.

### Test removed — `test_reset_withoutGoalStore_stillResetsPortfolio`

Deleted rather than kept. Its entire premise was the optional parameter ("`goalStore` defaults to `nil` so
existing call sites keep compiling… must not crash on the optional chain"); with no parameter there is no
optional chain and no second construction shape. Its surviving assertion (a reset with no goal store still
resets the portfolio) is now part of `test_reset_leavesGoalsIntact` above, and is separately covered by
`PortfolioUseCasesTests.test_reset_restoresStartingCash` / `test_reset_opensPortfolioAtRequestedStartingCash`.

### Test 2 (inverted, not deleted) — `shared/.../ResetPortfolioTest.kt:60`

`resetClearsEveryGoal` → **`resetLeavesEveryGoalIntact`**.

**Wrong implementation it rejects:** `goalStore.save(emptyList())` in `ResetPortfolio.execute`. Same
technique — inverted assertions run against the shipped implementation first:

```
ResetPortfolioTest[jvm] > resetLeavesEveryGoalIntact[jvm] FAILED
    java.lang.AssertionError: expected:<[Money(amount=5.0E+5, currencyCode=USD), Money(amount=6.0E+3, currencyCode=USD)]> but was:<[]>
    at ResetPortfolioTest.kt:60
3 tests completed, 1 failed
```

GREEN after the parameter removal:

```
com.aptrade.shared.application.ResetPortfolioTest tests 3 failures 0 errors 0
   PASSED resetPersistsTheFreshPortfolio[jvm]
   PASSED resetLeavesEveryGoalIntact[jvm]
   PASSED resetsToTheCallerSuppliedBalanceAndRecordsItAsStartingCash[jvm]
```

### Test 3 (inverted, not deleted) — `desktopApp/.../PortfolioResetAmountTest.kt:42`

`resetClearsTheValueGoalSoAStaleTargetCannotSurvive` → **`resetKeepsTheValueGoalAndRecomputesItAgainstTheFreshBalance`**.

**Wrong implementations it rejects, one per layer:** (a) `ResetPortfolio` clearing the store — caught by
`goalStore.goals.single().target`; (b) `PortfolioViewModel.reset()` setting `valueGoal = null` / publishing
`valueGoal = null` — caught by `assertNotNull(state.valueGoal)`. It further pins that the card
*recomputes*: `$1,000,000.00` current against a $120,000 target reads `GoalProjection.Reached`, which is
only reachable if the pre-reset curve was discarded and the fresh balance used.

RED (arm (b), with the shared clearing already removed so only the VM was wrong):

```
PortfolioResetAmountTest > resetKeepsTheValueGoalAndRecomputesItAgainstTheFreshBalance FAILED
    java.lang.AssertionError: actual value is null
    at PortfolioResetAmountTest.kt:42        (assertNotNull(state.valueGoal))
3 tests completed, 1 failed
```

GREEN after `reset()` re-reads the goal and calls `refreshValueProjection()`:

```
com.aptrade.desktop.portfolio.PortfolioResetAmountTest tests 3 failures 0 errors 0
   PASSED resetOpensThePortfolioAtTheSuppliedAmount
   PASSED theResetFieldRejectsOutOfRangeAmounts
   PASSED resetKeepsTheValueGoalAndRecomputesItAgainstTheFreshBalance
```

Arm (a) of the same test was proven by the `ResetPortfolioTest` RED above (identical wrong
implementation, one layer down); I did not re-run it a second time from the desktop test.

### Comment-only test change — `Tests/APTradeAppTests/PerformanceViewModelTests.swift:207-232`

`test_onAppear_reReadsGoal_evenWhenNotIdle_afterExternalReset` is unchanged in code but its doc claimed
"`ResetPortfolioUseCase` clears goals as a side effect of resetting the portfolio", which F1 makes false.
Re-documented around the case that still holds: two instances of the same screen (iOS pill host and macOS
sidebar shell) over one store, where a removal on one must not linger on the other. No assertions touched,
so no RED/GREEN pair applies.

### ⚠️ Does anything downstream assume goal-vs-portfolio consistency?

I traced every consumer of `GoalStore` / `PortfolioGoal` on both platforms. **No downstream code assumes a
goal was set against the portfolio that currently exists.** Findings:

- A `PortfolioGoal` is `(kind, target, createdAt)`. It carries **no** portfolio id, no baseline value, no
  starting balance — nothing that can dangle. There is no per-portfolio scoping to become stale.
- Every consumer measures the target against a value read *now*: Swift
  `PerformanceViewModel.refreshValueProjection()` (current value from the fresh report/portfolio, account
  age from `Portfolio.inceptionDate` read fresh on every call), `IncomeViewModel`'s income-goal projection
  (current run-rate from the current holdings), and their Kotlin twins. A reset therefore re-bases the
  card rather than orphaning it.
- The projection inputs that *would* have gone stale are exactly the ones both platforms now discard on
  reset: the equity curve (emptied), `currentValue` (recomputed from the fresh portfolio's floor), and
  account age (derived from `inceptionDate`, which a fresh portfolio has as `nil` → `GoalMath` reports
  `insufficientHistory`, not a fabricated ETA). That is the honest reading for a day-old account.
- `createdAt` survives the reset and now predates the portfolio. It is used for ordering/replacement in the
  store and is never used as a projection baseline, so this is cosmetic; the only place a user could notice
  is if a future feature renders "goal set on <date>" beside a portfolio that started later. Worth knowing,
  not worth blocking on.
- One real consequence, and it is the intended one: a value goal **below** the new starting cash reads
  `Goal reached` immediately after a reset upward. That is the plan's own acceptance row.

---

## GC1 — does the Kotlin/Compose desktop have the F2/F3/F4 defects?

Reported, not silently fixed and not silently skipped.

**F2 (reset doesn't reach Performance): NOT present.** The desktop has no second view model to notify —
`desktopApp/.../portfolio/PortfolioViewModel.kt` owns the portfolio *and* the performance/goal state in one
object, and `reset()` (`:388`) already updates the performance slice inline (clears `equityCurve`,
`performanceValues`, `performancePoints`, `metrics`, recomputes `currentValue`). The structural cause on
Swift — two independent `@State` view models over one store — does not exist there.

**F3 (`.idle` gate freezes `currentValue`): NOT present.** The desktop has no appearance-gated reload to
gate: `loadPerformanceReport()` runs once from `start()`'s first poll tick and again on every span or
benchmark change, and `reset()` recomputes `currentValue` directly from the fresh portfolio. There is no
code path where a portfolio change leaves `currentValue` pointing at a replaced portfolio.
(Observation, not a defect: after a reset the desktop shows `metrics = null` — the honest empty report —
until the next span/benchmark change rather than immediately refetching. Fresh portfolios have no curve to
fetch, so this is correct, just less eager than Swift's new behaviour.)

**F4 (goal card hidden while loading): NOT present — the desktop is the reference implementation.**
`desktopApp/.../portfolio/PerformanceSection.kt:86-99` (the `GoalCard(` call at `:93`) renders `GoalCard` first and unconditionally, with
a comment that already called out the Swift wave for putting it inside the loaded branch. Swift now
matches it.

**What the desktop *did* need, and got:** the F1 consequence — `reset()` nulling a goal that now survives
(fixed above, with an inverted test). That is the only Kotlin change outside `ResetPortfolio` itself.
Android has no goal surfaces yet (M11.3), so its `reset()` needed only the constructor update.

---

## Suite results

| Suite | Baseline (`8c135a7`) | After | Delta | Failures |
|---|---|---|---|---|
| `DEVELOPER_DIR=/Applications/Xcode.app swift test` | 715 | **720** | **+5** | 0 |
| `./gradlew :shared:jvmTest` | 712 (plan said 711) | **712** | **0** | 0 |
| `./gradlew :desktopApp:test` | 391 | **391** | 0 | 0 |
| `./gradlew :androidApp:testDebugUnitTest` | 282 | **282** | 0 | 0 |
| `./gradlew :androidApp:compileDebugKotlin` | — | BUILD SUCCESSFUL | — | — |

Swift +5 = +1 (F3) +1 (F2) +4 (F4) −1 (two F1 reset tests folded into one inverted test).
Kotlin ±0 = one test inverted and renamed in place, on each of `:shared` and `:desktopApp`.

Final full runs:

```
	 Executed 720 tests, with 0 failures (0 unexpected) in 5.011 (5.077) seconds

BUILD SUCCESSFUL in 9s          (:shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest)
shared jvmTest 712 tests, 0 failures
desktopApp 391 tests, 0 failures
androidApp 282 tests, 0 failures
```

`:shared:compileTestKotlinMacosArm64` remains known-red and was not run (out of scope, per the plan).

---

## Deviations, limits, and things I could not do

1. **`PortfolioSummaryHeader.applyReset` extraction (beyond the plan's letter).** The plan asked for an
   `onDidReset` callback fired after the awaited reset. I did that, and additionally lifted the confirm
   body into a static function so the reset path is reachable from a test — otherwise F2's fix would have
   had no discrimination-proven test at all, and GC2 explicitly rules out proving it with another
   `onAppear()`-calling test. No behavioural change beyond the added notification.
2. **Android `PortfolioGraph.goalStore` parameter removed** (not mentioned in the plan). It became an
   unused constructor parameter, which is the same class of latent hazard F1 is about. `AppGraph.goalStore`
   itself is kept for M11.3 with a comment explaining that it is intentionally unwired.
3. **F4 has no structural test.** No ViewInspector in this project; view hierarchy is not observable from
   XCTest. Mitigated by making the state→body decision a named, exhaustive, tested function and by leaving
   `content` state-free. Stated as a limit rather than papered over.
4. **Host wiring of `onDidReset` (two call sites) is untested** for the same reason. Both are wired and
   cross-referenced in comments.
5. **Kotlin baseline is 712, not the plan's 711.** Measured before any edit. Deltas are reported against
   the measured number.
6. **UAT of the running app was not performed** — per the standing project note, the iPhone/desktop build
   can't be driven from here. The plan's three-row acceptance table is covered at the view-model seam
   (`test_applyReset_notifiesTheHostAfterTheFreshPortfolioIsPersisted` asserts rows 1 and 2 including
   `.reached`; row 3 — card visible before the load completes — is the F4 mapping tests plus the
   state-free `content`), but the on-device confirmation is still owed to the user.
7. **Not touched, as instructed:** the Home-screen mixed decimal/comma formatting (recorded as out of
   scope in the plan) and everything PR #3 already changed.
