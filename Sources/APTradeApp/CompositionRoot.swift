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

    /// One shared KMP-backed repository instance for the process lifetime. Its `init`
    /// builds a Ktor `HttpClient(Darwin)` under the hood, which owns its own connection
    /// pool; constructing a fresh instance per `makeRepository()` call would allocate a
    /// new pool (and a new never-closed client) for every ViewModel factory.
    private static let sharedCoreRepository: MarketDataRepository =
        SharedCoreMarketDataRepository()

    static func makeRepository() -> MarketDataRepository {
        // All market-data calls are served by the shared Kotlin core.
        CachingMarketDataRepository(wrapping: sharedCoreRepository)
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
    static let bookmarkStore: BookmarkStore = UserDefaultsBookmarkStore()

    static func makeSettingsViewModel() -> SettingsViewModel {
        SettingsViewModel(
            loadSettings: LoadSettingsUseCase(store: settingsStore),
            saveSettings: SaveSettingsUseCase(store: settingsStore),
            // Same config.json the news factories below read — the key applies the next
            // time a news view model is built (News tab re-entry rebuilds it).
            loadFinnhubKey: { AppConfig.finnhubAPIKey() },
            persistFinnhubKey: { AppConfig.saveFinnhubAPIKey($0) }
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
            fetchHistory: FetchPortfolioHistoryUseCase(store: portfolioHistoryStore),
            clearHistory: ClearPortfolioHistoryUseCase(store: portfolioHistoryStore),
            fetchPerformance: FetchPortfolioPerformanceUseCase(repository: repo, store: portfolioStore)
        )
    }

    /// Reads the persisted portfolio, values it against freshly fetched quotes, and renders
    /// it to a chosen document format (PDF / Excel / Word) for the user to save.
    static func makeExportPortfolioUseCase() -> ExportPortfolioUseCase {
        ExportPortfolioUseCase(
            store: portfolioStore,
            fetchQuotes: FetchQuotesUseCase(repository: makeRepository()),
            renderer: DefaultPortfolioExportRenderer(),
            accountName: "APTrade Portfolio"
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
            fetchCandles: FetchCandlesUseCase(repository: repo),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore)
        )
    }

    static func makePerformanceViewModel() -> PerformanceViewModel {
        PerformanceViewModel(
            compute: ComputePerformanceMetricsUseCase(repository: makeRepository(),
                                                      store: portfolioStore))
    }

    static func makeCommandPaletteViewModel() -> CommandPaletteViewModel {
        CommandPaletteViewModel(searchAssets: SearchAssetsUseCase(repository: makeRepository()))
    }

    static func makeNewsViewModel() -> NewsViewModel {
        // The key is read only here, never above infrastructure.
        let key = AppConfig.finnhubAPIKey()
        let repo: NewsRepository = key.map { FinnhubNewsRepository(apiKey: $0) } ?? EmptyNewsRepository()
        return NewsViewModel(
            fetchMarketNews: FetchMarketNewsUseCase(repository: repo),
            loadBookmarks: LoadBookmarksUseCase(store: bookmarkStore),
            toggleBookmark: ToggleBookmarkUseCase(store: bookmarkStore),
            keyMissing: key == nil)
    }

    static func makeAssetNewsViewModel(for asset: Asset) -> AssetNewsViewModel {
        // The key is read only here, never above infrastructure.
        let key = AppConfig.finnhubAPIKey()
        let repo: NewsRepository = key.map { FinnhubNewsRepository(apiKey: $0) } ?? EmptyNewsRepository()
        return AssetNewsViewModel(
            symbol: asset.symbol,
            fetchCompanyNews: FetchCompanyNewsUseCase(repository: repo),
            loadBookmarks: LoadBookmarksUseCase(store: bookmarkStore),
            toggleBookmark: ToggleBookmarkUseCase(store: bookmarkStore),
            keyMissing: key == nil)
    }
}
