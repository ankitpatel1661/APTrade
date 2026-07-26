# M11 Swift backport — closing the four recorded Swift/Kotlin divergences

**Branch:** `feature/m11-swift-backport-parity`
**Reference:** `docs/superpowers/specs/2026-07-25-m11-carry-notes.md` §4c, §4f.1, §4f.2, §4a.2, §4a.3
**User ruling 2026-07-26:** full parity — all four divergences close. No recorded Swift/Kotlin
divergence in the M11 surface may survive this branch.

The Kotlin as-built on `main` is the **byte-authoritative reference**. Transcribe semantics from the
named Kotlin files; do not re-derive the algorithms. Every one of these four was found by a review
that re-derived the maths independently, and three of them are live defects putting wrong dollar
figures on a user's screen right now.

---

## Global Constraints

These bind every task. A reviewer should treat a violation as a Critical finding.

### GC1 — Kotlin is authoritative; Swift idiom is the only permitted difference

Semantics must match the Kotlin twin exactly. The **only** legitimate differences are language
idiom:

- Swift `DividendEvent.exDate` is a `Date`; Kotlin's is `exDateEpochSeconds: Long`. Use `Date` and
  `TimeInterval` in Swift. Convert via `timeIntervalSince1970` **only** where a mean/centroid must
  be computed.
- Swift `ForecastYear` and `ScheduledDividend` are **nested** in `DividendMath`; Kotlin's are
  top-level. Keep Swift's nesting.
- Swift `GoalProjection` is a flat `enum`; Kotlin's is a `sealed class`. Keep Swift's enum.
- Swift uses `Decimal` + `Money`; Kotlin uses ionspin `BigDecimal`. Keep Swift's types.

If a Kotlin construct has no clean Swift equivalent, say so in the task report rather than
inventing a divergence.

### GC2 — Exact constants, no new literals

Reuse the constants that already exist. Do not introduce a second copy of any of these:

| Constant | Value | Home |
|---|---|---|
| `DividendMath.minDividendGrowth` | `-0.20` | already exists, `DividendMath.swift:147` |
| `DividendMath.maxDividendGrowth` | `0.25` | already exists, `DividendMath.swift:149` |
| `GoalMath.minAnnualGrowth` | `-0.5` | already exists, `GoalMath.swift:27` |
| `GoalMath.maxAnnualGrowth` | `1.0` | already exists, `GoalMath.swift:28` |
| `GoalMath.minimumHistoryDays` | `180` | already exists, `GoalMath.swift:24` |
| `GoalMath.horizonYears` | `30.0` | already exists, `GoalMath.swift:26` |
| seconds per year | `365.25 * 86_400` | **extract a named constant** — see GC3 |

The per-symbol clamp pair (`-0.20 … 0.25`) and the portfolio clamp pair (`-0.5 … 1.0`) are
**deliberately different and must stay independent** (carry-notes §3.6). Do not unify them.

### GC3 — Extract `secondsPerYear`; do not add a fifth copy of `365.25 * 86_400`

`DividendMath.swift` already has `private static let secondsPerDay: TimeInterval = 86_400` (line 26)
and then spells `365.25 * 86_400` inline in several places. Add
`private static let secondsPerYear: TimeInterval = 365.25 * 86_400` beside it and use it. Kotlin has
exactly this (`SECONDS_PER_YEAR`, `DividendMath.kt:189`).

### GC4 — Every test must be discrimination-proven (carry-notes §4e)

This is the constraint this milestone exists to honour. **Five times** in M11.1/M11.2 a test passed
in correct code because the fixture made the right and wrong implementations produce identical
output — including the test guarding M11.2's headline constraint.

For **every** test whose purpose is to pin a decision:

1. Name in a comment the **specific wrong implementation** the test rejects.
2. Temporarily introduce that wrong implementation, run the test, **confirm RED**.
3. Revert, confirm GREEN.
4. **Paste both the RED and the GREEN output into the task report.** A task report claiming
   discrimination without pasted RED output is an incomplete report and will be sent back.

Additionally:

