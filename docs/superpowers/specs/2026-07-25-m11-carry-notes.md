# M11 carry-notes — binding corrections from the Swift wave (M11.1) for the Kotlin wave (M11.2)

**Status:** Authoritative. Written 2026-07-25 at M11.1 close, from the M11.1 final whole-branch review and the user rulings made during execution.

**Read this before writing or executing the M11.2 plan.** The M11.1 spec (`2026-07-25-goals-income-depth-design.md`) and the M11.1 plan (`../plans/2026-07-25-m11-1-goals-income-depth-swift.md`) both contain text that the as-built deliberately contradicts. Where this document and those two disagree, **this document wins**, because it records decisions the user made *after* seeing the implementation.

**The Swift as-built on `main` at `6faac85` is the byte-authoritative reference** — transcribe semantics from the named Swift files, not from the spec alone. This is the same discipline M8.2 and M10.2 used.

---

## 1. Five user rulings that override the plan and spec text

Each of these was escalated during M11.1 because the plan mandated something the review found wrong. The user decided. Do not re-derive these in M11.2 — implement the ruling.

### 1.1 DRIP reinvests at **quoted market price**, not cost basis

The plan specified `price: position.averageCost`. `Position` carries no market price, so no caller could correct it, and the model reinvested at yield-on-cost: a holding bought at $50 now trading at $150 bought **3× too many shares per year**, overstating year-30 income by roughly **66%**.

As-built signature (Swift), and the shape the Kotlin twin must take:

```
DividendMath.incomeForecast(positions:pricesBySymbol:eventsBySymbol:years:dripEnabled:asOf:) -> [ForecastYear]
```

- `pricesBySymbol` is **REQUIRED — no default** and sits **second, beside `positions`**. See §1.5 for why.
- Price resolution: `pricesBySymbol[symbol] ?? position.averageCost` — quoted price when present, cost basis only as fallback.
- The view model already builds this dict from its quote fetch; pass it. Omitting it silently re-arms the 66% bug.

### 1.2 Minimum history floor is **180 days**, not 30

The plan said 30. A 30-day window annualized by `365.25/days` is a **12.175× extrapolation**: a portfolio up 5% in its first month reads as **+80%/yr**, sails under the +100% clamp, and renders a confident multi-year ETA. That contradicts the type's own promise never to fabricate an ETA it cannot support.

`GoalMath.minimumHistoryDays = 180`.

**Also correct the doc comment.** The guard does not measure what the spec implies. The curve fed to `annualGrowthRate` is the **1-year price window** with a flat pre-inception cash point, so its span is ~365 days for any portfolio holding any seasoned symbol — the floor only actually fires for an all-cash portfolio or a symbol whose own price history is under 180 days. It guards short **price** history, not short **account** history. Say so plainly, or, if M11.2 wants the account-age semantics the spec intended, measure from the first transaction date instead — but decide deliberately rather than inheriting the mismatch.

### 1.3 **Both goal cards render unconditionally**

Neither card may sit behind an empty-ledger or `loaded`-state gate.

- The income-goal card was originally inside the `ledger` branch, so a user holding no dividend payer could never set an income goal.
- The value-goal card was originally inside Performance's `loaded` branch, so an all-cash portfolio — money deposited, nothing bought — collapsed the whole section and the card vanished.

A goal is a *plan*; it is most useful before you hold anything. Follow the DRIP-card precedent: hoist the card above the state switch so only the metrics/chart region toggles between empty and loaded.

### 1.4 The calendar card is titled **"Dividend Calendar"**

The spec calls it an "Upcoming" surface and the plan specified the literal string "Upcoming Dividends" — which **collides with a pre-existing card of that exact title** on the same scroll view. Two identically-titled cards shipped briefly before this was caught.

Title the new month-grouped projection card **"Dividend Calendar"** in all four languages. Leave the pre-existing next-payout list's title alone.

### 1.5 Goal targets validate against **per-kind ranges**, not the starting-balance range

The spec said the goal sheet uses "the same validation approach as F1's," and the plan implemented that literally — so the starting-balance bounds ($1,000–$10,000,000) policed goal targets. Consequences: an **income goal under $1,000/yr was unsettable** (a "$50/month in dividends" target is $600/yr and an entirely ordinary first goal), a value goal over $10M was unsettable, and the range hint shown to users described a different quantity than the field.

