import Foundation
import XCTest
@testable import APTradeDomain

/// Tests for `PieMathBacktest.dcaBacktest`. Fixture dates (2026-01-05 Mon, 2026-02-05
/// Thu, 2026-03-05 Thu) are plain trading weekdays with no US market holiday nearby, so
/// none of them roll — the monthly cadence steps land exactly on anchor, anchor+1mo,
/// anchor+2mo with no calendar-rolling side effects to account for by hand.
final class PieBacktestTests: XCTestCase {
    private let calendar = MarketCalendar()

    private let sixtyForty = [
        PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 60)),
        PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 40))
    ]

    private let allWeightA = [
        PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 100))
    ]

    // MARK: - Flat fixture: DCA return is exactly zero when prices never move

    func testFlatFixture_threeMonthlyContributions_zeroReturn() {
        // A=$10, B=$20 on every due day. 60/40 split means every $100 contribution buys
        // A:$60 (6 shares) and B:$40 (2 shares) each month (distribute() keeps the
        // 60/40 ratio exactly since prices never move the actual weights off target).
        let dailyCloses: [String: [String: Money]] = [
            "A": ["2026-01-05": Money(amount: 10), "2026-02-05": Money(amount: 10), "2026-03-05": Money(amount: 10)],
            "B": ["2026-01-05": Money(amount: 20), "2026-02-05": Money(amount: 20), "2026-03-05": Money(amount: 20)]
        ]

        let report = PieMathBacktest.dcaBacktest(
            slices: sixtyForty,
            amount: Money(amount: 100),
            cadence: .monthly,
            startDay: "2026-01-05",
            endDay: "2026-03-05",
            dailyCloses: dailyCloses,
            calendar: calendar
        )

        guard let report else {
            XCTFail("expected a report")
            return
        }

        XCTAssertEqual(report.totalInvested, Money(amount: 300))
        XCTAssertEqual(report.finalValue, Money(amount: 300))
        XCTAssertEqual(report.totalReturn, Percentage(value: 0))

        // One point per executed contribution (3) + one final valuation point = 4.
        XCTAssertEqual(report.points.count, 4)
        XCTAssertEqual(report.points[0], BacktestPoint(day: "2026-01-05", invested: Money(amount: 100), value: Money(amount: 100)))
        XCTAssertEqual(report.points[1], BacktestPoint(day: "2026-02-05", invested: Money(amount: 200), value: Money(amount: 200)))
        XCTAssertEqual(report.points[2], BacktestPoint(day: "2026-03-05", invested: Money(amount: 300), value: Money(amount: 300)))
        XCTAssertEqual(report.points[3], BacktestPoint(day: "2026-03-05", invested: Money(amount: 300), value: Money(amount: 300)))
    }

    // MARK: - Rising fixture: hand-computed final value; lump-sum beats DCA

    func testRisingFixture_handComputedFinalValue_lumpSumBeatsDCA() {
        // Single 100%-weight slice, price doubling each month: $10 -> $20 -> $40.
        //
        // Day 1 (2026-01-05, close $10): currentValues = {} (empty pie).
        //   distribute($100, {}, [A:100%]): totalAfter = 0+100 = 100, ideal = 100,
        //   deficit = 100-0 = 100 = contribution -> sufficient case, alloc = $100.
        //   shares bought = 100/10 = 10.  Running shares = 10.
        //   value = 10 * $10 = $100.  invested = $100.
        //
        // Day 2 (2026-02-05, close $20): currentValues = {A: 10*$20 = $200}.
        //   distribute($100, {A:$200}, [A:100%]): totalAfter = 200+100 = 300,
        //   ideal = 300, deficit = 300-200 = 100 = contribution -> alloc = $100.
        //   shares bought = 100/20 = 5.  Running shares = 15.
        //   value = 15 * $20 = $300.  invested = $200.
        //
        // Day 3 (2026-03-05, close $40): currentValues = {A: 15*$40 = $600}.
        //   distribute($100, {A:$600}, [A:100%]): totalAfter = 600+100 = 700,
        //   ideal = 700, deficit = 700-600 = 100 = contribution -> alloc = $100.
        //   shares bought = 100/40 = 2.5.  Running shares = 17.5.
        //   value = 17.5 * $40 = $700.  invested = $300.
        //
        // Final point (endDay = 2026-03-05, same close $40): value = 17.5*40 = $700.
        //
        // totalReturn = (700/300 - 1) * 100 = (7/3 - 1) * 100 = (4/3) * 100
        //             = 133.3333...% -> rounded 2dp = 133.33%.
        //
        // Lump sum: distribute($300, {}, [A:100%]) on day 1 (close $10) -> alloc $300,
        //   shares = 300/10 = 30.  Valued at the final close ($40): 30*40 = $1200.
        //   $1200 > $700, so lump sum beats DCA on a monotonically rising price.
        let dailyCloses: [String: [String: Money]] = [
            "A": [
                "2026-01-05": Money(amount: 10),
                "2026-02-05": Money(amount: 20),
                "2026-03-05": Money(amount: 40)
            ]
        ]

        let report = PieMathBacktest.dcaBacktest(
            slices: allWeightA,
            amount: Money(amount: 100),
            cadence: .monthly,
            startDay: "2026-01-05",
            endDay: "2026-03-05",
            dailyCloses: dailyCloses,
            calendar: calendar
        )

        guard let report else {
            XCTFail("expected a report")
            return
        }

        XCTAssertEqual(report.totalInvested, Money(amount: 300))
        XCTAssertEqual(report.finalValue, Money(amount: 700))
        XCTAssertEqual(report.totalReturn, Percentage(value: Decimal(string: "133.33")!))
        XCTAssertEqual(report.lumpSumFinalValue, Money(amount: 1200))
        XCTAssertGreaterThan(report.lumpSumFinalValue.amount, report.finalValue.amount)
    }

    // MARK: - Missing close on a due day: that contribution is skipped entirely

    func testMissingCloseOnDueDay_contributionSkippedEntirely_noPartialBuy() {
        // Same 60/40 flat-price setup as the zero-return test, but the 2026-02-05 close
        // for B is absent, so the whole February contribution is skipped (no A-only
        // partial buy). Only Jan 5 and Mar 5 execute: invested = $100 + $100 = $200.
        //
        // Day 1 (Jan 5): A gets $60 (6 shares @ $10), B gets $40 (2 shares @ $20).
        // Day 2 (Feb 5): SKIPPED (B has no close this day).
        // Day 3 (Mar 5): currentValues A=6*10=$60, B=2*20=$40, total=$100.
        //   totalAfter=200, ideal A=120,B=80, deficit A=60,B=40=contrib -> alloc A=$60,B=$40.
        //   shares bought: A += 60/10 = 6 (total 12), B += 40/20 = 2 (total 4).
        //   value = 12*$10 + 4*$20 = $120 + $80 = $200 (equal to invested, since prices
        //   never moved for either symbol on any executed day).
        let dailyCloses: [String: [String: Money]] = [
            "A": ["2026-01-05": Money(amount: 10), "2026-02-05": Money(amount: 10), "2026-03-05": Money(amount: 10)],
            "B": ["2026-01-05": Money(amount: 20), "2026-03-05": Money(amount: 20)]
            // B has no 2026-02-05 entry -> that due day is skipped entirely.
        ]

        let report = PieMathBacktest.dcaBacktest(
            slices: sixtyForty,
            amount: Money(amount: 100),
            cadence: .monthly,
            startDay: "2026-01-05",
            endDay: "2026-03-05",
            dailyCloses: dailyCloses,
            calendar: calendar
        )

        guard let report else {
            XCTFail("expected a report")
            return
        }

        XCTAssertEqual(report.totalInvested, Money(amount: 200))
        XCTAssertEqual(report.finalValue, Money(amount: 200))
        XCTAssertEqual(report.points.count, 3) // 2 executed contributions + final point
    }

    // MARK: - Every due day missing closes: insufficient history -> nil

    func testAllDueDaysMissingCloses_returnsNil() {
        let report = PieMathBacktest.dcaBacktest(
            slices: sixtyForty,
            amount: Money(amount: 100),
            cadence: .monthly,
            startDay: "2026-01-05",
            endDay: "2026-03-05",
            dailyCloses: [:], // no price data at all
            calendar: calendar
        )

        XCTAssertNil(report)
    }

    // MARK: - startDay itself is tradeable: it must count as the first due day

    func testStartDayItselfTradeable_countsAsFirstDueDay() {
        // dueDays() alone never treats its own anchor as a candidate (stepping starts
        // at anchor + 1*cadence). The backtest must still execute on startDay itself
        // when it's tradeable, via nextDueDay's step-0 eligibility -- otherwise a
        // single-day window like this one would wrongly report "insufficient history".
        let dailyCloses: [String: [String: Money]] = [
            "A": ["2026-01-05": Money(amount: 10)]
        ]

        let report = PieMathBacktest.dcaBacktest(
            slices: allWeightA,
            amount: Money(amount: 100),
            cadence: .monthly,
            startDay: "2026-01-05",
            endDay: "2026-01-05",
            dailyCloses: dailyCloses,
            calendar: calendar
        )

        guard let report else {
            XCTFail("expected a report — startDay itself should be the first due day")
            return
        }

        XCTAssertEqual(report.totalInvested, Money(amount: 100))
        XCTAssertEqual(report.finalValue, Money(amount: 100))
        // Only one contribution ever happens, so lump sum buys identically -> equal.
        XCTAssertEqual(report.lumpSumFinalValue, Money(amount: 100))
        XCTAssertEqual(report.points.count, 2) // 1 executed contribution + final point
    }
}