- **Never** write an assertion of the form `assertTrue(before != after || before == someCase)`. If
  the fixture makes the second disjunct true, the test passes unconditionally. Assert the
  **specific** post-action value, with no disjunction. (This exact shape left M11.2's headline
  constraint completely unguarded.)
- Be suspicious of any assertion landing on a clamp bound, an exact zero, or an enum case reachable
  by several paths — those are where the two behaviours converge.
- Prefer fixtures where correct and incorrect answers are **numerically distinct by construction**,
  and state both numbers in a comment.

### GC5 — Comment the divergences that close

Each of these four sites carries a Kotlin comment recording it as a backport candidate. When the
Swift side lands, the Swift site gets the **explanation** (why the algorithm is what it is), not a
copy of the divergence bookkeeping. Do not write "ported from Kotlin" as the explanation — write
what a reader needs to not reintroduce the bug.

The Kotlin side's now-stale "BACKPORT CANDIDATE" wording is **out of scope for this branch** and is
handled in Task 5.

### GC6 — Run the tests, report the real output

`swift test` in this repo requires `DEVELOPER_DIR`:

```
DEVELOPER_DIR=/Applications/Xcode.app swift test
```

Report actual output. A task whose tests were not run is not DONE.

---

## Task 1 — Port the growth-rate algorithm (carry-notes §4c, live defect)

**File:** `Sources/APTradeDomain/DividendMath.swift`
**Kotlin reference:** `shared/src/commonMain/kotlin/com/aptrade/shared/domain/DividendMath.kt`
— `dividendGrowthRate` (:260), `measuredDividendGrowthRate` (:300), `averagePerPayment` (:339),
`meanEpochSeconds` (:347), `clampGrowth` (:353), `hasMeasurableGrowth` (:276),
`anyPositionHasMeasurableGrowth` (:286). Read the CARRY-NOTE 6 KDoc at `:204` in full before
writing any code — it contains the entire derivation and both defects that were fixed.

### The defect being fixed

`DividendMath.dividendGrowthRate` (`DividendMath.swift:155-181`) derives growth by dividing two
rolling **365-day** dividend sums sampled at two instants. A 365-day window contains 5 quarterly
ex-dates sometimes and 4 other times (4 × 91 = 364 < 365), so a **genuinely flat payer reads
anywhere from −20% to +25%/yr purely on ex-date phase**. Reproduced byte-for-byte at
`0.13678039345929127`. Over the 30-year horizon that is roughly a **45× divergence in reported
income**, and it feeds both the income-goal ETA and the forecast chart.

A second structural contributor: the early anchor is `first + 365.25 days` while
`trailingAnnualPerShare`'s window is a strict 365 days — a 21,600-second (6-hour) mismatch that
structurally excludes the early window's own first event.

### 1a. Replace the algorithm

Rewrite the body as a private `measuredDividendGrowthRate(events:asOf:) -> Decimal?` returning
`nil` wherever growth cannot be honestly measured, with `dividendGrowthRate` as a thin
`?? 0` wrapper. Transcribe from Kotlin's `measuredDividendGrowthRate`:

1. Window: events with `exDate <= asOf` and `exDate >= asOf - 5 * secondsPerYear`, sorted ascending.
   `guard window.count >= 2 else { return nil }`.
2. `totalSpanYears = (window.last!.exDate - window.first!.exDate) / secondsPerYear`;
   `guard totalSpanYears >= 2 else { return nil }`. (Use safe indexing, not force-unwrap — see GC7
   below.)
3. **Count-matched halves.** `let half = window.count / 2`, `earlyHalf = window[0..<half]`,
   `lateHalf = window[(window.count - half)...]`. On an **odd-count window the middle event is
   dropped from both halves** — this is deliberate; assigning it to either half reintroduces an
   asymmetry between the two counts.
4. `earlyAvg` / `lateAvg` = **average per-payment amount** of each half (sum ÷ count), as `Double`.
   `guard earlyAvg > 0, lateAvg > 0 else { return nil }`.
5. **Centroid-gap divisor.** `centroidGapYears = (mean(lateHalf.exDate) - mean(earlyHalf.exDate)) /
   secondsPerYear`, where each mean is the arithmetic mean of the half's ex-dates as
   `timeIntervalSince1970`. `guard centroidGapYears > 0, centroidGapYears.isFinite else { return nil }`.