As-built: the parser takes a **range parameter defaulting to the starting-balance range** (so the reset sheet is untouched), and each goal kind supplies its own range plus its own hint copy in all four languages:

| Goal kind | Range |
|---|---|
| Income | 100 … 1,000,000 (per year) |
| Value  | 1,000 … 100,000,000 |

---

## 2. Defects in the M11.1 plan and spec — do not transcribe these

The final whole-branch review named these as **plan** defects rather than implementation defects. The Swift wave corrected them during execution; M11.2 must not re-import them.

### 2.1 `Portfolio.startingCash` has no consumer

The plan's Task 1 justified the field as serving "performance baselines and the reset flow." **Neither is true.** The reset flow reads `AppSettings.defaultStartingCash`, and total-return derives from the equity curve's own first point. A repo grep finds only the field's own initializer and decoder.

**Decide before porting:** either give it a real consumer (a "since inception" return on the Performance grid is the obvious and genuinely useful one), or **drop it from the Kotlin plan entirely** rather than porting dead persisted state. Do not port it with the plan's original rationale attached.

### 2.2 No task owns the DRIP-toggle → forecast-rebuild wire

The DRIP toggle and the forecast chart end up on the **same screen**, and no plan step connects them. The Swift wave shipped a toggle that wrote through to settings immediately while the chart directly beneath it kept the old assumption until the user happened to tap a horizon pill — with a caption actively promising DRIP compounding. Every displayed year was understated.

**Add an explicit step** to the M11.2 plan: toggling DRIP rebuilds the forecast **and** refreshes the income-goal projection (which reads the same curve). A fix that rebuilds only the chart leaves the ETA stale against a curve that just changed.

### 2.3 The `currentValue` fallback condition is wrong in the plan

The plan specified, in effect, `positions.isEmpty ? cash : 0`. That leaves a **fabricated `$0`** whenever the equity curve is empty for any *other* reason — and the common reason is not exotic: the performance use case swallows every history-fetch failure, so any offline session, rate limit, or upstream error yields an empty curve. A user holding positions with a $500,000 goal then sees `$0 / $500,000 · 0%`: a specific, wrong dollar figure for their own portfolio.

**Specify instead:** whenever the curve is empty, `currentValue` = cash + cost basis of all positions (`positions.reduce(cash) { $0 + marketValue(at: averageCost) }`). Never fabricated, and it collapses to exactly `cash` for the all-cash case.

### 2.4 `incomeProjection` and `valueProjection` disagree on the empty case

The plan's `incomeProjection` had no "no income at all" branch. A brand-new user setting an **income** goal holds nothing, so the forecast is 30 zero years, `last.income > current` is `0 > 0` = false, and the card reads **"Not on track at current rate."** The same user's **value** goal reads "needs more history." Identical situation, contradictory copy, on two cards this milestone deliberately made symmetric and always-visible — and the income wording is the wrong one: nothing is off track, there is simply no data.

**Specify:** `incomeProjection` returns the insufficient-history case when the forecast carries no positive income, checked *before* the not-on-track fallthrough.

### 2.5 Goal persistence diverges from the spec — the as-built is better, keep it

The spec says goals "persist in the portfolio store alongside portfolio state." The as-built persists them in a **separate store under their own key** (`UserDefaultsGoalStore` behind a `GoalStore` port). This turned out to matter: because `PortfolioGoal` is never embedded in another Codable payload, a pre-goals payload simply has no key and degrades to an empty list — the lenient-decoding problem never arises. Reset clears goals explicitly via the use case.

Port the **as-built** shape, not the spec sentence.

### 2.6 Four symbol names in the plan do not exist

The plan was written ahead of the code and named symbols that were never verified. All four cost real time during execution:

| Plan says | Reality |
|---|---|
| `PerformanceReport.currentValue` | **Does not exist.** Use `equityCurve.last?.value` — which is `cashAt + holdings`, the true total account value. |
| `DividendMath.DividendEvent` | `DividendEvent` is **top-level**, not nested. The nested form does not compile. |
| `InMemoryPortfolioStore` | The application-layer test double is **`MemoryStore`**. |
| `SummaryCards.projectedAnnual` (as the income-goal `current`) | Use `DividendMath.projectedAnnualIncome` — see §3.1. |

