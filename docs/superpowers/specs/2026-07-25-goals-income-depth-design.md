# M11 — Goals & Income Depth (configurable balance · portfolio goals · dividend calendar + forecast)

**Status:** Approved by user 2026-07-25 (conversational review). **M11.1 (Swift) SHIPPED 2026-07-25 at `6faac85`.**

> ⚠️ **This spec is superseded in places by the as-built.** Five user rulings made *during* M11.1 execution contradict the text below, and the final whole-branch review found further defects that originated here rather than in the implementation. Corrected points are marked **[SUPERSEDED — see carry-notes]** inline.
> **Before writing or executing M11.2, read `2026-07-25-m11-carry-notes.md`.** Where it and this spec disagree, the carry-notes win.

**Delivery:** M11.1 Swift (macOS + iPhone) → M11.2 Kotlin (Windows + Android), the established M7–M10 pattern. Bar Replay is deliberately NOT in this milestone — it is M12 and gets its own spec after M11 ships.
**Hard constraint:** no paid APIs or services. Everything runs on data the app already fetches (Yahoo quotes/history/dividend events, CoinGecko, Finnhub) and existing infra.

## North star

Three features, all extensions of engines that already exist — no new data sources, no new visual language. F1 removes the last hardcoded-simulation embarrassment (every competitor lets you pick your practice capital). F2 adds the goal layer Snowball ships on top of rebalancing mechanics we already have. F3 turns the Income tab's single "projected annual income" number into a real planning surface: a calendar of what's coming and a multi-year forecast of where income is heading. Section order, navigation, and visual tokens all stay exactly as shipped in M10.

---

## F1 — Configurable starting balance

### Behavior

