import XCTest
@testable import APTradeDomain

final class DividendMathTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func qty(_ s: String) -> Quantity { Quantity(Decimal(string: s) ?? 0) }

    private func date(_ y: Int, _ m: Int, _ d: Int) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: DateComponents(year: y, month: m, day: d))!
    }

    // MARK: sharesHeld

    func test_sharesHeld_netsBuysAndSellsBeforeDate() {
        let txns = [
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("10"), price: usd("100"), date: date(2026, 1, 1)),
            Transaction(symbol: "AAPL", side: .sell, quantity: qty("3"), price: usd("110"), date: date(2026, 2, 1)),
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("2"), price: usd("120"), date: date(2026, 3, 1)),
        ]
        let held = DividendMath.sharesHeld(symbol: "AAPL", at: date(2026, 4, 1), transactions: txns)
        XCTAssertEqual(held, qty("9"))
    }

    func test_sharesHeld_excludesTransactionAtExactDateInstant() {
        let txns = [
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("10"), price: usd("100"), date: date(2026, 1, 1)),
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("5"), price: usd("100"), date: date(2026, 2, 1)),
        ]
        let held = DividendMath.sharesHeld(symbol: "AAPL", at: date(2026, 2, 1), transactions: txns)
        XCTAssertEqual(held, qty("10"))
    }

    func test_sharesHeld_dripBuysCountLikeAnyBuy() {
        let txns = [
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("10"), price: usd("100"), date: date(2026, 1, 1)),
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("1"), price: usd("50"),
                        date: date(2026, 2, 1), isDrip: true),
        ]
        let held = DividendMath.sharesHeld(symbol: "AAPL", at: date(2026, 3, 1), transactions: txns)
        XCTAssertEqual(held, qty("11"))
    }

    func test_sharesHeld_dividendTransactionsContributeNothing() {
        let txns = [
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("10"), price: usd("100"), date: date(2026, 1, 1)),
            Transaction(symbol: "AAPL", side: .dividend, quantity: qty("10"), price: usd("0.5"), date: date(2026, 2, 1)),
        ]
        let held = DividendMath.sharesHeld(symbol: "AAPL", at: date(2026, 3, 1), transactions: txns)
        XCTAssertEqual(held, qty("10"))
    }

    // MARK: trailingAnnualPerShare

    func test_trailingAnnualPerShare_sumsExactlyLast365Days() {
        let asOf = date(2026, 7, 20)
        let excludedAtMinus365 = DividendEvent(symbol: "AAPL", exDate: asOf.addingTimeInterval(-365 * 86_400),
                                                amountPerShare: usd("0.20"))
        let includedJustInside = DividendEvent(symbol: "AAPL", exDate: asOf.addingTimeInterval(-364 * 86_400),
                                                amountPerShare: usd("0.30"))
        let includedAtAsOf = DividendEvent(symbol: "AAPL", exDate: asOf, amountPerShare: usd("0.40"))

        let sum = DividendMath.trailingAnnualPerShare(
            events: [excludedAtMinus365, includedJustInside, includedAtAsOf], asOf: asOf)

        XCTAssertEqual(sum, usd("0.70"))
    }

    func test_trailingAnnualPerShare_zeroWhenNoEvents() {
        XCTAssertEqual(DividendMath.trailingAnnualPerShare(events: [], asOf: date(2026, 7, 20)), usd("0"))
    }

    // MARK: inferredCadence

    func test_inferredCadence_quarterlyFromFourEvents() {
        let events = (0..<4).map { i in
            DividendEvent(symbol: "AAPL",
                           exDate: date(2026, 1, 1).addingTimeInterval(Double(i) * 91 * 86_400),
                           amountPerShare: usd("0.5"))
        }
        XCTAssertEqual(DividendMath.inferredCadence(events: events), .quarterly)
    }

    func test_inferredCadence_nilWithFewerThanTwoEvents() {
        let events = [DividendEvent(symbol: "AAPL", exDate: date(2026, 1, 1), amountPerShare: usd("0.5"))]
        XCTAssertNil(DividendMath.inferredCadence(events: events))
    }

    func test_inferredCadence_monthlySpacing() {
        let events = (0..<3).map { i in
            DividendEvent(symbol: "AAPL",
                           exDate: date(2026, 1, 1).addingTimeInterval(Double(i) * 30 * 86_400),
                           amountPerShare: usd("0.1"))
        }
        XCTAssertEqual(DividendMath.inferredCadence(events: events), .monthly)
    }

    // MARK: nextProjected

    func test_nextProjected_quarterlyAddsNinetyOneDays() {
        let events = (0..<4).map { i in
            DividendEvent(symbol: "AAPL",
                           exDate: date(2026, 1, 1).addingTimeInterval(Double(i) * 91 * 86_400),
                           amountPerShare: usd("0.55"))
        }
        let last = events.max(by: { $0.exDate < $1.exDate })!
        let projected = DividendMath.nextProjected(events: events)
        XCTAssertEqual(projected?.exDate, last.exDate.addingTimeInterval(91 * 86_400))
        XCTAssertEqual(projected?.amountPerShare, usd("0.55"))
        XCTAssertEqual(projected?.symbol, "AAPL")
    }

    func test_nextProjected_nilWhenCadenceNil() {
        let events = [DividendEvent(symbol: "AAPL", exDate: date(2026, 1, 1), amountPerShare: usd("0.5"))]
        XCTAssertNil(DividendMath.nextProjected(events: events))
    }

    // MARK: monthlyReceived

    func test_monthlyReceived_groupsSameMonthDividendsAndIgnoresBuysSells() {
        let txns = [
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("10"), price: usd("100"), date: date(2026, 7, 1)),
            Transaction(symbol: "AAPL", side: .dividend, quantity: qty("10"), price: usd("0.5"), date: date(2026, 7, 5)),
            Transaction(symbol: "MSFT", side: .dividend, quantity: qty("4"), price: usd("1.0"), date: date(2026, 7, 20)),
            Transaction(symbol: "AAPL", side: .sell, quantity: qty("2"), price: usd("110"), date: date(2026, 7, 25)),
        ]
        let result = DividendMath.monthlyReceived(transactions: txns)
        XCTAssertEqual(result, ["2026-07": usd("9")])
    }

    func test_monthlyReceived_emptyWhenNoDividendTransactions() {
        let txns = [
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("10"), price: usd("100"), date: date(2026, 7, 1)),
        ]
        XCTAssertEqual(DividendMath.monthlyReceived(transactions: txns), [:])
    }

    // MARK: projectedAnnualIncome

    func test_projectedAnnualIncome_multipliesTrailingSumsByPositionQuantitiesAndAdds() {
        let asOf = date(2026, 7, 20)
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let msft = Asset(symbol: "MSFT", name: "Microsoft", kind: .stock)
        let positions = [
            Position(asset: aapl, quantity: qty("10"), averageCost: usd("100"), realizedPnL: usd("0")),
            Position(asset: msft, quantity: qty("5"), averageCost: usd("200"), realizedPnL: usd("0")),
        ]
        let eventsBySymbol: [String: [DividendEvent]] = [
            "AAPL": [DividendEvent(symbol: "AAPL", exDate: asOf, amountPerShare: usd("1.00"))],
            // MSFT intentionally absent from the map -> contributes zero
        ]
        let income = DividendMath.projectedAnnualIncome(
            positions: positions, eventsBySymbol: eventsBySymbol, asOf: asOf)
        XCTAssertEqual(income, usd("10"))
    }
}
