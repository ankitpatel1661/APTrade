import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class SearchRepo: MarketDataRepository, @unchecked Sendable {
    var results: [Asset] = []
    private(set) var lastQuery: String?
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
    func search(query: String) async throws -> [Asset] { lastQuery = query; return results }
}

final class SearchAssetsUseCaseTests: XCTestCase {
    func test_emptyQuery_returnsEmptyWithoutHittingRepo() async throws {
        let repo = SearchRepo()
        let sut = SearchAssetsUseCase(repository: repo)
        let out = try await sut(query: "   ")
        XCTAssertTrue(out.isEmpty)
        XCTAssertNil(repo.lastQuery)
    }

    func test_trimsAndForwardsQuery() async throws {
        let repo = SearchRepo()
        repo.results = [Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)]
        let sut = SearchAssetsUseCase(repository: repo)
        let out = try await sut(query: "  aapl ")
        XCTAssertEqual(repo.lastQuery, "aapl")
        XCTAssertEqual(out.map(\.symbol), ["AAPL"])
    }
}
