# Risk & Performance Intelligence — Design

**Date:** 2026-06-28
**Status:** Approved (design); pending implementation plan
**Feature 1 of 3** in the "elevate the experience" arc (Risk/Perf → Command Palette → News).

## Goal

Turn the Portfolio tab from a value viewer into a genuine analytics surface. Add a
**Performance** segment that reports honest, professionally-correct portfolio metrics —
the kind of thing Wealthfront / Interactive Brokers show — all computed from data the app
already has (the transaction log + historical prices).

## Scope

In scope:

- **Returns:** Total Return (time-weighted, TWR) and Annualized Return (CAGR).
- **Risk:** Annualized Volatility, Max Drawdown, Sharpe ratio.
- **Benchmark:** user-selectable index (SPY / QQQ / VTI), a normalized cumulative-return
  overlay (portfolio vs. benchmark, both rebased to 100 at the window start), plus Alpha
  and Beta.
- **Concentration:** a diversification score (Herfindahl/HHI) and concentration warnings.
- **Surfacing:** a new `.performance` segment in the existing Portfolio picker, alongside
  Holdings / Allocation / Activity.

Explicitly out of scope (and why):

- **Money-weighted return / IRR.** In a fixed-balance paper account there are no external
  cash deposits or withdrawals — every buy/sell is an internal reallocation. With a single
  inception cashflow, IRR (money-weighted) and TWR are mathematically identical. Shipping
  both would be a misleading duplicate. IRR returns only if/when a deposit/withdraw action
  is added. Decided: **TWR + CAGR, drop IRR.**
- Per-position risk attribution, factor models, Monte Carlo — future work.

## Key correctness decisions

1. **Trade-aware equity curve (the honesty upgrade).** The existing
   `Portfolio.performanceSeries(histories:)` values *current* holdings backward against
   historical prices — a "what if I'd always held today's positions" curve. Risk metrics
   computed on that describe a hypothetical, not the real account. This design adds a new
   `equitySeries(histories:)` that **replays the transaction log** to reconstruct actual
   holdings + cash as of each date, then values them. This is the canonical curve for all
   metrics. The existing `performanceSeries` is left in place for the current P&L chart.

2. **No external cashflows ⇒ TWR = total holding-period return.** Because there are no
   interim external flows, the geometrically-linked daily-return TWR equals the simple
   end/start total return. We compute it via linked daily returns (robust, reusable for
   risk metrics) and present it as "Total Return."

3. **Annualization:** 252 trading days for CAGR and volatility (σ·√252). Documented
   assumption; revisit if the app becomes crypto-dominant (365).

4. **Risk-free rate:** a parameter on the use case, default **4.0% annualized**. Used in
   Sharpe and Alpha. Constant for v1; could later read from a T-bill series.

## Architecture (Clean Architecture, outer → inner)

### Domain layer (pure; imports only `Foundation`; fully unit-tested)

- **`RiskMetrics.swift`** — pure functions over a daily value series:
  - daily simple returns: `rₜ = Vₜ / Vₜ₋₁ − 1`
  - `totalReturn` = ∏(1 + rₜ) − 1 (geometrically linked TWR)
  - `annualizedReturn` (CAGR) = (1 + totalReturn)^(252 / n) − 1
  - `annualizedVolatility` = stddev(daily returns) · √252
  - `maxDrawdown` = worst peak-to-trough decline across the curve
  - `sharpeRatio(riskFree:)` = (annualizedReturn − rf) / annualizedVolatility
  - `beta(against:)`, `alpha(against:, riskFree:)` — regression of portfolio daily returns
    on benchmark daily returns (cov/var for beta; CAPM residual for alpha)
  - Output struct: `PerformanceMetrics { totalReturn, annualizedReturn, volatility,
    maxDrawdown, sharpe, beta?, alpha? }` (`beta`/`alpha` optional — nil when no benchmark)

