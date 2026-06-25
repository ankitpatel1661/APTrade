import XCTest
@testable import APTradeInfrastructure
import APTradeDomain

final class YahooSearchMapperTests: XCTestCase {
    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json"))
        return try Data(contentsOf: url)
    }

    func test_mapsSupportedKindsAndDropsUnsupported() throws {
        let assets = try YahooSearchMapper.assets(from: fixture("search"))
        // INDEX and FUTURE dropped; BADTYPE kept (EQUITY) with symbol as name fallback.
        XCTAssertEqual(assets.map(\.symbol), ["AAPL", "AMD", "ARKK", "AVAX-USD", "BADTYPE"])
        XCTAssertEqual(assets.first?.kind, .stock)
        XCTAssertEqual(assets.first { $0.symbol == "ARKK" }?.kind, .etf)
        XCTAssertEqual(assets.first { $0.symbol == "AVAX-USD" }?.kind, .crypto)
        XCTAssertEqual(assets.first { $0.symbol == "BADTYPE" }?.name, "BADTYPE")
    }

    func test_throwsDecodingOnGarbage() {
        XCTAssertThrowsError(try YahooSearchMapper.assets(from: Data("nope".utf8)))
    }
}
