package com.aptrade.desktop

import androidx.compose.ui.window.TrayState
import com.aptrade.shared.infrastructure.SP500Names
import com.aptrade.desktop.infra.AppSettings
import com.aptrade.desktop.infra.FileAlertStore
import com.aptrade.desktop.infra.FileBookmarkStore
import com.aptrade.desktop.infra.FileSchedulerStateStore
import com.aptrade.desktop.infra.FileSettingsStore
import com.aptrade.desktop.infra.FileWatchlistStore
import com.aptrade.desktop.infra.FinnhubKeyConfig
import com.aptrade.desktop.infra.TrayNotifier
import com.aptrade.desktop.home.HomeViewModel
import com.aptrade.desktop.income.IncomeViewModel
import com.aptrade.desktop.plans.PieWizardViewModel
import com.aptrade.desktop.plans.PlansViewModel
import com.aptrade.desktop.screener.ScreenerViewModel
import com.aptrade.shared.infrastructure.resolveConfigDir
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.AlertStore
import com.aptrade.shared.application.BookmarkStore
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.CreatePriceAlert
import com.aptrade.shared.application.EarningsCalendarRepository
import com.aptrade.shared.application.EmptyEarningsRepository
import com.aptrade.shared.application.EvaluateAlerts
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchCompanyNews
import com.aptrade.shared.application.FetchDividendEvents
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketNews
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPerformanceReport
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.FetchPortfolioPerformance
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.HomeFeedAssembler
import com.aptrade.shared.application.HomeIncomeSummary
import com.aptrade.shared.application.HomeUpcomingDividend
import com.aptrade.shared.application.LoadAlerts
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.ContributeToPie
import com.aptrade.shared.application.DeletePie
import com.aptrade.shared.application.ExecuteDueContributions
import com.aptrade.shared.application.LoadPies
import com.aptrade.shared.application.MarketActivityPlanner
import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.NewsRepository
import com.aptrade.shared.application.PieStore
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.ProcessDueDividends
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RebalancePie
import com.aptrade.shared.application.ReconcilePieLedgers
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.RemovePriceAlert
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SavePie
import com.aptrade.shared.application.SchedulerStateStore
import com.aptrade.shared.application.ScreenerScanEngine
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.application.SimulateDCA
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.application.normalized
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendMath
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.SP500Symbols
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.infrastructure.FilePieStore
import com.aptrade.shared.infrastructure.FilePortfolioStore
import com.aptrade.shared.infrastructure.FileScreenStore
import com.aptrade.shared.infrastructure.FileScreenerSnapshotStore
import com.aptrade.shared.infrastructure.FinnhubEarningsRepository
import com.aptrade.shared.infrastructure.FinnhubNewsRepository
import com.aptrade.shared.infrastructure.YahooMarketDataRepository
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate

/** Composition root. A plain CLASS constructed exactly once in main() — deliberately
 *  NOT an `object` (increment-5 review: don't copy the Android singleton to desktop).
 *  Exactly ONE YahooMarketDataRepository (one Ktor client) exists per process. */