- **`Diversification.swift`** — pure:
  - `concentration` (HHI) = Σ wᵢ² over current position weights
  - `effectiveHoldings` = 1 / HHI
  - `concentrationWarnings: [ConcentrationWarning]` — e.g. single-name weight > 25%,
    asset-kind dominance. Reuses existing allocation weights.

- **`PortfolioEquityCurve.swift`** — `Portfolio.equitySeries(histories:)`: replays the
  sorted transaction log to compute holdings + cash as of each date, values holdings
  against each symbol's historical closes, forward-filling per symbol to align differing
  trading calendars (reusing the approach already in `performanceSeries`). Output:
  `[PortfolioPerformancePoint]` (reused type).

### Application layer

- **`ComputePerformanceMetricsUseCase`** — orchestration:
  1. load portfolio (`PortfolioStore`)
  2. fetch histories for all held symbols **and** the benchmark over `timeframe`, in
     parallel (existing `withTaskGroup` pattern), via `MarketDataRepository.history` — no
     new port needed
  3. build the trade-aware equity curve; compute `PerformanceMetrics` + benchmark
     alpha/beta; compute diversification
  4. return `PerformanceReport { metrics, equityCurve, benchmarkCurve, diversification,
     warnings, benchmarkSymbol, timeframe }`
  - Risk-free rate is an init parameter (default 4.0%).

### Presentation layer

- `PortfolioView.Section` gains a `.performance` case (picker becomes Holdings /
  Allocation / Activity / Performance).
- **`PerformanceViewModel`** (dedicated, to respect the "no massive ViewModels" rule):
  state `loading / loaded(PerformanceReport) / empty / error`; inputs: selected timeframe,
  selected benchmark symbol; method `load(timeframe:benchmark:)`.
- **`PerformanceSection.swift`** (new file, to keep `PortfolioView` from bloating):
  - metric-card grid: Total Return, CAGR, Volatility, Max Drawdown, Sharpe, Beta, Alpha
  - normalized benchmark-overlay chart (portfolio vs. benchmark, both rebased to 100)
  - benchmark picker (SPY / QQQ / VTI)
  - diversification score + concentration warning chips
  - gold-on-black DesignKit components + Swift Charts, matching existing sections.

## Data flow

Select Performance → `PerformanceViewModel.load(timeframe, benchmark)` →
`ComputePerformanceMetricsUseCase` → parallel fetch of holding + benchmark histories +
portfolio load → domain builds equity curve + metrics + diversification → `PerformanceReport`
→ cards + overlay + warnings render.

## Error handling & edge cases

- No positions / all cash → empty state: "Add holdings to see performance analytics."
- Benchmark fetch fails → still render portfolio metrics; hide Alpha/Beta + overlay and
  show a quiet "benchmark unavailable" note. The panel never fully fails on benchmark error.
- Fewer than 2 data points → "Not enough history yet."
- Zero volatility → Sharpe / Beta render "—" (guard divide-by-zero).
- Crypto vs. equity calendar misalignment → handled by per-symbol forward-fill in the
  equity curve.
- Network errors surfaced via the existing `AppError` pattern.

## Testing

- **Domain (bulk of tests):** hand-computed fixtures for every metric — total return, CAGR,
  volatility, max drawdown, Sharpe, beta, alpha, HHI, effective holdings, warnings. Edge
  cases: empty series, single point, flat series (zero vol), all-cash portfolio. Equity-curve
  replay verified against a small known transaction log + price fixtures.
- **Application:** `ComputePerformanceMetricsUseCase` against a stub `MarketDataRepository`
  returning fixture histories, including the benchmark-failure path.
- **Presentation:** `PerformanceViewModel` state transitions (loading → loaded / empty /
  error) against a stub use case.

## Build/run/test notes

- `swift test` requires `DEVELOPER_DIR` pointing at Xcode.app (see project memory) or XCTest
  is missing.

## Out-of-scope follow-ups (tracked for later)

- Deposit/withdraw cash action → would make a real IRR meaningful.
- Configurable / live risk-free rate (T-bill series).
- Per-position risk contribution and sector-level concentration (needs sector metadata).
