import XCTest
import APTradeDomain

final class PortfolioAnalyticsTests: XCTestCase {
    private let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)

    func test_noTransactions_realizedIsZero() {
        XCTAssertEqual(Portfolio.starting().realizedPnL, Money(amount: 0))
    }

    func test_buyThenPartialSell_realizesGainOnSoldShares() {
        let p = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(10), at: Money(amount: 100))
            .selling("AAPL", quantity: Quantity(4), at: Money(amount: 130))
        // (130 - 100) * 4 = 120
        XCTAssertEqual(p.realizedPnL, Money(amount: 120))
    }

    func test_fullExit_stillCountsRealized() {
        // Position is removed after a full sell, but realized P&L must persist.
        let p = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(5), at: Money(amount: 100))
            .selling("AAPL", quantity: Quantity(5), at: Money(amount: 90))
        XCTAssertTrue(p.positions.isEmpty)
        // (90 - 100) * 5 = -50
        XCTAssertEqual(p.realizedPnL, Money(amount: -50))
    }

    func test_averageCostAcrossMultipleBuys() {
        let p = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(10), at: Money(amount: 100))
            .buying(aapl, quantity: Quantity(10), at: Money(amount: 200))  // avg = 150
            .selling("AAPL", quantity: Quantity(5), at: Money(amount: 250)) // (250-150)*5 = 500
        XCTAssertEqual(p.realizedPnL, Money(amount: 500))
    }
}
