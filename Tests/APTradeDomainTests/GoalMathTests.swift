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
        // Short-circuited by degenerateOrReachedProjection before hasMeasurableGrowth is
        // ever consulted — passed `true` only because some value must be supplied.
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("5000"), target: usd("5000"),
                                                 forecast: forecast(["5200", "5400"]),
                                                 hasMeasurableGrowth: true),
                       .reached)
    }

    func test_incomeProjection_returnsCrossingYear() {
        // Crossing is decided before hasMeasurableGrowth is consulted — `true` supplied
        // only because some value must be, not because this test exercises that gate.
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("3000"),
                                                   forecast: forecast(["1500", "2200", "3100", "4000"]),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .years(3))
    }

    func test_incomeProjection_neverCrossesWithinForecast_isBeyondHorizon() {
        // Documents a seasoned, genuinely-growing payer whose forecast never crosses the
        // target — reaching the `last.income > current` fallthrough requires
        // hasMeasurableGrowth: true; `false` would now report `.insufficientHistory`.
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("99999"),
                                                   forecast: forecast(["1500", "2200", "3100"]),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .beyondHorizon)
    }

    func test_incomeProjection_flatForecastBelowTarget_isNotOnTrack() {
        // Documents a seasoned flat payer — growth WAS measured, and it really is zero —
        // so hasMeasurableGrowth: true. The unmeasurable mirror of this exact fixture is
        // covered separately below by the two new discrimination tests.
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(["1000", "1000", "1000"]),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .notOnTrack)
    }

    func test_incomeProjection_emptyForecast_isInsufficientHistory() {
        // Short-circuited by the `forecast.last` guard before hasMeasurableGrowth is
        // consulted — `false` supplied to mirror the Kotlin twin, but the value is inert.
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("100"), target: usd("5000"), forecast: [],
                                                 hasMeasurableGrowth: false),
                       .insufficientHistory)
    }

    // MARK: - Gaps not covered by the brief's test block

    func test_incomeProjection_targetMetInFirstForecastYear() {
        // Crossing decided before hasMeasurableGrowth is consulted — `true` supplied only
        // because some value must be.
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("1500"),
                                                   forecast: forecast(["1500", "1800"]),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .years(1))
    }

    func test_incomeProjection_decliningForecastBelowTarget_isNotOnTrack() {
        // Documents a seasoned payer whose measured growth rate is genuinely negative —
        // hasMeasurableGrowth: true, since `false` would now report `.insufficientHistory`
        // instead of `.notOnTrack`, changing what this test was written to pin.
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(["900", "800", "700"]),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .notOnTrack)
    }

    func test_incomeProjection_nonPositiveTargetAgreesWithZeroProgress() {
        // Mirrors `valueProjection`'s degenerate-target handling: `progress()` reads a
        // non-positive target as 0%, so incomeProjection must not contradict that with `.reached`.
        // Short-circuited by degenerateOrReachedProjection — hasMeasurableGrowth is inert here.
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("5000"), target: Money(amount: 0),
                                                 forecast: forecast(["100"]), hasMeasurableGrowth: true),
                       .notOnTrack)
        XCTAssertEqual(GoalMath.incomeProjection(current: usd("5000"), target: usd("-100"),
                                                 forecast: forecast(["100"]), hasMeasurableGrowth: true),
                       .notOnTrack)
    }

    func test_incomeProjection_reachesExactlyAtHorizonBoundary() {
        // Uses GoalMath.horizonYears rather than hardcoding 30, per the milestone constraint.
        // Crossing decided before hasMeasurableGrowth is consulted — `true` supplied only
        // because some value must be. Exactly horizonYears is INSIDE the horizon (`>`, not
        // `>=`), so this must still resolve to a concrete `.years(horizonYears)`, not `.beyondHorizon`.
        let years = Int(GoalMath.horizonYears)
        var amounts = Array(repeating: "1000", count: years - 1)
        amounts.append("5000")
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(amounts),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .years(Double(years)))
    }

    func test_incomeProjection_neverReachesWithinHorizonLengthForecast_isBeyondHorizon() {
        // Documents a seasoned, flat-but-already-above-`current` payer that never crosses
        // `target` — reaching the `last.income > current` fallthrough requires
        // hasMeasurableGrowth: true.
        let years = Int(GoalMath.horizonYears)
        let amounts = Array(repeating: "1000", count: years)
        let projection = GoalMath.incomeProjection(current: usd("500"), target: usd("99999"),
                                                   forecast: forecast(amounts),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .beyondHorizon)
    }

    /// Whole-branch review fix 6: a brand-new user with an income goal but no holdings
    /// gets a forecast of 30 all-zero years (`DividendMath.incomeForecast` always returns
    /// one entry per requested year, even for a portfolio with no dividend payers). Before
    /// the fix, this fell through `last.income > current` (`0 > 0`, false) to `.notOnTrack`
    /// — "Not on track at current rate" — even though nothing is actually off track; there
    /// is simply no data. The symmetric value-goal card reads this identical situation as
    /// `.insufficientHistory` ("needs more history"); the income path must agree.
    ///
    /// `hasMeasurableGrowth: true` is deliberate (mirrors the Kotlin twin's Finding B): with
    /// `false`, the new `!hasMeasurableGrowth` guard would ALSO return `.insufficientHistory`
    /// for this exact input, so the all-zero guard this test claims to pin could be deleted
    /// entirely and the assertion would still pass. `true` keeps that second guard out of
    /// the way, so a RED result here can only mean the all-zero guard itself is gone.
    func test_incomeProjection_allZeroForecast_isInsufficientHistory_notNotOnTrack() {
        let allZero = Array(repeating: "0", count: 30)
        let projection = GoalMath.incomeProjection(current: usd("0"), target: usd("5000"),
                                                   forecast: forecast(allZero),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .insufficientHistory)
    }

    /// A forecast with even one positive year must NOT be treated as "no data" — the
    /// `.notOnTrack` reading is still correct once real (if flat/insufficient) income
    /// exists. Guards against an overly broad fix that swallows the legitimate
    /// `.notOnTrack` case tested above (`test_incomeProjection_flatForecastBelowTarget_isNotOnTrack`).
    /// `hasMeasurableGrowth: true` documents a seasoned payer with real (if partly zero)
    /// history — `false` would now report `.insufficientHistory` instead.
    func test_incomeProjection_someZeroYearsButSomePositive_stillNotOnTrack() {
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(["0", "1000", "0"]),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .notOnTrack)
    }

    // MARK: - hasMeasurableGrowth (§4f.2 fix)

    /// DISCRIMINATION. A flat-but-positive forecast (income never changes, which is
    /// EXACTLY what a forecast built entirely from unmeasurable-growth symbols looks like,
    /// since `dividendGrowthRate` defaults such symbols to 0%) with `hasMeasurableGrowth:
    /// false` must report `.insufficientHistory`, not `.notOnTrack` — the identical
    /// situation `valueProjection` already reports as "needs more history" for an
    /// equivalently young account. The WRONG implementation this rejects is
    /// `incomeProjection` ignoring `hasMeasurableGrowth` entirely (the pre-fix signature) —
    /// see the task report for the RED/GREEN transcript.
    func test_incomeProjection_flatForecastWithUnmeasurableGrowth_isInsufficientHistory_notNotOnTrack() {
        let projection = GoalMath.incomeProjection(current: usd("500"), target: usd("6000"),
                                                   forecast: forecast(["500", "500", "500"]),
                                                   hasMeasurableGrowth: false)
        XCTAssertEqual(projection, .insufficientHistory)
    }

    /// The mirror case: the SAME flat forecast with `hasMeasurableGrowth: true` (a
    /// genuinely flat, SEASONED payer — growth really was measured, and it really is zero)
    /// must still report `.notOnTrack`. Without this mirror, hardwiring
    /// `.insufficientHistory` for any flat forecast would also pass the test above — this
    /// one proves the guard reads `hasMeasurableGrowth`, not merely "is the forecast flat."
    func test_incomeProjection_flatForecastWithMeasuredGrowth_stillNotOnTrack() {
        let projection = GoalMath.incomeProjection(current: usd("500"), target: usd("6000"),
                                                   forecast: forecast(["500", "500", "500"]),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .notOnTrack)
    }

    /// Residual review, Finding C. Unlike the flat forecast above, this one is genuinely
    /// INCREASING — mirroring a DRIP-on young payer, where reinvestment compounds shares
    /// (and so income) even though the measured per-share growth rate is exactly 0%
    /// ($125 -> $130.30 -> $159.01). `hasMeasurableGrowth: false` must still win over the
    /// rising curve and report `.insufficientHistory` — NOT `.beyondHorizon`, which is what
    /// the `last.income > current` fallthrough would report if this guard were absent (see
    /// `test_incomeProjection_neverCrossesWithinForecast_isBeyondHorizon` above for that
    /// exact fallthrough with `hasMeasurableGrowth: true`). This is the case that proves
    /// the guard is a NEW decision surface, not a strict refinement: it FLIPS an
    /// otherwise-`.beyondHorizon` result for a DRIP-on young payer, rather than merely
    /// confirming one — a prior KDoc calling this shape "provably flat" was wrong.
    func test_incomeProjection_dripCompoundedForecastWithUnmeasurableGrowth_isInsufficientHistory_notBeyondHorizon() {
        let projection = GoalMath.incomeProjection(current: usd("125"), target: usd("999999"),
                                                   forecast: forecast(["125", "130.30", "159.01"]),
                                                   hasMeasurableGrowth: false)
        XCTAssertEqual(projection, .insufficientHistory)
    }

    /// PIN THE BRANCH ORDER (review Important 1). The crossing check (step 3) MUST run
    /// BEFORE the `hasMeasurableGrowth` guard (step 5) — a young DRIP payer whose
    /// reinvested shares alone push income past a modest, in-horizon target is validly
    /// "on track" and must report the concrete crossing year, even though the same
    /// forecast's per-share growth rate could not be measured. Same DRIP-compounded
    /// fixture as the test above ($125 -> $130.30 -> $159.01), but with `target: 150` —
    /// crossed at year 3 — instead of an uncrossable `999999`, so the crossing check
    /// actually has something to find. Rejects hoisting `guard hasMeasurableGrowth ...`
    /// to sit immediately after the `forecast.last` guard (i.e. above the crossing check):
    /// under that hoist this test alone goes RED while the rest of the 32-test file stays
    /// green, because it is the only existing test that combines `hasMeasurableGrowth:
    /// false` with a within-horizon crossing.
    func test_incomeProjection_dripCompoundedWithUnmeasurableGrowth_crossingWithinHorizonStillWins() {
        let projection = GoalMath.incomeProjection(current: usd("125"), target: usd("150"),
                                                   forecast: forecast(["125", "130.30", "159.01"]),
                                                   hasMeasurableGrowth: false)
        XCTAssertEqual(projection, .years(3))
    }

    /// §4a.3 horizon clamp. A crossing year strictly BEYOND `horizonYears` must report
    /// `.beyondHorizon`, not a concrete `.years(35)` — Swift previously returned the raw,
    /// unbounded crossing year here, disagreeing with `valueProjection`'s horizon-bounded
    /// twin for the identical span. Derived entirely from `GoalMath.horizonYears`, never a
    /// hardcoded 30, so this cannot drift from `test_incomeProjection_reachesExactlyAtHorizonBoundary`
    /// above (which pins the other side of the same `>` comparison: exactly `horizonYears`
    /// is INSIDE the horizon).
    func test_incomeProjection_crossingBeyondHorizon_isBeyondHorizonNotAConcreteYear() {
        let years = Int(GoalMath.horizonYears)
        var amounts = Array(repeating: "1000", count: years + 4)
        amounts.append("5000")
        let projection = GoalMath.incomeProjection(current: usd("1000"), target: usd("5000"),
                                                   forecast: forecast(amounts),
                                                   hasMeasurableGrowth: true)
        XCTAssertEqual(projection, .beyondHorizon)
    }
}
