import XCTest
@testable import APTradeDomain

final class PieScheduleTests: XCTestCase {
    private let calendar = MarketCalendar()

    // MARK: - date(fromDay:)

    func test_dateFromDay_parsesValidDayAtNoonET() {
        let date = PieSchedule.date(fromDay: "2026-07-15", calendar: calendar)
        XCTAssertNotNil(date)
        XCTAssertEqual(calendar.tradingDay(of: try XCTUnwrap(date)), "2026-07-15")
    }

    func test_dateFromDay_malformedInput_returnsNil() {
        XCTAssertNil(PieSchedule.date(fromDay: "not-a-date", calendar: calendar))
        XCTAssertNil(PieSchedule.date(fromDay: "2026-13-01", calendar: calendar))
        XCTAssertNil(PieSchedule.date(fromDay: "2026-02-30", calendar: calendar))
        XCTAssertNil(PieSchedule.date(fromDay: "2026-07", calendar: calendar))
    }

    // MARK: - rollToTradingDay (Step 1a)

    func test_rollToTradingDay_thanksgiving_rollsToHalfDayFriday() {
        // 2026-11-26 is Thanksgiving (full holiday); 2026-11-27 is the half-day-after,
        // which IS a trading day, so the roll lands there.
        XCTAssertEqual(PieSchedule.rollToTradingDay("2026-11-26", calendar: calendar), "2026-11-27")
    }

    func test_rollToTradingDay_july4thObserved_rollsPastWeekend() {
        // July 4 2026 is a Saturday, so July 3 (Friday) is the observed holiday.
        // Roll from Jul 3 skips the holiday itself, then Sat Jul 4 and Sun Jul 5.
        XCTAssertEqual(PieSchedule.rollToTradingDay("2026-07-03", calendar: calendar), "2026-07-06")
    }

    func test_rollToTradingDay_saturday_rollsToMonday() {
        XCTAssertEqual(PieSchedule.rollToTradingDay("2026-07-25", calendar: calendar), "2026-07-27")
    }

    func test_rollToTradingDay_alreadyTradingDay_isUnchanged() {
        XCTAssertEqual(PieSchedule.rollToTradingDay("2026-07-15", calendar: calendar), "2026-07-15")
    }

    // MARK: - dueDays: weekly (Step 1b)

    func test_dueDays_weekly_stepsSevenDaysAndStaysOnMondays() {
        let days = PieSchedule.dueDays(
            anchorDay: "2026-07-06",
            cadence: .weekly,
            afterDay: "2026-07-06",
            throughDay: "2026-07-27",
            calendar: calendar
        )
        XCTAssertEqual(days, ["2026-07-13", "2026-07-20", "2026-07-27"])
    }

    // MARK: - dueDays: monthly with clamp (Step 1c)

    func test_nextDueDay_monthlyFromJan31_clampsToFebAndRollsWeekend() {
        // Jan 31 + 1 month clamps to Feb 28 (Foundation), which is a Saturday;
        // rolling forward crosses Sun Mar 1 and lands on Mon Mar 2.
        let next = PieSchedule.nextDueDay(
            anchorDay: "2026-01-31",
            cadence: .monthly,
            afterDay: "2026-01-31",
            calendar: calendar
        )
        XCTAssertEqual(next, "2026-03-02")
    }

    // MARK: - dueDays: holiday roll inside the window (Step 1d)

    func test_dueDays_acrossThanksgivingWeek_rollsTheHolidayHit() {
        // Anchor is a Thursday; the first weekly step lands exactly on Thanksgiving.
        let days = PieSchedule.dueDays(
            anchorDay: "2026-11-19",
            cadence: .weekly,
            afterDay: "2026-11-19",
            throughDay: "2026-12-01",
            calendar: calendar
        )
        XCTAssertEqual(days, ["2026-11-27"])
    }

    // MARK: - dueDays: empty window (Step 1e)

    func test_dueDays_afterEqualsThrough_isEmpty() {
        let days = PieSchedule.dueDays(
            anchorDay: "2026-07-06",
            cadence: .weekly,
            afterDay: "2026-07-20",
            throughDay: "2026-07-20",
            calendar: calendar
        )
        XCTAssertEqual(days, [])
    }

    func test_dueDays_afterAfterThrough_isEmpty() {
        let days = PieSchedule.dueDays(
            anchorDay: "2026-07-06",
            cadence: .weekly,
            afterDay: "2026-07-27",
            throughDay: "2026-07-20",
            calendar: calendar
        )
        XCTAssertEqual(days, [])
    }

    // MARK: - dueDays: biweekly

    func test_dueDays_biweekly_stepsFourteenDays() {
        let days = PieSchedule.dueDays(
            anchorDay: "2026-07-06",
            cadence: .biweekly,
            afterDay: "2026-07-06",
            throughDay: "2026-08-10",
            calendar: calendar
        )
        XCTAssertEqual(days, ["2026-07-20", "2026-08-03"])
    }

    // MARK: - nextDueDay: anchor itself is the first due day

    func test_nextDueDay_beforeAnchor_returnsAnchorRolled() {
        // afterDay precedes the anchor entirely, so the very first due day is the
        // (rolled) anchor day itself.
        let next = PieSchedule.nextDueDay(
            anchorDay: "2026-07-06",
            cadence: .weekly,
            afterDay: "2026-07-01",
            calendar: calendar
        )
        XCTAssertEqual(next, "2026-07-06")
    }

    // MARK: - dueDays: sorted ascending, deduped

    func test_dueDays_resultsAreSortedAscending() {
        let days = PieSchedule.dueDays(
            anchorDay: "2026-07-06",
            cadence: .weekly,
            afterDay: "2026-07-06",
            throughDay: "2026-08-10",
            calendar: calendar
        )
        XCTAssertEqual(days, days.sorted())
        XCTAssertEqual(days, Array(Set(days)).sorted())
    }
}
