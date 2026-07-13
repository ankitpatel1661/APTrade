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

    func test_thanksgivingMiddayIsClosed() {
        XCTAssertEqual(calendar.status(at: et(2026, 11, 26, 12, 0)), .closed)
    }

    func test_halfDayClosesAtOnePm() {
        XCTAssertEqual(calendar.status(at: et(2026, 11, 27, 12, 59)), .open)
        XCTAssertEqual(calendar.status(at: et(2026, 11, 27, 13, 0)), .closed)
    }

    func test_plainWednesdayStaysOpen() {
        XCTAssertEqual(calendar.status(at: et(2026, 7, 15, 12, 0)), .open)
    }

    func test_holidayLookup() {
        XCTAssertEqual(calendar.holiday(on: et(2026, 11, 26, 12, 0)), .thanksgiving)
        XCTAssertTrue(calendar.isHalfDay(on: et(2026, 11, 27, 12, 0)))
    }

    func test_newYearsObservedOnPriorYearDec31_isClosed() {
        // Jan 1 2028 is a Saturday -> observed Fri Dec 31 2027.
        XCTAssertEqual(calendar.status(at: et(2027, 12, 31, 12, 0)), .closed)
    }
}
