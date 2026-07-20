# M8 — Dividend & Income Engine

**Date:** 2026-07-20
**Status:** Approved design
**Scope:** All four platforms (macOS, iPhone, Windows desktop, Android)
**Follows:** M7 Investment Plans (Pies) — reuses its planner/coordinator, TradeSerializer, and catch-up patterns.

## Summary

APTrade holdings that pay dividends now generate income automatically. The engine detects
dividend events for held stocks and ETFs, credits the cash to the portfolio (or reinvests it
when DRIP is enabled), backfills the full history for existing positions, and surfaces income
through a dedicated Income view, a dividend history feed, upcoming-dividend projections with
notifications, and dividend info on every asset detail screen.

## Product decisions (locked)

| Decision | Choice |
| --- | --- |
| Dividend cash handling | **Global DRIP toggle** (default off). Off → dividends credit portfolio cash. On → auto-reinvest into the paying asset. |
| Data source | **Yahoo**, same provider as quotes/candles. Chart endpoint `events=div` for history; quote payload trailing dividend rate/yield for projection. No API key. |
| Surfaces | Income dashboard, dividend history feed, upcoming dividends + notifications, dividend info on asset detail. |
| Backfill | **Full backfill**: on first run, credit every dividend since each position was opened, reconstructed from the transaction ledger. Backfill is always cash, never DRIP. |
| Recording | **First-class transactions**: dividends live in the existing transaction ledger, not a parallel store. |
| Assets | Stocks and ETFs only. Crypto never pays dividends and is excluded everywhere. |

## Domain

### DividendEvent

New domain entity (both stacks):

- `symbol: String`
- `exDate` — epoch seconds (app-wide time convention)
- `amountPerShare: Money`

### Transaction schema evolution

- `TradeSide` gains a `.dividend` case (Swift) / `DIVIDEND` (Kotlin).
  - `quantity` = shares held at ex-date
  - `price` = amount per share
  - `date` = ex-date
  - Cash effect: `+ quantity × price`
- `Transaction` gains optional `isDrip: Bool` (default `false`). A DRIP reinvestment is the
  `.dividend` entry **plus** a normal `.buy` funded by it, flagged `isDrip = true`.
- Persistence back-compat: the new field is optional with a default, so previously persisted
  JSON decodes unchanged. Old app builds cannot read new ledgers (forward-only; accepted).
- Every exhaustive `switch`/`when` over `TradeSide` must handle `.dividend`; the compilers
  surface all call sites. Positions math: `.dividend` changes cash only, never share counts
  or cost basis. Realized/total return includes dividend income by construction.

### Crediting rules

