import XCTest
@testable import APTradeDomain

final class PortfolioEquityCurveTests: XCTestCase {
    private func day(_ n: Int) -> Date { Date(timeIntervalSince1970: TimeInterval(n) * 86_400) }

    func test_equitySeries_emptyHistories_returnsEmpty() {
        let p = Portfolio.starting()
        XCTAssertTrue(p.equitySeries(histories: [:]).isEmpty)
    }

    func test_equitySeries_valuesHoldingsAndCashOverTime() {
        // Start $100k cash, buy 10 AAPL @ $100 on day 1 (cost $1,000 → cash $99,000).
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let p = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100), on: day(1))
        let histories = [
            "AAPL": [
                PricePoint(date: day(1), close: Money(amount: 100)),
                PricePoint(date: day(2), close: Money(amount: 110))
            ]
        ]
        let series = p.equitySeries(histories: histories)
        XCTAssertEqual(series.count, 2)
        // Day 1: cash 99,000 + 10*100 = 100,000.
        XCTAssertEqual(series[0].value, Money(amount: 100_000))
        // Day 2: cash 99,000 + 10*110 = 100,100.
        XCTAssertEqual(series[1].value, Money(amount: 100_100))
    }

    func test_equitySeries_preTradeDate_isAllCash() {
        // History includes a day *before* the buy → that day should be pure starting cash.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let p = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100), on: day(2))
        let histories = [
            "AAPL": [
                PricePoint(date: day(1), close: Money(amount: 90)),
                PricePoint(date: day(2), close: Money(amount: 100))
            ]
        ]
        let series = p.equitySeries(histories: histories)
        XCTAssertEqual(series.count, 2)
        // Day 1 (before the buy): cash reverses the later $1,000 buy → 100,000, no holdings.
        XCTAssertEqual(series[0].value, Money(amount: 100_000))
        // Day 2: cash 99,000 + 10*100 = 100,000.
        XCTAssertEqual(series[1].value, Money(amount: 100_000))
    }

    func test_equitySeries_forwardFillsMissingCloses() {
        // AAPL has no bar on day 2; should forward-fill day-1 close.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)
        var p = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(1)), at: Money(amount: 100), on: day(1))
        p = try! p.buying(btc, quantity: Quantity(Decimal(1)), at: Money(amount: 200), on: day(1))
        let histories = [
            "AAPL": [PricePoint(date: day(1), close: Money(amount: 100))],
            "BTC-USD": [
                PricePoint(date: day(1), close: Money(amount: 200)),
                PricePoint(date: day(2), close: Money(amount: 250))
            ]
        ]
        let series = p.equitySeries(histories: histories)
        XCTAssertEqual(series.count, 2)
        // Day 2: cash = 100,000 - 100 - 200 = 99,700; AAPL forward-filled 100 + BTC 250 = 350.
        XCTAssertEqual(series[1].value, Money(amount: 100_050))  // 99,700 + 350
    }

    func test_equitySeries_dividendDoesNotAlterReconstructedShareCount() throws {
        // Buy 10 AAPL @ $100 on day 1 (cash 99,000, holdings 10 shares).
        // Then a dividend of $0.50/share on 10 shares held on day 2 (cash +5, holdings UNCHANGED).
        // A non-exhaustive `side == .buy ? qty : -qty` reconstruction would subtract the
        // dividend's quantity (10) from the day-2 share count, zeroing it out (10 - 10 == 0)
        // and dropping the $1,100 of AAPL holdings value from that date's equity entirely.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let p = try Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100), on: day(1))
            .receivingDividend("AAPL", amountPerShare: Money(amount: 0.50),
                                shares: Quantity(Decimal(10)), on: day(2))
        let histories = [
            "AAPL": [
                PricePoint(date: day(1), close: Money(amount: 100)),
                PricePoint(date: day(2), close: Money(amount: 110))
            ]
        ]
        let series = p.equitySeries(histories: histories)
        XCTAssertEqual(series.count, 2)
        // Day 1 (before dividend): cash reverses the later +$5 dividend → 99,000 + 10*100 = 100,000.
        XCTAssertEqual(series[0].value, Money(amount: 100_000))
        // Day 2: share count still 10 (dividends never change holdings).
        // cash 99,000 + 5 = 99,005; holdings 10*110 = 1,100 → total 100,105.
        XCTAssertEqual(series[1].value, Money(amount: 100_105))
    }
}