6. `ratio = lateAvg / earlyAvg`; `guard ratio > 0`. `rate = pow(ratio, 1.0 / centroidGapYears) - 1.0`;
   `guard rate.isFinite`. Return clamped to `minDividendGrowth ... maxDividendGrowth`.

**Both step 3 and step 5 must land together.** Porting the count-normalization without the
centroid-gap divisor removes the aliasing bug but reproduces a *different*, systematic bias — a true
10% grower with 5 years of history reads 6.560%, a 2.51× divergence in reported income over 29
compounding years. That defect is silent and calibrated-looking, unlike the aliasing artifact it
replaces. The old divisor `spanYears = years - 1` is a holdover from the old design and must be
deleted, not adapted.

### 1b. Add the measurability API

Kotlin split these out so `dividendGrowthRate` and `hasMeasurableGrowth` **share one function body
and cannot drift**. Port that structure exactly — do not write a second set of preconditions.

```swift
public static func dividendGrowthRate(events: [DividendEvent], asOf: Date) -> Decimal
public static func hasMeasurableGrowth(events: [DividendEvent], asOf: Date) -> Bool
public static func anyPositionHasMeasurableGrowth(positions: [Position],
                                                  eventsBySymbol: [String: [DividendEvent]],
                                                  asOf: Date) -> Bool
```

`hasMeasurableGrowth` is `measuredDividendGrowthRate(...) != nil`.
`anyPositionHasMeasurableGrowth` mirrors Kotlin's `:286` — verify its exact quantity/inclusion
guard against the Kotlin source rather than assuming it matches `incomeForecast`'s.

Consumed by Task 3. Nothing else in this task calls it.

### 1c. Tests — `Tests/APTradeDomainTests/DividendMathForecastTests.swift`

Existing growth tests every one of which resolves to exactly `0.0`, exactly `minDividendGrowth`, or
exactly `maxDividendGrowth`: `test_growthRate_flatHistory_isZero` (:28),
`test_growthRate_clampsHighGrowthToTwentyFivePercent` (:44),
`test_growthRate_clampsCollapseToMinusTwentyPercent` (:52),
`test_growthRate_insufficientHistory_isZero` (:60),
`test_growthRate_ignoresHistoryOlderThanFiveYears` (:67). The reviewer verified that **inverting the
annualization root entirely** left the whole Swift suite green.

Add, each satisfying GC4:

1. **Anti-aliasing pin.** Kotlin twin: `aFlatThreeYearHistoryHasZeroGrowthEvenWithExactCadenceAliasing`
   (`DividendForecastTest.kt:91`). A flat payer, newest event exactly at `asOf`, exact 91-day
   spacing, must read **exactly 0.0**. Under the old algorithm this reads `0.13678039345929127` —
   state both numbers in the comment.
2. **Mid-range annualization pin.** Kotlin twin:
   `dividendGrowthRateAnnualizesAMidRangeRatioPrecisely` (`DividendForecastTest.kt:118`). Engineer a
   fixture with an **exact 2.0-year centroid gap** and an exact 1.44 late/early per-payment ratio, so
   `1.44^(1/2) - 1 = 0.20` exactly — comfortably inside both clamps, so a wrong exponent fails loudly
   instead of clamping. The Kotlin fixture's offsets are verified: late half at 27/118/209/300 days
   before `asOf` at `$0.36`, early half at 4 payments 91 days apart with the oldest at
   `1030.5 * 86_400` seconds before `asOf` at `$0.25`. **Assert additionally that both the
   inverted-exponent mutation and the total-span-denominator mutation produce values ≥ 1e-3 away
   from 0.20**, exactly as the Kotlin test does — that is what makes this fixture provably
   discriminating rather than merely passing.
   ⚠️ A fixture tuned to an exact **total span** instead of an exact **centroid gap** would falsely
   appear to validate the absence of step 5. This is precisely how the Kotlin round-1 test passed
   while carrying the round-2 defect. Force the centroid gap.