class AppGraph(
    private val repository: MarketDataRepository = YahooMarketDataRepository(),
    store: WatchlistStore = FileWatchlistStore(resolveConfigDir().resolve("watchlist.json")),
    private val portfolioStore: PortfolioStore = FilePortfolioStore(resolveConfigDir().resolve("portfolio.json")),
    val settingsStore: FileSettingsStore = FileSettingsStore(resolveConfigDir().resolve("settings.json")),
    val bookmarkStore: BookmarkStore = FileBookmarkStore(resolveConfigDir().resolve("bookmarks.json")),
    alertStore: AlertStore = FileAlertStore(resolveConfigDir().resolve("alerts.json")),
    val schedulerStateStore: SchedulerStateStore = FileSchedulerStateStore(resolveConfigDir().resolve("schedulerState.json")),
    // Investment Plans (Pies) persistence (M7.2 Task 12) — same whole-blob JSON-file shape as
    // every other store here. Public (like settingsStore/bookmarkStore) since the Plans UI
    // reads it directly for the edit wizard's raw-Pie lookup (mirrors macOS's
    // `CompositionRoot.pieStore.load()` — see PlansPane.kt).
    val pieStore: PieStore = FilePieStore(resolveConfigDir().resolve("pies.json")),
    // Constructed once here (not via rememberTrayState() in Main.kt) so the SAME instance
    // backs both the Tray composable (which renders the OS tray icon) and TrayNotifier
    // (which posts to it) — one tray, one notifier, one process, matching every other
    // "exactly one X per process" seam in this graph. TrayState's constructor is a plain
    // no-arg Kotlin constructor (not Composable-scoped), so this is safe outside Compose.
    val trayState: TrayState = TrayState(),
    finnhubApiKey: String? = FinnhubKeyConfig().finnhubApiKey(),
) {
    val fetchMarketQuotes = FetchMarketQuotes(repository)
    val fetchSearch = FetchSearch(repository)
    val fetchProfile = FetchProfile(repository)
    val fetchHistory = FetchHistory(repository)
    val fetchCandles = FetchCandles(repository)
    val fetchChartWindow = FetchChartWindow(repository)
    val fetchDividendEvents = FetchDividendEvents(repository)

    val defaultEntries = listOf(
        WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
        WatchlistEntry("SPY", "SPDR S&P 500 ETF Trust", AssetKind.Etf),
        WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto),
        WatchlistEntry("ETH-USD", "Ethereum USD", AssetKind.Crypto),
    )
    val fetchWatchlist = FetchWatchlist(store, defaultEntries)
    val addToWatchlist = AddToWatchlist(store)
    val removeFromWatchlist = RemoveFromWatchlist(store)

    val fetchPortfolio = FetchPortfolio(portfolioStore)
    // ONE shared Mutex, handed to BOTH use cases below: BuyAsset and SellAsset each perform
    // a load->validate->save RMW against this SAME portfolioStore, so only a mutex shared
    // between the two serializes a concurrent buy against a concurrent sell (two separate
    // `Mutex()` instances would only serialize buy-vs-buy and sell-vs-sell). See the KDoc on
    // BuyAsset/SellAsset's `portfolioMutex` parameter.
    val portfolioMutex = Mutex()
    val buyAsset = BuyAsset(repository, portfolioStore, portfolioMutex)
    val sellAsset = SellAsset(repository, portfolioStore, portfolioMutex)
    val resetPortfolio = ResetPortfolio(portfolioStore, portfolioMutex)
    val fetchPortfolioPerformance = FetchPortfolioPerformance(repository, portfolioStore)
    val fetchPerformanceReport = FetchPerformanceReport(repository, fetchPortfolioPerformance)

    // Investment Plans (Pies) — M7.2 Task 12. Every mutating pie use case shares the SAME
    // portfolioMutex above (Global Constraint #8 / BuyAsset's doc contract, extended below):
    // pie contributions/rebalances/reconciliation and plain buy/sell all read-modify-write the
    // same portfolioStore, so one shared lock is what makes them mutually exclusive rather than
    // only exclusive within their own use case. marketCalendar is stateless (see MarketCalendar's
    // KDoc) — one instance, shared by every pie use case and both Plans view models below, the
    // same way `graph.fetchEarningsCalendar` shares one calendar-reading closure.
    val marketCalendar = MarketCalendar()
    val loadPies = LoadPies(pieStore)
    val savePie = SavePie(pieStore, portfolioMutex)
    val deletePie = DeletePie(pieStore, portfolioMutex)
    val contributeToPie = ContributeToPie(pieStore, portfolioStore, repository, portfolioMutex)
    val executeDueContributions = ExecuteDueContributions(pieStore, portfolioStore, repository, marketCalendar, portfolioMutex)
    val rebalancePie = RebalancePie(pieStore, portfolioStore, repository, portfolioMutex)
    val reconcilePieLedgers = ReconcilePieLedgers(pieStore, portfolioStore, portfolioMutex, marketCalendar)
    val simulateDCA = SimulateDCA(repository, marketCalendar)

    // Dividend crediting (M8.2 Task 10). Shares the SAME portfolioMutex as every other
    // mutating portfolio use case above (BuyAsset's co-holder doc contract) — the engine
    // read-modify-writes portfolioStore just like a buy/sell/contribution does, so one shared
    // lock is what makes them all mutually exclusive. `isDripEnabled` is `suspend` (recorded
    // divergence: suspend isDripEnabled on `ProcessDueDividends` — Swift's `SettingsStore` is
    // sync `UserDefaults`; Kotlin's store is suspend IO, i.e. `FileSettingsStore.load()` is real
    // file I/O) and reads the live `dripEnabled` toggle fresh on every call — never captured
    // once at construction time — since the user can flip it at any point during a run.
    val processDueDividends = ProcessDueDividends(
        portfolioStore = portfolioStore,
        market = repository,
        stateStore = schedulerStateStore,
        calendar = marketCalendar,
        portfolioMutex = portfolioMutex,
        isDripEnabled = { settingsStore.load().dripEnabled },
    )

    /** Builds a fresh [PlansViewModel] bound to [scope] — mirrors macOS's
     *  `CompositionRoot.makePlansViewModel()` factory (there is no persistent, graph-owned VM
     *  instance; each caller, e.g. `PlansPane`, owns its own scope/instance lifetime). */
    fun makePlansViewModel(
        scope: CoroutineScope,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): PlansViewModel = PlansViewModel(
        loadPies = loadPies,
        deletePieUseCase = deletePie,
        contributeToPie = contributeToPie,
        rebalancePie = rebalancePie,
        reconcileLedgers = reconcilePieLedgers,
        fetchMarketQuotes = fetchMarketQuotes,
        calendar = marketCalendar,
        scope = scope,
        nowEpochSeconds = nowEpochSeconds,
    )

    /** Builds a fresh [PieWizardViewModel] bound to [scope], optionally pre-seeded from
     *  [existingPie] for an edit — mirrors macOS's
     *  `CompositionRoot.makePieWizardViewModel(existingPie:)` factory. */
    fun makePieWizardViewModel(
        existingPie: Pie? = null,
        scope: CoroutineScope,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): PieWizardViewModel = PieWizardViewModel(
        existingPie = existingPie,
        savePie = savePie,
        simulateDCA = simulateDCA,
        searchAssets = fetchSearch,
        calendar = marketCalendar,
        scope = scope,
        nowEpochSeconds = nowEpochSeconds,
    )

    /** Builds a fresh [IncomeViewModel] bound to [scope] — mirrors macOS's
     *  `CompositionRoot.makeIncomeViewModel()` factory (there is no persistent, graph-owned VM
     *  instance; each caller, e.g. `IncomePane`, owns its own scope/instance lifetime). Shares
     *  the same `portfolioStore`/`repository`/[marketCalendar] every other read-only VM factory
     *  here reads from — Income never mutates the portfolio, so it needs no mutex. */
    fun makeIncomeViewModel(
        scope: CoroutineScope,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): IncomeViewModel = IncomeViewModel(
        portfolioStore = portfolioStore,
        marketDataRepository = repository,
        calendar = marketCalendar,
        scope = scope,
        nowEpochSeconds = nowEpochSeconds,
    )

    // Screener (M9.2 Task 7) — full-universe technical scans over the S&P 500. Shares the
    // SAME market repository/[marketCalendar] every other read-only VM factory here reads
    // from (screening never mutates the portfolio, so it needs no mutex, matching Income's
    // factory above). [screenerSnapshotStore]/[screenStore] follow the SAME injected-Path,
    // atomic-write file-store shape as [pieStore]/[portfolioStore] (see
    // `FileScreenerSnapshotStore`/`FileScreenStore`'s own KDoc). `SP500Names` lives in
    // shared jvmCommonMain (reused by desktop Calendar pane and Android screener) — see
    // its KDoc on why it stays out of commonMain (xcframework size).
    val screenerScanEngine: ScreenerScanEngine = ScreenerScanEngine(repository, marketCalendar)
    val screenerSnapshotStore: FileScreenerSnapshotStore =
        FileScreenerSnapshotStore(resolveConfigDir().resolve("screener-snapshot.json"))
    val screenStore: FileScreenStore = FileScreenStore(resolveConfigDir().resolve("screens.json"))

    /** Builds a fresh [ScreenerViewModel] bound to [scope] — mirrors macOS's
     *  `CompositionRoot.makeScreenerViewModel()` factory (there is no persistent, graph-owned
     *  VM instance; each caller, e.g. `ScreenerPane`, owns its own scope/instance lifetime). */
    fun makeScreenerViewModel(
        scope: CoroutineScope,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): ScreenerViewModel = ScreenerViewModel(
        engine = screenerScanEngine,
        snapshotStore = screenerSnapshotStore,
        screenStore = screenStore,
        symbols = SP500Symbols.set.toList(),
        names = SP500Names,
        calendar = marketCalendar,
        scope = scope,
        nowEpochSeconds = nowEpochSeconds,
    )

    // Null when no Finnhub key is configured; the news UI reads [keyMissing] to show a
    // "no key configured" state rather than attempting requests that would all fail.
    val newsRepository: NewsRepository? = finnhubApiKey?.let { FinnhubNewsRepository(it) }
    val keyMissing: Boolean = newsRepository == null

    val loadBookmarks = LoadBookmarks(bookmarkStore)
    val toggleBookmark = ToggleBookmark(bookmarkStore)
    val fetchMarketNews = newsRepository?.let { FetchMarketNews(it) }
    val fetchCompanyNews = newsRepository?.let { FetchCompanyNews(it) }

    // Earnings calendar (Task 6): same key-gated, startup-frozen shape as newsRepository/
    // keyMissing above, sharing the SAME finnhubApiKey read at construction time — one key
    // read, two consumers. Unlike newsRepository, earningsRepository is never null: with no
    // key it falls back to EmptyEarningsRepository so the Calendar tab's holiday rows still
    // render (only the earnings rows are empty) rather than needing a nullable type.
    val earningsRepository: EarningsCalendarRepository =
        finnhubApiKey?.let { FinnhubEarningsRepository(it) } ?: EmptyEarningsRepository
    val earningsKeyMissing: Boolean = finnhubApiKey == null
    // Shared symbol-ownership provider: watchlist ∪ portfolio, read fresh per call — same two
    // use cases the coordinator's digestSummary() reads from (fetchWatchlist.execute()) plus
    // the portfolio store's holdings, mirroring PortfolioViewModel's own symbol source. Public
    // (not inlined into fetchEarningsCalendar below) so the desktop Calendar tab's ViewModel
    // (Task 7) can share this EXACT closure for its "gold dot" owned-row indicator instead of
    // Main.kt duplicating the watchlist-union-portfolio computation a second time.
    val ownSymbols: suspend () -> Set<String> = {
        val watchlistSymbols = fetchWatchlist.execute().map { it.symbol }
        val portfolioSymbols = fetchPortfolio.execute().positions.map { it.asset.symbol }
        (watchlistSymbols + portfolioSymbols).toSet()
    }
    val fetchEarningsCalendar: FetchEarningsCalendar = FetchEarningsCalendar(earningsRepository, ownSymbols)

    // Alerts & notifications (increment 6d.1). The notifier seam: TrayNotifier delivers
    // via the shared `trayState`, which the Tray composable in Main.kt also mounts.
    val trayNotifier = TrayNotifier(trayState)
    val loadAlerts = LoadAlerts(alertStore)
    val createPriceAlert = CreatePriceAlert(alertStore)
    val removePriceAlert = RemovePriceAlert(alertStore)
    val evaluateAlerts = EvaluateAlerts(
        store = alertStore,
        notifier = trayNotifier,
        isNotifyEnabled = { settingsStore.load().priceAlerts },
    )
    val marketActivityPlanner = MarketActivityPlanner()

    // Settings-gated order-fill delivery — mirrors macOS's `NotifyOrderFillUseCase`
    // (Sources/APTradeApplication/SettingsUseCases.swift): read `settings.orderFills`
    // once per call, and only deliver when it's on. Handed to PortfolioViewModel as a
    // plain suspend closure (Task 4) since :shared has no OrderFillNotifier port.
    //
    // The gate itself is extracted into `buildNotifyOrderFill` (below) so it can be
    // pinned by a test against a real `FileSettingsStore` without needing a real
    // `TrayNotifier`/`TrayState` (AWT) — review fix for Task 4's one ungated-in-tests
    // seam.
    val notifyOrderFill: suspend (TradeSide, String, String, String) -> Unit =
        buildNotifyOrderFill(settingsStore) { side, symbol, quantityText, amountFormatted ->
            trayNotifier.notifyFill(side, symbol, quantityText, amountFormatted)
        }

    /** Builds a fresh [HomeViewModel] bound to [scope] — mirrors macOS's
     *  `CompositionRoot.makeHomeViewModel()` factory shape (see [makeScreenerViewModel]
     *  immediately above): there is no persistent, graph-owned VM instance; each caller
     *  (HomePane, Task 4) owns its own scope/instance lifetime.
     *
     *  WIRING (M10.2 Task 2's assembler seams — every source below is an EXISTING graph
     *  piece, nothing new is constructed for Home alone):
     *  - `loadPortfolio`/`fetchQuotes` are the SAME [fetchPortfolio]/[fetchMarketQuotes]
     *    use cases [PortfolioViewModel] reads from — just re-shaped to the `Map<String,
     *    Quote>` the assembler wants (`fetchMarketQuotes.execute` returns a `List`).
     *  - `ownSymbols` (movers' dedup set) is the SAME watchlist-∪-portfolio closure
     *    [fetchEarningsCalendar] already shares with the Calendar tab's owned-row
     *    indicator (see that val's own KDoc, seam 1 of the T1 review) — Home's movers row
     *    dedupes against the identical "mine" set the rest of the app already uses,
     *    rather than a second union computed here.
     *  - `loadIncomeSummary` wraps [buildHomeIncomeSummary] (top-level function below) —
     *    see that function's KDoc for the day-type conversion seam (Global Constraint 2 /
     *    Task 2 seam 2: the epoch-seconds ex-date the desktop income pipeline represents
     *    dividends with is converted to `LocalDate` exactly once, at this boundary).
     *  - `fetchNextEarnings` reuses [fetchEarningsCalendar]'s existing owned-first,
     *    day-ascending sort over the SAME 14-day window [CalendarViewModel] scans,
     *    filtered down to owned/watched symbols only (Home cares about MINE, not any
     *    S&P 500 constituent the earnings calendar also carries) and takes the first
     *    (soonest) match — never re-deriving "next" from scratch.
     *  - `loadScreenerSnapshot`/`loadAlerts` read the SAME [screenerSnapshotStore]/
     *    [loadAlerts] the Screener pane and Alerts center already read from — one store
     *    per concern, no second cache.
     *  - `calendar` is the SAME [marketCalendar] instance every other read-only VM factory
     *    here shares, so "today"/"soonest" mean the same trading day everywhere (market
     *    status, screener freshness, and the earnings window below all agree). */
    fun makeHomeViewModel(
        scope: CoroutineScope,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): HomeViewModel {
        val fetchNextOwnedEarnings: suspend () -> EarningsEvent? = {
            val start = marketCalendar.localEpochDay(nowEpochSeconds())
            val fromDay = marketCalendar.dayString(start)
            val toDay = marketCalendar.dayString(start + 13)
            val events = fetchEarningsCalendar.execute(fromDay, toDay)
            val own = ownSymbols().mapTo(HashSet(), ::normalized)
            events.firstOrNull { normalized(it.symbol) in own }
        }
        val assembler = HomeFeedAssembler(
            loadPortfolio = { fetchPortfolio.execute() },
            fetchQuotes = { symbols -> fetchMarketQuotes.execute(symbols).associateBy { it.symbol } },
            ownSymbols = ownSymbols,
            loadIncomeSummary = {
                buildHomeIncomeSummary(fetchPortfolio, repository, marketCalendar, nowEpochSeconds)
            },
            fetchNextEarnings = fetchNextOwnedEarnings,
            loadScreenerSnapshot = { screenerSnapshotStore.load() },
            loadAlerts = { loadAlerts.execute() },
            calendar = marketCalendar,
            nowEpochSeconds = nowEpochSeconds,
        )
        return HomeViewModel(assembler = assembler, scope = scope)
    }

    // Only the production Yahoo repository and (when configured) the Finnhub news/earnings
    // repositories own closeable Ktor clients; test doubles passed via the constructor
    // typically aren't AutoCloseable and are safely skipped.
    fun close() {
        (repository as? AutoCloseable)?.close()
        (newsRepository as? AutoCloseable)?.close()
        (earningsRepository as? AutoCloseable)?.close()
    }
}

