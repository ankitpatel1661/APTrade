# Design — Symbol Autocomplete & Paper-Trading Portfolio

**Date:** 2026-06-24
**App:** APTrade Lite (native macOS SwiftUI, Clean Architecture, Swift 6)
**Status:** Approved for planning

## Summary

Two features for APTrade Lite:

1. **Symbol autocomplete** — typing a partial query in the add-symbol bar shows
   ranked suggestions (name + symbol + kind) instead of requiring the exact ticker.
2. **Paper-trading portfolio** — simulated Buy/Sell with a virtual cash balance,
   tracked holdings, average cost, and realized/unrealized P&L, surfaced through a new
   top-level Watchlist / Portfolio switch.

Both follow the existing Clean Architecture layering (Domain → Application →
Infrastructure → Presentation), the gold-on-black design system, and the 15 s
live-polling pattern. All new money/quantity logic lives in the pure domain layer.

### Decisions locked in brainstorming

- **Trading model:** paper trading with a virtual cash balance (closest to the
  Portfolio spec in CLAUDE.md).
- **Portfolio UI:** a top-level Watchlist / Portfolio switch; Buy/Sell via a trade
  sheet on the asset detail view.
- **Order quantity:** fractional + whole units (essential for crypto), with a "Max"
  helper.
- **Defaults:** market orders at the current live price; **$100,000** starting cash;
  no overspending / overselling; a "Reset portfolio" action.
- Holdings are **simulated / paper only** and labeled as such in the UI.

---

## Feature 1 — Symbol Autocomplete

### Problem

Today the add flow requires an exact ticker: `SearchSymbolUseCase` calls
`repository.profile(for:)`, which only *validates* a single symbol via Yahoo's chart
endpoint. There is no multi-result search.

### Data source

Yahoo's search endpoint:
`https://query1.finance.yahoo.com/v1/finance/search?q=<query>&quotesCount=10&newsCount=0`

Returns a `quotes` array; each entry carries `symbol`, `shortname` / `longname`, and
`quoteType` (`EQUITY`, `ETF`, `CRYPTOCURRENCY`, `INDEX`, `FUTURE`, `CURRENCY`, …).

### Domain

No new types — reuse `Asset` (`symbol`, `name`, `kind`).

### Application

- New `SearchAssetsUseCase(query:) async throws -> [Asset]` — trims/normalizes the
  query, returns up to ~8 matches, empty array for an empty query.
- Keep the existing exact `SearchSymbolUseCase` as the Enter-key fallback (add the
  typed symbol directly when no suggestion is chosen).

### Infrastructure

- Add `search(query:) async throws -> [Asset]` to the `MarketDataRepository` port.
- Implement in `YahooMarketDataRepository`: new `YahooSearchDTO` + a mapper that maps
  `quoteType` → `AssetKind` (`EQUITY`→`.stock`, `ETF`→`.etf`, `CRYPTOCURRENCY`→
  `.crypto`) and **drops unsupported types** (indices, futures, FX, options). Prefer
  `shortname`, fall back to `longname`, then `symbol`, for the display name.
- `CachingMarketDataRepository` gains a short-TTL cache for search queries (keyed by
  normalized query) so rapid keystrokes that repeat a query don't re-hit the network.
  The default `profile(_:)` extension and other methods are unaffected.

### Presentation

- The bottom add-bar (`WatchlistView.addBar`) gains a **suggestions dropdown** above
  the field:
  - The view model debounces input (~250 ms) and runs a **cancellable** search `Task`
    per keystroke (cancels the previous one), mirroring the live-loop pattern.
  - Each suggestion row: display name + monospaced symbol + a small kind chip
    (Stock / ETF / Crypto), styled from `DesignKit`. Clicking adds it to the
    watchlist and clears the field.
  - Enter still adds the top suggestion (or the exact typed symbol if none).
  - No results → a quiet "No matches" row; network error → silent (field still
    accepts Enter as today).

---

## Feature 2 — Paper-Trading Portfolio

### Domain (`APTradeDomain`) — pure, no frameworks

- **`Quantity`** — value object wrapping `Decimal`, non-negative; formatting helper.
  Supports fractional amounts (crypto).
- **`Position`** — `asset: Asset`, `quantity: Quantity`, `averageCost: Money`,
  `realizedPnL: Money`.
- **`Transaction`** — `id: UUID`, `symbol: String`, `side: TradeSide` (`buy` / `sell`),
  `quantity: Quantity`, `price: Money`, `date: Date`.
- **`TradeError`** — `insufficientFunds`, `insufficientShares`, `invalidQuantity`.
- **`Portfolio`** — `cash: Money`, `positions: [Position]`, `transactions:
  [Transaction]`. Pure transition methods:
  - `buying(_ asset: Asset, quantity: Quantity, at price: Money, on date:) throws ->
    Portfolio` — validates cash ≥ cost; debits cash; updates/creates the position with
    a new **average cost**; appends a transaction. Returns a new `Portfolio`.
  - `selling(_ symbol: String, quantity: Quantity, at price: Money, on date:) throws ->
    Portfolio` — validates shares owned ≥ quantity; credits cash; reduces the position;
    accrues **realized P&L** = `(price − averageCost) × quantity`; removes the position
    when it reaches zero; appends a transaction.
  - `valuation(quotes: [String: Quote]) -> PortfolioValuation` — pure: market value per
    position, total market value, total unrealized P&L, total account value
    (`cash + market value`). Positions whose quote is missing fall back to cost basis.
