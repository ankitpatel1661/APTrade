<div align="center">

<img src="logo/logo.png" alt="APTrade" width="200" />

# APTrade

**Trade. Invest. Grow.**

An ultra-premium **native macOS** investing platform — built in SwiftUI on a strict Clean Architecture, with a gold-on-black identity and live market data.

![Platform](https://img.shields.io/badge/platform-macOS%2014%2B-0C0B09?logo=apple)
![Swift](https://img.shields.io/badge/Swift-6.0-D4A94E?logo=swift)
![Architecture](https://img.shields.io/badge/architecture-Clean-D4A94E)
![Tests](https://img.shields.io/badge/tests-113%20passing-46C98A)

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

### Premium charts
- **Asset detail** — a hero price with timeframe-aware change, `1D · 1W · 1M · 1Y` timeframes (opens on the live 1D intraday view), and a key-stats grid.
- **Area / Candlestick toggle** — real OHLC candlesticks (green/red by close vs. open) drawn from genuine open-high-low-close data, or a smooth area + line.
- **Technical indicators** — **SMA 20**, **EMA 12**, **Bollinger Bands (20, 2σ)** overlaid on price, plus **RSI 14** and **MACD (12·26·9)** in their own panes. Each indicator has a distinct hue kept clear of the green/red price direction.
- **Crosshair** — hover for a tooltip with the price (and full OHLC in candle mode) at any point.

### Paper-trading portfolio
- **Buy / Sell** simulated orders with average-cost positions, an optional trade-confirmation step, and a Max helper.
- **Holdings / Allocation / Activity / Performance** — a portfolio sub-switcher:
  - **Holdings** — positions sorted by market value, each with unrealized P&L and return %, plus a **top-movers** strip of today's biggest moves.
  - **Allocation** — a donut by asset class (Stocks / ETFs / Crypto) with holdings value centered, and a per-holding percentage breakdown.
  - **Activity** — **realized P&L** (average-cost, computed from the full transaction log so closed positions still count) and the complete transactions ledger.
  - **Performance** — risk & return analytics computed on a trade-aware equity curve (replaying the transaction log, not just today's holdings backward): **Total Return**, **Annualized Return (CAGR)**, **Volatility**, **Max Drawdown**, **Sharpe**, **Beta**, and **Alpha**. A normalized overlay chart compares the portfolio against a selectable benchmark (**SPY · QQQ · VTI**), and a **diversification score** (effective holdings, Herfindahl-based) flags single-name and asset-class concentration.
- **P&L-over-time chart** — unrealized P&L reconstructed from real historical prices over a selectable timeframe, colored green/red by direction; tap the sparkline to expand it inline with axes and a hover crosshair.
- **Export** the portfolio as a **PDF**, **Excel (.xlsx)**, or **Word (.docx)** statement — genuine, standards-compliant documents saved anywhere via a native save panel.
- **Reset** the portfolio back to its starting cash at any time.

### Alerts & notifications
- **Price alerts** — _price above_, _price below_, and _percent daily move_ conditions per symbol, delivered as native macOS notifications.
- **Order-fill notifications** on completed buys/sells.
- **Market open/close** and a **daily digest** of your watchlist's biggest movers, driven by a pure market-hours scheduler.

### Command palette (⌘K)
- **Universal ⌘K palette** — press ⌘K (or the header search button) anywhere to fuzzy-search any stock, ETF, or crypto and jump straight to its detail view, plus quick "Go to Watchlist / Portfolio" shortcuts.
- **Keyboard-first** — arrow keys move the selection, Return opens it, Escape dismisses; every result is also clickable. Selecting an asset opens it in an isolated detail sheet without disturbing the tab you're on.

### News
- **Market headlines** in a dedicated News tab — **General**, **Crypto**, and **Merger** categories, backed by Finnhub, each a tap away.
- **Per-symbol company news** woven into every asset's detail view, showing the latest headlines for that ticker with the same row and bookmark behavior.
- **Filter** the feed instantly by headline or source, **bookmark** any article, and flip to a **Saved** view that persists across launches. Tapping a headline opens it in your default browser.
- **No-key state** — without a Finnhub key the app degrades gracefully to a "connect a news source" prompt; the key lives only in `~/.config/aptrade/config.json`, never in the bundle.

### Settings & appearance
- A unified, persisted **settings** layer — every preference (notification toggles, security/privacy, trade confirmation, theme, accent) flows through one store with a forward-compatible decoder.
- **Selectable accents** — Champagne Gold, Rose Gold, Sapphire, Amethyst, and Platinum re-tint the whole brand ramp **including the logo/wordmark artwork** (gold pixels are remapped onto the chosen accent); **dark / light** mode toggle.
- **Account drawer** with Profile, Account Settings, Notifications, Appearance, Language, Security & Privacy, Help, and About subpages.
- **In-app language switcher** — **English, Deutsch, Italiano, Español**, chosen from a dedicated Language row in the account drawer. The entire interface re-renders **live, with no restart** (the same Observation-driven mechanism as the theme/accent toggles), and the choice persists across launches. Live market data — news headlines, company and ticker names — and number/currency formatting stay in their source form; only the app's own chrome is translated.

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

- **Domain** — `Asset`, `Quote`, `Money`, `Percentage`, `Quantity`, `Portfolio`, `Position`, `Candle`, `PricePoint`, `PriceAlert`, `AppSettings`, `AccentTheme`, `MarketCalendar`, `Timeframe`, plus pure calculations: `TechnicalIndicators` (SMA/EMA/RSI/Bollinger/MACD), portfolio performance reconstruction, realized-P&L, and the `PortfolioExport` model. No framework imports.
- **Application** — use cases (quotes, history, **candles**, search, watchlist, buy/sell, portfolio snapshots, **performance reconstruction**, **export**, alerts, settings, market-activity planning) orchestrating over ports: `MarketDataRepository`, `WatchlistStore`, `PortfolioStore`, `PortfolioHistoryStore`, `AlertStore`, `AlertNotifier`, `OrderFillNotifier`, `MarketEventNotifier`, `SettingsStore`, `SchedulerStateStore`, `PortfolioExportRenderer`.
- **Infrastructure** — `YahooMarketDataRepository` (quotes, history, OHLC candles), `CachingMarketDataRepository`, `UserNotificationAlertNotifier`, `UserDefaults`-backed stores, and a dependency-free **export renderer** (Core Graphics PDF + a hand-rolled ZIP writer producing OOXML `.xlsx` / `.docx`).
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
| Export | Core Graphics (PDF) + OOXML `.xlsx`/`.docx` via a custom ZIP writer |
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

> `DEVELOPER_DIR` must point at a full Xcode (not the Command Line Tools) so XCTest is available. **113 tests** cover the domain math (money, percentages, indicators, realized-P&L, performance reconstruction), the market calendar, use cases, the market-activity planner, alert/order-fill gating, the Yahoo mapper, the caching repository, the portfolio export renderers, settings round-trips, and the view models.

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

- **VWAP** indicator (requires adding volume to the OHLC pipeline)
- Market-holiday calendar for the scheduler
- Real authentication (Apple Sign In), biometric gating, and cloud sync (Supabase)

Recently shipped: a Finnhub-backed **News** tab (company/market/crypto headlines, filter, bookmarks) plus per-symbol company news on the asset view; a ⌘K **command palette**; **risk & performance** analytics (TWR/CAGR, volatility, drawdown, Sharpe/Beta/Alpha, benchmark overlay, concentration warnings); an in-app **language switcher** (English/Deutsch/Italiano/Español); candlestick charts, SMA/EMA/RSI/MACD/Bollinger indicators, realized P&L and a transactions ledger, allocation breakdown, historical P&L reconstruction, and PDF/Excel/Word portfolio export.

## Disclaimer

For educational and personal use. Trading is **simulated paper trading** — no real orders are placed. Market data comes from public Yahoo Finance endpoints and may be delayed or inaccurate. **Not financial advice.**
