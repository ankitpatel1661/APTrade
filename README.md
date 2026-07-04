<div align="center">

<img src="logo/logo.png" alt="APTrade" width="200" />

# APTrade

**Trade. Invest. Grow.**

An ultra-premium **native macOS** investing platform — built in SwiftUI on a strict Clean Architecture, with a gold-on-black identity and live market data.

![Platform](https://img.shields.io/badge/platform-macOS%2014%2B-0C0B09?logo=apple)
![Swift](https://img.shields.io/badge/Swift-6.0-D4A94E?logo=swift)
![Architecture](https://img.shields.io/badge/architecture-Clean-D4A94E)
![Tests](https://img.shields.io/badge/tests-200%20passing-46C98A)

</div>

---

## Overview

APTrade is a desktop investing experience focused on **professional portfolio management and market intelligence** — not high-frequency trading. This repository is **APTrade Lite**: a fully native, live-updating watchlist, asset-detail, and **simulated paper-trading portfolio** covering **stocks, ETFs, and crypto**, built to a production-quality bar.

The whole app is written against pure domain logic with framework code pushed to the edges, so the market data source (currently Yahoo Finance), the news source (Finnhub), persistence, and notifications are each a single swappable adapter behind a port.

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
│  Infrastructure Shared-core repo, caching,   │  APTradeInfrastructure
│                 persistence, notifications   │
└─────────────────────────────────────────────┘
```

- **Domain** — `Asset`, `Quote`, `Money`, `Percentage`, `Quantity`, `Portfolio`, `Position`, `Candle`, `PricePoint`, `PriceAlert`, `NewsArticle`, `NewsCategory`, `AppSettings`, `AccentTheme`, `AppLanguage`, `MarketCalendar`, `Timeframe`, plus pure calculations: `TechnicalIndicators` (SMA/EMA/RSI/Bollinger/MACD), portfolio performance reconstruction, realized-P&L, and the `PortfolioExport` model. No framework imports.
- **Application** — use cases (quotes, history, **candles**, search, watchlist, buy/sell, portfolio snapshots, **performance reconstruction**, **export**, **news**, **bookmarks**, alerts, settings, market-activity planning) orchestrating over ports: `MarketDataRepository`, `WatchlistStore`, `PortfolioStore`, `PortfolioHistoryStore`, `AlertStore`, `AlertNotifier`, `OrderFillNotifier`, `MarketEventNotifier`, `SettingsStore`, `SchedulerStateStore`, `PortfolioExportRenderer`, `NewsRepository`, `BookmarkStore`.
- **Infrastructure** — `SharedCoreMarketDataRepository` (quotes, history, OHLC candles, profile, and search via the shared Kotlin core), `CachingMarketDataRepository`, `FinnhubNewsRepository` (with an `EmptyNewsRepository` no-key fallback), `AppConfig` (reads the Finnhub key from `~/.config/aptrade/config.json`), `UserNotificationAlertNotifier`, `UserDefaults`-backed stores (incl. bookmarks), and a dependency-free **export renderer** (Core Graphics PDF + a hand-rolled ZIP writer producing OOXML `.xlsx` / `.docx`).
- **Presentation** — declarative SwiftUI views with thin `@Observable` view models; a `MarketActivityCoordinator` runs the notification scheduler; `ThemeManager` and `LocalizationManager` drive live theme/accent and language switching; a typed `L10n` catalog backs `tr(_:)` localization; all dependencies wired in `CompositionRoot`.

Built throughout on Swift 6 concurrency — `async/await`, `actor` isolation, and `Sendable` types. Business policy (e.g. the market-hours scheduler) is kept as **pure, fully tested functions**, with clocks and I/O injected at the edges.

## Tech Stack

| Area | Choice |
|------|--------|
| UI | SwiftUI, Swift Charts |
| State | `@Observable` view models (MVVM) |
| Concurrency | Swift 6 `async/await`, actors |
| Market data | Yahoo Finance chart + search APIs |
| News | Finnhub company / market news API |
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
"$(swift build --show-bin-path)/APTradeMac"
```

> An `AppDelegate` promotes the process to a regular foreground app (`.regular` activation policy), so the window appears even without an `.app` bundle. Native notifications only surface from a signed `.app` bundle; the bare dev binary skips delivery rather than crashing.

### Test

```bash
DEVELOPER_DIR=/Applications/Xcode.app swift test
```

> `DEVELOPER_DIR` must point at a full Xcode (not the Command Line Tools) so XCTest is available. **200 tests** cover the domain math (money, percentages, indicators, realized-P&L, performance reconstruction, the all-priced gate + benchmark head-trim), the market calendar, use cases, the market-activity planner, alert/order-fill gating, the Yahoo mapper, the Finnhub news mapper, the caching repository, the portfolio export renderers, settings round-trips, the bookmark store, the localization catalog and language manager, and the view models.

### Building the shared Kotlin core

Parts of the app (live quotes) are served by a Kotlin Multiplatform core in `shared/`,
linked as `Shared.xcframework`. The framework is a build artifact (not committed), so run

    ./scripts/build-shared.sh

once per clone — and again after any change under `shared/` — before `swift build` or
opening the Xcode project. Requires JDK 17 and full Xcode (the script points at the
Homebrew OpenJDK 17 and `/Applications/Xcode.app` by default; override via `JAVA_HOME`
/ `DEVELOPER_DIR`). The framework ships Apple-Silicon (arm64) slices only — iOS Simulator
builds must target arm64 (the default on Apple-Silicon Macs).

### Android app (walking skeleton)

A three-screen Jetpack Compose app (`androidApp/`) runs on the same shared Kotlin core as the
macOS/iOS app: live quotes for the default watchlist, debounced asset search, and an asset
detail view with line/candlestick charts across 1D/1W/1M/1Y timeframes.

Requirements: Android SDK (API 35) with `sdk.dir` in `local.properties`, JDK 17.

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :androidApp:assembleDebug          # build the debug APK
./gradlew :androidApp:testDebugUnitTest      # ViewModel unit tests
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

A **Portfolio screen** brings paper trading to Android on the same shared portfolio core as
macOS and the desktop app — reached from the list icon in the quotes top bar. It opens with a
summary header (total value, day-change pill, and cash / holdings / unrealized / realized
metrics) starting from $100,000, a span-driven **Performance** chart (`1D · 1W · 1M · 1Y ·
MAX`) with a **SPY/QQQ/VTI** benchmark picker overlaying the portfolio's equity curve against
a cash-flow-replay benchmark twin, per-holding rows with **BUY/SELL** trading through a modal
bottom sheet, allocation bars by holding and by asset class, an activity ledger of every
buy/sell with its trade date, an **Export…** action, and a **Reset portfolio** action.

Divergences from the macOS/desktop Portfolio in this first Android cut: export is a plain-text
**share-sheet** (CSV / JSON) with **no PDF**; the performance chart has **no crosshair
scrubber**; allocation is **bars only** (no donut chart); and the entry point is the top-bar
**List icon** rather than a dedicated tab. Trading is reachable both from an existing holding
row's BUY/SELL **and** from a prominent **BUY / SELL** action on each asset's detail screen
(matching the macOS app) — so a fresh $100,000 portfolio can make its first buy from asset
detail. Both entry points open the same modal-bottom-sheet trade form; a detail-made trade
persists to the shared store and appears in the Portfolio on return.

### Windows desktop app (walking skeleton)

A Compose Desktop app (`desktopApp/`) targets Windows, on the same shared Kotlin core as
the macOS/iOS/Android apps: a Watchlist tab with live prices and add/remove, an asset
detail view with line/candlestick charts, and a Ctrl+K search palette — recreating the
macOS app's gold-on-black visual identity. It's developed and run on this Mac as a proxy
for the Windows target; CI builds and tests it on an actual Windows runner.

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :desktopApp:run    # Compose Desktop app (Windows target; runs on macOS for dev)
./gradlew :desktopApp:test   # desktop ViewModel/store suites
```

Watchlist entries persist to a JSON file in the OS config directory: `%APPDATA%\APTrade\watchlist.json`
on Windows, `~/Library/Application Support/APTrade/watchlist.json` on macOS (dev runs).
The `windows-desktop` GitHub Actions workflow (`.github/workflows/windows-desktop.yml`)
runs `:desktopApp:test` and `:shared:jvmTest` on `windows-latest`, then packages an
`.msi` installer via `:desktopApp:packageMsi` and uploads it as the `APTrade-msi` build
artifact — the Windows build proof for this increment.

A **Portfolio tab** brings paper trading to the desktop app on the same shared portfolio
core as macOS: a summary header (value, day change, total return) with formatted money
throughout, a single **Export…** chooser (CSV / JSON / PDF), and a reset action; a
**Holdings / Allocation / Activity** switcher — holdings sorted by market value with
per-row **BUY/SELL** and unrealized P&L, allocation as a donut chart plus per-holding and
by-asset-class percentage bars, and an activity ledger of every buy/sell with an absolute
trade timestamp (symbol, quantity, price, and the date/time it executed). Directly under
the summary header sits **one** span-driven **Performance** chart — the sole portfolio
chart, replacing the previous separate account-value chart — with a five-span bar
(`1D · 1W · 1M · 1Y · MAX`), a segmented **SPY/QQQ/VTI** benchmark picker overlaying the
portfolio's dollar equity curve against a **cash-flow-replay benchmark twin** — the same
dollars invested in the benchmark at each historical trade, so both curves are actual
dollar values plotted dollar-vs-dollar rather than rebased to a common index start (a
desktop-first design beyond the current macOS overlay) — a hover **crosshair
scrubber** over the overlay (a tooltip pill showing the hovered point's value and date,
plus a live running-change readout next to the "PERFORMANCE" label that tracks the
crosshair), and a 7-tile risk grid below it (Total Return, Annualized, Volatility, Max
Drawdown, Sharpe, Beta, Alpha). Buying a symbol not yet held starts from the **BUY / SELL**
button on its asset detail screen, which also gained **KEY STATS** (last, previous close,
day change/%, symbol, type) and, when held, a **YOUR POSITION** card (shares, average cost,
market value, unrealized P&L), plus the same six chart indicators as macOS — **SMA 20,
EMA 12, VWAP, Bollinger Bands (20), RSI (14), and MACD (12·26·9)** — as overlays and
dedicated RSI/MACD panes. An **account panel** (⋯ button) offers Appearance — **5** accent
themes (Champagne Gold, Rose Gold, Sapphire, Amethyst, Platinum), each of which also
retints the brand wordmark itself (gold pixels remapped onto the chosen accent's ramp by
luminance, the same technique as macOS) — persisted to a `settings.json` in the OS config
directory — and an About page; every other row (Profile, Notifications, Security &
Privacy, etc.) is a shared "not available yet" placeholder.
**Recorded divergences from macOS:** desktop Appearance has no Dark/Light toggle yet, and
most account-panel pages beyond Appearance/Export/About are placeholders. The
`PortfolioViewModel`, trade dialog, indicator math (`TechnicalIndicators`), and risk
metrics (`RiskMetrics`) all live on the `:shared` Kotlin core alongside the file-backed
`PortfolioStore`, so the same domain math and persistence format will carry to the macOS
app in a later increment; the risk-metrics grid itself (Total Return through Alpha) stays
macOS-parity, computed on benchmark closes exactly as before. The cash-flow-replay
benchmark twin feeding the overlay chart (`FetchPerformanceReport.benchmarkTwinValues`) is
a desktop-first design with no macOS equivalent yet; only the PDF byte-rendering itself is
a desktop-(JVM)-side adapter.

A **News tab** brings Finnhub-backed headlines to the desktop app: a category pill row —
**General · Crypto · Merger** — plus a trailing **Saved** toggle that swaps the feed for
bookmarked articles (selecting Saved visually deselects the category pills; picking a
category leaves the Saved view). A live filter capsule narrows the visible feed by headline
or source as you type, with no debounce. Each article row shows a thumbnail, headline,
`source · relative time` (e.g. "3 hours ago"), and a per-row bookmark toggle; clicking the
row body opens the article in your default browser. The same asset detail screen used for
Watchlist/Portfolio gained a **News** section — up to **8** company-news headlines for that
symbol, reusing the same row/bookmark behavior, and rendering nothing at all when there's no
key or no articles. Bookmarks persist to a `bookmarks.json` in the OS config directory (the
same directory `watchlist.json`/`settings.json` already use) and survive restarts via the
**Saved** view. **No-key state:** without a Finnhub key the whole News tab is replaced by a
"connect a news source" prompt reading *"Add a Finnhub API key to
`~/.config/aptrade/config.json` (field `finnhubAPIKey`) and relaunch."* The key itself is
read from `<config dir>/config.json` first (`~/Library/Application Support/APTrade/config.json`
on macOS dev runs, `%APPDATA%\APTrade\config.json` on Windows), falling back to the
macOS-style dotfile path `~/.config/aptrade/config.json` if the primary file is absent —
never from the source tree or the app bundle.
**Recorded divergences from macOS:** bookmarks persist to a JSON file in the OS config
directory rather than macOS `UserDefaults`, since the desktop app has no `UserDefaults`
equivalent; and relative-time labels ("N minutes/hours/days ago", falling back to an
absolute `MMM d` date past 7 days) are a custom fixed English-only formatter, since the JDK
has no equivalent to Foundation's locale-aware `RelativeDateTimeFormatter` that macOS uses.

## Project Structure

```
Sources/
├── APTradeDomain/          Entities & value objects (pure)
├── APTradeApplication/     Use cases & ports
├── APTradeInfrastructure/  Yahoo repo, caching, persistence, notifications
└── APTradeApp/             SwiftUI views, view models, DesignKit, Theme
Tests/                      One test target per layer
androidApp/                 Android app (Jetpack Compose walking skeleton)
desktopApp/                 Windows app (Compose Desktop walking skeleton)
logo/                       Brand assets
```

## Roadmap

APTrade Lite is the foundation. Planned toward the full platform:

- Market-holiday calendar for the scheduler
- Real authentication (Apple Sign In), biometric gating, and cloud sync (Supabase)
- **Windows parity, continued** — the `:desktopApp` Compose app now covers Watchlist +
  detail + palette (6a), a Portfolio tab with detail-screen indicators, performance/risk
  intelligence, and export (6b.1 + 6b.2), a News tab with per-symbol company news and
  bookmarks (6c), macOS adoption of the shared portfolio core's all-priced performance
  gate (6b.3), and an Android Portfolio screen on the shared portfolio core (6b.4). Still to
  come: **6d** alerts, account panel, settings, and light theme.

Recently shipped: an Android **Portfolio screen** (increment 6b.4) — paper trading on the
shared Kotlin portfolio core, reached from the quotes top-bar list icon: a summary header
(value, day-change pill, cash/holdings/unrealized/realized) from a $100,000 start, a
span-driven Performance chart (1D · 1W · 1M · 1Y · MAX) with an SPY/QQQ/VTI benchmark twin,
per-holding BUY/SELL through a modal bottom sheet, allocation bars by holding and asset class,
a dated activity ledger, a share-sheet CSV/JSON export, and a reset action — with
`FilePortfolioStore` relocated into the `:shared` `jvmCommon` source set so desktop and
Android share one persistence adapter; macOS **parity payback** (increment 6b.3) — the native `performanceSeries`
reconstruction now adopts the shared Kotlin core's all-priced gate (a date only counts once
every symbol with history is priced, rather than the moment any symbol is), and the
benchmark curve is head-trimmed to the portfolio curve's post-gate start date before risk
metrics are computed, so beta/alpha compare like-for-like ranges; desktop **News**
(`:desktopApp`, increment 6c) — a Finnhub-backed News tab
(General/Crypto/Merger categories, live headline/source filter, bookmarks with a persisted
Saved view, open-in-browser) plus a per-symbol News section (≤8 articles) on the asset detail
screen, backed by a new shared `:shared` Finnhub news core (`NewsRepository`,
`FetchMarketNews`/`FetchCompanyNews`/bookmark use cases) also consumed by a file-backed
`FileBookmarkStore` and a `FinnhubKeyConfig` reader; desktop **portfolio intelligence and
fidelity** (`:desktopApp`, increment 6b.2) — six chart indicators (SMA 20, EMA 12, VWAP,
Bollinger Bands, RSI, MACD) as detail-screen overlays with dedicated RSI/MACD panes, KEY
STATS and YOUR POSITION cards with a BUY/SELL action on the detail screen, an allocation
donut chart, a Performance section with an SPY/QQQ/VTI benchmark picker and a 7-metric risk
grid (Total Return, Annualized, Volatility, Max Drawdown, Sharpe, Beta, Alpha), a single
Export… chooser (CSV/JSON/PDF), and formatted money throughout; a desktop **Portfolio tab**
(`:desktopApp`, increment 6b.1) — paper trading, holdings with per-row buy/sell, allocation
bars, an activity ledger, an account-value chart, and CSV/JSON export, on a new shared Kotlin
portfolio core (`PortfolioStore`, buy/sell/reset/performance use cases) also consumed by a
file-backed persistence adapter; a **Windows Compose Desktop app** (`:desktopApp`, increment
6a) with a live Watchlist tab, asset detail (charts + stat tiles), and a Ctrl+K palette on
the shared Kotlin core, plus a `windows-desktop` CI workflow producing a Windows `.msi`; a
Finnhub-backed **News** tab (company/market/crypto headlines, filter, bookmarks) plus
per-symbol company news on the asset view — for the native **macOS** app; a ⌘K **command
palette**; **risk & performance** analytics (TWR/CAGR, volatility, drawdown, Sharpe/Beta/
Alpha, benchmark overlay, concentration warnings); an in-app **language switcher**
(English/Deutsch/Italiano/Español); candlestick charts, SMA/EMA/RSI/MACD/Bollinger
indicators, realized P&L and a transactions ledger, allocation breakdown, historical P&L
reconstruction, and PDF/Excel/Word portfolio export.

## Disclaimer

For educational and personal use. Trading is **simulated paper trading** — no real orders are placed. Market data comes from public Yahoo Finance endpoints and may be delayed or inaccurate. **Not financial advice.**
