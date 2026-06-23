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

    private(set) var quote: Quote?
    private(set) var points: [PricePoint] = []
    var timeframe: Timeframe = .oneMonth
    private(set) var loadState: LoadState = .idle

    init(asset: Asset, fetchHistory: FetchHistoryUseCase, fetchQuotes: FetchQuotesUseCase) {
        self.asset = asset
        self.fetchHistory = fetchHistory
        self.fetchQuotes = fetchQuotes
    }

    func load() async {
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
