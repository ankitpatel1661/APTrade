import Foundation

/// Pure return/risk statistics over a portfolio's daily equity curve. Deterministic
/// transforms with no I/O. Works in `Double` because the statistics need square roots
/// and regression that `Decimal` can't express; callers convert money to `Double` once.
///
/// ⚠️ Every return-like value here is a FRACTION (`0.5` means 50%), never percentage
/// points. They flow into `PerformanceMetrics`' `…Fraction` fields unchanged — see that
/// type for why the scale is spelled out in the names.
public enum RiskMetrics {
    /// Day-over-day simple returns: rₜ = vₜ / vₜ₋₁ − 1. Skips non-positive predecessors
    /// to avoid divide-by-zero. Fewer than 2 values → [].
    public static func dailyReturns(_ values: [Double]) -> [Double] {
        guard values.count > 1 else { return [] }
        var out: [Double] = []
        out.reserveCapacity(values.count - 1)
        for i in 1..<values.count {
            let prev = values[i - 1]
            guard prev > 0 else { continue }
            out.append(values[i] / prev - 1)
        }
        return out
    }

    /// Cumulative (time-weighted) return as a fraction: last / first − 1 (`0.5` = 50%).
    /// Degenerate input → 0.
    public static func totalReturn(_ values: [Double]) -> Double {
        guard values.count > 1, let first = values.first, let last = values.last, first > 0
        else { return 0 }
        return last / first - 1
    }

    /// Compound annual growth rate over the series' span, as a fraction (`0.5` = 50%).
    /// Degenerate input → 0.
    public static func annualizedReturn(_ values: [Double], periodsPerYear: Double = 252) -> Double {
        let periods = Double(values.count - 1)
        guard periods > 0, let first = values.first, first > 0 else { return 0 }
        return pow(1 + totalReturn(values), periodsPerYear / periods) - 1
    }

    /// Sample standard deviation of `returns`, annualized by √periodsPerYear, as a fraction
    /// (`0.2` = 20%). <2 → 0.
    public static func annualizedVolatility(_ returns: [Double], periodsPerYear: Double = 252) -> Double {
        guard returns.count > 1 else { return 0 }
        let mean = returns.reduce(0, +) / Double(returns.count)
        let variance = returns.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(returns.count - 1)
        return sqrt(variance) * sqrt(periodsPerYear)
    }

    /// Worst peak-to-trough decline as a negative fraction (−0.25 = −25%). No decline → 0.
    public static func maxDrawdown(_ values: [Double]) -> Double {
        guard values.count > 1 else { return 0 }
        var peak = values[0]
        var worst = 0.0
        for v in values {
            if v > peak { peak = v }
            guard peak > 0 else { continue }
            let dd = v / peak - 1
            if dd < worst { worst = dd }
        }
        return worst
    }

    /// Risk-adjusted excess return — a dimensionless RATIO, not a percentage. `nil` when
    /// volatility is zero (undefined).
    public static func sharpe(annualizedReturn: Double, annualizedVolatility: Double,
                              riskFree: Double) -> Double? {
        guard annualizedVolatility > 0 else { return nil }
        return (annualizedReturn - riskFree) / annualizedVolatility
    }

    /// Slope of portfolio returns regressed on benchmark returns (cov / var). `nil` when
    /// lengths differ, the series is too short, or the benchmark has zero variance.
    public static func beta(portfolioReturns p: [Double], benchmarkReturns b: [Double]) -> Double? {
        guard p.count == b.count, p.count > 1 else { return nil }
        let n = Double(p.count)
        let mp = p.reduce(0, +) / n
        let mb = b.reduce(0, +) / n
        var cov = 0.0, varb = 0.0
        for i in p.indices {
            cov += (p[i] - mp) * (b[i] - mb)
            varb += (b[i] - mb) * (b[i] - mb)
        }
        guard varb > 0 else { return nil }
        return cov / varb
    }

    /// CAPM alpha as a fraction (`0.01` = 1%): actual annualized return minus what beta
    /// predicts from the benchmark.
    public static func alpha(annualizedReturn: Double, beta: Double,
                             benchmarkAnnualizedReturn: Double, riskFree: Double) -> Double {
        annualizedReturn - (riskFree + beta * (benchmarkAnnualizedReturn - riskFree))
    }
}

/// The full set of computed statistics for one window. `beta`/`alphaFraction` are nil when
/// no benchmark was available.
///
/// ⚠️ Scale is in the names, and it is load-bearing. Every `…Fraction` field is a FRACTION,
/// never percentage points: a display layer MUST multiply by 100 before appending "%".
/// Manual UAT caught a Home tile that formatted `totalReturn` straight into "%" and so
/// rendered every return 100× too small; a call site that now forgets the `× 100` reads
/// wrong at a glance (`\(m.totalReturnFraction)%`) instead of failing silently.
///
/// `sharpe` and `beta` carry no scale suffix on purpose — they are dimensionless RATIOS,
/// not percentages, and must never go through a percent formatter.
///
/// Not `Codable`, and deliberately so: nothing persists this type, which is what makes
/// renaming its fields safe. Do not add a `Codable` conformance without re-checking that.
public struct PerformanceMetrics: Equatable, Sendable {
    /// Cumulative (time-weighted) return as a fraction — `0.5` means 50%.
    public let totalReturnFraction: Double
    /// Compound annual growth rate as a fraction — `0.5` means 50%.
    public let annualizedReturnFraction: Double
    /// Annualized standard deviation of daily returns as a fraction — `0.2` means 20%.
    public let volatilityFraction: Double
    /// Worst peak-to-trough decline as a NEGATIVE fraction — `-0.25` means −25%.
    public let maxDrawdownFraction: Double
    /// Dimensionless risk-adjusted excess return. NOT a percentage — never percent-format it.
    public let sharpe: Double?
    /// Dimensionless regression slope vs the benchmark. NOT a percentage — never percent-format it.
    public let beta: Double?
    /// CAPM alpha as a fraction — `0.01` means 1%. nil when no benchmark was available.
    public let alphaFraction: Double?

    public init(totalReturnFraction: Double, annualizedReturnFraction: Double,
                volatilityFraction: Double, maxDrawdownFraction: Double,
                sharpe: Double?, beta: Double?, alphaFraction: Double?) {
        self.totalReturnFraction = totalReturnFraction
        self.annualizedReturnFraction = annualizedReturnFraction
        self.volatilityFraction = volatilityFraction
        self.maxDrawdownFraction = maxDrawdownFraction
        self.sharpe = sharpe
        self.beta = beta
        self.alphaFraction = alphaFraction
    }
}
