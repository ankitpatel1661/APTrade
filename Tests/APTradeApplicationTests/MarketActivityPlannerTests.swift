import XCTest
@testable import APTradeApplication
import APTradeDomain

final class MarketActivityPlannerTests: XCTestCase {
    private let planner = MarketActivityPlanner()

    private func et(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York")!
        return cal.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
    }

    private func settings(
        marketOpenClose: Bool = false,
        newsDigest: Bool = false,
        earningsReports: Bool = false,
        pieContributions: Bool = false
    ) -> AppSettings {
        var s = AppSettings.default
        s.marketOpenClose = marketOpenClose
        s.newsDigest = newsDigest
        s.earningsReports = earningsReports
        s.pieContributions = pieContributions
        return s
    }

    func test_firstTick_seedsStatus_withoutTransitionEvent() {
        let now = et(2025, 6, 25, 10, 0) // open
        let (events, state) = planner.plan(now: now, state: SchedulerState(),
                                           settings: settings(marketOpenClose: true))
        XCTAssertFalse(events.contains(.marketOpened))
        XCTAssertEqual(state.lastStatus, .open)
    }

    func test_transitionToClosed_firesMarketClosed_whenEnabled() {
        let now = et(2025, 6, 25, 16, 30) // closed
        let prior = SchedulerState(lastStatus: .open)
        let (events, _) = planner.plan(now: now, state: prior, settings: settings(marketOpenClose: true))
        XCTAssertTrue(events.contains(.marketClosed))
    }

    func test_transition_suppressed_whenMarketOpenCloseDisabled() {
        let now = et(2025, 6, 25, 16, 30)
        let prior = SchedulerState(lastStatus: .open)
        let (events, state) = planner.plan(now: now, state: prior, settings: settings(marketOpenClose: false))
        XCTAssertTrue(events.isEmpty)
        XCTAssertEqual(state.lastStatus, .closed, "status still advances so it won't replay later")
    }

    func test_digest_firesOncePerDay_whenEnabled() {
        let now = et(2025, 6, 25, 10, 0)
        let (first, state1) = planner.plan(now: now, state: SchedulerState(lastStatus: .open),
                                           settings: settings(newsDigest: true))
        XCTAssertTrue(first.contains(.digestDue))

        let later = et(2025, 6, 25, 14, 0)
        let (second, _) = planner.plan(now: later, state: state1, settings: settings(newsDigest: true))
        XCTAssertFalse(second.contains(.digestDue), "already sent today")
    }

    func test_digest_suppressed_whenDisabled_andDayNotAdvanced() {
        let now = et(2025, 6, 25, 10, 0)
        let (events, state) = planner.plan(now: now, state: SchedulerState(lastStatus: .open),
                                           settings: settings(newsDigest: false))
        XCTAssertFalse(events.contains(.digestDue))
        XCTAssertNil(state.lastDigestDay, "so enabling later in the day still delivers it")
    }

    func test_digest_notFired_whenClosed() {
        let now = et(2025, 6, 28, 12, 0) // Saturday
        let (events, _) = planner.plan(now: now, state: SchedulerState(lastStatus: .closed),
                                       settings: settings(newsDigest: true))
        XCTAssertFalse(events.contains(.digestDue))
    }

    func test_earningsCheck_firesOncePerDay_whenEnabled() {
        let now = et(2025, 6, 25, 10, 0)
        let (first, state1) = planner.plan(now: now, state: SchedulerState(lastStatus: .open),
                                           settings: settings(earningsReports: true))
        XCTAssertTrue(first.contains(.earningsCheckDue))

        let later = et(2025, 6, 25, 14, 0)
        let (second, _) = planner.plan(now: later, state: state1, settings: settings(earningsReports: true))
        XCTAssertFalse(second.contains(.earningsCheckDue), "already sent today")
    }

    func test_earningsCheck_suppressed_whenToggleOff() {
        let now = et(2025, 6, 25, 10, 0)
        let (events, state) = planner.plan(now: now, state: SchedulerState(lastStatus: .open),
                                           settings: settings(earningsReports: false))
        XCTAssertFalse(events.contains(.earningsCheckDue))
        XCTAssertNil(state.lastEarningsDay, "so enabling later in the day still delivers it")
    }

    func test_contributionCheck_firesOncePerDay_whenEnabled() {
        let now = et(2025, 6, 25, 10, 0)
        let (first, state1) = planner.plan(now: now, state: SchedulerState(lastStatus: .open),
                                           settings: settings(pieContributions: true))
        XCTAssertTrue(first.contains(.contributionCheckDue))

        let later = et(2025, 6, 25, 14, 0)
        let (second, _) = planner.plan(now: later, state: state1, settings: settings(pieContributions: true))
        XCTAssertFalse(second.contains(.contributionCheckDue), "already sent today")
    }

    func test_contributionCheck_suppressed_whenToggleOff() {
        let now = et(2025, 6, 25, 10, 0)
        let (events, state) = planner.plan(now: now, state: SchedulerState(lastStatus: .open),
                                           settings: settings(pieContributions: false))
        XCTAssertFalse(events.contains(.contributionCheckDue))
        XCTAssertNil(state.lastContributionDay, "so enabling later in the day still delivers it")
    }

    func test_dividendCheck_firesOncePerDay_ungatedByAnySetting() {
        let now = et(2025, 6, 25, 10, 0)
        let (first, state1) = planner.plan(now: now, state: SchedulerState(lastStatus: .open),
                                           settings: settings())
        XCTAssertTrue(first.contains(.dividendCheckDue))

        let later = et(2025, 6, 25, 14, 0)
        let (second, _) = planner.plan(now: later, state: state1, settings: settings())
        XCTAssertFalse(second.contains(.dividendCheckDue), "already sent today")
    }

    func test_dividendCheck_fires_evenWhenEveryNotificationToggleIsFalse() {
        // Dividend crediting is bookkeeping truth, not a user-configurable notification —
        // it must fire regardless of the state of every other toggle.
        let now = et(2025, 6, 25, 10, 0)
        let (events, state) = planner.plan(now: now, state: SchedulerState(lastStatus: .open),
                                           settings: settings(marketOpenClose: false, newsDigest: false,
                                                              earningsReports: false, pieContributions: false))
        XCTAssertTrue(events.contains(.dividendCheckDue))
        XCTAssertNotNil(state.lastDividendDay)
    }

    func test_dividendCheck_notFired_whenClosed() {
        let now = et(2025, 6, 28, 12, 0) // Saturday
        let (events, _) = planner.plan(now: now, state: SchedulerState(lastStatus: .closed),
                                       settings: settings())
        XCTAssertFalse(events.contains(.dividendCheckDue))
    }
}