/**
 * Builds the settings-gated order-fill closure: reads `settingsStore.load().orderFills`
 * once per call and only invokes [deliver] when it's on. Extracted as a standalone,
 * package-visible function (rather than inlined in [AppGraph]'s constructor) so a test
 * can construct the REAL gate against a real [FileSettingsStore] (a temp-file store, no
 * AWT dependency) with a recording `deliver` fake, and pin `orderFills = true`/`false`
 * both ways — see `AppGraphNotifyOrderFillTest`. No behavior change from the inline form
 * this replaces.
 */
internal fun buildNotifyOrderFill(
    settingsStore: FileSettingsStore,
    deliver: suspend (TradeSide, String, String, String) -> Unit,
): suspend (TradeSide, String, String, String) -> Unit =
    { side, symbol, quantityText, amountFormatted ->
        if (settingsStore.load().orderFills) {
            deliver(side, symbol, quantityText, amountFormatted)
        }
    }

// How far back to fetch dividend events when projecting the next payout: mirrors
// PortfolioViewModel's DIVIDEND_EVENTS_LOOKBACK_SECONDS / IncomeViewModel's
// lookbackStart (both 730 days = two years, covering the trailing-annual window plus
// enough history for cadence inference on slower-paying assets) — the SAME constant,
// duplicated per this codebase's own established precedent (see DividendMath.kt's and
// MarketCalendar.kt's header docs) of each file keeping its own private copy of small
// domain constants/helpers rather than widening visibility to share one.
private const val HOME_DIVIDEND_EVENTS_LOOKBACK_SECONDS = 730L * 86_400L

