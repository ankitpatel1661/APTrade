import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class PortfolioViewModel {
    private let fetchPortfolio: FetchPortfolioUseCase
    private let fetchQuotes: FetchQuotesUseCase
    private let resetPortfolio: ResetPortfolioUseCase
    private let recordSnapshot: RecordPortfolioSnapshotUseCase
    private let fetchHistory: FetchPortfolioHistoryUseCase
    private let clearHistoryUseCase: ClearPortfolioHistoryUseCase
    private let fetchPerformance: FetchPortfolioPerformanceUseCase

    private(set) var portfolio: Portfolio
    private(set) var quotes: [String: Quote] = [:]
    private(set) var history: [PricePoint] = []
    /// Reconstructed value / P&L curve over `timeframe`, drawn by the chart.
    private(set) var performance: [PortfolioPerformancePoint] = []
    var timeframe: Timeframe = .oneMonth
    private(set) var isLive = false
    var isRefreshing = false
    private(set) var isLoadingPerformance = false

    init(fetchPortfolio: FetchPortfolioUseCase,
         fetchQuotes: FetchQuotesUseCase,
         resetPortfolio: ResetPortfolioUseCase,
         recordSnapshot: RecordPortfolioSnapshotUseCase,
         fetchHistory: FetchPortfolioHistoryUseCase,
         clearHistory: ClearPortfolioHistoryUseCase,
         fetchPerformance: FetchPortfolioPerformanceUseCase) {
        self.fetchPortfolio = fetchPortfolio
        self.fetchQuotes = fetchQuotes
        self.resetPortfolio = resetPortfolio
        self.recordSnapshot = recordSnapshot
        self.fetchHistory = fetchHistory
        self.clearHistoryUseCase = clearHistory
        self.fetchPerformance = fetchPerformance
        self.portfolio = fetchPortfolio()
        self.history = fetchHistory()
    }

    /// Holdings ordered by current market value, largest first.
    var holdings: [Position] {
        portfolio.positions.sorted { lhs, rhs in
            let lv = lhs.marketValue(at: quotes[lhs.asset.symbol]?.price ?? lhs.averageCost).amount
            let rv = rhs.marketValue(at: quotes[rhs.asset.symbol]?.price ?? rhs.averageCost).amount
            return lv > rv
        }
    }

    var valuation: PortfolioValuation { portfolio.valuation(quotes: quotes) }

    func quote(for symbol: String) -> Quote? { quotes[symbol] }

    /// Re-reads the persisted portfolio (e.g. after a trade made elsewhere).
    func reload() { portfolio = fetchPortfolio() }

    func onAppear() async {
        reload()
        await refresh()
        await loadPerformance()
    }

    /// Rebuilds the value/P&L curve for the current timeframe from historical prices.
    func loadPerformance() async {
        isLoadingPerformance = true
        defer { isLoadingPerformance = false }
        performance = await fetchPerformance(timeframe: timeframe)
    }

    func setTimeframe(_ newValue: Timeframe) async {
        guard newValue != timeframe else { return }
        timeframe = newValue
        await loadPerformance()
    }

    func refresh(showIndicator: Bool = true) async {
        let symbols = portfolio.positions.map { $0.asset.symbol }
        guard !symbols.isEmpty else { return }
        if showIndicator { isRefreshing = true }
        defer { if showIndicator { isRefreshing = false } }
        let results = await fetchQuotes(symbols: symbols)
        var fresh = quotes
        for (symbol, result) in results {
            if case .success(let q) = result { fresh[symbol] = q }
        }
        quotes = fresh
        recordSnapshot(totalValue: valuation.totalValue)
        history = fetchHistory()
    }

    /// Polls held quotes on the standard 15s cadence until cancelled on disappear.
    func runLiveUpdates() async {
        guard !portfolio.positions.isEmpty else { return }
        isLive = true
        defer { isLive = false }
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(15))
            if Task.isCancelled { break }
            await refresh(showIndicator: false)
        }
    }

    func reset() {
        portfolio = resetPortfolio()
        quotes = [:]
        clearHistoryUseCase()
        history = []
        performance = []
    }
}
