import XCTest
@testable import APTradeApplication
import APTradeDomain

final class FakeRepo: MarketDataRepository, @unchecked Sendable {
    var quotes: [String: Quote] = [:]
    var failSymbols: Set<String> = []

    func quote(for symbol: String) async throws -> Quote {
        if failSymbols.contains(symbol) { throw AppError.network }
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        guard quotes[symbol] != nil else { throw AppError.notFound }
        return [PricePoint(date: Date(timeIntervalSince1970: 0), close: Money(amount: 1))]
    }
}

/// Repository that resolves profiles from metadata, overriding the suffix-based default.
final class ProfilingRepo: MarketDataRepository, @unchecked Sendable {
    var profiles: [String: Asset] = [:]
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
    func profile(for symbol: String) async throws -> Asset {
        guard let asset = profiles[symbol] else { throw AppError.notFound }
        return asset
    }
}

final class MarketUseCasesTests: XCTestCase {
    func makeRepo() -> FakeRepo {
        let r = FakeRepo()
        r.quotes["AAPL"] = Quote(symbol: "AAPL", price: Money(amount: 10), previousClose: Money(amount: 9))
        return r
    }

    func test_fetchQuotes_returnsPerSymbolResults() async {
        let repo = makeRepo()
        repo.failSymbols = ["BAD"]
        let out = await FetchQuotesUseCase(repository: repo)(symbols: ["AAPL", "BAD"])
        XCTAssertEqual(try? out["AAPL"]?.get().symbol, "AAPL")
        if case .failure(let e) = out["BAD"] { XCTAssertEqual(e, .network) } else { XCTFail() }
    }

    func test_fetchHistory_returnsPoints() async throws {
        let points = try await FetchHistoryUseCase(repository: makeRepo())(symbol: "AAPL", timeframe: .oneMonth)
        XCTAssertEqual(points.count, 1)
    }

    func test_search_inferaCryptoKind_andNormalizes() async throws {
        let repo = makeRepo()
        repo.quotes["BTC-USD"] = Quote(symbol: "BTC-USD", price: Money(amount: 1), previousClose: Money(amount: 1))
        let asset = try await SearchSymbolUseCase(repository: repo)(query: " btc-usd ")
        XCTAssertEqual(asset.symbol, "BTC-USD")
        XCTAssertEqual(asset.kind, .crypto)
    }

    func test_search_usesRepositoryProfile_forNameAndKind() async throws {
        let repo = ProfilingRepo()
        repo.profiles["SPY"] = Asset(symbol: "SPY", name: "SPDR S&P 500 ETF Trust", kind: .etf)
        let asset = try await SearchSymbolUseCase(repository: repo)(query: " spy ")
        XCTAssertEqual(asset.name, "SPDR S&P 500 ETF Trust")
        XCTAssertEqual(asset.kind, .etf)
    }

    func test_search_unknownSymbol_throwsNotFound() async {
        do {
            _ = try await SearchSymbolUseCase(repository: makeRepo())(query: "NOPE")
            XCTFail("expected throw")
        } catch { XCTAssertEqual(error as? AppError, .notFound) }
    }
}
