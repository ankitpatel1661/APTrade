import Foundation
import APTradeApplication
import APTradeInfrastructure
import APTradeDomain

@MainActor
enum CompositionRoot {
    static let seed: [Asset] = [
        Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock),
        Asset(symbol: "SPY", name: "SPDR S&P 500 ETF", kind: .etf),
        Asset(symbol: "BTC-USD", name: "Bitcoin", kind: .crypto),
        Asset(symbol: "ETH-USD", name: "Ethereum", kind: .crypto),
    ]

    static func makeRepository() -> MarketDataRepository {
        CachingMarketDataRepository(wrapping: YahooMarketDataRepository())
    }

    static func makeStore() -> WatchlistStore {
        UserDefaultsWatchlistStore(seed: seed)
    }

    static func makeWatchlistViewModel() -> WatchlistViewModel {
        let repo = makeRepository()
        let store = makeStore()
        return WatchlistViewModel(
            load: LoadWatchlistUseCase(store: store),
            add: AddToWatchlistUseCase(store: store),
            remove: RemoveFromWatchlistUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            search: SearchSymbolUseCase(repository: repo)
        )
    }

    static func makeDetailViewModel(for asset: Asset) -> AssetDetailViewModel {
        let repo = makeRepository()
        return AssetDetailViewModel(
            asset: asset,
            fetchHistory: FetchHistoryUseCase(repository: repo),
            fetchQuotes: FetchQuotesUseCase(repository: repo)
        )
    }
}
