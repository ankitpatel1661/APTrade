import Foundation
import XCTest
import APTradeApplication
import APTradeDomain
import Shared
@testable import APTradeInfrastructure

// NOTE: KMP `Quote`/`Money` collide with APTradeDomain's — Shared.X vs APTradeDomain.X.
// Kotlin companion functions surface as `.companion`; Kotlin `object`s as `.shared`.
final class SharedCoreMarketDataRepositoryTests: XCTestCase {
    private func kmpMoney(_ text: String) -> Shared.Money {
        Shared.Money.companion.usd(value: text)
    }

    func testMapsMoneyExactly() throws {
        let mapped = try SharedCoreMarketDataRepository.mapMoney(kmpMoney("229.35"))
        XCTAssertEqual(mapped, APTradeDomain.Money(amount: Decimal(string: "229.35")!, currencyCode: "USD"))
    }

    func testMapsQuoteWithPreviousClose() throws {
        let kmp = Shared.Quote(
            symbol: "AAPL",
            price: kmpMoney("229.35"),
            previousClose: kmpMoney("227.45"),
            changePercent: 0.84)
        let mapped = try SharedCoreMarketDataRepository.mapQuote(kmp)
        XCTAssertEqual(mapped.symbol, "AAPL")
        XCTAssertEqual(mapped.price.amount, Decimal(string: "229.35")!)
        XCTAssertEqual(mapped.previousClose.amount, Decimal(string: "227.45")!)
    }

    func testMapsKotlinErrorsToAppError() {
        func nsError(_ kotlin: Any) -> Error {
            NSError(domain: "KotlinException", code: 0, userInfo: ["KotlinException": kotlin])
        }
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(Shared.QuoteError.RateLimited.shared)), .rateLimited)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(Shared.QuoteError.NotFound.shared)), .notFound)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(Shared.QuoteError.Network(reason: "boom"))), .network)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(URLError(.timedOut)), .network)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(AppError.notFound), .notFound)
    }

    func testDelegatesNonQuoteCallsToFallback() async throws {
        let fallback = RecordingRepository()
        let repo = SharedCoreMarketDataRepository(fallback: fallback)
        _ = try await repo.history(for: "AAPL", timeframe: .oneDay)
        _ = try await repo.candles(for: "AAPL", timeframe: .oneDay)
        _ = try await repo.profile(for: "AAPL")
        _ = try await repo.search(query: "app")
        let calls = await fallback.calls
        XCTAssertEqual(calls, ["history", "candles", "profile", "search"])
    }
}

private actor CallLog {
    var calls: [String] = []
    func record(_ name: String) { calls.append(name) }
}

private final class RecordingRepository: MarketDataRepository, @unchecked Sendable {
    private let log = CallLog()
    var calls: [String] { get async { await log.calls } }

    func quote(for symbol: String) async throws -> APTradeDomain.Quote {
        await log.record("quote")
        throw AppError.notFound
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        await log.record("history"); return []
    }
    func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        await log.record("candles"); return []
    }
    func profile(for symbol: String) async throws -> Asset {
        await log.record("profile"); return Asset(symbol: symbol, name: symbol, kind: .stock)
    }
    func search(query: String) async throws -> [Asset] {
        await log.record("search"); return []
    }
}
