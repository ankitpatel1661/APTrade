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

    private func position(_ symbol: String, shares: String, price: String) -> Position {
        Position(asset: Asset(symbol: symbol, name: symbol, kind: .stock),
                 quantity: Quantity(Decimal(string: shares) ?? 0),
                 averageCost: usd(price),
                 realizedPnL: Money(amount: 0))
    }

    func test_forecast_flatDividend_noDrip_isConstant() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!, perYear: 0)
        let out = DividendMath.incomeForecast(positions: [position("AAA", shares: "100", price: "50")],
                                              eventsBySymbol: ["AAA": events],
                                              years: 3, dripEnabled: false, asOf: date(2025, 1, 1))
        XCTAssertEqual(out.count, 3)
        XCTAssertEqual(out.map(\.yearOffset), [1, 2, 3])
        // 100 shares x $1.00/yr trailing
        XCTAssertEqual(out[0].income, usd("100"))
        XCTAssertEqual(out[1].income, usd("100"))
        XCTAssertEqual(out[2].income, usd("100"))
    }

    func test_forecast_growingDividend_compoundsGrowth() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!,
                               perYear: Decimal(string: "0.10")!)
        let out = DividendMath.incomeForecast(positions: [position("AAA", shares: "100", price: "50")],
                                              eventsBySymbol: ["AAA": events],
                                              years: 3, dripEnabled: false, asOf: date(2025, 1, 1))
        // Compounding, not a linear increase: each year must be the previous year times
        // the SAME growth factor — a linear (additive) model would fail this, since it
        // would not multiply by a constant ratio each period.
        //
        // (Note: a literal cross-multiplied ratio check — out[2]*out[0] == out[1]*out[1] —
        // was tried and rejected: squaring these already ~20-significant-digit compounded
        // values overflows Decimal's 38-significant-digit budget and rounds the two
        // products differently in their last digit, producing a false failure unrelated
        // to compounding correctness. Recomputing one multiplication at a time, as the
        // implementation itself does, avoids that overflow.)
        let growth = DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1))
        XCTAssertEqual(out[1].income.amount, out[0].income.amount * (1 + growth),
                       "year 2 must be year 1 times the constant growth factor")
        XCTAssertEqual(out[2].income.amount, out[1].income.amount * (1 + growth),
                       "year 3 must be year 2 times the SAME constant growth factor")
        XCTAssertGreaterThan(out[1].income.amount, out[0].income.amount)
        XCTAssertGreaterThan(out[2].income.amount, out[1].income.amount)
    }

    func test_forecast_dripProducesMoreIncomeThanCash() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!, perYear: 0)
        let positions = [position("AAA", shares: "100", price: "50")]
        // Quoted price ($100) deliberately differs from the $50 cost basis, so this
        // pins DRIP reinvestment to the quoted price rather than yield-on-cost.
        let prices = ["AAA": usd("100")]
        let cash = DividendMath.incomeForecast(positions: positions, eventsBySymbol: ["AAA": events],
                                               years: 5, dripEnabled: false, asOf: date(2025, 1, 1),
                                               pricesBySymbol: prices)
        let drip = DividendMath.incomeForecast(positions: positions, eventsBySymbol: ["AAA": events],
                                               years: 5, dripEnabled: true, asOf: date(2025, 1, 1),
                                               pricesBySymbol: prices)
        XCTAssertEqual(cash.map(\.income), [usd("100"), usd("100"), usd("100"), usd("100"), usd("100")])
        // 100sh @ $1.00/yr = $100 income; reinvested at $100/sh buys 1 share/yr, compounding:
        // 100 -> 101 -> 102.01 -> 103.0301 -> 104.060401
        XCTAssertEqual(drip.map(\.income),
                       [usd("100"), usd("101"), usd("102.01"), usd("103.0301"), usd("104.060401")],
                       "reinvestment must use the quoted $100 price, not the $50 cost basis")
        XCTAssertEqual(drip[0].income, cash[0].income, "year 1 is identical — reinvestment only helps later")
    }

    func test_forecast_nonPayerContributesNothing() {
        let out = DividendMath.incomeForecast(positions: [position("NOPAY", shares: "10", price: "20")],
                                              eventsBySymbol: [:],
                                              years: 2, dripEnabled: true, asOf: date(2025, 1, 1))
        XCTAssertEqual(out.map(\.income), [Money(amount: 0), Money(amount: 0)])
    }

    func test_forecast_sumsAcrossHoldings() {
        let a = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                          startAmount: Decimal(string: "0.25")!, perYear: 0)
        let b = quarterly(symbol: "BBB", startYear: 2021, years: 4,
                          startAmount: Decimal(string: "0.50")!, perYear: 0)
        let out = DividendMath.incomeForecast(
            positions: [position("AAA", shares: "100", price: "50"),
                        position("BBB", shares: "10", price: "80")],
            eventsBySymbol: ["AAA": a, "BBB": b],
            years: 1, dripEnabled: false, asOf: date(2025, 1, 1))
        XCTAssertEqual(out[0].income, usd("120")) // 100 + 20
    }

    func test_forecast_zeroOrNegativeYears_isEmpty() {
        XCTAssertTrue(DividendMath.incomeForecast(positions: [], eventsBySymbol: [:], years: 0,
                                                  dripEnabled: false, asOf: date(2025, 1, 1)).isEmpty)
        XCTAssertTrue(DividendMath.incomeForecast(positions: [], eventsBySymbol: [:], years: -3,
                                                  dripEnabled: false, asOf: date(2025, 1, 1)).isEmpty)
    }
}
