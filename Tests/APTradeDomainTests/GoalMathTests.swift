import XCTest
@testable import APTradeDomain

final class GoalMathTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func day(_ offset: Int) -> Date { Date(timeIntervalSince1970: 1_700_000_000 + Double(offset) * 86_400) }
    /// An equity curve of `days` points growing at a constant daily rate.
    private func curve(start: Decimal, days: Int, dailyRate: Double) -> [EquityPoint] {
        (0..<days).map { i in
            let factor = Decimal(pow(1 + dailyRate, Double(i)))
            return EquityPoint(date: day(i), value: Money(amount: start * factor))
        }
    }

    func test_progress_isFractionOfTarget() {
        XCTAssertEqual(GoalMath.progress(current: usd("25000"), target: usd("100000")), 0.25, accuracy: 0.0001)
    }

    func test_progress_exceedsOneWhenGoalBeaten() {
        XCTAssertEqual(GoalMath.progress(current: usd("112000"), target: usd("100000")), 1.12, accuracy: 0.0001)
    }

    func test_progress_zeroTargetIsZero() {
        XCTAssertEqual(GoalMath.progress(current: usd("500"), target: Money(amount: 0)), 0)
    }

    func test_annualGrowthRate_needsSufficientHistory() {
        // days: 100 -> span 99 (well below the 180-day floor); days: 250 -> span 249 (well above).
        XCTAssertNil(GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 100, dailyRate: 0.0005)))
        XCTAssertNotNil(GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 250, dailyRate: 0.0005)))
    }

    func test_annualGrowthRate_pinsHistoryFloorBoundary() {
        // curve(days:) spans (days - 1) days. days: 180 -> span 179 (just under the floor);
        // days: 181 -> span 180 (exactly at the floor).
        XCTAssertNil(GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 180, dailyRate: 0.0005)))
        XCTAssertNotNil(GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 181, dailyRate: 0.0005)))
    }

    func test_annualGrowthRate_emptyCurveIsNil() {
        XCTAssertNil(GoalMath.annualGrowthRate(curve: []))
    }

    func test_annualGrowthRate_singlePointCurveIsNil() {
        XCTAssertNil(GoalMath.annualGrowthRate(curve: [EquityPoint(date: day(0), value: usd("50000"))]))
    }

    func test_annualGrowthRate_clampsExtremeGrowth() {
        let rate = GoalMath.annualGrowthRate(curve: curve(start: 10_000, days: 200, dailyRate: 0.02))
        XCTAssertEqual(rate, Decimal(1.0)) // clamped to +100%/yr
    }

    func test_annualGrowthRate_clampsCollapse() {
        let rate = GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 200, dailyRate: -0.01))
        XCTAssertEqual(rate, Decimal(-0.5)) // clamped to -50%/yr
    }

    func test_valueProjection_reachedWhenCurrentMeetsTarget() {
        XCTAssertEqual(GoalMath.valueProjection(current: usd("100000"), target: usd("100000"),
                                                curve: curve(start: 100_000, days: 90, dailyRate: 0.0005)),
                       .reached)
    }

    func test_valueProjection_insufficientHistory() {
        XCTAssertEqual(GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                curve: curve(start: 50_000, days: 10, dailyRate: 0.0005)),
                       .insufficientHistory)
    }

    func test_valueProjection_flatOrShrinkingIsNotOnTrack() {
        // days: 181 -> span 180, clears the new history floor.
        XCTAssertEqual(GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                curve: curve(start: 50_000, days: 181, dailyRate: 0)),
                       .notOnTrack)
        XCTAssertEqual(GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                curve: curve(start: 60_000, days: 181, dailyRate: -0.001)),
                       .notOnTrack)
    }

    func test_valueProjection_returnsYearsForAchievableTarget() {
        // ~0.05%/day compounds to roughly +20%/yr; doubling then takes ~3.8 years.
        // For a constant-daily-rate curve the annualized rate is span-independent
        // (ratio^(365.25/span) collapses to (1+dailyRate)^365.25 regardless of span),
        // so days: 181 (span 180, clearing the new history floor) reproduces the same
        // ~20%/yr rate and ~3.8-year projection as a shorter window would have.
        let projection = GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                  curve: curve(start: 50_000, days: 181, dailyRate: 0.0005))
        guard case let .years(y) = projection else { return XCTFail("expected .years, got \(projection)") }
        XCTAssertGreaterThan(y, 3.0)
        XCTAssertLessThan(y, 5.0)
    }

    func test_valueProjection_beyondThirtyYearsIsBeyondHorizon() {
        // days: 181 -> span 180, clearing the new history floor (see rationale above:
        // the annualized rate for a constant daily rate is span-independent).
        let projection = GoalMath.valueProjection(current: usd("1000"), target: usd("10000000"),
                                                  curve: curve(start: 1_000, days: 181, dailyRate: 0.00005))
        XCTAssertEqual(projection, .beyondHorizon)
    }

    func test_valueProjection_nonPositiveTargetAgreesWithZeroProgress() {
        // `progress` reads a non-positive target as 0% (never started); `valueProjection`
        // must not contradict that by claiming `.reached`.
        XCTAssertEqual(GoalMath.valueProjection(current: usd("5000"), target: Money(amount: 0),
                                                curve: []),
                       .notOnTrack)
        XCTAssertEqual(GoalMath.valueProjection(current: usd("5000"), target: usd("-100"),
                                                curve: []),
                       .notOnTrack)
    }

    func test_progress_negativeCurrentClampsToZero() {
        XCTAssertEqual(GoalMath.progress(current: usd("-500"), target: usd("100000")), 0)
    }

    private func forecast(_ amounts: [String]) -> [DividendMath.ForecastYear] {
        amounts.enumerated().map { .init(yearOffset: $0.offset + 1, income: usd($0.element)) }
    }

    func test_incomeProjection_reachedWhenCurrentMeetsTarget() {
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("5000"), target: usd("5000"),
                                                 forecast: forecast(["5200", "5400"])),
                       .reached)
    }

    func test_incomeProjection_returnsCrossingYear() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("3000"),
                                                   forecast: forecast(["1500", "2200", "3100", "4000"]))
        XCTAssertEqual(projection, .years(3))
    }

    func test_incomeProjection_neverCrossesWithinForecast_isBeyondHorizon() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("99999"),
                                                   forecast: forecast(["1500", "2200", "3100"]))
        XCTAssertEqual(projection, .beyondHorizon)
    }

    func test_incomeProjection_flatForecastBelowTarget_isNotOnTrack() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(["1000", "1000", "1000"]))
        XCTAssertEqual(projection, .notOnTrack)
    }

    func test_incomeProjection_emptyForecast_isInsufficientHistory() {
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("100"), target: usd("5000"), forecast: []),
                       .insufficientHistory)
    }

    // MARK: - Gaps not covered by the brief's test block

    func test_incomeProjection_targetMetInFirstForecastYear() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("1500"),
                                                   forecast: forecast(["1500", "1800"]))
        XCTAssertEqual(projection, .years(1))
    }

    func test_incomeProjection_decliningForecastBelowTarget_isNotOnTrack() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(["900", "800", "700"]))
        XCTAssertEqual(projection, .notOnTrack)
    }

    func test_incomeProjection_nonPositiveTargetAgreesWithZeroProgress() {
        // Mirrors `valueProjection`'s degenerate-target handling: `progress()` reads a
        // non-positive target as 0%, so incomeProjection must not contradict that with `.reached`.
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("5000"), target: Money(amount: 0),
                                                 forecast: forecast(["100"])),
                       .notOnTrack)
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("5000"), target: usd("-100"),
                                                 forecast: forecast(["100"])),
                       .notOnTrack)
    }

    func test_incomeProjection_reachesExactlyAtHorizonBoundary() {
        // Uses GoalMath.horizonYears rather than hardcoding 30, per the milestone constraint.
        let years = Int(GoalMath.horizonYears)
        var amounts = Array(repeating: "1000", count: years - 1)
        amounts.append("5000")
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(amounts))
        XCTAssertEqual(projection, .years(Double(years)))
    }

    func test_incomeProjection_neverReachesWithinHorizonLengthForecast_isBeyondHorizon() {
        let years = Int(GoalMath.horizonYears)
        let amounts = Array(repeating: "1000", count: years)
        let projection = GoalMath.incomeProjection(current: usd("500"), target: usd("99999"),
                                                   forecast: forecast(amounts))
        XCTAssertEqual(projection, .beyondHorizon)
    }

    /// Whole-branch review fix 6: a brand-new user with an income goal but no holdings
    /// gets a forecast of 30 all-zero years (`DividendMath.incomeForecast` always returns
    /// one entry per requested year, even for a portfolio with no dividend payers). Before
    /// the fix, this fell through `last.income > current` (`0 > 0`, false) to `.notOnTrack`
    /// — "Not on track at current rate" — even though nothing is actually off track; there
    /// is simply no data. The symmetric value-goal card reads this identical situation as
    /// `.insufficientHistory` ("needs more history"); the income path must agree.
    func test_incomeProjection_allZeroForecast_isInsufficientHistory_notNotOnTrack() {
        let allZero = Array(repeating: "0", count: 30)
        let projection = GoalMath.incomeProjection(current: usd("0"), target: usd("5000"),
                                                   forecast: forecast(allZero))
        XCTAssertEqual(projection, .insufficientHistory)
    }

    /// A forecast with even one positive year must NOT be treated as "no data" — the
    /// `.notOnTrack` reading is still correct once real (if flat/insufficient) income
    /// exists. Guards against an overly broad fix that swallows the legitimate
    /// `.notOnTrack` case tested above (`test_incomeProjection_flatForecastBelowTarget_isNotOnTrack`).
    func test_incomeProjection_someZeroYearsButSomePositive_stillNotOnTrack() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(["0", "1000", "0"]))
        XCTAssertEqual(projection, .notOnTrack)
    }
}
