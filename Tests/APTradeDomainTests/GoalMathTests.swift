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

    func test_annualGrowthRate_needsThirtyDaysOfHistory() {
        XCTAssertNil(GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 20, dailyRate: 0.0005)))
        XCTAssertNotNil(GoalMath.annualGrowthRate(curve: curve(start: 100_000, days: 90, dailyRate: 0.0005)))
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
        XCTAssertEqual(GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                curve: curve(start: 50_000, days: 90, dailyRate: 0)),
                       .notOnTrack)
        XCTAssertEqual(GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                curve: curve(start: 60_000, days: 90, dailyRate: -0.001)),
                       .notOnTrack)
    }

    func test_valueProjection_returnsYearsForAchievableTarget() {
        // ~0.05%/day compounds to roughly +20%/yr; doubling then takes ~3.8 years.
        let projection = GoalMath.valueProjection(current: usd("50000"), target: usd("100000"),
                                                  curve: curve(start: 50_000, days: 120, dailyRate: 0.0005))
        guard case let .years(y) = projection else { return XCTFail("expected .years, got \(projection)") }
        XCTAssertGreaterThan(y, 3.0)
        XCTAssertLessThan(y, 5.0)
    }

    func test_valueProjection_beyondThirtyYearsIsBeyondHorizon() {
        let projection = GoalMath.valueProjection(current: usd("1000"), target: usd("10000000"),
                                                  curve: curve(start: 1_000, days: 90, dailyRate: 0.00005))
        XCTAssertEqual(projection, .beyondHorizon)
    }
}
