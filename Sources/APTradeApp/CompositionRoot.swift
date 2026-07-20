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
    /// new pool (and a new never-closed client) for every ViewModel factory. Held at its
    /// concrete type so both the `MarketDataRepository` and `DividendEventsRepository`
    /// facets below can be exposed without a force-cast.
    private static let sharedCoreRepositoryInstance = SharedCoreMarketDataRepository()
    private static var sharedCoreRepository: MarketDataRepository { sharedCoreRepositoryInstance }
    private static var sharedDividendEventsRepository: DividendEventsRepository { sharedCoreRepositoryInstance }

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
    /// A single shared Pie store so the Plans list, detail, and wizard all read and
    /// write the same persisted set of pies (mirrors `portfolioStore`'s sharing rationale).
    static let pieStore: PieStore = UserDefaultsPieStore()
    /// One shared `TradeSerializer` for the process lifetime — every mutating pie/portfolio
    /// use case (contribute, rebalance execute, save, delete, reconcile, catch-up) is
    /// injected with THIS instance, so they all serialize against each other rather than
    /// each getting their own independent (and therefore ineffective) lock.
    static let tradeSerializer = TradeSerializer()

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
        // Shared with the planner below so "today" means the same trading day for both
        // the open/close/digest scheduling and the earnings-day trading-day lookup.
        let calendar = MarketCalendar()
        let fetchEarnings = makeFetchEarningsUseCase()
        // Shares the same `pieStore`/`portfolioStore` singletons the Plans UI reads and
        // writes, so a coordinator-driven contribution is immediately reflected there.
        let executeDueContributions = ExecuteDueContributions(
            pieStore: pieStore, portfolioStore: portfolioStore, market: repo, calendar: calendar,
            serializer: tradeSerializer
        )
        // Shares the same `portfolioStore`/`tradeSerializer` singletons as the rest of the
        // app so a coordinator-driven dividend credit/reinvestment is immediately
        // reflected in the Portfolio and Income tabs. `sharedDividendEventsRepository`
        // is the same shared Kotlin-core instance's dividend-events facet the Income tab
        // reads (see its doc comment above).
        // Captured into a local so the `@Sendable` (non-async) `isDripEnabled` closure
        // below doesn't reference the MainActor-isolated `settingsStore` static property
        // directly — `SettingsStore` is itself `Sendable`, so the local copy captures
        // cleanly without hopping actors.
        let settingsStoreForDrip = settingsStore
        let processDueDividends = ProcessDueDividends(
            portfolioStore: portfolioStore, market: repo, dividends: sharedDividendEventsRepository,
            stateStore: schedulerStateStore, calendar: calendar, serializer: tradeSerializer,
            isDripEnabled: { LoadSettingsUseCase(store: settingsStoreForDrip)().dripEnabled }
        )
        return MarketActivityCoordinator(
            planner: MarketActivityPlanner(calendar: calendar),
            stateStore: schedulerStateStore,
            loadSettings: LoadSettingsUseCase(store: settingsStore),
            notifier: marketEventNotifier,
            loadWatchlist: LoadWatchlistUseCase(store: makeStore()),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            fetchOwnedEarningsToday: { day in
                // `fetchOwnedEarningsToday` can't throw (see the coordinator's init) — a
                // failure must never kill the coordinator's tick, so every error, including
                // cooperative cancellation, degrades to "no earnings this tick" here rather
                // than propagating. `FetchEarningsCalendarUseCase.ownedToday` already
                // degrades ordinary repository failures to `[]` internally; only
                // `CancellationError` can still reach this point, and `try?` gives it the
                // same treatment for the same reason.
                (try? await fetchEarnings.ownedToday(day: day)) ?? []
            },
            executeDueContributions: { now in await executeDueContributions(now: now) },
            processDueDividends: { now in await processDueDividends(now: now) },
            calendar: calendar
        )
    }

    static func makePortfolioViewModel() -> PortfolioViewModel {
        let repo = makeRepository()
        return PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            resetPortfolio: ResetPortfolioUseCase(store: portfolioStore, serializer: tradeSerializer),
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
            buy: BuyAssetUseCase(repository: repo, store: portfolioStore, serializer: tradeSerializer),
            sell: SellAssetUseCase(repository: repo, store: portfolioStore, serializer: tradeSerializer),
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
        // The key is read only here, never above infrastructure. Unlike
        // `makeFetchEarningsUseCase()` (used where a non-optional use case is required),
        // a missing key here means "no next-earnings stat" (nil), not a silent empty repo.
        let key = AppConfig.finnhubAPIKey()
        let fetchEarnings = key.map {
            FetchEarningsCalendarUseCase(repository: FinnhubEarningsRepository(apiKey: $0),
                                          ownSymbols: ownSymbolsProvider())
        }
        return AssetDetailViewModel(
            asset: asset,
            fetchCandles: FetchCandlesUseCase(repository: repo),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
            fetchEarnings: fetchEarnings,
            dividendEventsRepository: sharedDividendEventsRepository
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

    /// Drives the Plans tab: the Pie list, detail sheet, one-off contributions, and
    /// manual rebalancing. Mirrors `makePortfolioViewModel`'s wiring — same shared
    /// `portfolioStore`, a dedicated `pieStore`, and a fresh `MarketDataRepository`.
    static func makePlansViewModel() -> PlansViewModel {
        let repo = makeRepository()
        return PlansViewModel(
            loadPies: LoadPies(store: pieStore),
            deletePie: DeletePie(store: pieStore, serializer: tradeSerializer),
            contributeToPie: ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo,
                                             serializer: tradeSerializer),
            rebalancePie: RebalancePie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo,
                                       serializer: tradeSerializer),
            reconcileLedgers: ReconcilePieLedgers(pieStore: pieStore, portfolioStore: portfolioStore,
                                                  serializer: tradeSerializer),
            fetchQuotes: FetchQuotesUseCase(repository: repo)
        )
    }

    /// Drives the pie creation/edit wizard. Pass `existingPie` to edit a Pie in place;
    /// omit it to create a new one.
    static func makePieWizardViewModel(existingPie: Pie? = nil) -> PieWizardViewModel {
        let repo = makeRepository()
        return PieWizardViewModel(
            existingPie: existingPie,
            savePie: SavePie(store: pieStore, serializer: tradeSerializer),
            simulateDCA: SimulateDCA(market: repo, calendar: MarketCalendar()),
            // The wizard's slice-search step reuses the same asset search the command
            // palette and watchlist use — one more `SearchAssetsUseCase` over the shared repo.
            searchAssets: SearchAssetsUseCase(repository: repo)
        )
    }

    /// Drives the Portfolio tab's Income section: dividend summary cards, monthly bars,
    /// upcoming payouts, per-holding breakdown, and history. Reads the same shared
    /// `portfolioStore` the rest of the Portfolio tab uses so a dividend transaction
    /// posted elsewhere is immediately reflected here, plus the shared Kotlin-core
    /// repository's dividend-events facet for projections.
    static func makeIncomeViewModel() -> IncomeViewModel {
        let repo = makeRepository()
        return IncomeViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: portfolioStore),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            dividendEventsRepository: sharedDividendEventsRepository
        )
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

    static func makeCalendarViewModel() -> CalendarViewModel {
        // The key is read only here, never above infrastructure.
        let key = AppConfig.finnhubAPIKey()
        return CalendarViewModel(
            fetchEarnings: makeFetchEarningsUseCase(),
            loadOwnSymbols: ownSymbolsProvider(),
            keyMissing: key == nil)
    }

    /// Key-gated earnings use case for callers that need a non-optional
    /// `FetchEarningsCalendarUseCase` — an `EmptyEarningsRepository` fallback keeps them
    /// silently empty rather than erroring when no Finnhub key is configured (mirrors
    /// `makeNewsViewModel`/`makeCalendarViewModel`'s key gating).
    private static func makeFetchEarningsUseCase() -> FetchEarningsCalendarUseCase {
        let key = AppConfig.finnhubAPIKey()
        let repo: EarningsCalendarRepository = key.map { FinnhubEarningsRepository(apiKey: $0) } ?? EmptyEarningsRepository()
        return FetchEarningsCalendarUseCase(repository: repo, ownSymbols: ownSymbolsProvider())
    }

    /// watchlist ∪ portfolio symbols, read fresh per call. `makeStore()`/`portfolioStore` are
    /// MainActor-isolated (this enum is `@MainActor`), so the read hops onto the main actor
    /// explicitly, keeping the closure itself plain `@Sendable` — callable from any actor.
    private static func ownSymbolsProvider() -> @Sendable () async -> Set<String> {
        {
            await MainActor.run {
                Set(LoadWatchlistUseCase(store: makeStore())().map(\.symbol))
                    .union(FetchPortfolioUseCase(store: portfolioStore)().positions.map(\.asset.symbol))
            }
        }
    }
}
