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

    /// A single shared portfolio store so the Portfolio view and trade sheets read and
    /// write the same persisted state.
    static let portfolioStore: PortfolioStore = UserDefaultsPortfolioStore()

    static func makePortfolioViewModel() -> PortfolioViewModel {
        let repo = makeRepository()
        return PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            resetPortfolio: ResetPortfolioUseCase(store: portfolioStore)
        )
    }

    static func makeTradeViewModel(for asset: Asset) -> TradeViewModel {
        let repo = makeRepository()
        return TradeViewModel(
            asset: asset,
            buy: BuyAssetUseCase(repository: repo, store: portfolioStore),
            sell: SellAssetUseCase(repository: repo, store: portfolioStore),
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
            fetchQuotes: FetchQuotesUseCase(repository: repo)
        )
    }

    static func makeWatchlistViewModel() -> WatchlistViewModel {
        let repo = makeRepository()
        let store = makeStore()
        return WatchlistViewModel(
            load: LoadWatchlistUseCase(store: store),
            add: AddToWatchlistUseCase(store: store),
            remove: RemoveFromWatchlistUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            fetchHistory: FetchHistoryUseCase(repository: repo),
            search: SearchSymbolUseCase(repository: repo),
            searchAssets: SearchAssetsUseCase(repository: repo)
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
