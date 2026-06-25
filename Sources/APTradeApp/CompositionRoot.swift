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
    static let portfolioHistoryStore: PortfolioHistoryStore = UserDefaultsPortfolioHistoryStore()
    static let alertStore: AlertStore = UserDefaultsAlertStore()
    /// One macOS-notification deliverer, shared behind both notifier ports.
    private static let notificationDeliverer = UserNotificationAlertNotifier()
    static let alertNotifier: AlertNotifier = notificationDeliverer
    static let orderFillNotifier: OrderFillNotifier = notificationDeliverer
    static let marketEventNotifier: MarketEventNotifier = notificationDeliverer
    static let settingsStore: SettingsStore = UserDefaultsSettingsStore()
    static let schedulerStateStore: SchedulerStateStore = UserDefaultsSchedulerStateStore()

    static func makeSettingsViewModel() -> SettingsViewModel {
        SettingsViewModel(
            loadSettings: LoadSettingsUseCase(store: settingsStore),
            saveSettings: SaveSettingsUseCase(store: settingsStore)
        )
    }

    /// Current persisted settings, for surfaces that only need to read a preference
    /// (e.g. the trade sheet honoring `confirmTrades`) rather than edit it.
    static func loadSettings() -> AppSettings {
        LoadSettingsUseCase(store: settingsStore)()
    }

    static func makeMarketActivityCoordinator() -> MarketActivityCoordinator {
        let repo = makeRepository()
        return MarketActivityCoordinator(
            planner: MarketActivityPlanner(),
            stateStore: schedulerStateStore,
            loadSettings: LoadSettingsUseCase(store: settingsStore),
            notifier: marketEventNotifier,
            loadWatchlist: LoadWatchlistUseCase(store: makeStore()),
            fetchQuotes: FetchQuotesUseCase(repository: repo)
        )
    }

    static func makePortfolioViewModel() -> PortfolioViewModel {
        let repo = makeRepository()
        return PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            resetPortfolio: ResetPortfolioUseCase(store: portfolioStore),
            recordSnapshot: RecordPortfolioSnapshotUseCase(store: portfolioHistoryStore),
            fetchHistory: FetchPortfolioHistoryUseCase(store: portfolioHistoryStore)
        )
    }

    static func makeTradeViewModel(for asset: Asset) -> TradeViewModel {
        let repo = makeRepository()
        return TradeViewModel(
            asset: asset,
            buy: BuyAssetUseCase(repository: repo, store: portfolioStore),
            sell: SellAssetUseCase(repository: repo, store: portfolioStore),
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            notifyOrderFill: NotifyOrderFillUseCase(notifier: orderFillNotifier, settings: settingsStore)
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
            searchAssets: SearchAssetsUseCase(repository: repo),
            loadAlerts: LoadAlertsUseCase(store: alertStore),
            createAlert: CreatePriceAlertUseCase(store: alertStore),
            removeAlert: RemovePriceAlertUseCase(store: alertStore),
            evaluateAlerts: EvaluateAlertsUseCase(store: alertStore, notifier: alertNotifier, settings: settingsStore)
        )
    }

    static func makeDetailViewModel(for asset: Asset) -> AssetDetailViewModel {
        let repo = makeRepository()
        return AssetDetailViewModel(
            asset: asset,
            fetchHistory: FetchHistoryUseCase(repository: repo),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore)
        )
    }
}
