import APTradeDomain

public protocol MarketDataRepository: Sendable {
    func quote(for symbol: String) async throws -> Quote
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint]
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

/// Delivers time-based notifications: market open/close and the daily digest.
public protocol MarketEventNotifier: Sendable {
    func notifyMarketStatus(opened: Bool) async
    func notifyDigest(summary: String) async
}

/// Persists the scheduler's last-fired markers across launches.
public protocol SchedulerStateStore: Sendable {
    func load() -> SchedulerState
    func save(_ state: SchedulerState)
}
