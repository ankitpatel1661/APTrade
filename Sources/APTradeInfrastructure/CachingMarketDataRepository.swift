import Foundation
import APTradeApplication
import APTradeDomain

/// A short-TTL quote cache implemented as an `actor`, so concurrent access is
/// serialized without manual locking. Concurrent fetches for the same symbol are
/// coalesced into a single inner request via an in-flight task table.
public actor CachingMarketDataRepository: MarketDataRepository {
    private struct Entry { let quote: Quote; let at: Date }
    private struct SearchEntry { let results: [Asset]; let at: Date }
    private let inner: MarketDataRepository
    private let ttl: TimeInterval
    private let now: @Sendable () -> Date
    private var cache: [String: Entry] = [:]
    private var inFlight: [String: Task<Quote, Error>] = [:]
    private var searchCache: [String: SearchEntry] = [:]

    public init(wrapping inner: MarketDataRepository, ttl: TimeInterval = 15, now: @escaping @Sendable () -> Date = Date.init) {
        self.inner = inner
        self.ttl = ttl
        self.now = now
    }

    public func quote(for symbol: String) async throws -> Quote {
        if let entry = cache[symbol], now().timeIntervalSince(entry.at) < ttl {
            return entry.quote
        }
        // Coalesce: a concurrent caller for the same symbol awaits the same task
        // rather than firing a second inner request.
        if let existing = inFlight[symbol] {
            return try await existing.value
        }
        let inner = self.inner
        let task = Task { try await inner.quote(for: symbol) }
        inFlight[symbol] = task
        defer { inFlight[symbol] = nil }
        let fresh = try await task.value
        cache[symbol] = Entry(quote: fresh, at: now())
        return fresh
    }

    public func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        try await inner.history(for: symbol, timeframe: timeframe)
    }

    public func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        try await inner.candles(for: symbol, timeframe: timeframe)
    }

    public func profile(for symbol: String) async throws -> Asset {
        try await inner.profile(for: symbol)
    }

    public func search(query: String) async throws -> [Asset] {
        let key = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let entry = searchCache[key], now().timeIntervalSince(entry.at) < ttl {
            return entry.results
        }
        let results = try await inner.search(query: query)
        searchCache[key] = SearchEntry(results: results, at: now())
        return results
    }
}