3. **True-CAGR recovery.** Kotlin twin: `dividendGrowthRateRecoversAKnownTruePerPaymentCagr`
   (`DividendForecastTest.kt:157`). 12 quarterly payments growing continuously at a known true 10%
   annual rate sampled every 91 days; the late half is an exact rigid time-translate of the early
   half, so the ratio equals `(1 + trueRate) ^ centroidGapYears` exactly and the true rate is
   recovered to double-precision noise. **This is the strongest single regression guard for this
   function** and the property that would have caught the round-1 defect immediately.
4. **DRIP with non-zero growth.** Carry-notes §4c backport step 6: on **both** platforms every DRIP
   test uses growth 0 and every growth test has DRIP off, so the "reinvestment price grows at the
   dividend rate" choice is **entirely uncovered**. Add a forecast test with DRIP on *and* a
   non-zero growth rate, asserting a specific expected income for a named year.
5. **Correct the two clamp tests' comments** (backport step 7) — `test_growthRate_clampsHighGrowthToTwentyFivePercent`
   (:44) and `test_growthRate_clampsCollapseToMinusTwentyPercent` (:52) state raw rates the code does
   not actually compute. Fix the comments to state what is really computed. Do not weaken the
   assertions.

`test_growthRate_recoversTenPercentGrowth` (:35) is the one existing test not pinned to a
saturated value. **Re-derive its expected value under the new algorithm** — if it changes, that is
expected and correct; state the old and new values in the report and explain the delta. Do not
silently retune it to whatever the new code prints.

### GC7 — no force-unwraps

`CLAUDE.md` forbids them project-wide. The existing body uses `window[window.count - 1]` and
`window[0]` after a count guard, which is fine; `Decimal(string:)!` on a literal constant is the
established idiom in this file and may stay. Do not add `first!` / `last!`.

**Commit:** `fix(domain): derive dividend growth from count-matched halves and centroid gap`

---

## Task 2 — Guard the dividend calendar against suspended payers (carry-notes §4f.1, live defect)

**File:** `Sources/APTradeDomain/DividendMath.swift` — `projectedSchedule` (:267-294)
**Kotlin reference:** `DividendMath.kt:443-494`; the guard itself is `:471`.

### The defect being fixed

`projectedSchedule` rolls a stale cadence forward with **no trailing-income guard** — only
`shares > 0`. A holding whose last real ex-date is ~400 days ago is still inside `IncomeViewModel`'s
730-day cadence-inference lookback, so a cadence *is* inferable, but is outside
`trailingAnnualPerShare`'s 365-day window. It therefore produces four quarterly calendar rows at the
old per-share amount, with month totals, **while three neighbouring components on the same screen
correctly value it at zero**: the summary card's projected-annual, the forecast chart
(skips `trailing <= 0`), and the Upcoming Dividends list (`nextProjected` lands in the past and is
filtered).

The "est." badge disclaims **the date**, not **that the company still pays**.

### 2a. Add the guard

One line, immediately after the `shares > 0` guard and before `nextProjected`:

```swift
guard trailingAnnualPerShare(events: events, asOf: asOf).amount > 0 else { continue }
```

Note `events` must be bound before this guard — reorder the existing `let events = ...` above it.

### 2b. Comment it (GC5)

Carry-notes §4g: `trailingAnnualPerShare(events, asOf).amount > 0` is the **single
staleness/inclusion test for all four income surfaces** — the multi-year forecast, the summary
card's projected annual, the Upcoming Dividends list, and this calendar. Say so at the guard, and
say why the `while next <= asOf` roll-forward is not itself a staleness bound (it will happily roll a
payer that stopped a year ago into next quarter). Any future task adding an income surface applies
the same test; any task changing it changes it in one place.

### 2c. Tests — `Tests/APTradeDomainTests/DividendScheduleTests.swift`

Kotlin twins: `aPayerWhoseLastRealExDateIsBeyondTheTrailingWindowIsExcludedFromTheCalendar` and
`aCurrentlyPayingHoldingStillAppearsOnTheCalendar` (`DividendForecastTest.kt:441` and `:464`).

