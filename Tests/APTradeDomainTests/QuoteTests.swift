import XCTest
@testable import APTradeDomain

final class QuoteTests: XCTestCase {
    func test_change_isPriceMinusPreviousClose() {
        let q = Quote(symbol: "AAPL",
                      price: Money(amount: Decimal(string: "294.30")!),
                      previousClose: Money(amount: Decimal(string: "296.42")!))
        XCTAssertEqual(q.change, Money(amount: Decimal(string: "-2.12")!))
    }

    func test_changePercent_isRelativeToPreviousClose() {
        let q = Quote(symbol: "X",
                      price: Money(amount: 110),
                      previousClose: Money(amount: 100))
        XCTAssertEqual(q.changePercent, Percentage(value: 10))
    }

    func test_changePercent_zeroPreviousClose_isZero() {
        let q = Quote(symbol: "X",
                      price: Money(amount: 110),
                      previousClose: Money(amount: 0))
        XCTAssertEqual(q.changePercent, Percentage(value: 0))
    }

    func test_timeframe_allCases_haveDisplayNames() {
        XCTAssertEqual(Timeframe.oneDay.displayName, "1D")
        XCTAssertEqual(Timeframe.allCases.count, 4)
    }
}
