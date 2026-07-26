import Foundation
import XCTest
@testable import APTradeDomain

final class DividendMathForecastTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private let day: TimeInterval = 86_400
    /// A fixed "now" for the offset-based growth fixtures below: 2026-07-01T00:00:00Z. The Kotlin
    /// twins use epoch seconds directly; every offset here is relative, so the absolute instant
    /// only has to be deterministic.
    private var growthNow: Date { date(2026, 7, 1) }
    private func growthEvent(_ offsetDays: Double, _ amount: String) -> DividendEvent {
        DividendEvent(symbol: "AAA",
                      exDate: growthNow.addingTimeInterval(-offsetDays * day),
                      amountPerShare: usd(amount))
    }
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

    /// Re-derived under the count-matched-halves / centroid-gap algorithm.
    ///
    /// The fixture steps the amount 10% once a year, so the early half (2021 + 2022, avg
    /// $2.10/8 = $0.2625) and the late half (2023 + 2024, avg $2.541/8 = $0.317625) sit at an
    /// exact 1.21 = 1.10² ratio. The two halves are the same eight calendar slots two years
    /// apart, but three of those eight shifts straddle 2024's leap day, so the centroid gap is
    /// (5 × 730 + 3 × 731) / 8 = 730.375 days = 730.375 / 365.25 = 1.9996577686516084 years, not
    /// a flat 2.0. Expected rate = 1.21^(1/1.9996577686516084) − 1 = 0.1000179431889212.
    ///
    /// GC4 — this assertion is now tight enough (1e-6) to reject all three wrong implementations,
    /// which the previous 0.015 tolerance accepted:
    ///   * old instant-sampled algorithm .......... 0.0997255792150726  (2.9e-4 off)
    ///   * count-matched halves, `span − 1` divisor  0.0718262523213566  (2.8e-2 off)
    ///   * inverted root (pow(gap) not pow(1/gap)) . raw 0.4640044907031498, clamps to 0.25
    func test_growthRate_recoversTenPercentGrowth() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.25")!,
                               perYear: Decimal(string: "0.10")!)
        let r = DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1))
        let delta = abs((r - Decimal(string: "0.1000179431889212")!) as Decimal)
        XCTAssertLessThan(delta, Decimal(string: "0.000001")!,
                          "expected 1.21^(1/1.9996577686516084) - 1 = 0.1000179431889212, got \(r)")
    }

    /// Early half (2021 + 2022) averages $0.145/payment, late half (2023 + 2024) averages
    /// $0.52345 — a 3.61 ratio over a 1.9996577686516084-year centroid gap, i.e. a RAW rate of
    /// 0.9002087265734184 (+90.0%/yr), not the fixture's nominal +90%/yr step compounded to
    /// anything else. Far past `maxDividendGrowth`, so this pins clamp engagement.
    func test_growthRate_clampsHighGrowthToTwentyFivePercent() {
        let events = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                               startAmount: Decimal(string: "0.10")!,
                               perYear: Decimal(string: "0.90")!)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: events, asOf: date(2025, 1, 1)),
                       Decimal(string: "0.25")!)
    }

    /// Early half (2021 + 2022) averages $0.70/payment, late half (2023 + 2024) averages $0.112 —
    /// a 0.16 ratio over a 1.9996577686516084-year centroid gap, i.e. a RAW rate of
    /// −0.6000627224980278 (−60.0%/yr). Far past `minDividendGrowth`, so this pins clamp
    /// engagement at the lower bound.
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

    /// GC4 — the specific wrong implementation this rejects: deriving growth by sampling
    /// `trailingAnnualPerShare` at two instants (`asOf`, and `window[0].exDate + 365.25 days`)
    /// and annualizing over `totalSpanYears - 1`, which is exactly what this file's code did
    /// before the backport.
    ///
    /// Twelve flat $0.25 payments on an exact 91-day cadence with the NEWEST landing exactly on
    /// `asOf`. Four 91-day gaps span 364 days — one day short of the 365-day rolling window — so
    /// the late sample catches FIVE payments ($1.25) while the early sample catches FOUR ($1.00),
    /// and the old code reported 1.25^(1/1.7405886379192332) − 1 = **0.13678039345929127** of
    /// pure ex-date phase for a payer that is, per payment, exactly flat. Count-matched
    /// half-averages read **exactly 0.0**, because no day-count window boundary is computed at
    /// all. Kotlin twin: `aFlatThreeYearHistoryHasZeroGrowthEvenWithExactCadenceAliasing`.
    func test_growthRate_flatHistoryWithExactCadenceAliasing_isZero() {
        let events = (0..<12).map { i in growthEvent(Double(11 - i) * 91, "0.25") }
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: events, asOf: growthNow), 0,
                       "a flat payer must read 0.0 at every ex-date phase, not 0.13678039345929127")
    }

    /// GC4 — every other growth assertion in this file resolves to exactly 0.0, exactly
    /// `minDividendGrowth`, or exactly `maxDividendGrowth`, so the single most delicate line in
    /// the function — the annualization exponent — was uncovered; a reviewer confirmed that
    /// inverting the root entirely left the whole Swift suite green.
    ///
    /// This fixture engineers an EXACT 2.0-year gap between the early- and late-half CENTROIDS
    /// and an exact $0.36 / $0.25 = 1.44 late/early per-payment ratio, so
    /// `1.44^(1/2) − 1 = 0.20` exactly — comfortably inside both clamps, so a wrong exponent
    /// fails loudly instead of silently clamping.
    ///
    /// Late half at 27/118/209/300 days before `asOf` → centroid 163.5 days back. Early half at
    /// 1030.5/939.5/848.5/757.5 days before `asOf` → centroid 894.0 days back. Gap =
    /// 894.0 − 163.5 = 730.5 days = 730.5 / 365.25 = **exactly 2.0 years**.
    ///
    /// A fixture tuned to an exact TOTAL SPAN instead of an exact CENTROID GAP would falsely
    /// appear to validate the absence of the centroid divisor — that is precisely how the Kotlin
    /// round-1 test passed while carrying the round-2 defect. The two mutation assertions below
    /// are what make this fixture provably discriminating rather than merely passing.
    /// Kotlin twin: `dividendGrowthRateAnnualizesAMidRangeRatioPrecisely`.
    func test_growthRate_annualizesAMidRangeRatioPrecisely() {
        let newestOffsetDays = 27.0
        let oldestOffsetDays = 1030.5
        let early = (0..<4).map { i in growthEvent(oldestOffsetDays - Double(i) * 91, "0.25") }
        let late = (0..<4).map { i in growthEvent(newestOffsetDays + Double(3 - i) * 91, "0.36") }

        let rate = DividendMath.dividendGrowthRate(events: early + late, asOf: growthNow)
        XCTAssertEqual(NSDecimalNumber(decimal: rate).doubleValue, 0.20, accuracy: 1e-6)

        // Must land ≥ 1e-3 away from 0.20 under either mutation this fixture exists to catch.
        let ratio = 0.36 / 0.25
        let mutatedInvertedExponent = pow(ratio, 2.0) - 1.0              // pow(gap), not pow(1/gap)
        let totalSpanYears = (oldestOffsetDays - newestOffsetDays) / 365.25
        let mutatedTotalSpanDenominator = pow(ratio, 1.0 / totalSpanYears) - 1.0
        XCTAssertGreaterThan(abs(mutatedInvertedExponent - 0.20), 1e-3,
                             "inverted exponent must be distinguishable: \(mutatedInvertedExponent)")
        XCTAssertGreaterThan(abs(mutatedTotalSpanDenominator - 0.20), 1e-3,
                             "total-span divisor must be distinguishable: \(mutatedTotalSpanDenominator)")
    }

    /// GC4 — the strongest single regression guard for this function, and the property that would
    /// have caught the round-1 (`totalSpanYears - 1` divisor) defect immediately.
    ///
    /// Twelve quarterly payments whose amount grows CONTINUOUSLY at a known true 10%/yr, sampled
    /// every 91 days. The late half is an exact rigid time-translate of the early half (same
    /// spacing, same count), so the late/early average ratio equals
    /// `(1 + trueRate) ^ centroidGapYears` exactly — no averaging bias — and the true rate must
    /// come back to double-precision noise (0.10000000000000009 in an independent check).
    ///
    /// Wrong implementations this rejects, on this fixture:
    ///   * old instant-sampled algorithm ................. 0.1002061561580262 (2.1e-4 off)
    ///   * count-matched halves, `span − 1` divisor ....... 0.0852985017150716 (1.5e-2 off)
    ///   * inverted root (pow(gap) not pow(1/gap)) ........ 0.2373631213240801 (1.4e-1 off)
    /// Kotlin twin: `dividendGrowthRateRecoversAKnownTruePerPaymentCagr`.
    func test_growthRate_recoversAKnownTruePerPaymentCagr() {
        let trueRate = 0.10
        let offsetsDays = (0..<12).map { i in 27.0 + 91.0 * Double(11 - i) } // oldest .. newest
        let oldestOffsetDays = offsetsDays[0]
        let events = offsetsDays.map { offsetDays -> DividendEvent in
            let amount = 0.25 * pow(1.0 + trueRate, (oldestOffsetDays - offsetDays) / 365.25)
            return growthEvent(offsetDays, String(format: "%.12f", amount))
        }

        let rate = DividendMath.dividendGrowthRate(events: events, asOf: growthNow)
        XCTAssertEqual(NSDecimalNumber(decimal: rate).doubleValue, trueRate, accuracy: 1e-6)
    }

    /// GC4 — the wrong implementation this rejects is `hasMeasurableGrowth` written as
    /// `dividendGrowthRate(...) != 0`, which is the obvious shortcut and is wrong in BOTH
    /// directions here: it would call the seasoned flat payer unmeasurable (its measured rate is
    /// a real 0.0) and it agrees only by accident on the young payer. The whole point of the API
    /// is telling "measured a real zero" from "could not measure at all", so both rows below
    /// report `dividendGrowthRate == 0` and must still disagree on measurability.
    func test_hasMeasurableGrowth_separatesMeasuredZeroFromTooLittleHistory() {
        let asOf = date(2025, 1, 1)
        let seasonedFlat = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                                     startAmount: Decimal(string: "0.25")!, perYear: 0)
        let oneYear = quarterly(symbol: "AAA", startYear: 2024, years: 1,
                                startAmount: Decimal(string: "0.25")!, perYear: 0)

        XCTAssertEqual(DividendMath.dividendGrowthRate(events: seasonedFlat, asOf: asOf), 0)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: oneYear, asOf: asOf), 0)

        XCTAssertTrue(DividendMath.hasMeasurableGrowth(events: seasonedFlat, asOf: asOf),
                      "four years of flat history is a MEASURED zero, not an absent measurement")
        XCTAssertFalse(DividendMath.hasMeasurableGrowth(events: oneYear, asOf: asOf))
        XCTAssertFalse(DividendMath.hasMeasurableGrowth(events: [], asOf: asOf))
    }

    /// GC4 — this rejects an `anyPositionHasMeasurableGrowth` that forwards straight to
    /// `hasMeasurableGrowth` without reproducing `incomeForecast`'s inclusion test. Each of the
    /// last three rows has fully measurable growth history and is excluded for a different
    /// reason, so dropping either guard flips a specific row rather than the whole result.
    func test_anyPositionHasMeasurableGrowth_appliesTheForecastInclusionTest() {
        let asOf = date(2025, 1, 1)
        let seasoned = quarterly(symbol: "AAA", startYear: 2021, years: 4,
                                 startAmount: Decimal(string: "0.25")!, perYear: 0)
        // Measurable (2.75-year span) but nothing paid inside the trailing year at `asOf`.
        let lapsed = quarterly(symbol: "LAPSE", startYear: 2021, years: 3,
                               startAmount: Decimal(string: "0.25")!, perYear: 0)
        let young = quarterly(symbol: "YOUNG", startYear: 2024, years: 1,
                              startAmount: Decimal(string: "0.25")!, perYear: 0)
        let events = ["AAA": seasoned, "LAPSE": lapsed, "YOUNG": young]

        XCTAssertTrue(DividendMath.anyPositionHasMeasurableGrowth(
            positions: [position("AAA", shares: "100", price: "50")],
            eventsBySymbol: events, asOf: asOf))

        // Quantity guard: same seasoned payer, zero shares.
        XCTAssertFalse(DividendMath.anyPositionHasMeasurableGrowth(
            positions: [position("AAA", shares: "0", price: "50")],
            eventsBySymbol: events, asOf: asOf))

        // Trailing-income guard: measurable history, but zero trailing income.
        XCTAssertTrue(DividendMath.hasMeasurableGrowth(events: lapsed, asOf: asOf))
        XCTAssertFalse(DividendMath.anyPositionHasMeasurableGrowth(
            positions: [position("LAPSE", shares: "100", price: "50")],
            eventsBySymbol: events, asOf: asOf))

        // Measurability guard: trailing income and shares, but only one year of history.
        XCTAssertFalse(DividendMath.anyPositionHasMeasurableGrowth(
            positions: [position("YOUNG", shares: "100", price: "50")],
            eventsBySymbol: events, asOf: asOf))

        // One qualifying holding among several non-qualifying ones is enough.
        XCTAssertTrue(DividendMath.anyPositionHasMeasurableGrowth(
            positions: [position("YOUNG", shares: "100", price: "50"),
                        position("LAPSE", shares: "100", price: "50"),
                        position("AAA", shares: "100", price: "50")],
            eventsBySymbol: events, asOf: asOf))
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
                                              pricesBySymbol: [:], eventsBySymbol: ["AAA": events],
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
                                              pricesBySymbol: [:], eventsBySymbol: ["AAA": events],
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
        let cash = DividendMath.incomeForecast(positions: positions, pricesBySymbol: prices,
                                               eventsBySymbol: ["AAA": events],
                                               years: 5, dripEnabled: false, asOf: date(2025, 1, 1))
        let drip = DividendMath.incomeForecast(positions: positions, pricesBySymbol: prices,
                                               eventsBySymbol: ["AAA": events],
                                               years: 5, dripEnabled: true, asOf: date(2025, 1, 1))
        XCTAssertEqual(cash.map(\.income), [usd("100"), usd("100"), usd("100"), usd("100"), usd("100")])
        // 100sh @ $1.00/yr = $100 income; reinvested at $100/sh buys 1 share/yr, compounding:
        // 100 -> 101 -> 102.01 -> 103.0301 -> 104.060401
        XCTAssertEqual(drip.map(\.income),
                       [usd("100"), usd("101"), usd("102.01"), usd("103.0301"), usd("104.060401")],
                       "reinvestment must use the quoted $100 price, not the $50 cost basis")
        XCTAssertEqual(drip[0].income, cash[0].income, "year 1 is identical — reinvestment only helps later")
    }

    /// Quarterly events on the 15th of Jan/Apr/Jul/Oct, one explicit per-payment amount per year.
    private func steppedQuarterly(symbol: String, startYear: Int, amounts: [Decimal]) -> [DividendEvent] {
        var out: [DividendEvent] = []
        for (y, amount) in amounts.enumerated() {
            for q in 0..<4 {
                out.append(DividendEvent(symbol: symbol,
                                         exDate: date(startYear + y, 1 + q * 3, 15),
                                         amountPerShare: Money(amount: amount)))
            }
        }
        return out
    }

    /// GC4 / carry-notes §4c backport step 6: on BOTH platforms every DRIP test used growth 0 and
    /// every growth test had DRIP off, so the "reinvestment price grows at the dividend rate"
    /// choice was entirely uncovered — a forecast that reinvested at a FROZEN price would have
    /// passed the whole suite.
    ///
    /// Fixture: $0.05 → $0.10 → $0.15 → $0.25 per quarter across 2021-2024. Early half averages
    /// $0.075/payment, late half $0.20 — a 2.6667 ratio over the 1.9996577686516084-year centroid
    /// gap, raw 0.6331…, so growth clamps to EXACTLY `maxDividendGrowth` = 0.25 and every figure
    /// below is exact `Decimal` arithmetic with no floating-point tolerance. Trailing income is
    /// 4 × $0.25 = $1.00/share; 100 shares at a quoted $100.
    ///
    /// Because the assumed price grows at the same 25% as the dividend, the SHARE path is
    /// identical to the zero-growth DRIP test (100 → 101 → 102.01 → 103.0301) and each year's
    /// income is `shares(previous) × 1.25^(offset − 1)`:
    ///   y1 100 × 1.00       = $100
    ///   y2 101 × 1.25       = $126.25
    ///   y3 102.01 × 1.5625  = $159.390625
    ///   y4 103.0301 × 1.953125 = $201.2306640625
    ///
    /// The wrong implementations this rejects, on this fixture:
    ///   * price frozen while the dividend grows → y3 = $159.78515625, y4 = $202.8522491455078125
    ///   * growth applied to year 1              → y1 = $125
    ///   * DRIP ignored                          → y3 = $156.25, y4 = $195.3125
    func test_forecast_dripWithNonZeroGrowth_reinvestsAtAPriceGrowingWithTheDividend() {
        let events = steppedQuarterly(symbol: "AAA", startYear: 2021,
                                      amounts: [Decimal(string: "0.05")!, Decimal(string: "0.10")!,
                                                Decimal(string: "0.15")!, Decimal(string: "0.25")!])
        let asOf = date(2025, 1, 1)
        XCTAssertEqual(DividendMath.dividendGrowthRate(events: events, asOf: asOf),
                       DividendMath.maxDividendGrowth, "fixture must sit exactly on the upper clamp")
        XCTAssertEqual(DividendMath.trailingAnnualPerShare(events: events, asOf: asOf), usd("1.00"))

        let positions = [position("AAA", shares: "100", price: "50")]
        let prices = ["AAA": usd("100")]
        let drip = DividendMath.incomeForecast(positions: positions, pricesBySymbol: prices,
                                               eventsBySymbol: ["AAA": events],
                                               years: 4, dripEnabled: true, asOf: asOf)
        let cash = DividendMath.incomeForecast(positions: positions, pricesBySymbol: prices,
                                               eventsBySymbol: ["AAA": events],
                                               years: 4, dripEnabled: false, asOf: asOf)

        XCTAssertEqual(drip.map(\.income),
                       [usd("100"), usd("126.25"), usd("159.390625"), usd("201.2306640625")])
        XCTAssertEqual(cash.map(\.income),
                       [usd("100"), usd("125"), usd("156.25"), usd("195.3125")])
    }

    func test_forecast_nonPayerContributesNothing() {
        let out = DividendMath.incomeForecast(positions: [position("NOPAY", shares: "10", price: "20")],
                                              pricesBySymbol: [:], eventsBySymbol: [:],
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
            pricesBySymbol: [:],
            eventsBySymbol: ["AAA": a, "BBB": b],
            years: 1, dripEnabled: false, asOf: date(2025, 1, 1))
        XCTAssertEqual(out[0].income, usd("120")) // 100 + 20
    }

    func test_forecast_zeroOrNegativeYears_isEmpty() {
        XCTAssertTrue(DividendMath.incomeForecast(positions: [], pricesBySymbol: [:], eventsBySymbol: [:], years: 0,
                                                  dripEnabled: false, asOf: date(2025, 1, 1)).isEmpty)
        XCTAssertTrue(DividendMath.incomeForecast(positions: [], pricesBySymbol: [:], eventsBySymbol: [:], years: -3,
                                                  dripEnabled: false, asOf: date(2025, 1, 1)).isEmpty)
    }
}
