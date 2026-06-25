import XCTest
@testable import APTradeInfrastructure
import APTradeDomain

final class TimeframeMappingTests: XCTestCase {
    func test_mappings() {
        XCTAssertEqual(Timeframe.oneDay.yahooRange, "5d")
        XCTAssertEqual(Timeframe.oneDay.yahooInterval, "5m")
        XCTAssertEqual(Timeframe.oneWeek.yahooRange, "1mo")
        XCTAssertEqual(Timeframe.oneMonth.yahooInterval, "1d")
        XCTAssertEqual(Timeframe.oneYear.yahooRange, "1y")
    }

    func test_windowDurations() {
        XCTAssertEqual(Timeframe.oneDay.windowDuration, 24 * 3600)
        XCTAssertEqual(Timeframe.oneWeek.windowDuration, 7 * 24 * 3600)
    }
}
