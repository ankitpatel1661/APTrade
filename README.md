<div align="center">

<img src="logo/logo.png" alt="APTrade" width="200" />

# APTrade

**Trade. Invest. Grow.**

An ultra-premium **native macOS** investing platform — built in SwiftUI on a strict Clean Architecture, with a gold-on-black identity and live market data.

![Platform](https://img.shields.io/badge/platform-macOS%2014%2B-0C0B09?logo=apple)
![Swift](https://img.shields.io/badge/Swift-6.0-D4A94E?logo=swift)
![Architecture](https://img.shields.io/badge/architecture-Clean-D4A94E)
![Tests](https://img.shields.io/badge/tests-42%20passing-46C98A)

</div>

---

## Overview

APTrade is a desktop investing experience focused on **professional portfolio management and market intelligence** — not high-frequency trading. This repository is **APTrade Lite**, the foundation: a fully native, live-updating watchlist and asset-detail experience covering **stocks, ETFs, and crypto**, built to a production-quality bar.

The whole app is written against pure domain logic with framework code pushed to the edges, so the market data source (currently Yahoo Finance) is a single swappable adapter.

## Features

- **Category toggle** — a segmented `Stocks · ETFs · Crypto` control shows one category at a time, opening on the first populated one. Added symbols land in the right category automatically.
- **Live prices** — a 15-second polling loop refreshes quotes continuously, with a pulsing **LIVE** badge. The loop is tied to the view lifecycle and cancels on disappear.
- **Intraday sparklines** — each row draws today's 5-minute price trace, color-coded by direction.
- **Day pulse header** — the visible category's average day change plus an advancers / decliners split bar.
- **Asset detail** — a hero price, an area + line chart with `1D · 1W · 1M · 1Y` timeframes (opens on the live 1D intraday view), and a key-stats grid.
- **Distinctive numerics** — superscript-cents prices (`$131`⁶³) and bordered percentage-change pills throughout.
- **Persistent watchlist** — stored locally; add via symbol search (`AAPL`, `VOO`, `SOL-USD`).
- **Resilient data layer** — an `actor`-based quote cache (15s TTL) coalesces concurrent requests for the same symbol into a single network call.

## Design

A **gold-on-black** identity sampled from the APTrade logo: a champagne-gold gradient (`#A9772A → #D4A94E → #F2DDA0`) on a warm near-black ground, with silver for the secondary wordmark. Gold owns the brand and every interactive accent; **gains stay green and losses stay red** — in a trading app, price-direction color is data, never decoration.

## Architecture

Strict **Clean Architecture**. Dependencies point inward only; the domain knows nothing of frameworks, networking, or persistence.

```
┌─────────────────────────────────────────────┐
│  Presentation   SwiftUI views + @Observable  │  APTradeApp
│                 view models (MVVM)           │
├─────────────────────────────────────────────┤
│  Application    Use cases + ports (protocols)│  APTradeApplication
├─────────────────────────────────────────────┤
│  Domain         Entities + value objects     │  APTradeDomain
│                 (pure Swift, no imports)      │
├─────────────────────────────────────────────┤
│  Infrastructure Yahoo repository, caching,   │  APTradeInfrastructure
│                 persistence adapters         │
└─────────────────────────────────────────────┘
```

- **Domain** — `Asset`, `Quote`, `Money`, `Percentage`, `PricePoint`, `Timeframe`. No framework imports.
- **Application** — use cases (`FetchQuotes`, `FetchHistory`, `SearchSymbol`, watchlist add/remove/load) orchestrating over `MarketDataRepository` / `WatchlistStore` ports.
- **Infrastructure** — `YahooMarketDataRepository`, `CachingMarketDataRepository`, `UserDefaultsWatchlistStore`.
- **Presentation** — declarative SwiftUI views with thin `@Observable` view models; all dependencies wired in `CompositionRoot`.

Built throughout on Swift 6 concurrency — `async/await`, `actor` isolation, and `Sendable` types.

## Tech Stack

| Area | Choice |
|------|--------|
| UI | SwiftUI, Swift Charts |
| State | `@Observable` view models (MVVM) |
| Concurrency | Swift 6 `async/await`, actors |
| Market data | Yahoo Finance chart API |
| Persistence | `UserDefaults` (watchlist) |
| Build | Swift Package Manager |

## Getting Started

**Requirements:** macOS 14+, Xcode 16 / Swift 6 toolchain.

```bash
git clone https://github.com/ankitpatel1661/APTrade.git
cd APTrade
swift build
```

### Run

The app ships as a bare SwiftPM executable. Launching the built binary directly is the most reliable way to get a foreground window:

```bash
"$(swift build --show-bin-path)/APTradeApp"
```

> An `AppDelegate` promotes the process to a regular foreground app (`.regular` activation policy), so the window appears even without an `.app` bundle.

### Test

```bash
DEVELOPER_DIR=/Applications/Xcode.app swift test
```

> `DEVELOPER_DIR` must point at a full Xcode (not the Command Line Tools) so XCTest is available. 42 tests cover the domain math, use cases, the Yahoo mapper, the caching repository, and the view models.

## Project Structure

```
Sources/
├── APTradeDomain/          Entities & value objects (pure)
├── APTradeApplication/     Use cases & ports
├── APTradeInfrastructure/  Yahoo repo, caching, persistence
└── APTradeApp/             SwiftUI views, view models, DesignKit, Theme
Tests/                      One test target per layer
logo/                       Brand assets
```

## Roadmap

APTrade Lite is the foundation. Planned toward the full platform:

- Portfolio holdings, average cost, and unrealized / realized P&L
- Price & percentage-change alerts via native notifications
- Company, market, and crypto news
- More chart indicators (SMA, EMA, RSI, MACD, Bollinger, VWAP)
- Cloud sync and authentication

## Disclaimer

For educational and personal use. Market data comes from public Yahoo Finance endpoints and may be delayed or inaccurate. **Not financial advice.**