/**
 * Builds the [HomeIncomeSummary] [HomeFeedAssembler] wants — WRAPS the existing income
 * pipeline (Global Constraint 2 of the M10.2 plan: `receivedYTD` + first upcoming, never
 * a second recomputation of dividend math) rather than re-deriving it.
 *
 * [IncomeViewModel.receivedYTD]/[IncomeViewModel.buildUpcoming] are private methods on a
 * `@MutableStateFlow`-shaped, one-instance-per-pane VM, so this seam is built from the
 * SAME underlying pieces those private methods themselves wrap — [FetchPortfolio] for the
 * portfolio/transaction source, and [DividendMath.nextProjected] (shared, pure, already
 * used by IncomeViewModel/PortfolioViewModel/ExportPortfolioUseCase alike) for the
 * cadence-inference-and-projection math — rather than duplicating IncomeViewModel's
 * internals or widening that VM's visibility just for this one caller. The received-YTD
 * sum below (a plain "which Dividend transactions fall in the current UTC calendar year"
 * filter, not dividend MATH) is the one small piece unavoidably duplicated here; per-symbol
 * dividend-events isolation (one symbol's fetch failure degrades only that symbol) mirrors
 * [IncomeViewModel.load]'s own per-symbol catch exactly.
 *
 * DAY-TYPE SEAM (M10.2 Task 2, seam 2): the desktop income pipeline represents an upcoming
 * ex-date as `estimatedExDateEpochSeconds: Long` (see `IncomeViewModel.UpcomingRow`);
 * [HomeUpcomingDividend.estimatedExDate] wants a `kotlinx.datetime.LocalDate`. That
 * conversion happens EXACTLY ONCE, here, at this boundary, via [MarketCalendar.tradingDay]
 * — the SAME market-local (ET) convention every other trading-day computation in this
 * codebase already uses (the assembler's own market-status/screener-freshness checks,
 * [CalendarViewModel]'s window) — rather than round-tripping through `java.time.Instant`/
 * `ZoneId` arithmetic (Global Constraint 1: day-only values stay LocalDate end-to-end).
 * `tradingDay` already returns a plain `yyyy-MM-dd` string, which parses directly as a
 * `LocalDate` — the exact same "day string in, LocalDate out" shape the assembler's own
 * earnings seam uses for [EarningsEvent.day].
 */
