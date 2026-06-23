import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class YahooMapperTests: XCTestCase {
    func fixture() throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "aapl_chart", withExtension: "json"))
        return try Data(contentsOf: url)
    }

    func test_quote_mapsPriceAndPreviousClose() throws {
        let q = try YahooMapper.quote(from: fixture())
        XCTAssertEqual(q.symbol, "AAPL")
        XCTAssertEqual(q.price, Money(amount: Decimal(string: "294.3")!))
        XCTAssertEqual(q.previousClose, Money(amount: Decimal(string: "296.42")!))
    }

    func test_history_skipsNullCloses() throws {
        let points = try YahooMapper.history(from: fixture())
        XCTAssertEqual(points.count, 2)            // middle null dropped
        XCTAssertEqual(points.first?.close, Money(amount: Decimal(string: "299.24")!))
    }

    func test_malformedJson_throwsDecoding() {
        XCTAssertThrowsError(try YahooMapper.quote(from: Data("{}".utf8))) { error in
            XCTAssertEqual(error as? AppError, .decoding)
        }
    }
}
