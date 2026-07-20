import Foundation
import APTradeDomain

public protocol MarketDataRepository: Sendable {
    func quote(for symbol: String) async throws -> Quote
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint]
    /// OHLC bars for candlestick charts.
    func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle]
    /// Resolves an asset's display name and kind, validating that the symbol exists.
    func profile(for symbol: String) async throws -> Asset
    /// Returns ranked asset matches for a free-text query (autocomplete).
    func search(query: String) async throws -> [Asset]
}

public extension MarketDataRepository {
    /// Default profile resolution: validates existence via a quote and infers the
    /// kind from the symbol shape. Concrete repositories that carry richer metadata
    /// (e.g. Yahoo's `longName`/`instrumentType`) override this for accurate results.
    func profile(for symbol: String) async throws -> Asset {
        _ = try await quote(for: symbol)
        let kind: AssetKind = symbol.uppercased().hasSuffix("-USD") ? .crypto : .stock
        return Asset(symbol: symbol, name: symbol, kind: kind)
    }

    /// Default: no search capability. Concrete repositories override this.
    func search(query: String) async throws -> [Asset] { [] }

    /// Default candles derive flat OHLC bars from the close-only history, so sources
    /// without true OHLC still render (as a line/area). Concrete repositories override.
    func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        try await history(for: symbol, timeframe: timeframe).map {
            Candle(date: $0.date, open: $0.close, high: $0.close, low: $0.close, close: $0.close)
        }
    }
}

public protocol WatchlistStore: Sendable {
    func load() -> [Asset]
    func save(_ assets: [Asset])
}

public protocol PortfolioStore: Sendable {
    func load() -> Portfolio
    func save(_ portfolio: Portfolio)
}

/// Persists a rolling history of portfolio value snapshots, used to chart total value
/// over time. Snapshots are recorded locally as they're observed — there is no
/// historical backfill from price data.
public protocol PortfolioHistoryStore: Sendable {
    func record(_ point: PricePoint)
    func load() -> [PricePoint]
    /// Discards all recorded snapshots (e.g. to clear a noisy chart or on reset).
    func clear()
}

/// Persists user preferences (notifications, security, privacy) as a single value.
public protocol SettingsStore: Sendable {
    func load() -> AppSettings
    func save(_ settings: AppSettings)
}

public protocol AlertStore: Sendable {
    func load() -> [PriceAlert]
    func save(_ alerts: [PriceAlert])
}

/// Delivers a triggered alert outside the app's own state — e.g. a native macOS
/// notification. Kept as a port so the application layer never imports UserNotifications.
public protocol AlertNotifier: Sendable {
    func notify(_ alert: PriceAlert, quote: Quote) async
}

/// Delivers a confirmation when an order fills (a simulated buy/sell completes). Kept
/// separate from `AlertNotifier` so each can be wired — or stubbed — independently.
public protocol OrderFillNotifier: Sendable {
    func notifyFill(side: TradeSide, symbol: String, quantity: Quantity, amount: Money) async
}

/// Delivers time-based notifications: market open/close, the daily digest, and
/// earnings-day alerts.
public protocol MarketEventNotifier: Sendable {
    func notifyMarketStatus(opened: Bool) async
    func notifyDigest(summary: String) async
    func notifyEarnings(title: String, body: String) async
    func notifyPieContribution(title: String, body: String) async
}

/// Persists the scheduler's last-fired markers across launches.
public protocol SchedulerStateStore: Sendable {
    func load() -> SchedulerState
    func save(_ state: SchedulerState)
}

/// Supplies news articles from an external source. Methods throw `AppError` on failure;
/// callers (use cases) decide whether to surface or swallow.
public protocol NewsRepository: Sendable {
    func marketNews(category: NewsCategory) async throws -> [NewsArticle]
    func companyNews(symbol: String) async throws -> [NewsArticle]
}

/// Persists the user's bookmarked articles.
public protocol BookmarkStore: Sendable {
    func load() -> [NewsArticle]
    func save(_ articles: [NewsArticle])
}

/// Persists portfolio allocation strategies (Pies).
public protocol PieStore: Sendable {
    func load() -> [Pie]
    func save(_ pies: [Pie])
}

/// Supplies upcoming/reported earnings releases for a date window. Methods throw on
/// failure; `FetchEarningsCalendarUseCase` degrades failures to an empty list so the
/// calendar's holiday banners still render when the network is down — cooperative
/// cancellation (`CancellationError`) is the one exception to the degrade-to-empty rule
/// and is rethrown.
public protocol EarningsCalendarRepository: Sendable {
    func earnings(fromDay: String, toDay: String) async throws -> [EarningsEvent]
}

/// Supplies historical dividend events for a symbol, ascending by ex-date.
public protocol DividendEventsRepository: Sendable {
    func dividendEvents(for symbol: String, since: Date) async throws -> [DividendEvent]
}
