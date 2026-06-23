import Foundation
import APTradeApplication
import APTradeDomain

public final class CachingMarketDataRepository: MarketDataRepository, @unchecked Sendable {
    private struct Entry { let quote: Quote; let at: Date }
    private let inner: MarketDataRepository
    private let ttl: TimeInterval
    private let now: () -> Date
    private var cache: [String: Entry] = [:]
    private let lock = NSLock()

    public init(wrapping inner: MarketDataRepository, ttl: TimeInterval = 15, now: @escaping () -> Date = Date.init) {
        self.inner = inner
        self.ttl = ttl
        self.now = now
    }

    public func quote(for symbol: String) async throws -> Quote {
        // Scoped lock for the read; released before the await so we never hold
        // the lock across a suspension point.
        if let cached = lock.withLock({ cachedQuote(for: symbol) }) {
            return cached
        }
        let fresh = try await inner.quote(for: symbol)
        lock.withLock { cache[symbol] = Entry(quote: fresh, at: now()) }
        return fresh
    }

    private func cachedQuote(for symbol: String) -> Quote? {
        guard let entry = cache[symbol], now().timeIntervalSince(entry.at) < ttl else { return nil }
        return entry.quote
    }

    public func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        try await inner.history(for: symbol, timeframe: timeframe)
    }
}
