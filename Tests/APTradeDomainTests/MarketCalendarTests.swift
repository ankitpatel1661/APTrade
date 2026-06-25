import XCTest
@testable import APTradeDomain

final class MarketCalendarTests: XCTestCase {
    private let calendar = MarketCalendar()

    // 2025-06-25 is a Wednesday; 2025-06-28 is a Saturday.
    private func et(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York")!
        return cal.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
    }

    func test_weekdayDuringHours_isOpen() {
        XCTAssertEqual(calendar.status(at: et(2025, 6, 25, 10, 0)), .open)
    }

    func test_atOpenBell_isOpen_atCloseBell_isClosed() {
        XCTAssertEqual(calendar.status(at: et(2025, 6, 25, 9, 30)), .open)
        XCTAssertEqual(calendar.status(at: et(2025, 6, 25, 16, 0)), .closed)
    }

    func test_beforeOpenAndAfterClose_isClosed() {
        XCTAssertEqual(calendar.status(at: et(2025, 6, 25, 9, 0)), .closed)
        XCTAssertEqual(calendar.status(at: et(2025, 6, 25, 16, 30)), .closed)
    }

    func test_weekend_isClosed() {
        XCTAssertEqual(calendar.status(at: et(2025, 6, 28, 12, 0)), .closed)
    }

    func test_tradingDay_usesMarketLocalDate() {
        // 00:30 ET on the 25th is still 04:30 UTC on the 25th — same market day.
        XCTAssertEqual(calendar.tradingDay(of: et(2025, 6, 25, 0, 30)), "2025-06-25")
    }
}
