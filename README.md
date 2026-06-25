<div align="center">

<img src="logo/logo.png" alt="APTrade" width="200" />

# APTrade

**Trade. Invest. Grow.**

An ultra-premium **native macOS** investing platform — built in SwiftUI on a strict Clean Architecture, with a gold-on-black identity and live market data.

![Platform](https://img.shields.io/badge/platform-macOS%2014%2B-0C0B09?logo=apple)
![Swift](https://img.shields.io/badge/Swift-6.0-D4A94E?logo=swift)
![Architecture](https://img.shields.io/badge/architecture-Clean-D4A94E)
![Tests](https://img.shields.io/badge/tests-94%20passing-46C98A)

</div>

---

## Overview

APTrade is a desktop investing experience focused on **professional portfolio management and market intelligence** — not high-frequency trading. This repository is **APTrade Lite**: a fully native, live-updating watchlist, asset-detail, and **simulated paper-trading portfolio** covering **stocks, ETFs, and crypto**, built to a production-quality bar.

The whole app is written against pure domain logic with framework code pushed to the edges, so the market data source (currently Yahoo Finance), persistence, and notifications are each a single swappable adapter behind a port.

## Features

### Watchlist & market data
- **Category toggle** — a segmented `Stocks · ETFs · Crypto` control shows one category at a time, opening on the first populated one. Added symbols land in the right category automatically.
- **Live prices** — a 15-second polling loop refreshes quotes continuously, with a pulsing **LIVE** badge. The loop is tied to the view lifecycle and cancels on disappear.
- **Intraday sparklines** — each row draws today's 5-minute price trace, color-coded by direction.
- **Day pulse header** — the visible category's average day change plus an advancers / decliners split bar, with its own intraday trace.
- **Symbol search** — debounced autocomplete (`AAPL`, `VOO`, `SOL-USD`) backed by Yahoo's search endpoint.
- **Asset detail** — a hero price, an area + line chart with `1D · 1W · 1M · 1Y` timeframes (opens on the live 1D intraday view), and a key-stats grid.

### Paper-trading portfolio
- **Buy / Sell** simulated orders with average-cost positions, an optional trade-confirmation step, and a Max helper.
- **Portfolio dashboard** — total value, Day P&L, Unrealized P&L, and cash, with holdings sorted by market value.
- **Value-over-time chart** — portfolio value is snapshotted on each refresh; tap the sparkline to expand an inline chart with axes, a hover crosshair, and Day / Unrealized P&L.
- **Reset** the portfolio back to its starting cash at any time.

### Alerts & notifications
- **Price alerts** — _price above_, _price below_, and _percent daily move_ conditions per symbol, delivered as native macOS notifications.
- **Order-fill notifications** on completed buys/sells.
- **Market open/close** and a **daily digest** of your watchlist's biggest movers, driven by a pure market-hours scheduler.

### Settings & appearance
- A unified, persisted **settings** layer — every preference (notification toggles, security/privacy, trade confirmation, theme, accent) flows through one store with a forward-compatible decoder.
- **Selectable accents** — Champagne Gold, Rose Gold, Sapphire, Amethyst, and Platinum re-tint the whole brand ramp; **dark / light** mode toggle.
- **Account drawer** with Profile, Account Settings, Notifications, Appearance, Security & Privacy, Help, and About subpages.

### Throughout
- **Distinctive numerics** — superscript-cents prices (`$131`⁶³) and bordered percentage-change pills.
- **Resilient data layer** — an `actor`-based quote cache (15s TTL) coalesces concurrent requests for the same symbol into a single network call, and persistence adapters fall back gracefully on corrupt data.

## Design

A **gold-on-black** identity sampled from the APTrade logo: a champagne-gold gradient (`#A9772A → #D4A94E → #F2DDA0`) on a warm near-black ground, with silver for the secondary wordmark. The accent can be re-themed (rose gold, sapphire, amethyst, platinum), but the rule is fixed: the accent owns the brand and **gains stay green, losses stay red** — in a trading app, price-direction color is data, never decoration, so the accent palette deliberately avoids green/red.

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
│                 persistence, notifications   │
└─────────────────────────────────────────────┘
```

- **Domain** — `Asset`, `Quote`, `Money`, `Percentage`, `Quantity`, `Portfolio`, `Position`, `PriceAlert`, `AppSettings`, `AccentTheme`, `MarketCalendar`, `Timeframe`. No framework imports.
- **Application** — use cases (quotes, history, search, watchlist, buy/sell, portfolio snapshots, alerts, settings, market-activity planning) orchestrating over ports: `MarketDataRepository`, `WatchlistStore`, `PortfolioStore`, `PortfolioHistoryStore`, `AlertStore`, `AlertNotifier`, `OrderFillNotifier`, `MarketEventNotifier`, `SettingsStore`, `SchedulerStateStore`.
- **Infrastructure** — `YahooMarketDataRepository`, `CachingMarketDataRepository`, `UserNotificationAlertNotifier`, and `UserDefaults`-backed stores for the watchlist, portfolio, history, alerts, settings, and scheduler state.
- **Presentation** — declarative SwiftUI views with thin `@Observable` view models; a `MarketActivityCoordinator` runs the notification scheduler; all dependencies wired in `CompositionRoot`.

Built throughout on Swift 6 concurrency — `async/await`, `actor` isolation, and `Sendable` types. Business policy (e.g. the market-hours scheduler) is kept as **pure, fully tested functions**, with clocks and I/O injected at the edges.

## Tech Stack

| Area | Choice |
|------|--------|
| UI | SwiftUI, Swift Charts |
| State | `@Observable` view models (MVVM) |
| Concurrency | Swift 6 `async/await`, actors |
| Market data | Yahoo Finance chart + search APIs |
| Notifications | `UserNotifications` (native macOS) |
| Persistence | `UserDefaults` adapters behind ports |
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

> An `AppDelegate` promotes the process to a regular foreground app (`.regular` activation policy), so the window appears even without an `.app` bundle. Native notifications only surface from a signed `.app` bundle; the bare dev binary skips delivery rather than crashing.

### Test

```bash
DEVELOPER_DIR=/Applications/Xcode.app swift test
```

> `DEVELOPER_DIR` must point at a full Xcode (not the Command Line Tools) so XCTest is available. **94 tests** cover the domain math, market calendar, use cases, the market-activity planner, alert/order-fill gating, the Yahoo mapper, the caching repository, settings round-trips, and the view models.

## Project Structure

```
Sources/
├── APTradeDomain/          Entities & value objects (pure)
├── APTradeApplication/     Use cases & ports
├── APTradeInfrastructure/  Yahoo repo, caching, persistence, notifications
└── APTradeApp/             SwiftUI views, view models, DesignKit, Theme
Tests/                      One test target per layer
logo/                       Brand assets
```

## Roadmap

APTrade Lite is the foundation. Planned toward the full platform:

- Company, market, and crypto **news** (replacing the watchlist-movers digest with real headlines)
- More chart indicators (SMA, EMA, RSI, MACD, Bollinger, VWAP)
- Realized P&L and transaction history
- Market-holiday calendar for the scheduler
- Real authentication (Apple Sign In), biometric gating, and cloud sync

## Disclaimer

For educational and personal use. Trading is **simulated paper trading** — no real orders are placed. Market data comes from public Yahoo Finance endpoints and may be delayed or inaccurate. **Not financial advice.**
