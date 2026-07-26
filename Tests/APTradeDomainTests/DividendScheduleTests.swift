import XCTest
@testable import APTradeDomain

final class DividendScheduleTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func date(_ y: Int, _ m: Int, _ d: Int) -> Date {
        var c = DateComponents(); c.year = y; c.month = m; c.day = d
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: c)!
    }
    private func position(_ symbol: String, shares: String) -> Position {
        Position(asset: Asset(symbol: symbol, name: symbol, kind: .stock),
                 quantity: Quantity(Decimal(string: shares) ?? 0),
                 averageCost: usd("50"), realizedPnL: Money(amount: 0))
    }
    /// Four quarterly payments through 2024.
    private func quarterlyHistory(_ symbol: String, amount: String) -> [DividendEvent] {
        [3, 6, 9, 12].map {
            DividendEvent(symbol: symbol, exDate: date(2024, $0, 10),
                         amountPerShare: usd(amount))
        }
    }

    func test_schedule_projectsQuarterlyEventsForwardOneYear() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("AAA", shares: "100")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50")],
            through: date(2026, 1, 1), asOf: date(2025, 1, 1))
        XCTAssertEqual(rows.count, 4)
        XCTAssertEqual(rows.map(\.symbol), ["AAA", "AAA", "AAA", "AAA"])
        XCTAssertEqual(rows[0].estimatedAmount, usd("50")) // 100 shares x $0.50
        XCTAssertEqual(rows[0].perShare, usd("0.50"))
    }

    func test_schedule_isSortedAscendingAcrossSymbols() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("AAA", shares: "10"), position("BBB", shares: "10")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50"),
                             "BBB": quarterlyHistory("BBB", amount: "0.25")],
            through: date(2026, 1, 1), asOf: date(2025, 1, 1))
        XCTAssertEqual(rows, rows.sorted { $0.exDate < $1.exDate })
        XCTAssertEqual(Set(rows.map(\.symbol)), ["AAA", "BBB"])
    }

    func test_schedule_neverEmitsDatesInThePast() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("AAA", shares: "100")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50")],
            through: date(2026, 1, 1), asOf: date(2025, 6, 1))
        XCTAssertTrue(rows.allSatisfy { $0.exDate > date(2025, 6, 1) },
                      "stale projections must be rolled forward, not shown in the past")
    }

    func test_schedule_skipsNonPayersAndZeroShareHoldings() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("NOPAY", shares: "100"), position("AAA", shares: "0")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50")],
            through: date(2026, 1, 1), asOf: date(2025, 1, 1))
        XCTAssertTrue(rows.isEmpty)
    }

    func test_schedule_respectsHorizon() {
        let rows = DividendMath.projectedSchedule(
            positions: [position("AAA", shares: "100")],
            eventsBySymbol: ["AAA": quarterlyHistory("AAA", amount: "0.50")],
            through: date(2025, 7, 1), asOf: date(2025, 1, 1))
        XCTAssertTrue(rows.allSatisfy { $0.exDate <= date(2025, 7, 1) })
        XCTAssertLessThan(rows.count, 4)
    }

    /// DISCRIMINATION (carry-notes §4f.1 / §4g). Last real ex-date ~400 days ago -- inside the
    /// cadence-inference lookback (quarterly gaps of 91 days are all well under it), so a cadence
    /// IS inferable, but outside `trailingAnnualPerShare`'s 365-day window, so the summary cards,
    /// the income forecast chart, and the "Upcoming Dividends" list all already value this holding
    /// at zero. The WRONG implementation this test rejects is `projectedSchedule` rolling a stale
    /// cadence forward with no staleness bound of its own (the `while next <= asOf` roll-forward
    /// alone, with no trailing-income guard) -- verified to fail (RED) with the guard removed,
    /// then pass (GREEN) once restored; see the task report's RED/GREEN transcript. Without the
    /// guard this fixture emits exactly FOUR quarterly $25 rows (100 shares x $0.25) at
    /// asOf+55d/+146d/+237d/+328d -- a specific, wrong, non-empty answer, not a vacuous one.
    func test_schedule_excludesPayerWhoseLastExDateIsBeyondTheTrailingWindow() {
        let day: TimeInterval = 86_400
        let asOf = date(2025, 1, 1)
        let stale: [DividendEvent] = [673, 582, 491, 400].map { offset in
            DividendEvent(symbol: "A", exDate: asOf.addingTimeInterval(-Double(offset) * day),
                         amountPerShare: usd("0.25"))
        }
        // Sanity precondition: the trailing window must already read zero for this fixture,
        // so a future fixture drift fails loudly rather than making the real assertion vacuous.
        XCTAssertEqual(DividendMath.trailingAnnualPerShare(events: stale, asOf: asOf), usd("0"),
                       "sanity: the trailing window must already read zero for this fixture")

        let rows = DividendMath.projectedSchedule(
            positions: [position("A", shares: "100")],
            eventsBySymbol: ["A": stale],
            through: asOf.addingTimeInterval(365 * day), asOf: asOf)
        XCTAssertTrue(rows.isEmpty, "a payer with no trailing income must not resurrect a stale cadence")
    }

    /// The mirror case: a holding that IS currently paying must not be caught by the new guard.
    /// Without this test, deleting the whole `projectedSchedule` body would satisfy the exclusion
    /// test above.
    func test_schedule_currentlyPayingHoldingStillAppearsOnTheCalendar() {
        let asOf = date(2025, 1, 1)
        let rows = DividendMath.projectedSchedule(
            positions: [position("A", shares: "100")],
            eventsBySymbol: ["A": quarterlyHistory("A", amount: "0.25")],
            through: asOf.addingTimeInterval(365 * 86_400), asOf: asOf)
        XCTAssertFalse(rows.isEmpty,
                       "a currently-paying holding must not be excluded by the trailing-income guard")
    }
}