1. **Eligibility:** shares held at an ex-date are reconstructed from transactions
   **strictly before** the ex-date (ex-date buyers don't receive the dividend).
2. **Timing:** crediting happens on the ex-date. Yahoo does not expose pay dates —
   documented simplification.
3. **Idempotency / dedup:** key is `(symbol, exDate)`. If a `.dividend` transaction for that
   pair exists, the event is already credited. Crash-safe: replaying a check never
   double-credits (same discipline as M7 contribution catch-up).
4. **Backfill:** first run fetches events back to each position's earliest transaction and
   credits everything as **cash**. After first run, the DRIP toggle's state *at processing
   time* decides cash vs reinvest.
5. **DRIP execution:** buy the paying asset at the **ex-date close** (historical close when
   catching up), fractional shares allowed (as with Pies). If the close price cannot be
   fetched, **fall back to a cash credit** — never drop the event.

### Income math (pure domain, no I/O)

- `sharesHeld(symbol, at:)` — ledger reconstruction (strictly-before semantics).
- Projected annual income per holding = trailing annual dividend rate × shares.
- Portfolio yield = projected income / market value; yield on cost = projected income / cost basis.
- **Cadence inference** from historical event spacing: ~30d monthly, ~91d quarterly,
  ~182d semi-annual, ~365d annual. Next projected event = last ex-date + cadence,
  amount = last amount. Fewer than 2 events → no projection.
- Monthly aggregation of received income for the chart.

## Data layer (shared Kotlin core — written once, bridged everywhere)

- `YahooMarketDataRepository.fetchDividendEvents(symbol, from)` — same chart endpoint with
  `events=div`; parse `events.dividends` map into `DividendEvent`s. New DTO fields + mapper.
- Quote mapper extended with nullable `trailingAnnualDividendRate` and
  `trailingAnnualDividendYield`.
- New use case `FetchDividendEvents`.
- Swift consumes both through the existing xcframework bridge
  (`SharedCoreMarketDataRepository`); never guess bridged names — verify in the generated header.

## Application layer (per stack, mirrors M7 contribution machinery)

- **`ProcessDueDividends`** use case: for each held stock/ETF symbol, fetch events in the
  window, filter to un-credited events with `sharesHeld > 0`, then credit cash or DRIP.
  All portfolio mutations go through **TradeSerializer** so dividend credits, DRIP buys,
  manual trades, and Pie contributions can never race.
- **Planner:** a dividend-check event joins `MarketActivityPlanner` alongside the
  contribution check — runs on launch and daily thereafter.
- **Cursor:** scheduler state store gains `lastDividendCheck` (epoch seconds). Normal runs
  fetch a trailing window with **7-day grace overlap**; first run (`lastDividendCheck`
  absent) fetches back to the earliest transaction (full backfill). The cursor advances
  only when every held symbol checks successfully; a partial failure leaves it untouched
  so the next run re-covers the window (the `(symbol, exDate)` dedup makes replays safe).
- **`DividendNotifier`** port → native notifications per platform.

## Settings

- **DRIP toggle** (default off) — new "Dividends" group.
- **Dividend notifications toggle** — next to the Pie-contribution notification toggle.
- Both persisted in the existing settings stores; localized EN/DE/IT/ES.
- Dividend *crediting* itself is always on — it is bookkeeping truth, not a gated action
  (unlike Pie execution, which stays behind its M7 toggle).

## Consistency (free wins from ledger integration)

- Reset portfolio wipes dividend history automatically (one ledger).
- Total return includes dividends by construction.
- `PortfolioExportRenderer` gains dividend rows.

## Error handling

- Network failure during a check → silent skip; retried next cycle (cursor not advanced
  on partial failure).
- Malformed Yahoo events → dropped with a log, never crash the check.
- DRIP close-price failure → cash-credit fallback (rule 5).
- Offline first run → backfill simply happens on the first successful check.

## UI

### Income view (new top-level destination beside Plans, gold-on-black design system)

- Summary cards: projected annual income, received YTD, portfolio yield, yield on cost.
- Monthly income bar chart: trailing 12 months received; projected future months rendered
  muted/hatched so real and estimated never blur.
- Upcoming section: next estimated ex-date + amount per holding (cadence inference),
  sorted by date.
- Per-holding breakdown: shares, annual income, yield on cost, last payment.

### Dividend history feed

- Chronological `.dividend` ledger: date, symbol, per-share amount, shares, cash credited,
  "reinvested" badge when a paired `isDrip` buy exists.
- Lives as a tab/filter inside the Income view.

### Asset detail

- For any stock/ETF (held or not): yield, dividend rate, next estimated ex-date,
  payment-history mini chart.
- Hidden entirely for non-payers and crypto.

### Notifications

- "Dividend received — AAPL paid $12.34" / "…reinvested into 0.052 shares".
- Localized EN/DE/IT/ES.

## Testing (TDD throughout)

- Domain: shares-at-ex-date reconstruction, cadence inference, backfill dedup/idempotency,
  DRIP cash fallback, monthly aggregation, strictly-before eligibility.
- Application: planner/coordinator tests with fake clocks; cursor advance/failure semantics;
  TradeSerializer integration.
- Infrastructure: Yahoo dividend DTO mapper against a captured real fixture; quote-mapper
  extension.
- Presentation: ViewModel tests per platform.
- Full suites green on every platform before merge.

## Rollout

Same three-increment arc as M7 — spec → plan → subagent-per-task with independent reviews →
whole-branch review → user UAT:

- **M8.1** — shared-core Yahoo dividend fetch + Swift domain/engine/UI → macOS + iPhone
- **M8.2** — Kotlin engine + Windows desktop UI
- **M8.3** — Android

## Out of scope

- Withholding tax / tax reporting.
- Real pay-date modeling (Yahoo exposes ex-dates only).
- Per-Pie DRIP routing (global toggle only; revisit if requested).
- Special/irregular dividend classification.