- The portfolio-reset flow gains an **amount field**: the user picks the starting cash for the fresh portfolio.
- Validation: $1,000 – $10,000,000 inclusive. Locale-aware decimal parsing (reuse the screener's locale-parsing approach — this trap was already hit and fixed in M9). Invalid/out-of-range input disables the reset confirm button with an inline explanation, never silently clamps.
- Default shown = the **last-used starting amount** (persisted in settings; initially $100,000). Resetting with a new amount updates the remembered value.
- USD only. The amount applies **on reset only** — no mid-flight balance edits, no top-ups.

### Domain / persistence

- Swift: `Portfolio.starting(cash:)` is already parameterized (`Sources/APTradeDomain/Portfolio.swift:35`) — the work is at the call sites and the reset use case, which must accept an amount instead of assuming the default.
- Kotlin: `Portfolio.starting()` (`shared/src/commonMain/kotlin/com/aptrade/shared/domain/Portfolio.kt:150`) gains a `cash: Money` parameter (default retained for source compatibility during migration, then call sites pass explicitly).
- Persisted starting amount lives in the existing settings store (AppSettings pattern on Swift; the equivalent shared settings store on Kotlin). It is a *preference*, not portfolio state.
- **No hidden 100k assumptions may survive**: equity-curve baseline, total-return %, performance report, and export must all derive the starting value from the portfolio itself (its initial cash / transaction ledger), never from a constant. Acceptance includes a repo grep showing no remaining `100_000` / `100000` literals outside tests and **the two permitted defaults** (`Portfolio.starting`'s parameter default and the settings default).
  **[CORRECTED — the spec said "the single settings default"; there are two.]** Write the grep against *content*, not line numbers — the plan's version pinned an exclusion to a line number that moved the moment the file was edited. Two further literals were found during verification and removed: a **defaulted `seedCash` closure** on the portfolio store, and a **no-op test double returning a fabricated $100,000**. Expect the same two hiding places in Kotlin — defaulted parameters and no-op doubles are where hardcoded balances live.
- **`Portfolio.startingCash` — decide before porting; do not port the rationale.** **[CORRECTED.]** The Swift wave added a persisted `startingCash` field justified as serving "performance baselines and the reset flow." **Neither is true**: reset reads the settings default, and total-return derives from the equity curve's own first point. A grep finds only the field's initializer and decoder — it has no reader. Either give it a real consumer (a "since inception" return on the Performance grid is the obvious, genuinely useful one) or **drop it from the Kotlin plan** rather than porting dead persisted state.

### Edge cases

- Reset with a different amount while positions exist: same confirm-destructive flow as today; the new portfolio starts clean at the chosen cash.
- Goals (F2) are **cleared on reset** — a fresh $10k practice run must not inherit a $1M goal (see F2 persistence).

---

## F2 — Portfolio-level goals

### Concept

Two goal kinds, at most one of each, attached to the whole portfolio:

| Kind | Target | Progress source | Projection source |
|---|---|---|---|
| **Value goal** | portfolio total value (e.g. $250,000) | current total value (market value + cash) | equity-curve growth rate |
| **Income goal** | annual dividend income (e.g. $5,000/yr) | current projected annual income (existing DividendMath trailing figure) | F3 income-forecast math |

Per-Pie goals are explicitly out (backlog — they need per-Pie equity history that doesn't exist). A Home-feed goal chip is out of M11 (candidate for a later parity pass).

### Domain

New pure domain types (Swift + Kotlin twins, identical semantics):

- `PortfolioGoal` — kind (`value` | `income`), target `Money`, `createdAt`. No target *date* input: the app tells the user *when* they'll get there, the user doesn't promise a date.
- `GoalMath` (pure, exhaustively unit-tested):
  - `progress(current:target:) -> fraction` clamped to [0, 1+] (display caps the bar at 100% but shows the real % text, e.g. "112%").
  - Value-goal projection: annualized growth rate from the trade-aware equity curve (CAGR over the available window, **minimum 180 days** of history required — below that, no projection, show "tracking…" state). Years-to-target = solve `current × (1+r)^t ≥ target`. Clamp r to a sane band (−50%…+100%/yr) and cap the answer display at "> 30 yrs".
    **[SUPERSEDED — this said 30 days.]** 30 days annualized is a 12× extrapolation: a first month up 5% reads as +80%/yr, passes the clamp, and renders a confident multi-year ETA. Note also that the guard as built measures the **price-history span** of the curve, not account age — decide deliberately which one M11.2 measures.
  - Income-goal projection: reuse F3's `incomeForecast` — the crossing year where forecast annual income ≥ target (or "> 30 yrs"). This dependency means **F3 domain math builds before F2 projections** in plan ordering. The forecast passed in is always the **full 30-year horizon**, independent of the chart's 5/10/20/30 pill — a shorter one makes an unreachable goal indistinguishable from one reached in year 31.
  - Negative/zero growth with target above current: honest "not on track at current rate" state, never a fake ETA.
  - **No data at all is a different state from not-on-track.** **[ADDED — the spec omitted this and the omission shipped.]** A user holding nothing has a forecast of zero years; `last ≥ current` is false, so a naive implementation reads "not on track" while the symmetric value goal reads "needs more history". Return the insufficient-history state when the forecast carries no positive income, checked *before* the not-on-track fallthrough.

### UI

- **Value-goal card** in Portfolio · Performance; **income-goal card** in Portfolio · Income. Same card anatomy: title + target, progress bar (existing token/gradient language), "current vs target" line, one-line projection ("At your average growth, ~4 yrs" / "Not on track at current rate" / "Goal reached" — the celebratory state is plain text, no confetti; this is a quiet app).
- Empty state: a subtle "Set a goal" affordance in the same card slot — visible but unobtrusive, matching DesignKit's secondary-action styling.
- **Both cards render unconditionally.** **[ADDED — the spec was silent and both cards shipped gated, twice.]** Neither may sit behind an empty-ledger or loaded-state gate: the income card was unreachable for a user holding no dividend payer, and the value card vanished for an all-cash portfolio (money deposited, nothing bought). A goal is a plan — it is most useful *before* you hold anything. Hoist the card above the state switch so only the metrics/chart region toggles.
- Create/edit via a small sheet: amount field, Save / Remove. Editing an existing goal pre-fills it.
  **Validation uses per-kind ranges — NOT F1's.** **[SUPERSEDED — this said "same validation approach as F1's", and that was implemented literally.]** Borrowing the starting-balance bounds made an income goal under $1,000/yr unsettable — "$50/month in dividends" is $600/yr and an ordinary first goal — and showed a range hint describing a different quantity than the field. Share the *parser* (one locale story), parameterize the *range*: **income 100 … 1,000,000/yr; value 1,000 … 100,000,000**, each with its own hint copy in all four languages.
- Both platforms in a wave get identical placement (Performance ↔ Income), per the cross-platform parity discipline.

### Persistence

- Goals persist in a **dedicated store behind their own port, under their own key** — *not* embedded in the portfolio payload. **[SUPERSEDED — this said "in the portfolio store alongside portfolio state".]** The as-built shape is better and should be ported: because the goal type is never nested inside another serialized payload, a pre-goals payload simply has no key and degrades to an empty list, so the lenient-decoding problem never arises at all.
- Portfolio reset deletes goals, explicitly via the reset use case. Removing a goal is confirm-free (recreation is cheap), matching the M10 alerts-removal precedent.
- **Goal state must re-read on every screen appearance.** A screen that gates its reload on a first-load flag will keep showing a deleted goal — with a progress bar and ETA computed against the pre-reset curve — after a reset.

---

## F3 — Dividend calendar + income forecast

### Calendar

- A new **"Dividend Calendar"** card inside Portfolio · Income: dividend events for current holdings, grouped by month, list-first
  **[SUPERSEDED — the spec called this an "Upcoming" surface and the plan specified the literal title "Upcoming Dividends", which collides with a pre-existing card of that exact title on the same scroll view.]** Two identically-titled cards shipped briefly before this was caught. Title the new month-grouped projection card **"Dividend Calendar"**; leave the existing next-payout list's title alone. (the premium/quiet idiom — no dense month grid). Each row: symbol, event kind (ex-dividend / payment), date, estimated amount for the user's share count (shares × per-share dividend; DRIP-projected share counts are NOT used here — calendar shows what today's holdings earn).
- Data: **projected events, clearly labeled "est."** (user decision 2026-07-25, revising the earlier "real fetched events only" rule after implementation research showed Yahoo exposes *historical* ex-dividend events only — zero future declared dates exist in the free data; even M8's "next dividend" row is already a cadence projection). The calendar generalizes that same M8 projection — last real event + inferred cadence + last amount — to cover roughly the next 12 months per holding, every row marked estimated. The M8 stale-date bug class (fixed twice) makes freshness handling an explicit test target here.
- macOS may add a compact month strip above the list where width allows; phone is list-only.
- Empty state (no dividend payers held): one quiet line, no dead surface.

### Forecast

- A projection chart in Portfolio · Income: **projected annual dividend income by year**, horizon picked by the user — pill options **5 / 10 / 20 / 30 years** (not a free slider; matches the app's pill idiom).
- Per-holding math, summed:
  1. Base = current annual dividend rate × shares (existing DividendMath trailing figure).
  2. Growth = per-symbol dividend CAGR computed from the historical dividend series already fetched via the Yahoo events pipeline (`YahooDividendMapper`): use up to 5 years of history; require ≥ 2 years and ≥ 2 payments to compute growth, else growth = 0 for that symbol (flat projection, honest fallback).
  3. Clamp per-symbol growth to **−20%…+25%/yr** so one weird series can't produce absurd curves.
  4. If DRIP is on (it is a single **global** setting — `AppSettings.dripEnabled` — not per-holding), compound: each projected year's dividends buy shares at a price that **starts from the quoted market price** and grows at the same clamped rate as the dividend (simple, stated assumption), increasing the next year's share count. DRIP off = cash accumulation, flat share count.
  **[SUPERSEDED — the spec stated only the growth *rate* and was silent on the price *level*, so the plan specified cost basis.]** Reinvesting at cost basis is yield-on-cost: a holding bought at $50 now trading at $150 buys **3× too many shares per year**, overstating year-30 income by ~66%. Quoted price when available; cost basis only as an explicit fallback.
- **Toggling DRIP must rebuild the forecast** — and refresh the income-goal projection, which reads the same curve. **[ADDED — no task owned this wire and the toggle shipped inert.]** The global DRIP toggle and this chart land on the same screen; without an explicit rebuild the chart keeps the old assumption while its own caption promises otherwise.
- Chart: existing chart components/tokens (area style like Performance). A caption states the assumptions in one line: "Assumes historical dividend growth continues; DRIP compounding where enabled."
- Domain: extend `DividendMath` (Swift, then Kotlin twin) with `dividendGrowthRate(history:) -> clamped rate` and `incomeForecast(positions:, pricesBySymbol:, eventsBySymbol:, years:, dripEnabled:, asOf:) -> [YearlyIncome]` — pure functions, no I/O, exhaustively unit-tested including the M8 trap classes (fractional DRIP rounding, share-count integrity).
  **`pricesBySymbol` is REQUIRED — never a defaulted or trailing parameter.** Omitting it silently reverts to the cost-basis bug above, and a compiler will never complain. A default whose omission produces a *wrong* answer rather than a different-but-acceptable one is not a default.
  Year 1 is the trailing twelve months with **no growth applied** (deliberate); growth compounds from year 2. Document that where a reader will meet it.

### Explicitly parked

- **Dividend safety rating**: requires a fundamentals-data feasibility spike (Yahoo deep fundamentals were adversarially refuted as reliably available). Not in M11; revisit only after a spike proves the inputs are free.

---

## Cross-cutting

### Non-goals

No new data sources or paid services; no multi-currency (USD stays); no per-Pie goals; no Home-feed changes; no safety rating; no changes to trade flow, charts engine, screener, or navigation structure; no Bar Replay (M12).

### L10n

All new strings localized in **all four shipped languages — EN, DE, IT, ES** (the `L10n.table` catalog requires every key to supply all four; a completeness test enforces it). Translations are reviewed as part of the increment, not patched after — M8's German-corrections wave must not repeat.

Note: the reset-confirmation string currently hardcodes the amount ("Reset portfolio to $100,000 cash…") in all four languages — F1 makes that text dynamic.

### Testing

House pattern, strictly ordered: domain math first (GoalMath, DividendMath forecast/growth-rate, reset parameterization — exhaustive unit tests including clamps, minimum-history fallbacks, DRIP fractional rounding, goal-reached/not-on-track states), then use-case tests (reset with amount, goal CRUD + reset-clears-goals), then ViewModel tests, then platform UI. Kotlin twins get transcribed test suites, per the shared-core discipline.

### Acceptance (per platform wave)

1. Reset sheet offers amount entry with validation; remembered across resets; no stray 100k literals beyond the two permitted defaults (grep-verified against content, not line numbers — check defaulted parameters and no-op doubles).
2. Value goal and income goal can each be set, edited, removed; cards render progress + honest projections; goals cleared by portfolio reset. **Both cards are reachable on an empty portfolio**, and a goal set before any holding exists behaves sensibly.
3. Income shows the Dividend Calendar (projected events, every row labeled "est.") and the forecast chart with 5/10/20/30 horizons, DRIP-aware, assumption caption present. **Toggling DRIP visibly changes the curve without leaving the screen**, and the income-goal ETA moves with it.
4. All strings present in EN + DE + IT + ES (L10n completeness test green); all existing tests green; new domain suites green on both toolchains.
5. **An offline / failed-quote session shows no fabricated figure** — the value-goal card falls back to cash + cost basis, never a `$0` against a real target.
6. **The goal ETA does not change when the chart horizon pill changes.**

## M12 pointer (not this spec)

Bar Replay — single-symbol day-by-day session (pick symbol + start date, step daily bars, simulated orders against replayed prices, fully isolated session state, post-session report incl. vs-buy-and-hold). Scoped and approved in principle; full spec to be brainstormed after M11 closes.