1. **The exclusion.** Four quarterly `$0.25` events at 673/582/491/400 days before `asOf` — inside a
   cadence-inference lookback, outside the trailing window. First `assert` that
   `trailingAnnualPerShare` already reads zero for this fixture (a sanity precondition, so a future
   fixture drift fails loudly rather than making the real assertion vacuous). Then assert the
   schedule is **empty**. Comment must state that without the guard this fixture emits exactly
   **four** rows of `100 × $0.25 = $25` at `asOf` + 55/146/237/328 days — a specific wrong non-empty
   answer, not a vacuous one.
2. **The mirror.** A currently-paying holding must **not** be caught by the new guard — assert
   non-empty. Without this, deleting the whole function body would satisfy test 1.

GC4's revert-and-confirm applies: remove the guard, paste the RED output showing the four rows.

**Commit:** `fix(domain): stop the dividend calendar resurrecting suspended payers`

---

## Task 3 — Gate the income projection on measurability and the horizon (§4f.2 live defect, §4a.3)

**Files:** `Sources/APTradeDomain/GoalMath.swift`, `Sources/APTradeApp/IncomeViewModel.swift`
**Kotlin reference:** `GoalMath.kt:154-225` — read the full KDoc, especially the Finding C
paragraph, before writing code.

### The two defects being fixed

**§4f.2 (live).** `GoalMath.incomeProjection` (`GoalMath.swift:83`) has no measurability parameter.
`dividendGrowthRate` returns `0` both for a genuinely flat seasoned payer **and** when growth cannot
be measured at all. So a young account with three quarterly payments reads **"Not on track at
current rate"** on the income card, while the value card on the same account reads **"Tracking —
needs more history"**. Identical situation, contradictory copy, on two cards this milestone
deliberately made symmetric and always-visible — and the income wording is the wrong one.

**§4a.3.** Swift returns `.years(crossing.yearOffset)` **unbounded**, so a crossing at year 35
renders a concrete "35 yrs" while `valueProjection` returns `.beyondHorizon` for the same span. The
"> 30 yrs" rule is currently satisfied only by caller discipline that nothing enforces.

### 3a. New signature

```swift
public static func incomeProjection(current: Money,
                                    target: Money,
                                    forecast: [DividendMath.ForecastYear],
                                    hasMeasurableGrowth: Bool) -> GoalProjection
```

Source-breaking: 1 production call site (`IncomeViewModel.swift:260-262`) and 12 tests in
`GoalMathTests.swift`. **Do not add a default value** for `hasMeasurableGrowth` — carry-notes §5:
"a defaulted parameter whose omission is a correctness bug is not a default, it is a re-armed bug
with a compiler that will never complain." Every call site must state its answer.

### 3b. Body, in this exact order (transcribe from Kotlin `:216-225`)

1. `degenerateOrReachedProjection` early return (unchanged).
2. `guard let last = forecast.last else { return .insufficientHistory }`.
3. Crossing: if a year's income ≥ target, return `.beyondHorizon` when
   `Double(crossing.yearOffset) > horizonYears`, else `.years(Double(crossing.yearOffset))`.
   **This is the §4a.3 fix** — interpolate `horizonYears`, never a hardcoded 30.
4. If no year carries positive income → `.insufficientHistory` (already present; keep).
5. **If `!hasMeasurableGrowth` → `.insufficientHistory`.** ← the §4f.2 fix.
6. Else `last.income > current ? .beyondHorizon : .notOnTrack`.

Steps 4 and 5 are **coextensive in production** but both are kept for clarity (carry-notes §4g) —
an all-zero forecast implies no position passed the inclusion test. Do not read the overlap as a
defect and delete either.

⚠️ Step 5 is a **new decision surface, not a refinement**. With DRIP enabled, reinvestment compounds
share count and therefore income even at an exactly-0% measured per-share rate, so the forecast can
be genuinely increasing (e.g. $125 → $130.30 → $159.01) while `hasMeasurableGrowth` is false. Absent
the guard that shape falls through to `last.income > current` and reports `.beyondHorizon`; with it,
`.insufficientHistory`. This is a real behaviour change for a DRIP-on young payer, defensible on the
merits. Document it as a case this guard **flips**, not one it merely confirms — a prior Kotlin KDoc
claimed the forecast is "provably flat" whenever measurability is false, which is false, and it had
to be corrected.

