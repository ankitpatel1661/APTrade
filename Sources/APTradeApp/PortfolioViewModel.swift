import Foundation
import APTradeApplication
import APTradeDomain

/// One slice of the allocation breakdown — a holding or an asset class — with its share
/// of total holdings value.
struct AllocationSlice: Identifiable, Equatable {
    let id: String
    let label: String
    let value: Double
    let fraction: Double

    static func label(for kind: AssetKind) -> String {
        switch kind {
        case .stock: return "Stocks"
        case .etf: return "ETFs"
        case .crypto: return "Crypto"
        }
    }
}

/// A holding annotated with today's move, for the "top movers" strip.
struct PortfolioMover: Identifiable {
    var id: String { position.asset.symbol }
    let position: Position
    let quote: Quote
    let dayChange: Money
    let dayChangePercent: Double
}

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

    // MARK: - Analytics

    private func marketValue(_ position: Position) -> Decimal {
        (quotes[position.asset.symbol]?.price.amount ?? position.averageCost.amount) * position.quantity.amount
    }

    private var holdingsValueDouble: Double {
        (valuation.holdingsValue.amount as NSDecimalNumber).doubleValue
    }

    /// Each holding's share of total holdings value, largest first.
    var allocationByHolding: [AllocationSlice] {
        let total = holdingsValueDouble
        return holdings.map { position in
            let value = (marketValue(position) as NSDecimalNumber).doubleValue
            return AllocationSlice(id: position.asset.symbol, label: position.asset.symbol,
                                   value: value, fraction: total == 0 ? 0 : value / total)
        }
    }

    /// Holdings value grouped by asset class (Stocks / ETFs / Crypto).
    var allocationByKind: [AllocationSlice] {
        let total = holdingsValueDouble
        var sums: [AssetKind: Double] = [:]
        for position in holdings {
            sums[position.asset.kind, default: 0] += (marketValue(position) as NSDecimalNumber).doubleValue
        }
        return [AssetKind.stock, .etf, .crypto].compactMap { kind in
            guard let value = sums[kind], value > 0 else { return nil }
            return AllocationSlice(id: kind.rawValue, label: AllocationSlice.label(for: kind),
                                   value: value, fraction: total == 0 ? 0 : value / total)
        }
    }

    /// Holdings ranked by the magnitude of today's move (gainers and losers).
    var topMovers: [PortfolioMover] {
        holdings.compactMap { position in
            guard let quote = quotes[position.asset.symbol] else { return nil }
            let dayChange = Money(amount: quote.change.amount * position.quantity.amount,
                                  currencyCode: quote.price.currencyCode)
            let percent = (quote.changePercent.value as NSDecimalNumber).doubleValue
            return PortfolioMover(position: position, quote: quote,
                                  dayChange: dayChange, dayChangePercent: percent)
        }
        .sorted { abs($0.dayChangePercent) > abs($1.dayChangePercent) }
    }

    var realizedPnL: Money { portfolio.realizedPnL }

    /// Transaction history, most recent first.
    var transactions: [Transaction] { portfolio.transactions.sorted { $0.date > $1.date } }

    /// Display name for a transaction's symbol, if it's (still) a known holding.
    func assetName(for symbol: String) -> String {
        portfolio.positions.first { $0.asset.symbol == symbol }?.asset.name ?? symbol
    }

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
