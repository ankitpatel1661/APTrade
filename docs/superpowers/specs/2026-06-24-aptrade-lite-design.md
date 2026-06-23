# APTrade Lite — Design

**Date:** 2026-06-24
**Status:** Approved (scope locked)

## Purpose

A small, runnable first slice of APTrade: a native macOS SwiftUI app that lets a
user keep a **watchlist** of stocks, ETFs, and crypto with live prices and daily
change, and drill into any symbol to see a **price chart** over a few timeframes.

This is deliberately the smallest *real* vertical slice — no accounts, no
portfolio P&L, no news, no alerts yet. It establishes the architecture and a
working data path so those features can grow in later behind the same seams.

## Non-goals (YAGNI for this slice)

- No authentication, Supabase, or sync.
- No portfolio / positions / transactions / P&L.
- No news, alerts, or notifications.
- No Metal/candlestick/technical indicators — a simple line chart only.
- No multiple watchlists — one list.

## Data source

A single free, keyless source: the Yahoo Finance chart endpoint.

```
https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range={range}&interval={interval}
```

- Works for stocks (`AAPL`), ETFs (`SPY`), and crypto (`BTC-USD`) — verified live
  on 2026-06-24.
- Response `chart.result[0].meta` gives `regularMarketPrice` and
  `chartPreviousClose` → current price + daily % change.
- Response `chart.result[0]` gives `timestamp[]` + `indicators.quote[0].close[]`
  → the historical series for the chart.

Timeframe → (range, interval) mapping:

| Timeframe | range | interval |
|-----------|-------|----------|
| 1D | `1d` | `5m` |
| 1W | `5d` | `30m` |
| 1M | `1mo` | `1d` |
| 1Y | `1y` | `1d` |

**Caveat:** this endpoint is undocumented/unofficial. Acceptable for a small app.
It lives behind a `MarketDataRepository` protocol so a keyed provider
(Finnhub/Alpha Vantage) can replace it later without touching other layers.

## Architecture

Miniature Clean Architecture. Dependency rule points inward; Presentation depends
on Application, Application on Domain. Infrastructure implements Application's
protocols and is injected at the composition root (the app entry point).

```
Presentation (SwiftUI + MVVM)
      │  calls use cases
      ▼
Application (use cases + repository protocols)
      │  depends on
      ▼
Domain (entities + value objects, pure)
      ▲  implemented by
Infrastructure (Yahoo repo, cache, persistence)  ── injected at App root
```

### Domain (pure Swift, no framework imports beyond Foundation)

- `Asset` — `symbol: String`, `name: String`, `kind: AssetKind` (`stock`, `etf`,
  `crypto`).
- `Quote` — `price: Money`, `previousClose: Money`, derived `change` /
  `changePercent: Percentage`.
- `PricePoint` — `date: Date`, `close: Money`.
- `Timeframe` — enum `oneDay`, `oneWeek`, `oneMonth`, `oneYear`.
- Value objects: `Money` (Decimal-backed, currency code), `Percentage`.
  No floats for money. No force-unwraps.

### Application

Repository protocols (the DI seams):

- `MarketDataRepository`
  - `func quote(for symbol: String) async throws -> Quote`
  - `func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint]`
- `WatchlistStore`
  - `func load() -> [Asset]`
  - `func save(_ assets: [Asset])`

Use cases (thin orchestrators):

- `FetchQuotesUseCase` — quotes for all watchlist symbols (concurrently).
- `FetchHistoryUseCase` — history for one symbol + timeframe.
- `AddToWatchlistUseCase` / `RemoveFromWatchlistUseCase`.
- `SearchSymbolUseCase` — resolve a typed symbol to an `Asset` (validates it
  exists by attempting a quote; infers `kind` from `-USD` suffix / exchange).

Errors: typed `AppError` (`network`, `notFound`, `decoding`, `rateLimited`).

### Infrastructure

- `YahooMarketDataRepository` — `URLSession` + `Codable` DTOs mapping the Yahoo
  JSON onto domain types. Pure mapping is unit-testable offline against a saved
  JSON fixture.
- `InMemoryQuoteCache` — short TTL (e.g. 15s) to avoid hammering the endpoint on
  refresh; wraps the repository (decorator).
- `UserDefaultsWatchlistStore` — persists the watchlist as JSON.

### Presentation (SwiftUI + MVVM)

- `WatchlistView` + `WatchlistViewModel` (`@MainActor`, `@Observable`): list of
  rows (symbol, name, price, % change colored green/red), add-symbol field,
  swipe/row delete, pull-to-refresh / periodic refresh via a timer.
- `AssetDetailView` + `AssetDetailViewModel`: header (price, change), Swift Charts
  line chart, timeframe segmented control.
- `APTradeLiteApp` — composition root: constructs infrastructure adapters, injects
  them into view models. Dark-mode-first styling per CLAUDE.md UI philosophy.

## Data flow (refresh)

1. View appears → `WatchlistViewModel.refresh()`.
2. Calls `FetchQuotesUseCase` → iterates symbols, calls cache→repository
   concurrently (`async let` / task group).
3. Repository fetches Yahoo JSON, maps to `Quote`.
4. View model publishes updated rows on `@MainActor`; UI re-renders.
5. Detail view: on appear / timeframe change → `FetchHistoryUseCase` → chart data.

## Error handling

- Each use case surfaces typed `AppError`.
- View models hold a `loadState` (`idle/loading/loaded/failed`) per screen and
  show an inline error with a retry button. A single failed symbol does not fail
  the whole watchlist (per-row error state).

## Testing

- **Domain:** unit tests for `Money`/`Percentage` math and `Quote` derivations.
- **Infrastructure:** `YahooMarketDataRepository` mapping tested against a checked-in
  JSON fixture (no network in tests).
- **Application:** use cases tested with a fake `MarketDataRepository` /
  `WatchlistStore`.
- **Presentation:** view-model state-transition tests with fakes.
- Live network calls are not part of the test suite.

## Project layout

Swift Package Manager (`Package.swift`) for the buildable/testable core, plus the
SwiftUI app target. Folder structure mirrors the layers:

```
Sources/
  APTradeDomain/
  APTradeApplication/
  APTradeInfrastructure/
  APTradeApp/            (SwiftUI views, view models, App entry / composition root)
Tests/
  APTradeDomainTests/
  APTradeApplicationTests/
  APTradeInfrastructureTests/
```

Build/run: `swift build` / `swift test` for the core; the app runs via the SwiftUI
target (SPM executable or a thin Xcode app target wrapping the packages).

## Success criteria

- `swift test` passes (domain, application, infrastructure mapping).
- App launches, shows a seeded watchlist (AAPL, SPY, BTC-USD, ETH-USD), fetches
  live prices with daily % change, add/remove works and persists across launches.
- Tapping a row opens the detail chart; switching timeframes reloads the series.
