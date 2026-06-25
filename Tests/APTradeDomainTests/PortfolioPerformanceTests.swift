import XCTest
import APTradeDomain

final class PortfolioPerformanceTests: XCTestCase {
    private func date(_ day: Int) -> Date {
        Date(timeIntervalSince1970: TimeInterval(day) * 86_400)
    }

    func test_emptyPortfolio_returnsNoPoints() {
        let portfolio = Portfolio.starting()
        XCTAssertTrue(portfolio.performanceSeries(histories: [:]).isEmpty)
    }

    func test_singleHolding_pnlIsQuantityTimesPriceMinusCost() {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let portfolio = try! Portfolio.starting(cash: Money(amount: 1_000))
            .buying(aapl, quantity: Quantity(2), at: Money(amount: 100))

        let series = portfolio.performanceSeries(histories: [
            "AAPL": [
                PricePoint(date: date(1), close: Money(amount: 100)),  // flat: pnl 0
                PricePoint(date: date(2), close: Money(amount: 110))   // +10 * 2 = +20
            ]
        ])

        XCTAssertEqual(series.count, 2)
        XCTAssertEqual(series[0].pnl, Money(amount: 0))
        XCTAssertEqual(series[1].pnl, Money(amount: 20))
        // value = cash (1000 - 200 spent) + holdings (2 * 110) = 800 + 220 = 1020
        XCTAssertEqual(series[1].value, Money(amount: 1_020))
    }

    func test_twoSymbols_forwardFillsMissingDates() {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)
        let portfolio = try! Portfolio.starting(cash: Money(amount: 10_000))
            .buying(aapl, quantity: Quantity(1), at: Money(amount: 100))
            .buying(btc, quantity: Quantity(1), at: Money(amount: 1_000))

        // AAPL trades on days 1 and 3; BTC (crypto) trades every day including day 2.
        let series = portfolio.performanceSeries(histories: [
            "AAPL": [
                PricePoint(date: date(1), close: Money(amount: 100)),
                PricePoint(date: date(3), close: Money(amount: 130))
            ],
            "BTC-USD": [
                PricePoint(date: date(1), close: Money(amount: 1_000)),
                PricePoint(date: date(2), close: Money(amount: 1_100)),
                PricePoint(date: date(3), close: Money(amount: 1_200))
            ]
        ])

        // Union of dates → 3 points.
        XCTAssertEqual(series.count, 3)
        // Day 2: AAPL forward-filled at 100 (pnl 0), BTC at 1100 (pnl +100) → +100.
        XCTAssertEqual(series[1].date, date(2))
        XCTAssertEqual(series[1].pnl, Money(amount: 100))
        // Day 3: AAPL +30, BTC +200 → +230.
        XCTAssertEqual(series[2].pnl, Money(amount: 230))
    }
}
