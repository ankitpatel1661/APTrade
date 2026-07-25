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
    /// Minimum days of equity-curve history (by date span between the first and last
    /// point, not point count) before a growth rate is trustworthy. A short span
    /// annualizes via a large exponent, so this floor keeps the extrapolation modest
    /// enough that the clamp remains a meaningful sanity bound rather than a rubber stamp.
    ///
    /// This guards short PRICE history, not short ACCOUNT history: the curve passed to
    /// `annualGrowthRate` is the 1-year price window (with a flat pre-inception cash
    /// point), so its span is ~365 days for any portfolio holding any seasoned symbol —
    /// this floor only actually fires for an all-cash portfolio or a symbol whose own
    /// price history is under 180 days.
    public static let minimumHistoryDays = 180
    /// Projections longer than this report `.beyondHorizon`.
    public static let horizonYears = 30.0
    public static let minAnnualGrowth = Decimal(string: "-0.5")!
    public static let maxAnnualGrowth = Decimal(string: "1.0")!

    /// Fraction of the target achieved. May exceed 1. Zero or negative target yields 0.
    /// Result is always finite and non-negative.
    public static func progress(current: Money, target: Money) -> Double {
        guard target.amount > 0 else { return 0 }
        let value = NSDecimalNumber(decimal: current.amount / target.amount).doubleValue
        guard value.isFinite else { return 0 }
        return max(value, 0)
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

    /// Degenerate-input guard shared by `valueProjection`/`incomeProjection`: a
    /// non-positive target reads as 0% progress (mirrors `progress`) and is reported as
    /// `.notOnTrack` rather than a misleading `.reached`; a `current` already at or past
    /// `target` reports `.reached`. Returns `nil` when neither shortcut applies, meaning
    /// the caller must consult its own projection data. Touches only `current`/`target`
    /// (both `Money`), so it applies identically to the value and income paths.
    private static func degenerateOrReachedProjection(current: Money, target: Money) -> GoalProjection? {
        guard target.amount > 0 else { return .notOnTrack }
        guard current.amount < target.amount else { return .reached }
        return nil
    }

    /// When the portfolio's value reaches `target` at its historical growth rate.
    public static func valueProjection(current: Money, target: Money,
                                       curve: [EquityPoint]) -> GoalProjection {
        if let shortCircuit = degenerateOrReachedProjection(current: current, target: target) {
            return shortCircuit
        }
        guard let rate = annualGrowthRate(curve: curve) else { return .insufficientHistory }
        return yearsToTarget(current: current.amount, target: target.amount, annualRate: rate)
    }

    /// When projected annual income reaches `target`, read off the forecast curve.
    /// A forecast that never grows reports `.notOnTrack` rather than a fake ETA. A
    /// forecast that carries no positive income at all (e.g. a brand-new portfolio with
    /// no holdings, whose forecast is all-zero years) reports `.insufficientHistory` —
    /// there is no data to be "off track" against, so `.notOnTrack` would misstate the
    /// situation as a failing rate rather than an absence of one.
    public static func incomeProjection(current: Money, target: Money,
                                        forecast: [DividendMath.ForecastYear]) -> GoalProjection {
        if let shortCircuit = degenerateOrReachedProjection(current: current, target: target) {
            return shortCircuit
        }
        guard let last = forecast.last else { return .insufficientHistory }
        if let crossing = forecast.first(where: { $0.income.amount >= target.amount }) {
            return .years(Double(crossing.yearOffset))
        }
        guard forecast.contains(where: { $0.income.amount > 0 }) else { return .insufficientHistory }
        return last.income.amount > current.amount ? .beyondHorizon : .notOnTrack
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
