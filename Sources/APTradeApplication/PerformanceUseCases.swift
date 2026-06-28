import Foundation
import APTradeDomain

/// Everything the Performance section renders for one (timeframe, benchmark) selection.
public struct PerformanceReport: Equatable, Sendable {
    public let metrics: PerformanceMetrics
    public let equityCurve: [EquityPoint]
    public let benchmarkCurve: [PricePoint]   // empty when the benchmark is unavailable
    public let benchmarkSymbol: String
    public let concentration: Double
    public let effectiveHoldings: Double
    public let warnings: [ConcentrationWarning]
    public let isEmpty: Bool

    public init(metrics: PerformanceMetrics, equityCurve: [EquityPoint],
                benchmarkCurve: [PricePoint], benchmarkSymbol: String,
                concentration: Double, effectiveHoldings: Double,
                warnings: [ConcentrationWarning], isEmpty: Bool) {
        self.metrics = metrics
        self.equityCurve = equityCurve
        self.benchmarkCurve = benchmarkCurve
        self.benchmarkSymbol = benchmarkSymbol
        self.concentration = concentration
        self.effectiveHoldings = effectiveHoldings
        self.warnings = warnings
        self.isEmpty = isEmpty
    }

    public static let empty = PerformanceReport(
        metrics: PerformanceMetrics(totalReturn: 0, annualizedReturn: 0, volatility: 0,
                                    maxDrawdown: 0, sharpe: nil, beta: nil, alpha: nil),
        equityCurve: [], benchmarkCurve: [], benchmarkSymbol: "",
        concentration: 0, effectiveHoldings: 0, warnings: [], isEmpty: true)
}

/// Loads the portfolio, fetches holding + benchmark histories in parallel, builds the
/// trade-aware equity curve, and computes returns / risk / benchmark / concentration.
public struct ComputePerformanceMetricsUseCase: Sendable {
    private let repository: MarketDataRepository
    private let store: PortfolioStore
    private let riskFreeRate: Double

    public init(repository: MarketDataRepository, store: PortfolioStore,
                riskFreeRate: Double = 0.04) {
        self.repository = repository
        self.store = store
        self.riskFreeRate = riskFreeRate
    }

    public func callAsFunction(timeframe: Timeframe, benchmark: String) async -> PerformanceReport {
        let portfolio = store.load()
        guard !portfolio.positions.isEmpty else { return .empty }

        // Holding histories, in parallel.
        var histories: [String: [PricePoint]] = [:]
        await withTaskGroup(of: (String, [PricePoint]).self) { group in
            for position in portfolio.positions {
                let symbol = position.asset.symbol
                let repository = repository
                group.addTask {
                    let points = (try? await repository.history(for: symbol, timeframe: timeframe)) ?? []
                    return (symbol, points)
                }
            }
            for await (symbol, points) in group { histories[symbol] = points }
        }

        let equity = portfolio.equitySeries(histories: histories)
        guard equity.count > 1 else { return .empty }

        let values = equity.map { ($0.value.amount as NSDecimalNumber).doubleValue }
        let returns = RiskMetrics.dailyReturns(values)
        let annual = RiskMetrics.annualizedReturn(values)
        let vol = RiskMetrics.annualizedVolatility(returns)

        // Benchmark (optional — failure must not sink the report).
        let benchmarkCurve = (try? await repository.history(for: benchmark, timeframe: timeframe)) ?? []
        var beta: Double?
        var alpha: Double?
        if benchmarkCurve.count > 1 {
            let bValues = benchmarkCurve.map { ($0.close.amount as NSDecimalNumber).doubleValue }
            let bReturns = RiskMetrics.dailyReturns(bValues)
            let n = min(returns.count, bReturns.count)
            if n > 1, let b = RiskMetrics.beta(portfolioReturns: Array(returns.suffix(n)),
                                               benchmarkReturns: Array(bReturns.suffix(n))) {
                beta = b
                alpha = RiskMetrics.alpha(annualizedReturn: annual, beta: b,
                                          benchmarkAnnualizedReturn: RiskMetrics.annualizedReturn(bValues),
                                          riskFree: riskFreeRate)
            }
        }

        let metrics = PerformanceMetrics(
            totalReturn: RiskMetrics.totalReturn(values),
            annualizedReturn: annual,
            volatility: vol,
            maxDrawdown: RiskMetrics.maxDrawdown(values),
            sharpe: RiskMetrics.sharpe(annualizedReturn: annual, annualizedVolatility: vol, riskFree: riskFreeRate),
            beta: beta, alpha: alpha)

        let weights = currentWeights(portfolio: portfolio, histories: histories)
        let rawWeights = weights.map(\.weight)

        return PerformanceReport(
            metrics: metrics,
            equityCurve: equity,
            benchmarkCurve: benchmarkCurve.count > 1 ? benchmarkCurve : [],
            benchmarkSymbol: benchmark,
            concentration: Diversification.concentration(rawWeights),
            effectiveHoldings: Diversification.effectiveHoldings(rawWeights),
            warnings: Diversification.warnings(weights),
            isEmpty: false)
    }

    /// Current portfolio weights from each symbol's latest available close (cost-basis
    /// fallback). Used for concentration analysis.
    private func currentWeights(portfolio: Portfolio,
                                histories: [String: [PricePoint]]) -> [HoldingWeight] {
        var rows: [(label: String, kind: String, value: Double)] = []
        for position in portfolio.positions {
            let q = (position.quantity.amount as NSDecimalNumber).doubleValue
            let price = histories[position.asset.symbol]?.last?.close.amount ?? position.averageCost.amount
            rows.append((position.asset.name, position.asset.kind.rawValue,
                         (price as NSDecimalNumber).doubleValue * q))
        }
        let total = rows.reduce(0) { $0 + $1.value }
        guard total > 0 else { return [] }
        return rows.map { HoldingWeight(label: $0.label, kind: $0.kind, weight: $0.value / total) }
    }
}
