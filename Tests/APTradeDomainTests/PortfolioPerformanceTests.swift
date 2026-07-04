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

    func test_twoSymbols_forwardFillsAfterGateOpens() {
        // Gate-aware successor to the old cliff test. BTC (24/7) only starts at day 2;
        // AAPL (market-hours) already has a close at day 1. Per the all-priced gate, day 1
        // is skipped (BTC not priced yet); the curve starts at day 2 where both are priced,
        // and day 3 forward-fills BTC's day-2 close where only AAPL moves.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)
        let portfolio = try! Portfolio.starting(cash: Money(amount: 10_000))
            .buying(aapl, quantity: Quantity(1), at: Money(amount: 100))
            .buying(btc, quantity: Quantity(1), at: Money(amount: 1_000))

        let series = portfolio.performanceSeries(histories: [
            "AAPL": [
                PricePoint(date: date(1), close: Money(amount: 100)),
                PricePoint(date: date(2), close: Money(amount: 110)),
                PricePoint(date: date(3), close: Money(amount: 130))
            ],
            "BTC-USD": [
                PricePoint(date: date(2), close: Money(amount: 1_100)),
                PricePoint(date: date(3), close: Money(amount: 1_200))
            ]
        ])

        // Day 1 gated out (BTC unpriced) → curve starts at day 2 → 2 points.
        XCTAssertEqual(series.count, 2)
        // Day 2 (gate opens): AAPL 110 (pnl +10), BTC 1100 (pnl +100) → +110.
        XCTAssertEqual(series[0].date, date(2))
        XCTAssertEqual(series[0].pnl, Money(amount: 110))
        // Day 3: AAPL +30, BTC +200 → +230.
        XCTAssertEqual(series[1].date, date(3))
        XCTAssertEqual(series[1].pnl, Money(amount: 230))
    }

    // MARK: - All-priced gate (ported from Kotlin PortfolioPerformanceTest, increment 6b.3)
    // These mirror the Kotlin fixtures 1:1, constructing Portfolio directly (like Kotlin) so
    // averageCost is set independently of cash — .buying() would reject the zero-cash cases.

    private func position(_ asset: Asset, qty: Decimal, cost: Decimal) -> Position {
        Position(asset: asset, quantity: Quantity(qty),
                 averageCost: Money(amount: cost), realizedPnL: Money(amount: 0))
    }

    func test_gate_forwardFillsMissingDatesAfterGateOpens() {
        // BTC (24/7) only starts at date 2; AAPL already has a close at date 1. The gate
        // skips date 1; the curve starts at date 2 where both are priced, and forward-fill
        // holds BTC's date-2 close steady through date 3 where only AAPL moves.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)
        let portfolio = Portfolio(cash: Money(amount: 0), positions: [
            position(aapl, qty: 10, cost: 300),
            position(btc, qty: 1, cost: 100)
        ])

        let series = portfolio.performanceSeries(histories: [
            "AAPL": [
                PricePoint(date: date(1), close: Money(amount: 300)),
                PricePoint(date: date(2), close: Money(amount: 310)),
                PricePoint(date: date(3), close: Money(amount: 320))
            ],
            "BTC-USD": [
                PricePoint(date: date(2), close: Money(amount: 150))
            ]
        ])

        XCTAssertEqual(series.count, 2)
        // date 2 (gate opens): AAPL 310*10=3100 + BTC 150 = 3250.
        XCTAssertEqual(series[0].date, date(2))
        XCTAssertEqual(series[0].value, Money(amount: 3_250))
        // date 3: AAPL 320*10=3200 + BTC forward-filled 150 = 3350.
        XCTAssertEqual(series[1].date, date(3))
        XCTAssertEqual(series[1].value, Money(amount: 3_350))
    }

    func test_gate_skipsDatesWhereNothingIsPriced() {
        // AAPL (held) only has data starting at date 2; an unheld symbol (MSFT) has a point
        // at date 1, but iteration is over positions, so that date never produces a point.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let portfolio = Portfolio(cash: Money(amount: 0), positions: [
            position(aapl, qty: 10, cost: 300)
        ])

        let series = portfolio.performanceSeries(histories: [
            "AAPL": [PricePoint(date: date(2), close: Money(amount: 310))],
            "MSFT": [PricePoint(date: date(1), close: Money(amount: 50))]
        ])

        XCTAssertEqual(series.count, 1)
        XCTAssertEqual(series[0].date, date(2))
        XCTAssertEqual(series[0].value, Money(amount: 3_100))
    }

    func test_gate_startsAtFirstDateWhereAllHeldSymbolsArePriced() {
        // AAPL priced from date 1; BTC only starts at date 3. The old behavior emitted a
        // cash+AAPL-only point at date 1/2, then a cliff when BTC joins. The gate must skip
        // date 1/2 entirely and start at date 3.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)
        let portfolio = Portfolio(cash: Money(amount: 1_000), positions: [
            position(aapl, qty: 10, cost: 300),
            position(btc, qty: 2, cost: 100)
        ])

        let series = portfolio.performanceSeries(histories: [
            "AAPL": [
                PricePoint(date: date(1), close: Money(amount: 300)),
                PricePoint(date: date(2), close: Money(amount: 305)),
                PricePoint(date: date(3), close: Money(amount: 310))
            ],
            "BTC-USD": [PricePoint(date: date(3), close: Money(amount: 150))]
        ])

        XCTAssertEqual(series.count, 1)
        XCTAssertEqual(series[0].date, date(3))
    }

    func test_gate_valueAtGateOpenEqualsCashPlusAllHeldSymbolsPriced() {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let btc = Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto)
        let portfolio = Portfolio(cash: Money(amount: 1_000), positions: [
            position(aapl, qty: 10, cost: 300),
            position(btc, qty: 2, cost: 100)
        ])

        let series = portfolio.performanceSeries(histories: [
            "AAPL": [
                PricePoint(date: date(1), close: Money(amount: 300)),
                PricePoint(date: date(2), close: Money(amount: 305)),
                PricePoint(date: date(3), close: Money(amount: 310))
            ],
            "BTC-USD": [PricePoint(date: date(3), close: Money(amount: 150))]
        ])

        // cash 1000 + AAPL 310*10=3100 + BTC 150*2=300 = 4400 exactly.
        XCTAssertEqual(series.count, 1)
        XCTAssertEqual(series[0].value, Money(amount: 4_400))
    }

    func test_gate_singleSymbolSeriesUnchanged() {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let portfolio = Portfolio(cash: Money(amount: 97_000), positions: [
            position(aapl, qty: 10, cost: 300)
        ])

        let series = portfolio.performanceSeries(histories: [
            "AAPL": [
                PricePoint(date: date(1), close: Money(amount: 300)),
                PricePoint(date: date(2), close: Money(amount: 310))
            ]
        ])

        XCTAssertEqual(series.count, 2)
        XCTAssertEqual(series[0].date, date(1))
        XCTAssertEqual(series[1].date, date(2))
    }

    func test_gate_positionWithNoHistoryLeavesRemainingSymbolsCurveIntact() {
        // MSFT is held but has zero history points; it must stay excluded from the gate and
        // must NOT blank the AAPL curve.
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let msft = Asset(symbol: "MSFT", name: "Microsoft", kind: .stock)
        let portfolio = Portfolio(cash: Money(amount: 0), positions: [
            position(aapl, qty: 10, cost: 300),
            position(msft, qty: 5, cost: 50)
        ])

        let series = portfolio.performanceSeries(histories: [
            "AAPL": [
                PricePoint(date: date(1), close: Money(amount: 300)),
                PricePoint(date: date(2), close: Money(amount: 310))
            ]
        ])

        XCTAssertEqual(series.count, 2)
        XCTAssertEqual(series[0].date, date(1))
        XCTAssertEqual(series[0].value, Money(amount: 3_000))
        XCTAssertEqual(series[1].date, date(2))
        XCTAssertEqual(series[1].value, Money(amount: 3_100))
    }
}
