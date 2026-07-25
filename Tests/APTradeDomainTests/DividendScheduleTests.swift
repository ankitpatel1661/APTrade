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
}
