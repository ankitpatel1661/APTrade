<div align="center">

<img src="logo/logo.png" alt="APTrade" width="200" />

# APTrade

**Trade. Invest. Grow.**

An ultra-premium **native investing platform across four OSes** — a SwiftUI flagship on **macOS**, with full-parity native apps on **Windows**, **iPhone**, and **Android** — built on a strict Clean Architecture, a gold-on-black identity, and live market data.

![macOS](https://img.shields.io/badge/macOS-14%2B-0C0B09?logo=apple)
![Windows](https://img.shields.io/badge/Windows-Compose%20Desktop-0C0B09?logo=windows)
![iOS](https://img.shields.io/badge/iPhone-iOS%2017%2B-0C0B09?logo=apple)
![Android](https://img.shields.io/badge/Android-API%2035-0C0B09?logo=android)
![Swift](https://img.shields.io/badge/Swift-6.0-D4A94E?logo=swift)
![Kotlin](https://img.shields.io/badge/Kotlin-Multiplatform-D4A94E?logo=kotlin)
![Architecture](https://img.shields.io/badge/architecture-Clean-D4A94E)
![Tests](https://img.shields.io/badge/macOS%20tests-400%20passing-46C98A)

</div>

---

## Overview

APTrade is an investing experience focused on **professional portfolio management and market intelligence** — not high-frequency trading. This repository is **APTrade Lite**: a fully native, live-updating watchlist, asset-detail, and **simulated paper-trading portfolio** covering **stocks, ETFs, and crypto**, built to a production-quality bar — and it now ships as **four native apps at feature parity**.

The whole app is written against pure domain logic with framework code pushed to the edges, so the market data source (currently Yahoo Finance), the news source (Finnhub), persistence, and notifications are each a single swappable adapter behind a port. The two Apple apps (macOS + iPhone) share one SwiftUI presentation layer; the two Compose apps (Windows + Android) share a Kotlin Multiplatform core — so the same domain math, indicators, and persistence formats carry across all four.

## Platforms

APTrade ships as **four native apps at feature parity** — macOS is the flagship reference implementation, and the other three mirror its feature set (with a handful of per-platform divergences recorded in each app's section below). The **Features** section below describes this shared feature set.

| Platform | Target | UI stack | Status | Build |
|----------|--------|----------|--------|-------|
| **macOS** | `APTradeApp` (SwiftPM executable) | SwiftUI + AppKit | Flagship — full feature set | [Getting Started](#getting-started) |
| **iPhone** | `APTradeiOS` (iOS 17, portrait) | SwiftUI (same presentation code as macOS) | Full parity | [iOS app](#iphone-app) |
| **Windows** | `:desktopApp` (Compose Desktop) | Compose Multiplatform + shared KMP core | Full parity | [Windows desktop app](#windows-desktop-app) |
| **Android** | `:androidApp` (Jetpack Compose, API 35) | Jetpack Compose + shared KMP core | Full parity | [Android app](#android-app) |

Every app carries: a live watchlist, asset detail with candlestick/area charts and technical indicators, a paper-trading portfolio with performance/risk analytics and export, Finnhub-backed news, price alerts and scheduled notifications, a holiday-aware calendar with an S&P 500 earnings list, a four-language switcher (English/Deutsch/Italiano/Español), and the gold-on-black accent-themable identity.

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

### Calendar & earnings
- **Calendar tab** — a dedicated tab showing **holiday and half-day banners** for the visible range, driven by a computed, US-DST-aware NYSE `MarketCalendar` (statutory holidays with observed-weekend shifts, plus the day-before-July-4th, day-after-Thanksgiving, and Christmas Eve 1pm-ET half-days) — no hard-coded date table.
- **Earnings list** — upcoming **S&P 500 + your-watchlist** earnings grouped by trading day, each row showing the ticker, company name, reporting **session** (before open / after close), and EPS estimate; symbols you hold are flagged. Backed by a shared `FinnhubEarningsRepository` (`/calendar/earnings`, 6-hour TTL cache) and a `FetchEarningsCalendar` use case (`.execute` / `.nextEarnings` / `.ownedToday`).
- **Next-earnings stat** on every asset's detail view, plus a settings-gated **earnings-day notification** that rides the same market-hours scheduler as the open/close and daily-digest alerts.
- **No-key state** — without a Finnhub key the earnings list degrades to an empty state (an `EmptyEarningsRepository` fallback); the holiday/half-day banners still render, since the calendar is computed locally.

### Investment Plans
- **Pies** — target-weight allocation strategies layered over the paper portfolio: define a set of assets with target weights and contribute or rebalance into them without leaving the plan's own ledger and activity log.
- **Self-balancing contributions** on **weekly / biweekly / monthly** schedules — each contribution splits across slices to close the gap toward target weight, and a **catch-up** run (on launch, and once per trading day thereafter) settles any due days accrued at their historical closes while the app was closed, all without duplicating an already-executed day.
- **Drift tracking and manual rebalance** — every slice shows target vs. actual weight and drift; a rebalance preview lists the exact buy/sell orders before you confirm, honoring the **Confirm Trades** setting the same way a manual trade does.
- **In-wizard DCA backtest** while building a plan — a dollar-cost-average simulation over real historical closes, shown alongside a lump-sum comparison so the schedule's trade-off is visible before you commit.
- **Settings-gated notifications** — a dedicated **Plan Contributions** toggle (Notifications settings) governs both the scheduled auto-contribution run and its "contribution executed / skipped" notifications, mirroring the earnings-day toggle.
- **Platform status:** live on macOS, iPhone, and Windows desktop (M7.2); Android Plans support is targeted for the next increment (M7.3).

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

- **Domain** — `Asset`, `Quote`, `Money`, `Percentage`, `Quantity`, `Portfolio`, `Position`, `Candle`, `PricePoint`, `PriceAlert`, `NewsArticle`, `NewsCategory`, `AppSettings`, `AccentTheme`, `AppLanguage`, `MarketCalendar`, `EarningsEvent`, `Timeframe`, `Pie`, `PieSlice`, `ContributionSchedule`, plus pure calculations: `TechnicalIndicators` (SMA/EMA/RSI/Bollinger/MACD), portfolio performance reconstruction, realized-P&L, `PieMath`/`PieSchedule`/`PieBacktest` (target-weight distribution, cadence due-day math, DCA-vs-lump-sum backtesting), and the `PortfolioExport` model. No framework imports.
- **Application** — use cases (quotes, history, **candles**, search, watchlist, buy/sell, portfolio snapshots, **performance reconstruction**, **export**, **news**, **bookmarks**, alerts, settings, market-activity planning, **earnings calendar**, **Pie contribution / rebalance / backtest**) orchestrating over ports: `MarketDataRepository`, `WatchlistStore`, `PortfolioStore`, `PortfolioHistoryStore`, `AlertStore`, `AlertNotifier`, `OrderFillNotifier`, `MarketEventNotifier`, `SettingsStore`, `SchedulerStateStore`, `PortfolioExportRenderer`, `NewsRepository`, `BookmarkStore`, `EarningsCalendarRepository`, `PieStore`.
- **Infrastructure** — `SharedCoreMarketDataRepository` (quotes, history, OHLC candles, profile, and search via the shared Kotlin core), `CachingMarketDataRepository`, `FinnhubNewsRepository` (with an `EmptyNewsRepository` no-key fallback), `FinnhubEarningsRepository` (with an `EmptyEarningsRepository` no-key fallback), `AppConfig` (reads the Finnhub key from `~/.config/aptrade/config.json`), `UserNotificationAlertNotifier`, `UserDefaults`-backed stores (incl. bookmarks and `UserDefaultsPieStore`), and a dependency-free **export renderer** (Core Graphics PDF + a hand-rolled ZIP writer producing OOXML `.xlsx` / `.docx`).
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

> `DEVELOPER_DIR` must point at a full Xcode (not the Command Line Tools) so XCTest is available. **400 tests** cover the domain math (money, percentages, indicators, realized-P&L, performance reconstruction, the all-priced gate + benchmark head-trim), the market calendar and earnings calendar, use cases, the market-activity planner (incl. earnings-check and Pie-contribution-check scheduling), alert/order-fill gating, the Yahoo mapper, the Finnhub news mapper, the Finnhub earnings mapper, the caching repository, the portfolio export renderers, settings round-trips, the bookmark store, the localization catalog and language manager, the view models, and Investment Plans (`PieMath` distribution/drift, `PieSchedule` cadence math, `PieBacktest` DCA-vs-lump-sum, contribution/rebalance use cases and catch-up, the `UserDefaultsPieStore`, and the coordinator's contribution notifications).

### Building the shared Kotlin core

Parts of the app (live quotes) are served by a Kotlin Multiplatform core in `shared/`,
linked as `Shared.xcframework`. The framework is a build artifact (not committed), so run

    ./scripts/build-shared.sh

once per clone — and again after any change under `shared/` — before `swift build` or
opening the Xcode project. Requires JDK 17 and full Xcode (the script points at the
Homebrew OpenJDK 17 and `/Applications/Xcode.app` by default; override via `JAVA_HOME`
/ `DEVELOPER_DIR`). The framework ships Apple-Silicon (arm64) slices only — iOS Simulator
builds must target arm64 (the default on Apple-Silicon Macs).

### iPhone app

The iPhone app is the `APTradeiOS` target (**iPhone-only, portrait, iOS 17**), which reuses the
**same SwiftUI presentation code as macOS** from the `APTradeApp` package — every platform
difference is `#if os(iOS)`-gated, so macOS output is unchanged. It ships the full macOS feature
set (watchlist, charts + indicators, paper-trading portfolio with export via a themed
`.fileExporter`, news, alerts, calendar, and the four-language switcher), with iPhone-fitted
touch interactions (a touch-drag chart crosshair where macOS hovers) and sheet presentations.

The Xcode project is generated from `project.yml` with [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`) and is gitignored. Build the shared framework first (above), then:

```bash
xcodegen generate                     # writes APTrade.xcodeproj from project.yml
open APTrade.xcodeproj                 # select the APTradeiOS scheme + an iPhone simulator, Run
```

To run the domain/application/view-model suites on the iOS simulator, use the SwiftPM-generated
`APTradeLite-Package` scheme (there is no bare `APTradeLite` scheme):

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test \
  -scheme APTradeLite-Package \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -skipPackagePluginValidation
```

> **Gotcha:** the generated `APTrade.xcodeproj` **shadows** the package — while it sits beside
> `Package.swift`, `xcodebuild` uses the app project (target `APTradeiOS`) and the
> `APTradeLite-Package` scheme disappears. To run the package tests, move `APTrade.xcodeproj`
> aside temporarily (it regenerates via `xcodegen generate`), run the tests, then restore it.

### Android app

A full Jetpack Compose app (`androidApp/`) at **feature parity** with macOS/Windows, on the same
shared Kotlin Multiplatform core: a persisted watchlist (live quotes, sparklines,
swipe-to-remove with Undo, add-from-search), asset detail with line/candlestick charts and the
full indicator set (SMA/EMA/VWAP/Bollinger overlays + RSI/MACD panes), a paper-trading
**Portfolio** with performance/risk analytics and export, a **News** tab with categories,
bookmarks and a Saved view, **price alerts** with system notifications and a market-activity
coordinator, a holiday-aware **Calendar** with an S&P 500 earnings list, and full **Settings**
pages with a live four-language switcher, light/dark themes, and the accent picker.

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

**Recorded divergences** from the macOS/desktop Portfolio: export is a plain-text
**share-sheet** (CSV / JSON) with **no PDF**; the performance chart has **no crosshair
scrubber**; allocation is **bars only** (no donut chart); and the entry point is the top-bar
**List icon** rather than a dedicated tab. Trading is reachable both from an existing holding
row's BUY/SELL **and** from a prominent **BUY / SELL** action on each asset's detail screen
(matching the macOS app) — so a fresh $100,000 portfolio can make its first buy from asset
detail. Both entry points open the same modal-bottom-sheet trade form; a detail-made trade
persists to the shared store and appears in the Portfolio on return.

### Windows desktop app

A Compose Desktop app (`desktopApp/`) targets Windows at **full macOS parity**, on the same
shared Kotlin Multiplatform core as the Android app: Watchlist, asset detail (charts +
indicators + KEY STATS / YOUR POSITION), a Ctrl+K search palette, a Portfolio tab with a
performance chart, benchmark overlay and 7-metric risk grid, a News tab with per-symbol
company news and bookmarks, price alerts and tray notifications, a holiday-aware Calendar
with an S&P 500 earnings list, and a full account panel (light/dark themes, accent picker,
four-language switcher, and real settings pages) — recreating the macOS app's gold-on-black
visual identity. It's developed and run on this Mac as a proxy for the Windows target; CI
builds and tests it on an actual Windows runner, packaging an `.msi` installer.

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
directory — and an About page (Notifications became a real page in increment 6d.1).
**Profile, Account Settings, Security & Privacy, and Help & Support are now real pages**
too (increment 6d.2); only Language still falls through to the shared "not available yet"
placeholder. The
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

**Alerts & notifications** bring price alerts and scheduled market notifications to the
desktop app (`:desktopApp`, increment 6d.1), on a new shared Kotlin alerts core
(`PriceAlert`/`AlertCondition`, `LoadAlerts`/`CreatePriceAlert`/`RemovePriceAlert`/
`EvaluateAlerts`) plus a `MarketActivityPlanner` driven by a statutory, US-DST-aware
`MarketCalendar`. Each watchlist row grows an alert **bell** — gold-filled with a count
when the symbol has active alerts, tertiary-outline otherwise — opening a
**PriceAlertSheet** (macOS-parity strings and layout) to set a _price above_, _price
below_, or _percent daily move_ condition, or review/remove existing ones. Alerts are
evaluated on the same 15-second watchlist poll that refreshes quotes; completed buys/sells
raise a settings-gated order-fill notification; and a separate 60-second
market-activity coordinator delivers market open/close notifications plus a daily digest
of the watchlist's top-3 movers by absolute change. A real **Notifications** page in the
account panel replaces the old placeholder: **Price Alerts**, **Order Fills**, **Market
Open & Close**, and **Daily News Digest** toggles under PUSH NOTIFICATIONS, plus an
**Email Notifications** toggle, all persisted through the same settings store.
**Recorded divergences from macOS:** notification *delivery* goes through Compose
Desktop's `TrayState`/tray-icon API rather than `UNUserNotificationCenter` — Compose
Multiplatform for Desktop has no cross-platform notification-center equivalent, so this is
a deliberate, scoped substitution of transport only; every notification title/body string
is transcribed verbatim from the macOS source. **Email Notifications** is
persisted-but-unwired — toggling it saves the preference but no email delivery pipeline
exists yet — which is **not** a desktop shortfall: the macOS app has the identical
placeholder toggle with the same "no delivery pipeline yet" caveat. This increment does
not touch the Android app; its shared-core alerts plumbing is unused there for now.

A **light theme and four real account-panel pages** (`:desktopApp`, increment 6d.2) bring
the desktop app's Appearance and account settings to macOS-level completeness. The
Appearance page gains a **THEME** section — **Dark / Light** rows above the existing
**ACCENT** picker — backed by nine mode-branching `DesignKit` colors (two background
stops, surface, surface-high, hairline, three text tiers, and the silver wordmark tone)
transcribed hex-for-hex from the macOS `Theme.swift` light palette, plus a
light-mode wordmark remap (neutral pixels recolor to charcoal `#1E1C18` instead of staying
neutral, while the champagne-gold ramp itself is unchanged in both modes — accent color is
brand, never a mode signal). The switch is an **instant whole-tree recolor with no
animation** (a recorded design decision, not a missing-polish gap), and `isDarkMode`
persists to `settings.json` alongside the accent. **Security & Privacy**, **Profile**,
**Account Settings**, and **Help & Support** replace their former placeholders (increment
6e goes on to give **Language** a real destination too — see below). Of Security &
Privacy's four toggles (Biometric Login, Require
Auth on Launch, Confirm Trades, Share Usage Analytics), only **Confirm Trades** is
functional; the other three persist to `settings.json` but drive nothing yet — **HONEST
PARITY** with macOS, whose own Security page has the identical three-toggle gap. Profile
and Account Settings are decorative detail-field pages (fixed values, no editable state),
and Help & Support's Resources/Contact rows are decorative link rows that just dismiss the
panel — both mirroring macOS's own non-functional rows verbatim, not a desktop shortfall.
Turning on **Confirm Trades** inserts an in-dialog confirmation step before a buy/sell
submits (title, estimate line, and a Confirm/Cancel choice), gated on the toggle's value at
the moment the trade dialog opened. **Recorded divergence from macOS:** the confirmation
renders as an **in-dialog layer** inside the same trade sheet, not a separate native
`confirmationDialog` sheet-of-a-sheet — Compose Desktop has no equivalent primitive — though
the title/message/button strings and gating behavior match exactly. This increment also
closes two concurrency gaps found during hardening: `BuyAsset`/`SellAsset` now share a
single `Mutex` (previously each held its own, so a buy racing a sell could still lose an
update even though buy-vs-buy and sell-vs-sell were already safe), and the desktop
`persistSettings` load-merge-save sequence is now serialized under a `Mutex`, closing a
lost-update window where two concurrent preference changes (e.g. an accent change and a
notification toggle) could silently drop one write.

A **desktop language switcher and chart/UX polish pass** (`:desktopApp`, increment 6e)
completes macOS parity and closes the roadmap. **Language** is now a real page: choosing
**English, Deutsch, Italiano, or Español** re-renders the entire desktop UI —
nav, watchlist, portfolio, asset detail, and every settings page — through a Compose-state
`LocalizationManager` and a 217-key `L10n` catalog (`tr`/`trf` helpers), with the choice
persisted to `settings.json` and restored on relaunch. 205 of those keys are transcribed
verbatim from the macOS `L10n.swift` catalog (same English/German/Italian/Spanish strings);
the remaining **12 are desktop-only** additions (short button labels, chart-mode names, and
a couple of RSI/MACD chip words with nothing to transcribe from macOS) — their DE/IT/ES
values are the author's own translations, not yet reviewed by a native speaker, so treat
them as provisional pending a follow-up pass. Two chart-fidelity fixes ride alongside:
the Performance chart's equity curve (and its SPY/QQQ/VTI benchmark twin) now **resamples
to one point per calendar day** on 1W/1M/1Y/MAX — collapsing the dense intraday/weekend
grid that made both curves render as flat or stair-stepped segments — while **1D is left
untouched**, since its native intraday granularity is the whole point of that view; and
asset-detail indicators (SMA 20, EMA 12, VWAP, Bollinger Bands, RSI 14, MACD) now fetch
a 26-bar lookback pad ahead of the visible window so every indicator's warm-up is already
satisfied by the time the chart's visible range begins, fixing overlays that used to render
across only the right half of the chart. The lookback clamp lives at the application layer
(a new `FetchChartWindow` use case; the `MarketDataRepository` port itself only grew a KDoc
clarification, no signature change) — Android's `FetchCandles`-based path is untouched.
Portfolio holding-row **BUY/SELL** buttons are now **always visible at 35% opacity**,
brightening to full on hover, reserving their layout space permanently — a **recorded
divergence** from macOS, which still hover-reveals its buy/sell affordance.

**Investment Plans (Pies)** reach the desktop app at parity (`:desktopApp`, M7.2): a **Plans**
section in the Portfolio tab lists pies with per-slice drift badges, a 4-step creation
wizard (including an in-wizard DCA-vs-lump-sum backtest) builds and edits them, contributions
run on weekly/biweekly/monthly schedules with launch-time and daily catch-up for days accrued
while the app was closed, and a rebalance preview lists the exact buy/sell orders before
confirming, honoring **Confirm Trades** the same as a manual trade — all on the same shared
Kotlin core (`PieMath`, `PieSchedule`, `PieBacktest`, contribution/rebalance use cases) as
the macOS reference. Android Plans support is targeted for the next increment (M7.3).

## Project Structure

```
Sources/
├── APTradeDomain/          Entities & value objects (pure)
├── APTradeApplication/     Use cases & ports
├── APTradeInfrastructure/  Yahoo repo, caching, persistence, notifications
├── APTradeApp/             SwiftUI views, view models, DesignKit, Theme (macOS + iOS)
└── APTradeiOS/             iPhone app target (reuses APTradeApp)
Tests/                      One test target per layer
shared/                     Kotlin Multiplatform core (quotes, portfolio, indicators, stores)
desktopApp/                 Windows app (Compose Desktop, full parity)
androidApp/                 Android app (Jetpack Compose, full parity)
project.yml                 XcodeGen spec for the APTradeiOS Xcode project
logo/                       Brand assets
```

## Roadmap

APTrade Lite is the foundation. Planned toward the full platform:

- Real authentication (Apple Sign In), biometric gating, and cloud sync (Supabase)
- **Windows parity — complete.** The `:desktopApp` Compose app now covers Watchlist +
  detail + palette (6a), a Portfolio tab with detail-screen indicators, performance/risk
  intelligence, and export (6b.1 + 6b.2), a News tab with per-symbol company news and
  bookmarks (6c), macOS adoption of the shared portfolio core's all-priced performance
  gate (6b.3), an Android Portfolio screen on the shared portfolio core (6b.4), alerts &
  notifications (6d.1), a light theme plus real account-panel settings pages (6d.2), and a
  language switcher plus chart/UX polish (6e). Still to come: **none** — macOS parity and
  localization are complete; the `:desktopApp` roadmap that opened at 6a is closed.

Recently shipped: **holiday-aware `MarketCalendar` + S&P 500 earnings calendar** — a
computed NYSE holiday and half-day calendar replaces the fixed one on both codebases
(New Year's/MLK/Presidents/Good Friday/Memorial/Juneteenth/July 4th/Labor/Thanksgiving/
Christmas with observed-weekend shifts, plus the day-before-July-4th, day-after-
Thanksgiving, and Christmas Eve 1pm-ET half-days), closing the market-holiday-calendar
roadmap item. A new **Calendar tab** ships on all four platforms (macOS, iOS, desktop,
Android) — holiday and half-day banners for the visible range alongside an S&P 500 +
your-watchlist earnings list, backed by a shared `FinnhubEarningsRepository`
(`/calendar/earnings`, 6h TTL cache) and a `FetchEarningsCalendar` use case
(`.execute`/`.nextEarnings`/`.ownedToday`). Asset detail gains a **Next-earnings** stat
on all four platforms, and a settings-gated **earnings-day notification** (riding the
existing `EarningsCheckDue` planner event) joins the open/close and top-movers digest
alerts. Android's market-activity coordinator goes fully functional in this pass — the
open/close and daily-movers-digest toggles, previously inert, now drive real
notifications alongside the new earnings check. 22 new L10n keys (tab, banners,
holidays, sessions, earnings) across EN/DE/IT/ES bring the catalog to 231. Suites at
merge: macOS 249 / iOS sim suite green (249) / shared 366 / desktop 215 / android 144.

Before that: **cross-platform follow-ups sweep** — the follow-ups recorded at the two
parity merges are closed. In-app **Finnhub key-entry fields** land on iOS and Android
(Account Settings on both; the sandboxed `config.json` isn't user-reachable on either
platform): `AppConfig`/`FinnhubKeyConfig` gained merge-writing save paths into the same
`config.json` the news wiring reads, the news graphs re-resolve the key per News-tab
visit (no relaunch), and both platforms' News empty states now point at the field. Android
notifications got a **branded small icon** (white AP-monogram vector + gold shade accent,
replacing the stock system glyph). The **`AssetKind` label localization sweep** removed
every hard-coded Stock/ETF/Crypto display string across Android, desktop, and shared: UI
models now carry the typed `AssetKind` (the fragile label→kind reverse maps are gone) and
localize only at render, with 3 new singular kind keys ("Aktie" is neither "Aktien" nor
"AKTIE") and a typed `kind` on by-class allocation slices. And the portfolio **segmented
rows now cap at 480 dp** (centered) on tablets instead of stretching full-bleed. Catalog:
227 keys. Suites at merge: macOS 209 / iOS sim suite green / shared 306 / desktop 212 /
android 127. Before that: **Android → Windows parity** — the `:androidApp` Compose app now carries
the full desktop feature set on the shared KMP core: a persisted watchlist (sparklines,
swipe-to-remove with Undo, add-from-search), price alerts with system notifications and
settings-gated order-fill notifications, a confirm-trades layer on the trade sheet, news
with categories, a Saved view, headline filter, bookmarks and a custom-tab reader, full
settings pages with a live 4-language switcher (persisted across relaunch), the desktop
light/dark color tables and accent picker, technical indicators (SMA/EMA/VWAP/Bollinger
overlays + RSI/MACD panels) over the shared `TechnicalIndicators` math, monotone-cubic
smooth charts with follow-the-finger crosshairs, a portfolio section switcher, and a real
launcher icon. Foundation: the desktop's file stores (watchlist/alerts/settings/bookmarks/
Finnhub key + `ConfigDir`) moved to `shared/jvmCommonMain` and the 220-key L10n catalog +
`AppLanguage` + an identity-only `AccentTheme` to `shared/commonMain`, with desktop
typealias re-exports keeping the desktop app byte-identical (its suite stayed green
throughout; store JSON formats unchanged). Suites at merge: desktop 212 / shared 302 /
android 125. Recorded follow-ups: an in-app Finnhub key-entry field, branded notification
icon assets, and an `AssetKind` label localization sweep. Before that: the
**iPhone app closeout** — the `APTradeiOS` target (iPhone-only,
portrait, iOS 17) now ships the full macOS feature set from the same SwiftPM presentation
code: real portfolio export via a themed `.fileExporter` (with a shared, tested
`PortfolioExportNaming` Domain helper and a user-cancel guard), a touch-drag chart
crosshair on the detail and expanded-value charts (macOS keeps hover), an always-visible
watchlist alert bell, iPhone-fitted command palette and price-alert sheets on themed
`presentationBackground`s, an expand-in-place portfolio P&L card with deterministic
sizing (no more compression clipping), and an iOS-appropriate Finnhub key empty-state
string (EN/DE/IT/ES). All changes are `#if os(iOS)`-gated — macOS output is unchanged —
and both suites hold at 202/202 (macOS `swift test`, iOS simulator via `xcodebuild`).
The one recorded follow-up: a Settings field for entering the Finnhub key on iPhone,
where the sandboxed config file isn't user-reachable. Before that: a desktop
**language switcher and chart/UX polish pass** (increment 6e) —
a real Language page (English/Deutsch/Italiano/Español) driving a whole-UI live re-render
through `LocalizationManager` and a 217-key `L10n` catalog (205 transcribed verbatim from
macOS, 12 desktop-only additions with provisional, not-yet-native-reviewed translations),
persisted across launches; a daily-resample fix for the Performance chart and its benchmark
twin (flat/stair-step artifacts gone on 1W/1M/1Y/MAX, 1D still intraday); a 26-bar indicator
lookback pad so SMA/EMA/VWAP/Bollinger/RSI/MACD render full-width instead of only across the
chart's right half; and an always-visible, hover-brightening BUY/SELL affordance on
portfolio holding rows (a recorded divergence from macOS's hover-reveal). This closes the
`:desktopApp` Windows-parity roadmap opened at 6a — no further desktop-parity work is
planned. A desktop **light theme and account-panel settings pages** (increment
6d.2) — a THEME section (Dark/Light rows) on the Appearance page above the existing accent
picker, backed by nine mode-branching `DesignKit` colors transcribed from macOS's
`Theme.swift` and an instant, non-animated whole-tree recolor; a light-mode wordmark remap
(neutral pixels to charcoal, accent ramp unchanged); real **Security & Privacy**,
**Profile**, **Account Settings**, and **Help & Support** pages (Language remains the one
placeholder), with only **Confirm Trades** functional among Security's four toggles
(HONEST PARITY with macOS's identical gap) and an in-dialog trade-confirmation layer
(a recorded mechanism divergence from macOS's native `confirmationDialog`); and two
concurrency fixes — a single shared `Mutex` serializing `BuyAsset`/`SellAsset` (closing a
buy-vs-sell lost-update race) and a `Mutex`-serialized `persistSettings` load-merge-save
(closing a concurrent-preference-write lost-update race). Desktop **alerts &
notifications** (increment 6d.1) — price alerts
(price above / price below / percent daily move) on a new shared Kotlin alerts core
(`PriceAlert`, `LoadAlerts`/`CreatePriceAlert`/`RemovePriceAlert`/`EvaluateAlerts`) and a
`MarketActivityPlanner` driven by a statutory, US-DST-aware `MarketCalendar`; a watchlist
row alert bell and `PriceAlertSheet` matching the macOS anatomy; alert evaluation folded
into the existing 15-second watchlist poll; settings-gated order-fill notifications; a
60-second market-activity coordinator for open/close and a top-3-movers daily digest; and
a real Notifications settings page (push toggles + email, persisted through the same
settings store). Notification delivery goes through Compose Desktop's tray-icon API
rather than `UNUserNotificationCenter` (no cross-platform equivalent exists), and Email
Notifications is persisted-but-unwired — both recorded divergences, the latter matching
macOS's identical placeholder. Not yet adopted on Android. An Android **Portfolio screen**
(increment 6b.4) — paper trading on the
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