**Verify every symbol name in the M11.2 plan against the shared Kotlin core before dispatching it**, not during. Eight name/behaviour corrections in one plan is a high error rate and each one burned an implementer's turn.

### 2.7 Two acceptance-criterion details are stale

- The plan's grep step pins an exclusion to a **line number** (`Portfolio.swift:35`) that moves the moment Task 1 edits the file. Write the grep against content, not line numbers.
- The **spec** (not the plan) undercounted the permitted literals as "the single settings default"; there are **two** — `Portfolio.starting`'s parameter default and the settings default. The plan's Task 14 already said "two" correctly. *(Corrected 2026-07-25: an earlier revision of this document attributed the undercount to the plan. It was the spec; the spec has been fixed.)*
- More important than the count: **two further literals were found during verification** that neither document anticipated — a defaulted `seedCash` closure on the portfolio store, and a no-op test double returning a fabricated $100,000. Both were removed. Expect the same two hiding places in Kotlin: **defaulted parameters and no-op test doubles are where hardcoded balances hide**, and a grep written only against obvious call sites will miss both.

---

## 3. Invariants the Swift wave had to earn — pin these in Kotlin tests too

These are correct in the as-built and were each nearly lost. Your Kotlin tests must pin them.

### 3.1 The income goal's `current` must equal forecast year 1

`current` is `DividendMath.projectedAnnualIncome(positions:eventsBySymbol:asOf:)` — the *same sum* `incomeForecast` produces for `yearOffset == 1`. If they diverge, the progress percentage and the ETA disagree with the forecast chart rendered directly beside them.

The equivalence holds only because no held position can have a non-positive quantity (selling removes a position at zero and overselling is rejected), which is what makes `incomeForecast`'s `quantity > 0` guard inert. If the Kotlin portfolio model permits zero-quantity positions, this equivalence breaks.

### 3.2 Forecast year 1 carries **no growth**

`yearOffset == 1` is the trailing twelve months, growth applied zero times, deliberately. Growth compounds from year 2. Document it where a reader will meet it — the Swift wave shipped a comment claiming the opposite and it had to be corrected.

### 3.3 The goal ETA must not move when the user changes the chart horizon

`incomeProjection` always receives a forecast of exactly `horizonYears` (30) length, independent of the 5/10/20/30 pill. A shorter forecast makes an unreachable goal indistinguishable from one reached in year 31 — "not on track" where "beyond horizon" is correct.

### 3.4 Goal state must re-read on every appearance

Reset clears goals. If a screen gates its reload on a first-load flag, it will keep showing a deleted goal with a progress bar and ETA computed against the pre-reset curve. The Swift wave had exactly this asymmetry — one screen self-healed, its symmetric twin did not.

### 3.5 The projection type must distinguish all five outcomes

`reached` / `notOnTrack` / `beyondHorizon` / `insufficientHistory` / `years(n)`. Callers must tell "already met" from "unreachable" from "too far out" from "no data" from a concrete ETA **without string-matching**. `beyondHorizon` renders as "> 30 yrs" by **interpolating the horizon constant**, never a hardcoded 30 — and the test should derive its expectation from the same constant.

### 3.6 Clamps are exact, and the two are different

Per-symbol dividend growth: **−0.20 … 0.25**. Portfolio value-goal growth: **−0.50 … 1.00**. They are easy to conflate and were kept independent. Pin both boundaries with exact-equality tests.

### 3.7 Every calendar row is an estimate

Yahoo exposes **no** forward-declared dividend dates. Every row is a cadence projection and must be labeled. Nothing in naming, comments, or copy may imply an announced date.

### 3.8 Reuse the cadence helper — do not duplicate the constants

The 30/91/182/365-day cadence steps already exist inside the M8 "next projected" path. Extract and share; do not add a second copy.

---

## 4. Known-deferred items (recorded, not blocking)

Carried from M11.1's ledger so they are not lost when its scratch workspace is deleted. None block M11.2; several are worth folding in while the same code is open.

