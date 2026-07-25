import XCTest
@testable import APTradeDomain

final class DividendMathForecastTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func date(_ y: Int, _ m: Int, _ d: Int) -> Date {
        var c = DateComponents(); c.year = y; c.month = m; c.day = d
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: c)!
    }
    /// Quarterly events for `years` years ending before `asOf`, growing `perYear` annually.
    private func quarterly(symbol: String, startYear: Int, years: Int,
                           startAmount: Decimal, perYear: Decimal) -> [DividendEvent] {
        var out: [DividendEvent] = []
        var amount = startAmount
        for y in 0..<years {
            for q in 0..<4 {
                out.append(DividendEvent(symbol: symbol,
                                         exDate: date(startYear + y, 1 + q * 3, 15),
                                         amountPerShare: Money(amount: amount)))
            }
            amount = amount * (1 + perYear)
        }
        return out
    }

    func test_growthRate_flatHistory_isZero() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!, perYear: 0)
        let r = DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1))
        XCTAssertEqual(r, 0)
    }

    func test_growthRate_recoversTenPercentGrowth() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!,
                               perYear: Decimal(string: "0.10")!)
        let r = DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1))
        let delta = abs((r - Decimal(string: "0.10")!) as Decimal)
        XCTAssertLessThan(delta, Decimal(string: "0.015")!, "expected ~10%, got \(r)")
    }

    func test_growthRate_clampsHighGrowthToTwentyFivePercent() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.10")!,
                               perYear: Decimal(string: "0.90")!)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1)),
                       Decimal(string: "0.25")!)
    }

    func test_growthRate_clampsCollapseToMinusTwentyPercent() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "1.00")!,
                               perYear: Decimal(string: "-0.60")!)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1)),
                       Decimal(string: "-0.20")!)
    }

    func test_growthRate_insufficientHistory_isZero() {
        let oneYear = quarterly(symbol: "AAA", startYear: 2024, years: 1,
                                startAmount: Decimal(string: "0.25")!, perYear: 0)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: oneYear, asOf: date(2025, 1, 1)), 0)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: [], asOf: date(2025, 1, 1)), 0)
    }

    func test_growthRate_ignoresHistoryOlderThanFiveYears() {
        var events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!, perYear: 0)
        // A tiny ancient payment must not inflate the measured growth.
        events.insert(DividendEvent(symbol: "AAA", exDate: date(2005, 3, 1),
                                    amountPerShare: usd("0.01")), at: 0)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1)), 0)
    }
}