- **`PortfolioValuation`** — derived view model-agnostic struct (per-symbol market value
  + unrealized P&L, plus totals). Pure.
- Starting portfolio: `Portfolio(cash: $100,000, positions: [], transactions: [])`.

Average-cost method (not FIFO lots) — simpler, matches a "premium investing overview"
product rather than a tax-lot tracker.

### Application (`APTradeApplication`)

- New port **`PortfolioStore: Sendable`** — `load() -> Portfolio`,
  `save(_ portfolio: Portfolio)`.
- Use cases:
  - `BuyAssetUseCase(asset:quantity:)` — fetches the live quote (market price), applies
    `Portfolio.buying`, persists, returns the updated portfolio (or throws
    `TradeError`).
  - `SellAssetUseCase(symbol:quantity:)` — symmetric, via `Portfolio.selling`.
  - `FetchPortfolioUseCase()` — loads the stored portfolio.
  - `ResetPortfolioUseCase()` — restores the $100k starting portfolio.
- Trades use `FetchQuotesUseCase`/the repository for the execution price so the domain
  stays free of networking.

### Infrastructure (`APTradeInfrastructure`)

- **`UserDefaultsPortfolioStore`** — Codable `Portfolio` persisted to UserDefaults,
  mirroring `UserDefaultsWatchlistStore` (seeded with the $100k starting portfolio on
  first run).

### Presentation (`APTradeApp`)

- **`RootView`** — hosts a top-level **Watchlist / Portfolio** segmented switch
  (gold-accented, consistent with `KindToggle`) and swaps between `WatchlistView` and
  `PortfolioView`. Becomes the app's root content.
- **`PortfolioView` + `PortfolioViewModel`**:
  - Header: total account value, day P&L (green/red), available cash, a small
    "Simulated / paper" label, and the `LiveBadge`.
  - Holdings list: per row — name + symbol, shares, average cost, market value,
    unrealized P&L (green/red). Empty state when no holdings.
  - Live-updates holdings' quotes on the same 15 s cadence (cancels on disappear).
  - Tapping a holding → `AssetDetailView`.
  - Overflow menu with "Reset portfolio" (confirmation).
- **`AssetDetailView`** gains **Buy / Sell** buttons that present a **`TradeSheet`**:
  - Shows live price, a fractional quantity field, a **Max** helper (max affordable on
    buy / shares owned on sell), estimated cost/proceeds, and available cash / shares
    owned.
  - Confirm executes the trade; disabled / shows an error on insufficient funds or
    shares.
  - When the asset is held, the detail view also shows the current position (shares,
    average cost, unrealized P&L).

### Color discipline

Buy/Sell buttons, the Watchlist/Portfolio switch, and the confirm action are
interactive accents → **gold** (`Theme.goldGradient`). Every P&L / change figure stays
**green/red** via `Theme.changeColor(_:)` — direction is data, never spent on branding.

### Composition root

Wire the new `PortfolioStore`, the four portfolio use cases, and the `search` port
through `CompositionRoot`. Add `makePortfolioViewModel()` and a `RootView` entry point.
No singletons — everything injected, as today.

---

## Testing

- **Domain:** `Quantity` validation/formatting; `Portfolio.buying` (cash debit, average
  cost across multiple buys); `Portfolio.selling` (cash credit, realized P&L, position
  removal at zero); both error paths (`insufficientFunds`, `insufficientShares`);
  `valuation` totals with present and missing quotes.
- **Application:** `BuyAssetUseCase` / `SellAssetUseCase` against a mock repository +
  in-memory portfolio store (happy path + error propagation); `ResetPortfolioUseCase`.
- **Infrastructure:** `YahooSearchMapper` against a fixture JSON (kind mapping +
  unsupported-type filtering); `UserDefaultsPortfolioStore` round-trip.
- **App:** `PortfolioViewModel` (valuation wiring, live refresh) and the autocomplete
  debounce/cancel behavior in `WatchlistViewModel` where unit-testable.
- The existing 42 tests stay green.

## Build order

1. Feature 1 (autocomplete) — self-contained, fast win.
2. Portfolio domain + application + tests.
3. Infrastructure (`UserDefaultsPortfolioStore`, search DTO/mapper).
4. Presentation (`RootView`, `PortfolioView`, `TradeSheet`, detail buttons).

## Out of scope (YAGNI)

Limit/stop orders, order history UI beyond the transactions used for P&L, multiple
portfolios, tax lots/FIFO, multi-currency, real brokerage integration, Supabase
persistence (UserDefaults remains the local store for Lite).
