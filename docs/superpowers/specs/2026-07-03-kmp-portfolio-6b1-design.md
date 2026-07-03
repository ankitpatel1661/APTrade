# KMP Increment 6b.1 — Shared Portfolio Core + Desktop Portfolio Tab (Design)

**Date:** 2026-07-03
**Status:** Approved (Approach A + program expansion approved in-session)
**Predecessors:** 6a (7889208), 6a.5 (96a0857) — desktop app with watchlist/detail/palette live.

## Context — the 6b program

The user expanded 6b to full portfolio parity INCLUDING items previously deferred. It is
decomposed into four sub-increments, executed sequentially, each with its own spec/plan:

- **6b.1 (this spec):** shared portfolio core + desktop Portfolio tab.
- **6b.2:** performance/risk intelligence (RiskMetrics, Diversification, performance metrics,
  snapshot history store) + desktop PerformanceSection.
- **6b.3:** macOS app adopts the shared portfolio core (first replacement of live Swift
  domain logic; the 193 Swift tests must stay green or be deliberately adapted).
- **6b.4:** Android Portfolio screen on the shared core.

**Approach A locked:** Kotlin port with Swift and Android untouched in 6b.1 (adoption is
6b.3/6b.4) — the WatchlistStore/market-data precedent.

## Goal of 6b.1

The desktop app's Portfolio tab reaches macOS parity for: paper trading (buy/sell/reset),
holdings with live valuation, realized/unrealized PnL, allocation, the dense equity curve,
and CSV/JSON export — all business logic in `:shared` commonMain, exact-decimal throughout.

## Shared domain (`:shared` commonMain — pure, framework-free, BigDecimal everywhere Swift uses Decimal)

Transcribed from the Swift originals (`Sources/APTradeDomain/…`), preserving semantics
exactly:

- `TradeSide` (Buy/Sell), sealed `TradeError` (`InsufficientFunds`, `InsufficientShares`,
  `InvalidQuantity`) as exceptions alongside `QuoteError`.
- `Transaction(id: String, symbol, side, quantity: BigDecimal, price: Money,
  epochSeconds: Long)` — id generated (no platform UUID dependency), epoch-seconds per the
  existing PricePoint convention.
- `Position(asset, quantity: BigDecimal, averageCost: Money, realizedPnL: Money)` with
  `marketValue(at)` / `unrealizedPnL(at)`.
- `Portfolio(cash, positions, transactions)`:
  - `starting()` = $100,000 cash (same constant).
  - `buying(asset, quantity, price, epochSeconds)` — validation order preserved: zero
    quantity → InvalidQuantity; cost > cash → InsufficientFunds; average-cost merge math
    identical to Swift (`(oldAvg×oldQty + cost) / newQty`).
  - `selling(symbol, quantity, price, epochSeconds)` — InsufficientShares when the position
    is missing or short; realized-PnL delta `(price − avgCost) × qty`; position removed at
    zero quantity.
  - `valuation(quotes: Map<String, Quote>): PortfolioValuation` — cash/holdings/total/
    unrealized/dayChange; cost-basis fallback for missing quotes; day change derived as
    `(price − previousClose) × qty` (Kotlin Quote carries previousClose, not change).
- Allocation (ported from `PortfolioAnalytics.swift`): per-position share of holdings value.
- Equity curve (ported from `PortfolioEquityCurve.swift`): `performanceSeries(histories)`
  building the dense value/unrealized-PnL curve from per-symbol PricePoint histories.
- Export (ported from `PortfolioExport.swift`): pure CSV and JSON renderers of the
  portfolio + valuation (golden-string tested). File writing is the platform's job.

Quantity representation: BigDecimal (exact fractional quantities for crypto), serialized as
plain decimal strings.

## Shared application layer

- `PortfolioStore` port: `load(): Portfolio?` (null = never saved → callers use
  `starting()`), `save(portfolio)`.
- Use cases (Kotlin `Fetch*` convention): `FetchPortfolio`, `BuyAsset` (live quote via
  MarketDataRepository → `buying` → save), `SellAsset`, `ResetPortfolio`,
  `FetchPortfolioPerformance(timeframe, sinceInception)` — concurrent per-symbol history
  fetches (coroutines async/awaitAll, per-symbol failures tolerated as empty), inception
  trim by first-transaction day.

## Desktop infrastructure

`FilePortfolioStore` — JSON in the config dir beside `watchlist.json`; atomic temp+rename
writes; missing/corrupt file → null (never-saved). BigDecimal fields as decimal strings.

## Desktop Portfolio tab (replaces the placeholder; fidelity targets pinned at plan time from `PortfolioView.swift`)

- Summary header: total value (SuperscriptPrice 34sp), day-change ChangePill, stat tiles
  (Cash / Holdings / Unrealized PnL / Realized PnL — PnL tiles in changeColor).
- Equity curve: LineChart with TimeframeBar (1D/1W/1M/1Y) + since-inception behavior per the
  macOS "Max" semantics if present in PortfolioView (confirmed at plan time).
- Allocation breakdown per macOS's presentation (pinned at plan time).
- Holdings list: name/symbol, quantity @ avg cost, market value, unrealized-PnL pill; row
  actions Buy / Sell.
- Trade dialog (TradeSheet parity): side, live price (refreshed), quantity input with exact-
  decimal parsing, validation errors inline (insufficient funds/shares/invalid quantity),
  confirm executes the use case and refreshes.
- Reset (confirmation dialog) and Export (CSV/JSON via desktop file dialog — AWT FileDialog).
- Live valuation on the 15s polling cadence (quotes for held symbols).
- MVVM: `PortfolioViewModel` (single-thread-confined scope, CancellationException-before-
  error discipline, amountText-only money to UI).

## Testing

- Domain TDD is the core of this increment: buy averaging, sell realized PnL, validation
  errors and their order, valuation incl. fallback and day-change derivation, allocation,
  equity-curve series, export goldens — mirroring Swift behavior exactly.
- Store round-trip/corrupt/missing; ViewModel tests (trade flow success + each error path,
  polling valuation refresh, reset, export content handed to the file layer); suite growth:
  shared 51 → +domain/use-case tests, desktop 55 → +VM/store tests. Android 13 / Swift 193
  untouched.
- Live verification on this Mac + CI `windows-desktop` green on merge.

## Out of scope for 6b.1 (lives in the program's later parts)

6b.2: RiskMetrics/Diversification/ComputePerformanceMetrics/PortfolioHistoryStore snapshots
+ PerformanceSection UI. 6b.3: macOS adoption. 6b.4: Android portfolio. Also out: alerts,
light theme, localization, TechnicalIndicators.

## Risks

- Exact-decimal parity between Swift Decimal and ionspin BigDecimal division: average-cost
  math uses division — define scale/rounding explicitly (documented in the plan; tests pin
  the behavior) so Kotlin results match Swift's Decimal semantics for realistic inputs.
- Trade dialog quantity parsing must reject locale-odd input; exact-decimal only.
- The desktop file dialog (AWT) is the first AWT use — keep it isolated in one small
  export-save helper so designkit stays extraction-ready.