internal suspend fun buildHomeIncomeSummary(
    fetchPortfolio: FetchPortfolio,
    repository: MarketDataRepository,
    calendar: MarketCalendar,
    nowEpochSeconds: () -> Long,
): HomeIncomeSummary {
    val portfolio = fetchPortfolio.execute()
    val asOf = nowEpochSeconds()

    // Same filter IncomeViewModel.receivedYTD applies: Dividend-side transaction cash
    // whose UTC calendar year matches `asOf`'s.
    val currentYear = utcYear(asOf)
    var receivedYTD = Money(BigDecimal.ZERO, "USD")
    for (txn in portfolio.transactions) {
        if (txn.side != TradeSide.Dividend) continue
        if (utcYear(txn.epochSeconds) != currentYear) continue
        receivedYTD += Money(txn.price.amount * txn.quantity, txn.price.currencyCode)
    }

    // Same per-symbol isolation IncomeViewModel.load() applies: one symbol's
    // dividend-events fetch failure degrades only that symbol (no projection for it),
    // never the others — and never the receivedYTD sum above, which needs no network call.
    val nonCryptoPositions = portfolio.positions.filter { it.asset.kind != AssetKind.Crypto }
    var nextEpochSeconds: Long? = null
    var nextSymbol: String? = null
    var nextAmount: Money? = null
    for (position in nonCryptoPositions) {
        val symbol = position.asset.symbol
        val events = try {
            repository.dividendEvents(symbol, asOf - HOME_DIVIDEND_EVENTS_LOOKBACK_SECONDS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuoteError) {
            emptyList()
        }
        val projected = DividendMath.nextProjected(events) ?: continue
        if (projected.exDateEpochSeconds <= asOf) continue
        if (nextEpochSeconds == null || projected.exDateEpochSeconds < nextEpochSeconds) {
            nextEpochSeconds = projected.exDateEpochSeconds
            nextSymbol = symbol
            nextAmount = Money(projected.amountPerShare.amount * position.quantity, projected.amountPerShare.currencyCode)
        }
    }

    val nextDividend = if (nextEpochSeconds != null && nextSymbol != null && nextAmount != null) {
        HomeUpcomingDividend(
            symbol = nextSymbol,
            estimatedAmount = nextAmount,
            estimatedExDate = LocalDate.parse(calendar.tradingDay(nextEpochSeconds)),
        )
    } else {
        null
    }

    return HomeIncomeSummary(receivedYTD = receivedYTD, nextDividend = nextDividend)
}

// --- UTC epoch-day civil-date math (private copy) -------------------------------------
// DividendMath.kt/MarketCalendar.kt/IncomeViewModel.kt/PieSchedule.kt all implement this
// exact Hinnant civil-date algorithm, privately, per this codebase's own established
// precedent (see those files' header docs) of each file keeping its OWN copy rather than
// widening visibility to share it. Only the minimal piece this file needs (UTC epoch-
// seconds -> UTC calendar year, for the receivedYTD "same year as asOf" filter above) is
// reproduced here.

private fun utcYear(epochSeconds: Long): Long {
    val epochDay = homeFloorDiv(epochSeconds, 86_400L)
    return homeCivilYearFromDays(epochDay)
}

private fun homeFloorDiv(x: Long, y: Long): Long {
    val q = x / y
    return if ((x xor y) < 0 && q * y != x) q - 1 else q
}

private fun homeCivilYearFromDays(z0: Long): Long {
    val z = z0 + 719_468L
    val era = homeFloorDiv(z, 146_097L)
    val doe = z - era * 146_097L // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
    val mp = (5 * doy + 2) / 153 // [0, 11]
    val m = if (mp < 10) mp + 3 else mp - 9 // [1, 12]
    return if (m <= 2) y + 1 else y
}

/** Serializes [persistSettings]'s load-merge-save (6d.2 Task 4 — closes the RMW
 *  lost-update window recorded by 6d.1's final review): the settings blob is re-loaded
 *  from the store *inside* the lock (never taken from a possibly-stale caller snapshot),
 *  so two concurrent mutators (e.g. an accent change and a notification-toggle flip fired
 *  in quick succession) serialize against the single-blob store instead of one clobbering
 *  the other's write. A single file-level [Mutex] is correct here (rather than one per
 *  call site) because `AppGraph.settingsStore` is itself a single process-wide instance
 *  shared by every caller — same shape as the single shared `portfolioMutex` this file
 *  hands to both [BuyAsset] and [SellAsset] to guard [PortfolioStore]'s RMW. */
private val settingsMutex = Mutex()

/**
 * The settings load-merge-save seam (review fix for Task 5): reads the persisted
 * [AppSettings] blob, applies [mutate], and writes the result back — so two independent
 * mutators (accent selection, per-toggle notification settings) sharing one single-blob
 * store never clobber each other's fields. Extracted as a standalone, package-visible
 * suspend function (mirroring [buildNotifyOrderFill]'s placement/rationale) so a test can
 * exercise the REAL load-merge-save sequence against a real [FileSettingsStore] (temp-file
 * backed, no AWT/Compose dependency) rather than only the store's own load/save round-trip.
 *
 * The load->mutate->save sequence itself runs under [settingsMutex] (6d.2 Task 4): without
 * it, two concurrent callers could both load the same pre-mutation blob and the second
 * save would silently discard the first mutation. This is the exact sequence `Main.kt`'s
 * local `persistSettings` function used inline (load → mutate → save), which itself
 * replaced an earlier bug where `selectAccent` wrote a fresh `AppSettings(accent = theme)`,
 * silently resetting every notification flag to its default on every accent change.
 */
internal suspend fun persistSettings(
    settingsStore: FileSettingsStore,
    mutate: (AppSettings) -> AppSettings,
) = settingsMutex.withLock {
    val current = settingsStore.load()
    settingsStore.save(mutate(current))
}