### 3c. Wire the call site

`IncomeViewModel.swift:260-262` (inside `refreshGoalProjection()`). Pass
`DividendMath.anyPositionHasMeasurableGrowth(positions:eventsBySymbol:asOf:)` from Task 1, built
from the **same** positions and events the forecast at `:247` was built from. If those differ, say
so in the report rather than papering over it.

Carry-notes §3.3: the forecast handed to `incomeProjection` must remain exactly `horizonYears` long,
independent of the 5/10/20/30 chart pill. Confirm this call site still passes the full 30-year
forecast (`:247`), not the pill-scoped one (`:231`), and state the confirmation in the report.

### 3d. Tests

Update the 12 existing `GoalMathTests.swift` call sites. Each must pass the value that preserves
its **original intent** — a test named `..._isNotOnTrack` documents a *seasoned flat payer*, so it
passes `true`. Do not blanket-pass `false` to make things compile; that would silently convert most
of the suite into insufficient-history assertions.

Add, satisfying GC4 (Kotlin twins in `GoalMathTest.kt`):

1. `aFlatForecastWithUnmeasurableGrowthReportsInsufficientHistoryNotNotOnTrack` (`:248`) — flat
   positive forecast, `hasMeasurableGrowth: false` → `.insufficientHistory`.
2. `aFlatForecastWithMeasuredGrowthStillReportsNotOnTrack` — the **mirror**, same fixture,
   `true` → `.notOnTrack`. Without the mirror, hardwiring `.insufficientHistory` passes test 1.
3. The DRIP-compounded case (`:344` twin): an increasing forecast (`$125 → $130.30 → $159.01`) with
   `hasMeasurableGrowth: false` → `.insufficientHistory`, **not** `.beyondHorizon`. This is the case
   3b's ⚠️ describes; it is the one that proves the guard is a new decision surface.
4. **Horizon clamp (§4a.3).** A forecast whose crossing year is **beyond** `horizonYears` returns
   `.beyondHorizon`, not `.years(35)`. Derive the expectation by interpolating `horizonYears` —
   never a hardcoded 30 (carry-notes §3.5). Check `test_incomeProjection_reachesExactlyAtHorizonBoundary`
   (:174) still passes: **exactly 30 is inside** the horizon (Kotlin `exactlyThirtyYearsIsInsideTheHorizon`,
   `GoalMathTest.kt:301`) — the comparison is `>`, not `>=`.
5. Carry-notes §3.1: the income goal's `current` must equal forecast year 1. If any fixture here
   makes those diverge, flag it rather than adjusting.

**Commit:** `fix(domain): gate income projection on measurable growth and the horizon constant`

---

## Task 4 — Port the account-age history gate (carry-notes §4a.2)

**Files:** `Sources/APTradeDomain/Portfolio.swift`, `Sources/APTradeDomain/GoalMath.swift`,
`Sources/APTradeApplication/PortfolioUseCases.swift`, `Sources/APTradeApp/PerformanceViewModel.swift`
**Kotlin reference:** `GoalMath.kt:78-85` (`accountAgeDays`), `:109-129` (`annualGrowthRate`, both
gates), `:142-152` (`valueProjection`), `Portfolio.kt:51` (`inceptionEpochSeconds`),
`desktopApp/.../portfolio/PortfolioViewModel.kt:303-308` (the wiring).

Swift's `GoalMath.minimumHistoryDays = 180` measures the span of the **equity curve**, which is
~365 days for anyone holding a seasoned symbol — so the floor is nearly inert. Kotlin measures
**account age from the first transaction date** as well, which is what the 30→180 raise was actually
intended to protect.

**Both gates are required** — account age ≥ 180 days **AND** curve span ≥ 180 days. The M11.2 Task 6
implementer read "measure from the first transaction date *instead*" as replacing the span floor and
reduced the span requirement to 1 day, reproducing exactly the fabrication the floor exists to
prevent: for a 400-day-old account, a 30-day curve up 5% annualizes to **+81.1%/yr**, passes under
the +100% clamp, and renders a confident **"1.2 yrs"** ETA. Reachable in production, because
`performanceSeries`'s all-priced gate truncates the entire curve to the newest-history symbol's
first candle — so an old account that buys one recently-listed symbol collapses its own curve span.

