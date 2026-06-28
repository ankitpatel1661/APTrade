import Foundation

/// Pure return/risk statistics over a portfolio's daily equity curve. Deterministic
/// transforms with no I/O. Works in `Double` because the statistics need square roots
/// and regression that `Decimal` can't express; callers convert money to `Double` once.
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

    /// Cumulative (time-weighted) return: last / first − 1. Degenerate input → 0.
    public static func totalReturn(_ values: [Double]) -> Double {
        guard values.count > 1, let first = values.first, let last = values.last, first > 0
        else { return 0 }
        return last / first - 1
    }

    /// Compound annual growth rate over the series' span. Degenerate input → 0.
    public static func annualizedReturn(_ values: [Double], periodsPerYear: Double = 252) -> Double {
        let periods = Double(values.count - 1)
        guard periods > 0, let first = values.first, first > 0 else { return 0 }
        return pow(1 + totalReturn(values), periodsPerYear / periods) - 1
    }

    /// Sample standard deviation of `returns`, annualized by √periodsPerYear. <2 → 0.
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

    /// Risk-adjusted excess return. `nil` when volatility is zero (undefined).
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

    /// CAPM alpha: actual annualized return minus what beta predicts from the benchmark.
    public static func alpha(annualizedReturn: Double, beta: Double,
                             benchmarkAnnualizedReturn: Double, riskFree: Double) -> Double {
        annualizedReturn - (riskFree + beta * (benchmarkAnnualizedReturn - riskFree))
    }
}

/// The full set of computed statistics for one window. `beta`/`alpha` are nil when no
/// benchmark was available.
public struct PerformanceMetrics: Equatable, Sendable {
    public let totalReturn: Double
    public let annualizedReturn: Double
    public let volatility: Double
    public let maxDrawdown: Double
    public let sharpe: Double?
    public let beta: Double?
    public let alpha: Double?

    public init(totalReturn: Double, annualizedReturn: Double, volatility: Double,
                maxDrawdown: Double, sharpe: Double?, beta: Double?, alpha: Double?) {
        self.totalReturn = totalReturn
        self.annualizedReturn = annualizedReturn
        self.volatility = volatility
        self.maxDrawdown = maxDrawdown
        self.sharpe = sharpe
        self.beta = beta
        self.alpha = alpha
    }
}
