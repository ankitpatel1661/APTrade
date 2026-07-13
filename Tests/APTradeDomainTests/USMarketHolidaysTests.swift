import XCTest
@testable import APTradeDomain

final class USMarketHolidaysTests: XCTestCase {
    func test_2026Holidays() {
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 1, day: 1), .newYearsDay)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 1, day: 19), .martinLutherKingDay)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 2, day: 16), .washingtonsBirthday)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 4, day: 3), .goodFriday)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 5, day: 25), .memorialDay)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 6, day: 19), .juneteenth)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 7, day: 3), .independenceDay) // Sat Jul 4 observed Fri
        XCTAssertNil(USMarketHolidays.fullHoliday(year: 2026, month: 7, day: 4))
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 9, day: 7), .laborDay)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 11, day: 26), .thanksgiving)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 12, day: 25), .christmas)
    }

    func test_observationShifts2027() {
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2027, month: 6, day: 18), .juneteenth)      // Sat -> Fri
        XCTAssertNil(USMarketHolidays.fullHoliday(year: 2027, month: 6, day: 19))
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2027, month: 7, day: 5), .independenceDay)  // Sun -> Mon
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2027, month: 12, day: 24), .christmas)      // Sat -> Fri
    }

    func test_goodFridayAcrossYears() {
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2027, month: 3, day: 26), .goodFriday)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2028, month: 4, day: 14), .goodFriday)
    }

    func test_halfDays() {
        XCTAssertTrue(USMarketHolidays.isHalfDay(year: 2026, month: 11, day: 27))   // day after Thanksgiving
        XCTAssertTrue(USMarketHolidays.isHalfDay(year: 2026, month: 12, day: 24))   // Thu Christmas Eve
        XCTAssertFalse(USMarketHolidays.isHalfDay(year: 2026, month: 7, day: 3))    // observed July 4 -> FULL closure
        XCTAssertTrue(USMarketHolidays.isHalfDay(year: 2025, month: 7, day: 3))     // Thu, Jul 4 2025 = Fri
        XCTAssertFalse(USMarketHolidays.isHalfDay(year: 2027, month: 12, day: 24))  // observed Christmas -> FULL
        XCTAssertFalse(USMarketHolidays.isHalfDay(year: 2026, month: 7, day: 15))
    }

    func test_plainDayIsNoHoliday() {
        XCTAssertNil(USMarketHolidays.fullHoliday(year: 2026, month: 7, day: 15))
    }

    // MARK: - Year-boundary regression (Kotlin twin review, Task 1/8)

    func test_newYearsObservedShiftsIntoPriorYear() {
        // Jan 1 2028 is a Saturday -> observed Fri Dec 31 2027.
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2027, month: 12, day: 31), .newYearsDay)
        XCTAssertNil(USMarketHolidays.fullHoliday(year: 2028, month: 1, day: 1))
    }
}
