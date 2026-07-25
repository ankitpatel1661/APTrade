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

## 5. Process notes for M11.2

- **Budget a top-tier whole-branch review at close.** Per-task reviews are structurally blind to *seam* defects — every one of §2.2, §2.3, §2.4 and §3.4 lived between two tasks and no single task's review could have seen them. The whole-branch review found all four.
- **A defaulted parameter whose omission is a correctness bug is not a default — it is a re-armed bug with a compiler that will never complain.** This applies to `pricesBySymbol` and to any Kotlin equivalent.
- **Verify a symbol's real declaration before calling it**, and verify the *cost* of an option before presenting it as a reason to decline one.
