import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

/// A mutable clock the test can advance; reference type so the `now` closure stays Sendable.
final class MutableClock: @unchecked Sendable {
    var date: Date
    init(_ date: Date) { self.date = date }
}

final class CountingRepo: MarketDataRepository, @unchecked Sendable {
    private(set) var quoteCalls = 0
    var searchResults: [Asset] = []
    private(set) var searchCallCount = 0
    func quote(for symbol: String) async throws -> Quote {
        quoteCalls += 1
        return Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
    func search(query: String) async throws -> [Asset] {
        searchCallCount += 1
        return searchResults
    }
}

/// Counts inner calls and holds each `quote` open briefly so concurrent callers
/// overlap, exercising the cache's in-flight coalescing.
actor SlowCountingRepo: MarketDataRepository {
    private(set) var quoteCalls = 0
    func quote(for symbol: String) async throws -> Quote {
        quoteCalls += 1
        try? await Task.sleep(nanoseconds: 50_000_000)
        return Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

final class CachingRepositoryTests: XCTestCase {
    func test_secondCallWithinTTL_isCached() async throws {
        let inner = CountingRepo()
        let cache = CachingMarketDataRepository(wrapping: inner, ttl: 100, now: { Date(timeIntervalSince1970: 0) })
        _ = try await cache.quote(for: "AAPL")
        _ = try await cache.quote(for: "AAPL")
        XCTAssertEqual(inner.quoteCalls, 1)
    }

    func test_callAfterTTL_refetches() async throws {
        let inner = CountingRepo()
        let clock = MutableClock(Date(timeIntervalSince1970: 0))
        let cache = CachingMarketDataRepository(wrapping: inner, ttl: 10, now: { clock.date })
        _ = try await cache.quote(for: "AAPL")
        clock.date = Date(timeIntervalSince1970: 20)
        _ = try await cache.quote(for: "AAPL")
        XCTAssertEqual(inner.quoteCalls, 2)
    }

    func test_concurrentCallsForSameSymbol_coalesceToSingleFetch() async throws {
        let inner = SlowCountingRepo()
        let cache = CachingMarketDataRepository(wrapping: inner, ttl: 100, now: { Date(timeIntervalSince1970: 0) })
        async let a = cache.quote(for: "AAPL")
        async let b = cache.quote(for: "AAPL")
        async let c = cache.quote(for: "AAPL")
        _ = try await (a, b, c)
        let calls = await inner.quoteCalls
        XCTAssertEqual(calls, 1)
    }

    func test_search_isCachedWithinTTL() async throws {
        let inner = CountingRepo()
        inner.searchResults = [Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)]
        let cache = CachingMarketDataRepository(wrapping: inner, ttl: 15, now: { Date(timeIntervalSince1970: 0) })

        _ = try await cache.search(query: "aapl")
        _ = try await cache.search(query: "aapl")   // same normalized query, within TTL

        XCTAssertEqual(inner.searchCallCount, 1)
    }
}
