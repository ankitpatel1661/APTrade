import Foundation

/// How a goal is tracking. Never fabricates an ETA it cannot support.
public enum GoalProjection: Equatable, Sendable {
    case reached
    case years(Double)
    case beyondHorizon
    case notOnTrack
    case insufficientHistory
}

/// Progress and honest time-to-target math for portfolio goals. Pure.
public enum GoalMath {
    /// Minimum equity-curve points before a growth rate is trustworthy.
    public static let minimumHistoryDays = 30
    /// Projections longer than this report `.beyondHorizon`.
    public static let horizonYears = 30.0
    public static let minAnnualGrowth = Decimal(-0.5)
    public static let maxAnnualGrowth = Decimal(1.0)

    /// Fraction of the target achieved. May exceed 1. Zero target yields 0.
    public static func progress(current: Money, target: Money) -> Double {
        guard target.amount > 0 else { return 0 }
        return NSDecimalNumber(decimal: current.amount / target.amount).doubleValue
    }

    /// Annualized growth of the equity curve, clamped to
    /// `minAnnualGrowth ... maxAnnualGrowth`. `nil` when history is too short.
    public static func annualGrowthRate(curve: [EquityPoint]) -> Decimal? {
        let sorted = curve.sorted { $0.date < $1.date }
        guard let first = sorted.first, let last = sorted.last else { return nil }
        let days = last.date.timeIntervalSince(first.date) / 86_400
        guard days >= Double(minimumHistoryDays) else { return nil }
        guard first.value.amount > 0, last.value.amount > 0 else { return nil }

        let ratio = NSDecimalNumber(decimal: last.value.amount / first.value.amount).doubleValue
        guard ratio > 0 else { return nil }
        let rate = pow(ratio, 365.25 / days) - 1.0
        guard rate.isFinite else { return nil }
        return min(max(Decimal(rate), minAnnualGrowth), maxAnnualGrowth)
    }

    /// When the portfolio's value reaches `target` at its historical growth rate.
    public static func valueProjection(current: Money, target: Money,
                                       curve: [EquityPoint]) -> GoalProjection {
        guard current.amount < target.amount else { return .reached }
        guard let rate = annualGrowthRate(curve: curve) else { return .insufficientHistory }
        return yearsToTarget(current: current.amount, target: target.amount, annualRate: rate)
    }

    /// Solves `current * (1 + rate)^t >= target`, honestly.
    static func yearsToTarget(current: Decimal, target: Decimal, annualRate: Decimal) -> GoalProjection {
        guard current > 0 else { return .notOnTrack }
        let rate = NSDecimalNumber(decimal: annualRate).doubleValue
        guard rate > 0 else { return .notOnTrack }
        let ratio = NSDecimalNumber(decimal: target / current).doubleValue
        guard ratio > 0 else { return .notOnTrack }
        let years = log(ratio) / log(1 + rate)
        guard years.isFinite, years > 0 else { return .notOnTrack }
        return years > horizonYears ? .beyondHorizon : .years(years)
    }
}