- **Currency codes dropped** in three `Money` construction sites where the neighbouring code threads them through. Inert under the USD-only constraint; live bugs the day multi-currency lands. One backlog chip covers all three.
- **Fractional exponentiation** routes `Decimal → Double → pow → Decimal` (no fractional `Decimal.pow` exists in Foundation). Tolerance-covered; worth a one-line comment. Kotlin's bignum may allow a cleaner route — check before transcribing the workaround.
- **Test-helper duplication** — `usd`, `date`, and an in-memory goal store now have 4+ verbatim copies across suites. A shared test-support file is the next housekeeping chip.
- **A no-op goal store silently discards saves**; a construction site that forgets to inject the real one loses goals with no signal.
- **Redundant "est." labels** — the badge appears per-row *and* as a card caption. One disclaimer would suit "zero clutter" better.
- **Overflow maps to insufficient-history** via a shared nil return — semantically wrong (a user with ten years of history would be told there isn't enough), though unreachable given upstream guards.
- **A locale round-trip gap:** an amount is formatted with an unconditional "." decimal point but parsed with a locale-aware parser that reads "." as a grouping separator in e.g. `de_DE`. Two consumers now share this idiom — fix both or neither. Only bites on a fractional stored amount.
- **The forecast horizon picker's narrow-width behaviour is unverified.** It uses a fixed-size segmented control sharing a row with the section header; a "fits" fallback was added but never confirmed on a device at 375pt. Kotlin should design for narrow width from the start rather than inheriting an unverified fallback.

---

## 4a. Two M11.2 decisions the user made at kickoff (2026-07-25)

Both were left deliberately open by §2.1 and §1.2. Both create **recorded, intentional twin divergences** — comment-document them in the Kotlin source the way M10.2 documented its four, and treat each as a Swift backport candidate rather than an accident.

### 4a.1 `startingCash` is ported **and given a real consumer**

Do not port it as dead state, and do not drop it. Kotlin ports the field **and** adds a **"Since inception" return** to the Performance metrics: total return measured from the portfolio's actual starting balance rather than from the equity curve's first point.

This is the right home for it now that the starting balance is user-chosen — a $10k practice run and a $1M one should not both report return against whatever the curve happened to open at. **Swift currently has the field with no reader; this is a backport candidate once Kotlin proves the metric.**

### 4a.2 The history floor measures **account age**, not price-history span

Swift's as-built measures the span of the 1-year price window, which is ~365 days for anyone holding a seasoned symbol — so the floor is nearly inert there (see §1.2). Kotlin measures **from the first transaction date** instead, which is what the 30→180 raise was actually intended to protect.

Consequence to accept deliberately: a genuinely new account gets the insufficient-history state regardless of how seasoned its holdings are — which is the honest reading, since the projection extrapolates *the account's* growth rate. A user who transfers in a long-held position still waits until the account itself has 180 days of history.

**This is a real behavioural divergence from Swift, not a transcription slip.** Comment it at the constant, pin it with a Kotlin test that a seasoned-holdings/new-account portfolio returns insufficient-history, and record it as a Swift backport candidate.

⚠️ **WORDING CORRECTION 2026-07-25 — "instead" above caused a real defect.** The M11.2 Task 6 implementer read "measure from the first transaction date **instead**" as *replacing* the 180-day curve-span floor, and reduced the span requirement to 1 day. That reproduced exactly the fabrication this floor exists to prevent: for a 400-day-old account, a 30-day curve up 5% annualizes to **+81.1%/yr**, passes under the +100% clamp, and renders a confident **"1.2 yrs"** ETA. Reachable in production, because `performanceSeries`'s all-priced gate truncates the *entire* curve to the newest-history symbol's first candle — so an old account that buys one recently-listed symbol collapses its own curve span.

**Both gates are required.** Account age ≥ 180 days **and** curve span ≥ 180 days. Changing *what* the primary floor measures was the intent; dropping the span protection was not. Swift already has the span gate — the Kotlin port must keep it and add the age gate on top, not swap one for the other.

### 4a.3 `incomeProjection` enforces the horizon constant (M11.2 addition, backport candidate)

Swift's `incomeProjection` returns `.years(crossing.yearOffset)` unbounded, so a crossing at year 35 renders a concrete "35 yrs" while `valueProjection` returns `.beyondHorizon` for the same span — the two symmetric cards disagree, and the "> 30 yrs" rule is satisfied only by caller discipline that nothing enforces.

Kotlin adds `if (crossing.yearOffset > HORIZON_YEARS) return BeyondHorizon`, making the boundary provably identical in both projections. **Backport candidate** — Swift has the same gap.

---

## 4b. A hard requirement M11.3 (Android) inherits from M11.2

**Android's reset ignores `AppSettings.defaultStartingCash` when M11.2 ships. M11.3 must close it.**

M11.2 scopes Android to compile-fixes only, so `androidApp/.../portfolio/PortfolioViewModel.kt`'s `fun reset()` takes no amount and calls `resetPortfolio.execute(Portfolio.DEFAULT_STARTING_CASH)` — the hardcoded $100,000 — even though the preference now exists and the Windows desktop honours it (M11.2 Task 9 replaces the same placeholder there and pre-fills the reset dialog from the setting).

Consequence to be aware of: between M11.2 and M11.3, **a user who sets a starting balance sees Windows respect it and Android silently ignore it.** That is a real cross-platform inconsistency, not a cosmetic one — it was accepted deliberately because Android UI is out of M11.2's scope, and it is verified as genuinely owned by M11.3 rather than orphaned.

M11.3's plan must include, as an explicit task step: give Android's `reset()` a `startingCash: Money` parameter, thread the configured default into its reset affordance, and pre-fill it the way desktop does. Pin it with a test that a configured non-default balance actually reaches `resetPortfolio.execute`.

---

## 4c. A REAL DEFECT IN THE SHIPPED SWIFT CODE — found during M11.2, backport owed

**`DividendMath.dividendGrowthRate` manufactures growth from ex-date phase alone. This is live in M11.1 on `main` right now.**

Found 2026-07-25 by the M11.2 Task 5 review, which re-derived it independently in Python and reproduced the failing value byte-for-byte (`0.13678039345929127`).

The function derives a growth rate by dividing two rolling **365-day** dividend sums. But a 365-day window contains **5 quarterly ex-dates sometimes and 4 other times** (4 × 91 = 364 < 365). So a genuinely *flat* quarterly payer reads anywhere from −20% to +25%/yr purely on where its ex-dates happen to sit relative to `asOf`. Compounded over the 30-year horizon pill that is roughly a **45× divergence** in reported income — and it feeds both the income-goal ETA and the forecast chart, i.e. specific dollar figures on screen.

A second structural contributor: the early comparison anchor is `first + SECONDS_PER_YEAR` (365.25 days) while the trailing window is 365 days, so the early window **structurally excludes its own first event by exactly 21,600 seconds** (6 hours).

The existing Swift tests cannot detect it: every growth assertion resolves to exactly 0.0, exactly the clamp maximum, or exactly the clamp minimum. The reviewer verified that **inverting the annualization root entirely** — `ratio.pow(spanYears)` instead of `ratio.pow(1.0 / spanYears)` — leaves every test green. The Swift suite has the same shape and the same blind spot.

**USER RULING 2026-07-25: fix it properly in Kotlin, then backport to Swift.** The Kotlin fix normalizes by payment count so the rate reflects per-payment growth rather than window-membership accident; a flat payer must read exactly 0.0 regardless of ex-date phase.

### 4c.1 The first fix introduced its own defect — the annualization denominator was never updated to match

The payment-count-normalization fix above (round 1) removed the aliasing but kept `spanYears = totalSpanYears - 1.0` as the annualization exponent's divisor — a holdover from the OLD design, where the early anchor genuinely sat one year after the first event, so `span − 1` really was the gap between the two sampled points.

The round-1 rewrite compares **half-window centroids** (mean ex-date of the oldest half vs. the newest half) instead, and those are separated by roughly `span / 2`, not `span − 1`. The two coincide only at exactly a 2-year span; off that one point the result is **systematically biased**, flipping direction at exactly 2 years:

| true g | span 2y | 3y | 4y | 5y |
|---|---|---|---|---|
| 3%  | 3.764  | 2.620  | 2.242  | 1.990 |
| 5%  | 6.289  | 4.362  | 3.727  | 3.306 |
| 10% | 12.653 | 8.697  | 7.410  | 6.560 |
| 15% | 19.089 | 13.008 | 11.051 | 9.765 |

A true 10% grower with 5 years of history reported 6.560% under the round-1 fix — over 29 compounding years that is 6.31× versus the true 15.86×, a **2.51× divergence in reported income**. Silent and calibrated-looking, unlike the aliasing artifact it replaced, and caught only because the round-2 review independently re-derived the exponent's calibration rather than re-testing only the fixtures the round-1 fix already passed.

**USER RULING 2026-07-25 (round 2): the annualization divisor must be the actual elapsed time between what's being compared** — the gap, in years, between each half's centroid (mean ex-date), not the total window span and not `span − 1`. This is now implemented (`DividendMath.kt`'s `dividendGrowthRate`, CARRY-NOTE 6) and verified to recover a known true per-payment CAGR exactly (`dividendGrowthRateRecoversAKnownTruePerPaymentCagr`).

**Backport checklist for Swift (`Sources/APTradeDomain/DividendMath.swift`), owed and not yet done:**
1. Port the payment-count normalization from the Kotlin `dividendGrowthRate` — compare average per-payment amount across count-matched oldest/newest halves of the window (`window[0 ..< size/2]` and `window[size - size/2 ..< size]`), not a raw day-windowed sum sampled at two instants. **On an odd-count window, the middle event is dropped from both halves** (`size/2` truncates), not included in either — a porter who instead assigns it to one half reintroduces an asymmetry between the two counts.
2. **Port the centroid-gap annualization divisor, not `span − 1`.** The divisor is `(late-half centroid − early-half centroid) / SECONDS_PER_YEAR`, where each half's centroid is the mean ex-date (epoch seconds) of its events. Porting step 1 without this step reproduces a *different*, still-wrong bias (see 4c.1 above) — do these together, not in sequence.
3. Add the test the round-1 Kotlin fix added: the aliasing fixture (newest event exactly at `asOf`, exact 91-day spacing) must read **0.0**, not 13.7%.
4. Add a **mid-range** growth test that pins the annualization exponent to the centroid-gap divisor specifically — a test tuned only to `span − 1` would falsely appear to validate step 2's *absence* (this is exactly how the round-1 Kotlin mid-range test passed while carrying the round-2 defect; see `dividendGrowthRateAnnualizesAMidRangeRatioPrecisely`'s corrected version for the engineering technique: force an exact centroid gap, not an exact total span).
5. Add a **true-rate-recovery** test: a series with a known per-payment CAGR must be recovered within tolerance (`dividendGrowthRateRecoversAKnownTruePerPaymentCagr`). This is the property that would have caught 4c.1 immediately and is the strongest single regression guard for this function.
6. Add a **DRIP-with-non-zero-growth** test. Swift has the same gap: every DRIP test uses growth 0 and every growth test has DRIP off, so the reinvestment-price-grows-at-dividend-rate choice is entirely uncovered on both platforms.
7. Correct the Swift equivalents of the two clamp tests whose comments state raw rates the code does not actually compute.

Until steps 1 **and** 2 both land, Kotlin and Swift will report **different growth rates for identical data** — a deliberate, recorded divergence, not a transcription slip. Porting step 1 alone is not a stopping point: it removes the aliasing bug but (per 4c.1 above) reproduces a *different* systematic bias unless step 2's centroid-gap divisor lands with it.

---

## 5. Process notes for M11.2

- **Budget a top-tier whole-branch review at close.** Per-task reviews are structurally blind to *seam* defects — every one of §2.2, §2.3, §2.4 and §3.4 lived between two tasks and no single task's review could have seen them. The whole-branch review found all four.
- **A defaulted parameter whose omission is a correctness bug is not a default — it is a re-armed bug with a compiler that will never complain.** This applies to `pricesBySymbol` and to any Kotlin equivalent.
- **Verify a symbol's real declaration before calling it**, and verify the *cost* of an option before presenting it as a reason to decline one.