Swift already has the span gate. **Add the age gate on top; do not swap one for the other.**

Accepted consequence, deliberate: a genuinely new account gets `.insufficientHistory` regardless of
how seasoned its holdings are. That is the honest reading — the projection extrapolates *the
account's* growth rate. **This is a user-visible behaviour change to shipped Swift**, ruled in by the
user on 2026-07-26.

### 4a. One named inception derivation on `Portfolio`

Swift has **no** inception concept on `Portfolio` today. It does have an inline one-off at
`PortfolioUseCases.swift:133` — `portfolio.transactions.map(\.date).min()` — inside the
`sinceInception` curve trim.

Kotlin's `Portfolio.inceptionEpochSeconds()` KDoc is explicit that it is *the* ONE named derivation
and that "nobody re-derives `transactions.minOfOrNull { it.epochSeconds }` locally", precisely so the
goal floor and the `sinceInception` trim cannot drift apart. Port that discipline, not just the
value:

1. Add to `Portfolio`: `public var inceptionDate: Date? { transactions.map(\.date).min() }` — a
   computed property, not stored (nothing new to persist, no `Codable` change, no migration).
2. **Refactor `PortfolioUseCases.swift:133` to call it** instead of re-deriving inline. If you leave
   two derivations in the tree this task has failed its point. Behaviour there must not change —
   the existing `sinceInception` tests must stay green untouched.

### 4b. `GoalMath.accountAgeDays`

```swift
public static func accountAgeDays(inception: Date?, asOf: Date) -> Double?
```

`nil` when `inception` is `nil` (an account that has never traded). Otherwise
`asOf.timeIntervalSince(inception) / secondsPerDay`, **floored at 0** (Kotlin: `max(days, 0.0)`) so a
clock skew or a future-dated transaction cannot produce a negative age.

Doc comment must say: feed it `Portfolio.inceptionDate` — never a locally re-derived minimum.

### 4c. Both gates in `annualGrowthRate`

```swift
public static func annualGrowthRate(curve: [EquityPoint], accountAgeDays: Double?) -> Decimal?
```

Add, **before** the existing curve logic:

```swift
guard let accountAgeDays, accountAgeDays >= Double(minimumHistoryDays) else { return nil }
```

Then leave the existing curve-span gate **exactly as it is**. Both gates use the same
`minimumHistoryDays` constant; do not introduce a second threshold.

`valueProjection` gains the same `accountAgeDays: Double?` parameter and forwards it. **No default
value** on either — same reasoning as Task 3a.

Update the KDoc on `minimumHistoryDays` (`GoalMath.swift:14-23`), which currently explains that the
floor only guards *price* history and is nearly inert. That is no longer the whole story — say what
each gate now protects and why both are needed.

### 4d. Wire `PerformanceViewModel`

`refreshValueProjection()` (`PerformanceViewModel.swift:120-125`) is the **only** production call
site. The view model already holds `fetchPortfolio: FetchPortfolioUseCase` and already calls it at
`:111` — **no new dependency injection is needed**. Obtain the portfolio, derive
`GoalMath.accountAgeDays(inception: portfolio.inceptionDate, asOf: <now>)`, and pass it.

Match how the surrounding code obtains "now" rather than introducing a new clock; if the file has no
established idiom, say so in the report instead of inventing one.

Carry-notes §3.4: goal state must re-read on every appearance, because reset clears goals. Confirm
this change does not introduce a first-load flag or cache that would let a stale account age survive
a reset, and state the confirmation in the report.

### 4e. Tests — `Tests/APTradeDomainTests/GoalMathTests.swift`

The 8 existing `annualGrowthRate` call sites (lines 29, 30, 36, 37, 41, 45, 49, 54) and 8
`valueProjection` call sites (59, 65, 72, 75, 86, 96, 104, 107) all need the new argument. **Pass a
seasoned age** (e.g. `500`) wherever the test's intent is to exercise curve-span or rate behaviour —
passing `nil` would make most of the suite trivially return `.insufficientHistory` and silently
destroy its coverage. The two existing floor tests at `:27` and `:33` must keep pinning **curve-span**
behaviour specifically, with the age gate held open.

