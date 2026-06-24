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
