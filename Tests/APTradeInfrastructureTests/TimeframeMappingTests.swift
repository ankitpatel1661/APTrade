import XCTest
@testable import APTradeInfrastructure
import APTradeDomain

final class TimeframeMappingTests: XCTestCase {
    func test_mappings() {
        XCTAssertEqual(Timeframe.oneDay.yahooRange, "1d")
        XCTAssertEqual(Timeframe.oneDay.yahooInterval, "5m")
        XCTAssertEqual(Timeframe.oneWeek.yahooRange, "5d")
        XCTAssertEqual(Timeframe.oneMonth.yahooInterval, "1d")
        XCTAssertEqual(Timeframe.oneYear.yahooRange, "1y")
    }
}