Add, satisfying GC4 (Kotlin twins in `GoalMathTest.kt`):

1. `accountAgeIsNullWithNoTransactions` (`:48`) — `accountAgeDays(inception: nil, ...)` is `nil`.
2. `accountAgeIsTheSpanFromTheFirstTransaction` (`:53`) — 200 days reads `200.0`.
3. **The divergence pin** (`:66`) — a **new account holding a seasoned symbol**: a long, healthy
   curve (well past the span floor) but an account age under 180 days must return
   `.insufficientHistory`. This is the test that proves the age gate exists; without it the whole
   task is unguarded.
4. **The converse, dual-gate pin** (`:85`) — an **old account with a thin curve**: age well past 180
   days but a curve spanning under 180 days must *also* return `.insufficientHistory`. This is the
   test that proves the span gate was **not** dropped — exactly the mistake the M11.2 implementer
   made. Both 3 and 4 are required; either alone permits swapping one gate for the other.
5. `accountAgeDaysWiresThroughARealPortfoliosInception` (`:359`) — end-to-end through a real
   `Portfolio`: one buy 200 days ago reads `200.0`; an untraded portfolio reads `nil`. This pins
   4a's computed property, not just the arithmetic.
6. A test pinning that `PortfolioUseCases`' `sinceInception` trim and `Portfolio.inceptionDate` agree
   — i.e. that 4a.2's refactor did not change trim behaviour. If an existing test already covers the
   trim, note it in the report and do not duplicate.

**Commit:** `fix(domain): gate portfolio growth on account age as well as curve span`

---

## Task 5 — Retire the stale backport-candidate markers in Kotlin

**Files:** `shared/src/commonMain/kotlin/com/aptrade/shared/domain/DividendMath.kt`,
`shared/src/commonMain/kotlin/com/aptrade/shared/domain/GoalMath.kt`

Four Kotlin comment blocks currently describe Swift divergences that Tasks 1-4 close. Left alone,
they tell a future reader the platforms disagree when they no longer do — the same class of
stale-documentation defect M11.2 found in a KDoc.

- `DividendMath.kt:204` CARRY-NOTE 6 — "recorded divergence from the Swift AS-BUILT, pending
  backport". **Keep the entire derivation** (it is the best explanation of the algorithm on either
  platform); retire only the pending-backport framing.
- `DividendMath.kt` — the `projectedSchedule` inclusion-guard note, if it names Swift as divergent.
- `GoalMath.kt:154` `incomeProjection` — "DELIBERATE DIVERGENCE FROM SWIFT, BACKPORT CANDIDATE".
- `GoalMath.kt:45` — `MINIMUM_HISTORY_DAYS` "a Swift BACKPORT CANDIDATE, not a transcription slip".

Rewrite each to describe the behaviour and its rationale in the present tense, noting the platforms
now agree. Do not delete the derivations — they are why the code is correct.

Also update `docs/superpowers/specs/2026-07-25-m11-carry-notes.md`: mark §4c, §4f.1, §4f.2, §4a.2 and
§4a.3 as **closed on both platforms** with this branch named, so M11.3 does not re-open them. Leave
§4b (Android reset) open — that is M11.3's, and untouched here.

**No behaviour change. No test change.** If this task finds itself editing a `.kt` line that is not a
comment, stop and report.

**Commit:** `docs: close the M11 Swift/Kotlin divergences now that parity landed`

---

## Verification

Full suite green, run from the branch root:

```
DEVELOPER_DIR=/Applications/Xcode.app swift test
```

Baseline on `main` at `f783291` is **686 tests**. Tasks 1-4 add tests; none may be removed. Report
the final count and the delta.

Kotlin must also stay green after Task 5 (comments only, but prove it):

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :shared:test :desktop:test :androidApp:testDebugUnitTest
```

Known-red and **out of scope**: `:shared:compileTestKotlinMacosArm64` has failed since ~M8.2 on
backtick test names containing `()` and `,`. Do not attempt to fix it here.
