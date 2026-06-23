import APTradeDomain

public protocol MarketDataRepository: Sendable {
    func quote(for symbol: String) async throws -> Quote
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint]
}

public protocol WatchlistStore: Sendable {
    func load() -> [Asset]
    func save(_ assets: [Asset])
}
