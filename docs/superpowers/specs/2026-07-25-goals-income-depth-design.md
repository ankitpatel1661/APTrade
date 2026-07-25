# M11 — Goals & Income Depth (configurable balance · portfolio goals · dividend calendar + forecast)

**Status:** Approved by user 2026-07-25 (conversational review).
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
- **No hidden 100k assumptions may survive**: equity-curve baseline, total-return %, performance report, and export must all derive the starting value from the portfolio itself (its initial cash / transaction ledger), never from a constant. Acceptance includes a repo grep showing no remaining `100_000` / `100000` literals outside tests and the single settings default.

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
  - Value-goal projection: annualized growth rate from the trade-aware equity curve (CAGR over the available window, minimum 30 days of history required — below that, no projection, show "tracking…" state). Years-to-target = solve `current × (1+r)^t ≥ target`. Clamp r to a sane band (−50%…+100%/yr) and cap the answer display at "> 30 yrs".
  - Income-goal projection: reuse F3's `incomeForecast` — the crossing year where forecast annual income ≥ target (or "> 30 yrs"). This dependency means **F3 domain math builds before F2 projections** in plan ordering.
  - Negative/zero growth with target above current: honest "not on track at current rate" state, never a fake ETA.

### UI

- **Value-goal card** in Portfolio · Performance; **income-goal card** in Portfolio · Income. Same card anatomy: title + target, progress bar (existing token/gradient language), "current vs target" line, one-line projection ("At your average growth, ~4 yrs" / "Not on track at current rate" / "Goal reached" — the celebratory state is plain text, no confetti; this is a quiet app).
- Empty state: a subtle "Set a goal" affordance in the same card slot — visible but unobtrusive, matching DesignKit's secondary-action styling.
- Create/edit via a small sheet: amount field (same validation approach as F1's), Save / Remove. Editing an existing goal pre-fills it.
- Both platforms in a wave get identical placement (Performance ↔ Income), per the cross-platform parity discipline.

### Persistence

- Goals persist in the portfolio store alongside portfolio state (they are portfolio-scoped, unlike F1's settings-scoped default amount).
- Portfolio reset deletes goals. Removing a goal is confirm-free (recreation is cheap), matching the M10 alerts-removal precedent.

---

## F3 — Dividend calendar + income forecast

### Calendar

- A new **Upcoming** surface inside Portfolio · Income: dividend events for current holdings, grouped by month, list-first (the premium/quiet idiom — no dense month grid). Each row: symbol, event kind (ex-dividend / payment), date, estimated amount for the user's share count (shares × per-share dividend; DRIP-projected share counts are NOT used here — calendar shows what today's holdings earn).
- Data: the per-holding upcoming-dividend data the Income engine already derives (the "next dividend" pipeline from M8, generalized from "next one event" to "known future events"). Where Yahoo only exposes the next declared event per symbol, the calendar shows that — **no synthetic future events are fabricated for the calendar** (the forecast, below, is where projection belongs). The M8 stale-date bug class (fixed twice) makes freshness handling an explicit test target here.
- macOS may add a compact month strip above the list where width allows; phone is list-only.
- Empty state (no dividend payers held): one quiet line, no dead surface.

### Forecast

- A projection chart in Portfolio · Income: **projected annual dividend income by year**, horizon picked by the user — pill options **5 / 10 / 20 / 30 years** (not a free slider; matches the app's pill idiom).
- Per-holding math, summed:
  1. Base = current annual dividend rate × shares (existing DividendMath trailing figure).
  2. Growth = per-symbol dividend CAGR computed from the historical dividend series already fetched via the Yahoo events pipeline (`YahooDividendMapper`): use up to 5 years of history; require ≥ 2 years and ≥ 2 payments to compute growth, else growth = 0 for that symbol (flat projection, honest fallback).
  3. Clamp per-symbol growth to **−20%…+25%/yr** so one weird series can't produce absurd curves.
  4. If the holding's DRIP is on, compound: each projected year's dividends buy shares at a price that grows at the same clamped rate as the dividend (simple, stated assumption), increasing the next year's share count. DRIP off = cash accumulation, flat share count.
- Chart: existing chart components/tokens (area style like Performance). A caption states the assumptions in one line: "Assumes historical dividend growth continues; DRIP compounding where enabled."
- Domain: extend `DividendMath` (Swift, then Kotlin twin) with `dividendGrowthRate(history:) -> clamped rate` and `incomeForecast(holdings:, years:, dripFlags:) -> [YearlyIncome]` — pure functions, no I/O, exhaustively unit-tested including the M8 trap classes (fractional DRIP rounding, share-count integrity).

### Explicitly parked

- **Dividend safety rating**: requires a fundamentals-data feasibility spike (Yahoo deep fundamentals were adversarially refuted as reliably available). Not in M11; revisit only after a spike proves the inputs are free.

---

## Cross-cutting

### Non-goals

No new data sources or paid services; no multi-currency (USD stays); no per-Pie goals; no Home-feed changes; no safety rating; no changes to trade flow, charts engine, screener, or navigation structure; no Bar Replay (M12).

### L10n

All new strings localized EN + DE from the start (M8's German-corrections wave must not repeat — translations reviewed as part of the increment, not patched after).

### Testing

House pattern, strictly ordered: domain math first (GoalMath, DividendMath forecast/growth-rate, reset parameterization — exhaustive unit tests including clamps, minimum-history fallbacks, DRIP fractional rounding, goal-reached/not-on-track states), then use-case tests (reset with amount, goal CRUD + reset-clears-goals), then ViewModel tests, then platform UI. Kotlin twins get transcribed test suites, per the shared-core discipline.

### Acceptance (per platform wave)

1. Reset sheet offers amount entry with validation; remembered across resets; no stray 100k literals (grep-verified).
2. Value goal and income goal can each be set, edited, removed; cards render progress + honest projections; goals cleared by portfolio reset.
3. Income shows the upcoming-dividends list (real fetched events only) and the forecast chart with 5/10/20/30 horizons, DRIP-aware, assumption caption present.
4. All strings EN + DE; all existing tests green; new domain suites green on both toolchains.

## M12 pointer (not this spec)

Bar Replay — single-symbol day-by-day session (pick symbol + start date, step daily bars, simulated orders against replayed prices, fully isolated session state, post-session report incl. vs-buy-and-hold). Scoped and approved in principle; full spec to be brainstormed after M11 closes.
