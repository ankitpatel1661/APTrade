import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class AssetDetailViewModel {
    enum LoadState: Equatable { case idle, loading, loaded, failed }

    let asset: Asset
    private let fetchHistory: FetchHistoryUseCase
    private let fetchQuotes: FetchQuotesUseCase
    private let fetchPortfolio: FetchPortfolioUseCase

    private(set) var quote: Quote?
    private(set) var points: [PricePoint] = []
    var timeframe: Timeframe = .oneDay
    private(set) var loadState: LoadState = .idle
    private(set) var isLive = false
    private(set) var position: Position?

    init(asset: Asset,
         fetchHistory: FetchHistoryUseCase,
         fetchQuotes: FetchQuotesUseCase,
         fetchPortfolio: FetchPortfolioUseCase) {
        self.asset = asset
        self.fetchHistory = fetchHistory
        self.fetchQuotes = fetchQuotes
        self.fetchPortfolio = fetchPortfolio
    }

    /// Re-reads whether this asset is currently held (after a trade or on appear).
    func reloadPosition() {
        position = fetchPortfolio().position(for: asset.symbol)
    }

    func load() async {
        reloadPosition()
        loadState = .loading
        let quotes = await fetchQuotes(symbols: [asset.symbol])
        if case .success(let q) = quotes[asset.symbol] { quote = q }
        do {
            points = try await fetchHistory(symbol: asset.symbol, timeframe: timeframe)
            loadState = .loaded
        } catch {
            loadState = .failed
        }
    }

    /// Polls the live quote (and, on the intraday timeframe, the chart itself) every 15s
    /// until the surrounding task is cancelled on disappear.
    func runLiveUpdates() async {
        isLive = true
        defer { isLive = false }
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(15))
            if Task.isCancelled { break }
            let quotes = await fetchQuotes(symbols: [asset.symbol])
            if case .success(let q) = quotes[asset.symbol] { quote = q }
            if timeframe == .oneDay, let fresh = try? await fetchHistory(symbol: asset.symbol, timeframe: .oneDay) {
                points = fresh
            }
        }
    }

    /// Change over the currently selected timeframe's window, derived from the chart's
    /// own points rather than the quote's always-intraday `changePercent` — so the
    /// badge and chart color reflect 1W/1M/1Y performance, not just today's move.
    var periodChange: Money? {
        guard let first = points.first?.close, let last = points.last?.close else { return nil }
        return last - first
    }

    var periodChangePercent: Percentage? {
        guard let first = points.first?.close, let last = points.last?.close, first.amount != 0 else { return nil }
        let percent = (last.amount - first.amount) / first.amount * 100
        return Percentage(value: percent)
    }

    func select(_ timeframe: Timeframe) async {
        self.timeframe = timeframe
        loadState = .loading
        do {
            points = try await fetchHistory(symbol: asset.symbol, timeframe: timeframe)
            loadState = .loaded
        } catch {
            loadState = .failed
        }
    }
}
