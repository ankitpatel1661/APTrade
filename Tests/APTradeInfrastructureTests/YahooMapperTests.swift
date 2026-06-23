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

    func test_asset_mapsNameAndKindFromMeta() throws {
        let asset = try YahooMapper.asset(from: fixture())
        XCTAssertEqual(asset.symbol, "AAPL")
        XCTAssertEqual(asset.name, "Apple Inc.")     // longName
        XCTAssertEqual(asset.kind, .stock)           // EQUITY
    }

    func test_asset_etfInstrumentType_mapsToEtf() throws {
        let json = """
        {"chart":{"result":[{"meta":{"symbol":"SPY","currency":"USD",
        "instrumentType":"ETF","regularMarketPrice":1,"chartPreviousClose":1,
        "longName":"SPDR S&P 500 ETF Trust"},"indicators":{}}],"error":null}}
        """
        let asset = try YahooMapper.asset(from: Data(json.utf8))
        XCTAssertEqual(asset.name, "SPDR S&P 500 ETF Trust")
        XCTAssertEqual(asset.kind, .etf)
    }

    func test_asset_missingInstrumentType_infersCryptoFromSuffix() throws {
        let json = """
        {"chart":{"result":[{"meta":{"symbol":"BTC-USD","currency":"USD",
        "regularMarketPrice":1,"chartPreviousClose":1},"indicators":{}}],"error":null}}
        """
        let asset = try YahooMapper.asset(from: Data(json.utf8))
        XCTAssertEqual(asset.name, "BTC-USD")        // no longName/shortName
        XCTAssertEqual(asset.kind, .crypto)
    }

    func test_malformedJson_throwsDecoding() {
        XCTAssertThrowsError(try YahooMapper.quote(from: Data("{}".utf8))) { error in
            XCTAssertEqual(error as? AppError, .decoding)
        }
    }

    // Yahoo returns a valid `result` with `meta` but an empty `indicators` (no `quote`
    // array) for some symbols. The quote must still map from meta; history is empty.
    func test_missingQuoteArray_stillMapsQuote_andEmptyHistory() throws {
        let json = """
        {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
        "regularMarketPrice":294.3,"chartPreviousClose":296.42},
        "timestamp":[1782000000],"indicators":{}}],"error":null}}
        """
        let data = Data(json.utf8)
        let q = try YahooMapper.quote(from: data)
        XCTAssertEqual(q.price, Money(amount: Decimal(string: "294.3")!))
        XCTAssertEqual(try YahooMapper.history(from: data).count, 0)
    }
}
