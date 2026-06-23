import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class CountingRepo: MarketDataRepository, @unchecked Sendable {
    private(set) var quoteCalls = 0
    func quote(for symbol: String) async throws -> Quote {
        quoteCalls += 1
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
        var t = Date(timeIntervalSince1970: 0)
        let cache = CachingMarketDataRepository(wrapping: inner, ttl: 10, now: { t })
        _ = try await cache.quote(for: "AAPL")
        t = Date(timeIntervalSince1970: 20)
        _ = try await cache.quote(for: "AAPL")
        XCTAssertEqual(inner.quoteCalls, 2)
    }
}
