package com.aptrade.android

import android.content.Context
import com.aptrade.android.alerts.AndroidAlertNotifier
import com.aptrade.android.alerts.AndroidMarketActivityCoordinator
import com.aptrade.android.calendar.sessionLabel
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.AlertNotifier
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.ContributeToPie
import com.aptrade.shared.application.ContributionOutcome
import com.aptrade.shared.application.CreatePriceAlert
import com.aptrade.shared.application.DeletePie
import com.aptrade.shared.application.DividendOutcome
import com.aptrade.shared.application.EarningsCalendarRepository
import com.aptrade.shared.application.EmptyEarningsRepository
import com.aptrade.shared.application.EvaluateAlerts
import com.aptrade.shared.application.ExecuteDueContributions
import com.aptrade.shared.application.FetchChartWindow
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
import com.aptrade.shared.application.IncomeSummaryMath
import com.aptrade.shared.application.LoadAlerts
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.LoadPies
import com.aptrade.shared.application.MarketActivityPlanner
import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.NewsRepository
import com.aptrade.shared.application.PieStore
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.ProcessDueDividends
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
import com.aptrade.shared.application.normalized
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.infrastructure.FileAlertStore
import com.aptrade.shared.infrastructure.FileBookmarkStore
import com.aptrade.shared.infrastructure.FilePieStore
import com.aptrade.shared.infrastructure.FilePortfolioStore
import com.aptrade.shared.infrastructure.FileScreenerSnapshotStore
import com.aptrade.shared.infrastructure.FileScreenStore
import com.aptrade.shared.infrastructure.FileSchedulerStateStore
import com.aptrade.shared.infrastructure.FileSettingsStore
import com.aptrade.shared.infrastructure.FileWatchlistStore
import com.aptrade.shared.infrastructure.FinnhubEarningsRepository
import com.aptrade.shared.infrastructure.FinnhubKeyConfig
import com.aptrade.shared.infrastructure.FinnhubNewsRepository
import com.aptrade.shared.infrastructure.YahooMarketDataRepository
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import java.io.File
import java.nio.file.Path

/**
 * Process-wide composition root, mirroring the Swift CompositionRoot's `static let`
 * pattern. Exactly ONE YahooMarketDataRepository (and therefore ONE Ktor HttpClient
 * with its own connection pool) exists per process — constructing a repository per
 * ViewModel would leak a new never-closed client each time.
 *
 * The portfolio graph needs an Android [File] (app-private storage), which the market
 * use cases do not. Rather than thread a Context through every construction site, the
 * Activity calls [initialize] with `filesDir` exactly once, BEFORE any screen is
 * composed (MainActivity.onCreate, before setContent). The portfolio graph is then
 * materialized lazily on first access. Accessing it before [initialize] is a programmer
 * error and fails fast rather than silently writing to the wrong location.
 */
object AppGraph {
    private val repository: MarketDataRepository = YahooMarketDataRepository()

    val fetchMarketQuotes = FetchMarketQuotes(repository)
    val fetchSearch = FetchSearch(repository)
    val fetchProfile = FetchProfile(repository)
    val fetchHistory = FetchHistory(repository)
    // Indicator warm-up lookback pad + visible window (desktop parity) — replaces the plain
    // window-only FetchCandles as DetailViewModel's sole candle source, since Candles-mode
    // rendering and indicator math now share the one fetch.
    val fetchChartWindow = FetchChartWindow(repository)

    // The macOS app's seed watchlist.
    val defaultSymbols = listOf("AAPL", "SPY", "BTC-USD", "ETH-USD")

    private var filesDir: File? = null
    private var appContext: Context? = null

    /** Supply the app-private storage directory and an application [Context] (for the
     *  [AndroidAlertNotifier]'s `NotificationManager`). Call once from MainActivity.onCreate
     *  BEFORE setContent, so [portfolio]/[alertNotifier] are ready by the time any screen
     *  composes. Stores `context.applicationContext` (never the Activity itself) since the
     *  notifier is expected to outlive any single Activity instance. */
    fun initialize(context: Context) {
        this.filesDir = context.filesDir
        this.appContext = context.applicationContext
    }

    /** Portfolio use cases, built lazily from the [filesDir] provided by [initialize].
     *  Shares the single process-wide [repository]. Also carries the Investment Plans (Pies)
     *  use cases (M7.3 Task 2) — see [PortfolioGraph]'s KDoc for why they live here rather
     *  than as their own top-level AppGraph vals. */
    val portfolio: PortfolioGraph by lazy {
        val dir = requireNotNull(filesDir) {
            "AppGraph.initialize(context) must be called before accessing AppGraph.portfolio"
        }
        PortfolioGraph(
            repository,
            FilePortfolioStore(File(dir, "portfolio.json").toPath()),
            FilePieStore(configDir().resolve("pies.json")),
            schedulerStateStore,
            isDripEnabled = { settingsStore.load().dripEnabled },
        )
    }

    /** App-private config directory (`filesDir/aptrade`), mirroring the desktop
     *  ConfigDir wiring but rooted in Android's sandbox rather than a platform-specific
     *  user config path. Lazily-backed stores below resolve their JSON file against it. */
    private fun configDir(): Path {
        val dir = requireNotNull(filesDir) { "AppGraph.initialize(context) must run before stores are touched" }
        return dir.toPath().resolve("aptrade")
    }

    // Watchlist + alerts + news + settings — all against app-private filesDir, mirroring
    // the desktop ConfigDir wiring but rooted in Android's sandbox. News use cases are
    // wired in their own task (7); the store is created now so that task only needs to
    // add use-case vals against it.
    val watchlistStore by lazy { FileWatchlistStore(configDir().resolve("watchlist.json")) }
    val alertStore by lazy { FileAlertStore(configDir().resolve("alerts.json")) }
    val settingsStore by lazy { FileSettingsStore(configDir().resolve("settings.json")) }
    val bookmarkStore by lazy { FileBookmarkStore(configDir().resolve("bookmarks.json")) }

    // Market-activity scheduler markers (Task 8) — same filename desktop's AppGraph uses
    // (resolveConfigDir().resolve("schedulerState.json")), rooted at THIS app's configDir()
    // instead, mirroring every other store above. Backed by the promoted
    // `com.aptrade.shared.infrastructure.FileSchedulerStateStore` (moved out of
    // desktop-only `com.aptrade.desktop.infra` so both platforms share one implementation).
    val schedulerStateStore: SchedulerStateStore by lazy {
        FileSchedulerStateStore(configDir().resolve("schedulerState.json"))
    }
    val marketActivityPlanner: MarketActivityPlanner by lazy { MarketActivityPlanner() }

    // News (Task 7). FinnhubKeyConfig's default `configDir` param (`resolveConfigDir()`)
    // targets a desktop home-dir convention — rooted here explicitly at THIS app's own
    // configDir() (filesDir/aptrade) instead, so the key resolves from the Android app
    // sandbox rather than a path that doesn't exist/apply on this platform. (Its secondary
    // `{userHome}/.config/aptrade/config.json` fallback is left at its default; Android's
    // `user.home` system property doesn't point anywhere meaningful, so that fallback simply
    // never matches here — harmless.)
    private val finnhubKeyConfig by lazy { FinnhubKeyConfig(configDir = configDir()) }

    /** Exposed for the Settings screen's key-entry field (read current key / save a new one).
     *  Saving writes the same config.json [newsRepository] below re-reads, so no explicit
     *  refresh hook is needed. */
    val finnhubKey: FinnhubKeyConfig get() = finnhubKeyConfig

    // Null when no Finnhub key is configured; the News tab reads this via NewsViewModel's
    // `needsKey` rather than attempting requests that would all fail. Diverges from desktop
    // AppGraph's startup-frozen `newsRepository`/`keyMissing` val pair: Android has an in-app
    // key-entry field (the sandboxed config.json isn't user-reachable), so the key is re-read
    // per access and the repository rebuilt ONLY when it actually changed — never per call,
    // since each FinnhubNewsRepository owns a Ktor client (the CompositionRoot one-instance
    // rule from the macOS retrofit).
    private val newsRepositoryLock = Any()
    private var newsRepositoryKey: String? = null
    private var newsRepositoryInstance: NewsRepository? = null
    val newsRepository: NewsRepository?
        get() = synchronized(newsRepositoryLock) {
            val key = finnhubKeyConfig.finnhubApiKey()
            if (key != newsRepositoryKey) {
                newsRepositoryKey = key
                newsRepositoryInstance = key?.let { FinnhubNewsRepository(it) }
            }
            newsRepositoryInstance
        }
    val loadBookmarks by lazy { LoadBookmarks(bookmarkStore) }
    val toggleBookmark by lazy { ToggleBookmark(bookmarkStore) }
    val fetchMarketNews: FetchMarketNews? get() = newsRepository?.let { FetchMarketNews(it) }

    // Earnings calendar (Task 8). Mirrors newsRepository's key-caching getter EXACTLY (same
    // re-read-per-access, rebuild-only-on-change shape, one Ktor client per key) but is never
    // null: with no key it falls back to [EmptyEarningsRepository], same as desktop AppGraph's
    // non-nullable `earningsRepository` — the market-activity coordinator's earnings check
    // (and any future Calendar tab) can always call it without a null check.
    private val earningsRepositoryLock = Any()
    private var earningsRepositoryKey: String? = null
    private var earningsRepositoryInstance: EarningsCalendarRepository = EmptyEarningsRepository
    val earningsRepository: EarningsCalendarRepository
        get() = synchronized(earningsRepositoryLock) {
            val key = finnhubKeyConfig.finnhubApiKey()
            if (key != earningsRepositoryKey) {
                earningsRepositoryKey = key
                earningsRepositoryInstance = key?.let { FinnhubEarningsRepository(it) } ?: EmptyEarningsRepository
            }
            earningsRepositoryInstance
        }
    val earningsKeyMissing: Boolean get() = finnhubKeyConfig.finnhubApiKey() == null

    // Shared symbol-ownership provider: watchlist ∪ portfolio, read fresh per call — the
    // Android counterpart to desktop AppGraph's public `ownSymbols` (used there by both the
    // coordinator's digestSummary()-adjacent earnings check and the Calendar tab's ViewModel).
    // `portfolio` is the lazy PortfolioGraph below (requires AppGraph.initialize() first, same
    // precondition as configDir()).
    val ownSymbols: suspend () -> Set<String> = {
        val watchlistSymbols = fetchWatchlist.execute().map { it.symbol }
        val portfolioSymbols = portfolio.fetchPortfolio.execute().positions.map { it.asset.symbol }
        (watchlistSymbols + portfolioSymbols).toSet()
    }
    val fetchEarningsCalendar: FetchEarningsCalendar get() = FetchEarningsCalendar(earningsRepository, ownSymbols)

    // Alerts & notifications (Task 6). The notifier seam mirrors desktop's AppGraph: one
    // AlertNotifier instance shared by EvaluateAlerts, backed here by Android's
    // NotificationManager rather than desktop's Tray. Typed to the concrete class (not the
    // AlertNotifier interface) so [notifyOrderFill] below can also reach its `notifyFill`
    // extra method — the same one instance backs both, same as desktop's single TrayNotifier
    // implementing AlertNotifier AND exposing notifyFill/notifyMarketStatus/notifyDigest.
    val alertNotifier: AndroidAlertNotifier by lazy {
        val context = requireNotNull(appContext) {
            "AppGraph.initialize(context) must be called before accessing AppGraph.alertNotifier"
        }
        AndroidAlertNotifier(context)
    }
    val loadAlerts by lazy { LoadAlerts(alertStore) }
    val createPriceAlert by lazy { CreatePriceAlert(alertStore) }
    val removePriceAlert by lazy { RemovePriceAlert(alertStore) }
    val evaluateAlerts by lazy {
        EvaluateAlerts(
            store = alertStore,
            notifier = alertNotifier,
            isNotifyEnabled = { settingsStore.load().priceAlerts },
        )
    }

    // Market-local trading-day calendar (Task 8) — same class desktop's Main.kt instantiates
    // (`MarketCalendar()`), used to key `fetchTodaysOwnEarnings`'s "today" against the exact
    // trading day the coordinator's own 60s tick considers current.
    private val marketCalendar: MarketCalendar by lazy { MarketCalendar() }

    // Screener (M9.3 Task 2) — full-universe technical scans over the S&P 500. Shares the
    // SAME market repository/[marketCalendar] every other read-only VM factory here reads
    // from (screening never mutates the portfolio, so it needs no mutex, mirroring desktop
    // AppGraph's screener wiring — desktopApp/.../AppGraph.kt:221-249). [screenerSnapshotStore]/
    // [screenStore] follow the SAME injected-Path, atomic-write file-store shape as
    // [pieStore]/[portfolioStore] above (see `FileScreenerSnapshotStore`/`FileScreenStore`'s
    // own KDoc). No factory function here — unlike desktop's `makeScreenerViewModel`, the
    // Android Screener screen (Task 3) constructs `ScreenerViewModel` directly via
    // `viewModel { }`, per the `DetailScreen.kt:84` precedent — so only the engine + both
    // stores are exposed as graph vals for that call site to read.
    val screenerScanEngine: ScreenerScanEngine by lazy { ScreenerScanEngine(repository, marketCalendar) }
    val screenerSnapshotStore: FileScreenerSnapshotStore by lazy {
        FileScreenerSnapshotStore(configDir().resolve("screener-snapshot.json"))
    }
    val screenStore: FileScreenStore by lazy { FileScreenStore(configDir().resolve("screens.json")) }

    /**
     * Builds a fresh [HomeFeedAssembler] for the Home dashboard (M10.3 Task 1) — the Android
     * twin of desktop `AppGraph.makeHomeViewModel`'s wiring (see that factory's own KDoc for
     * the full source-by-source rationale, carried over verbatim below): every source is an
     * EXISTING graph piece, nothing new is constructed for Home alone.
     *
     * Returns the ASSEMBLER, not a constructed [HomeViewModel] — mirroring the Screener
     * precedent immediately above ([screenerScanEngine]/[screenerSnapshotStore]/[screenStore]
     * exposed as plain vals/factories, `ScreenerViewModel` itself constructed by the screen's
     * own `viewModel { }` call, `DetailScreen.kt:84`): [HomeViewModel] is an androidx
     * `ViewModel` constructed directly by ITS screen (Task 3's `HomeScreen`), not by a
     * graph-owned VM factory — desktop's `AppGraph` returns the VM because desktop VMs take a
     * constructor-injected `CoroutineScope`; Android VMs use their own `viewModelScope`
     * instead (see [com.aptrade.android.plans.PlansViewModel]'s KDoc), so there is no `scope`
     * for a graph factory to thread through here.
     *
     * WIRING (mirrors desktop's makeHomeViewModel exactly):
     *  - `loadPortfolio`/`fetchQuotes` are the SAME [PortfolioGraph.fetchPortfolio]/
     *    [fetchMarketQuotes] use cases every other read-only VM reads from.
     *  - `ownSymbols` is the SAME watchlist-∪-portfolio closure [fetchEarningsCalendar]
     *    already shares (see that val's own KDoc) — Home's movers row dedupes against the
     *    identical "mine" set the rest of the app already uses.
     *  - `loadIncomeSummary` wraps [buildHomeIncomeSummary] (below), which itself delegates
     *    to [IncomeSummaryMath] (`:shared`, M10.3 Task 1's hoist) — never a second
     *    recomputation of dividend math.
     *  - `fetchNextEarnings` reuses [fetchEarningsCalendar]'s existing owned-first,
     *    day-ascending sort over the SAME 14-day window, filtered to owned/watched symbols.
     *  - `loadScreenerSnapshot`/`loadAlerts` read the SAME [screenerSnapshotStore]/
     *    [loadAlerts] the Screener screen and Alerts center already read from.
     *  - `calendar` is the SAME [marketCalendar] instance every other read-only VM factory
     *    here shares, so "today"/"soonest" mean the same trading day everywhere.
     *
     * STRICTMODE I/O SEAM (M10.3 Global Constraint 1, binding): every source below that
     * touches a file-backed store is wrapped in `withContext(Dispatchers.IO)` AT THIS SEAM —
     * the assembler itself is platform-agnostic Kotlin with no dispatcher opinion, and
     * Android (unlike desktop, whose Main dispatcher has no StrictMode-equivalent
     * enforcement) must never let disk I/O run on the Main thread this is invoked from
     * (`viewModelScope`, i.e. `Dispatchers.Main.immediate`). [PortfolioGraph.fetchPortfolio]/
     * [loadAlerts] already hop to `Dispatchers.IO` internally (their stores,
     * `FilePortfolioStore`/`FileAlertStore`, wrap `load`/`save` themselves) — wrapped again
     * here anyway, defensively, so this seam's own StrictMode contract never silently depends
     * on an internal implementation detail of a store it doesn't own. [FileScreenerSnapshotStore]
     * is the one store that does NOT self-wrap (it implements the non-suspend, synchronous
     * `ScreenerSnapshotStore` port — see that class's own KDoc for why) — for THAT one, the
     * `withContext` wrap below is the ONLY thing standing between a screener-freshness check
     * and a StrictMode disk-read-on-main violation.
     */
    fun makeHomeFeedAssembler(
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): HomeFeedAssembler {
        val fetchNextOwnedEarnings: suspend () -> EarningsEvent? = {
            val start = marketCalendar.localEpochDay(nowEpochSeconds())
            val fromDay = marketCalendar.dayString(start)
            val toDay = marketCalendar.dayString(start + 13)
            val events = fetchEarningsCalendar.execute(fromDay, toDay)
            val own = ownSymbols().mapTo(HashSet(), ::normalized)
            events.firstOrNull { normalized(it.symbol) in own }
        }
        return HomeFeedAssembler(
            loadPortfolio = { withContext(Dispatchers.IO) { portfolio.fetchPortfolio.execute() } },
            fetchQuotes = { symbols -> fetchMarketQuotes.execute(symbols).associateBy { it.symbol } },
            ownSymbols = ownSymbols,
            loadIncomeSummary = {
                buildHomeIncomeSummary(portfolio.fetchPortfolio, portfolio.repository, marketCalendar, nowEpochSeconds)
            },
            fetchNextEarnings = fetchNextOwnedEarnings,
            loadScreenerSnapshot = { withContext(Dispatchers.IO) { screenerSnapshotStore.load() } },
            loadAlerts = { withContext(Dispatchers.IO) { loadAlerts.execute() } },
            calendar = marketCalendar,
            nowEpochSeconds = nowEpochSeconds,
        )
    }

    /**
     * Builds the market-activity coordinator (open/close + digest + earnings-day
     * notifications, Task 8) — a direct port of desktop's wiring in
     * `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (search
     * `marketActivityCoordinator`). All L10n formatting happens HERE (the wiring site, where
     * `tr`/`trf` are imported) rather than inside [AndroidMarketActivityCoordinator] itself —
     * same "coordinator stays L10n-ignorant" shape desktop keeps, see that class's KDoc.
     *
     * A factory function (not a lazy singleton val) because [scope] must be supplied by the
     * caller (MainActivity) so it can be cancelled alongside the caller's own lifecycle —
     * [scope] MUST be single-thread-confined (Dispatchers.Main), matching desktop's `appScope`
     * and this coordinator's own KDoc requirement.
     */
    fun marketActivityCoordinator(
        scope: CoroutineScope,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): AndroidMarketActivityCoordinator = AndroidMarketActivityCoordinator(
        planner = marketActivityPlanner,
        stateStore = schedulerStateStore,
        loadSettings = { settingsStore.load() },
        notifyMarketStatus = { opened -> alertNotifier.notifyMarketStatus(opened) },
        notifyDigest = { summary -> alertNotifier.notifyDigest(summary) },
        notifyEarnings = { event ->
            // sessionLabel(Unknown) is "" (no L10n key for it); every language's
            // EarningsTodayBodyFmt ends with "· %2$s" (EN/DE/IT/ES all verified), so an
            // empty label would leave a dangling "… · " — trim the orphaned separator in
            // that one case only, never when a real session label is present. Mirrors
            // desktop Main.kt's notifyEarnings closure verbatim.
            val label = sessionLabel(event.session)
            val body = trf(L10n.Key.EarningsTodayBodyFmt, event.symbol, label)
            alertNotifier.notifyEarnings(
                title = tr(L10n.Key.EarningsTodayTitle),
                body = if (label.isEmpty()) body.trimEnd(' ', '·') else body,
            )
        },
        fetchTodaysOwnEarnings = {
            val today = marketCalendar.tradingDay(nowEpochSeconds())
            fetchEarningsCalendar.ownedToday(today)
        },
        executeDueContributions = { now -> portfolio.executeDueContributions.execute(now) },
        // Same L10n-here, coordinator-stays-ignorant split as notifyEarnings above: the
        // coordinator hands back the typed ContributionOutcome it produced, and only this
        // (wiring-site) closure resolves the executed/skipped title+body via tr/trf. Mirrors
        // desktop Main.kt's notifyPieContribution closure verbatim.
        notifyPieContribution = { outcome ->
            when (outcome) {
                is ContributionOutcome.Executed ->
                    alertNotifier.notifyPieContribution(
                        title = tr(L10n.Key.NotifPieExecutedTitle),
                        body = trf(L10n.Key.NotifPieExecutedBody, outcome.pie.name),
                    )
                is ContributionOutcome.SkippedInsufficientCash ->
                    alertNotifier.notifyPieContribution(
                        title = tr(L10n.Key.NotifPieSkippedTitle),
                        body = trf(L10n.Key.NotifPieSkippedBody, outcome.pie.name),
                    )
            }
        },
        processDueDividends = { now -> portfolio.processDueDividends.execute(now) },
        // Same L10n-here, coordinator-stays-ignorant split as notifyPieContribution above:
        // the coordinator hands back the typed DividendOutcome(s) it produced (or, for a
        // collapsed backfill run, the tallied count + summed cash), and only this
        // (wiring-site) closure resolves the cash/DRIP/backfill-summary title+body via
        // tr/trf. Mirrors desktop Main.kt's notifyDividendOutcome closure verbatim.
        notifyDividendOutcome = { outcome ->
            when (outcome) {
                is DividendOutcome.Credited ->
                    alertNotifier.notifyDividend(
                        title = tr(L10n.Key.NotifDividendTitle),
                        body = trf(L10n.Key.NotifDividendCashBodyFmt, outcome.symbol, outcome.cash.formatted),
                    )
                is DividendOutcome.Reinvested ->
                    alertNotifier.notifyDividend(
                        title = tr(L10n.Key.NotifDividendTitle),
                        body = trf(L10n.Key.NotifDividendDripBodyFmt, outcome.symbol, outcome.cash.formatted),
                    )
            }
        },
        notifyDividendBackfillSummary = { count, totalCash ->
            alertNotifier.notifyDividend(
                title = tr(L10n.Key.NotifDividendTitle),
                body = trf(L10n.Key.NotifDividendBackfillBodyFmt, count.toString(), totalCash.formatted),
            )
        },
        fetchWatchlist = fetchWatchlist,
        fetchMarketQuotes = fetchMarketQuotes,
        scope = scope,
        nowEpochSeconds = nowEpochSeconds,
        calendar = marketCalendar,
    )

    // Settings-gated order-fill delivery (spec A2) — the Android port of desktop AppGraph's
    // `notifyOrderFill` seam (`AppGraphNotifyOrderFill` pattern): read `settings.orderFills`
    // once per call and only deliver when it's on. Handed to PortfolioViewModel/DetailViewModel
    // as a plain suspend closure, same shape as desktop hands it to its PortfolioViewModel.
    // The gate itself is [buildNotifyOrderFill] (below), extracted so a test can pin it against
    // a real FileSettingsStore without needing a real AndroidAlertNotifier/Context/
    // NotificationManager — mirrors desktop's `AppGraphNotifyOrderFillTest` rationale exactly.
    val notifyOrderFill: suspend (TradeSide, String, String, String) -> Unit by lazy {
        buildNotifyOrderFill(settingsStore) { side, symbol, quantityText, amountFormatted ->
            alertNotifier.notifyFill(side, symbol, quantityText, amountFormatted)
        }
    }

    // The macOS app's seed watchlist (parity with desktopApp's AppGraph.defaultEntries).
    private val defaultWatchlistEntries = listOf(
        WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
        WatchlistEntry("SPY", "SPDR S&P 500 ETF Trust", AssetKind.Etf),
        WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto),
        WatchlistEntry("ETH-USD", "Ethereum USD", AssetKind.Crypto),
    )

    val fetchWatchlist by lazy { FetchWatchlist(watchlistStore, defaultWatchlistEntries) }
    val addToWatchlist by lazy { AddToWatchlist(watchlistStore) }
    val removeFromWatchlist by lazy { RemoveFromWatchlist(watchlistStore) }
}

/**
 * Builds the settings-gated order-fill closure: reads `settingsStore.load().orderFills`
 * once per call and only invokes [deliver] when it's on. Extracted as a standalone,
 * package-visible function (rather than inlined in [AppGraph]'s `notifyOrderFill` lazy val)
 * so a test can construct the REAL gate against a real [FileSettingsStore] (a temp-file
 * store, no Context/NotificationManager dependency) with a recording `deliver` fake, and pin
 * `orderFills = true`/`false` both ways — see `AppGraphNotifyOrderFillTest`. Direct port of
 * desktop's `buildNotifyOrderFill` (`desktopApp/src/main/kotlin/com/aptrade/desktop/
 * AppGraph.kt`) — same signature, same behavior, no divergence.
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
// PortfolioViewModel's DIVIDEND_EVENTS_LOOKBACK_SECONDS / IncomeViewModel's lookbackStart
// (both 730 days = two years) — the SAME constant desktop AppGraph.kt's
// HOME_DIVIDEND_EVENTS_LOOKBACK_SECONDS uses, duplicated per this codebase's own established
// precedent of each file keeping its own private copy of small domain constants.
private const val HOME_DIVIDEND_EVENTS_LOOKBACK_SECONDS = 730L * 86_400L

/**
 * Builds the [HomeIncomeSummary] [HomeFeedAssembler] wants — Android twin of desktop
 * `AppGraph.buildHomeIncomeSummary`, transcribed near-verbatim (both now delegate to the SAME
 * [IncomeSummaryMath] hoisted functions, M10.3 Task 1, so there is nothing left to diverge on
 * between the two platforms' wiring here).
 *
 * DAY-TYPE SEAM (mirrors desktop's own doc on this exact function): [IncomeSummaryMath
 * .nextUpcomingDividend]'s result stays on the epoch-seconds ex-date convention deliberately;
 * [HomeUpcomingDividend.estimatedExDate] wants a `kotlinx.datetime.LocalDate`. That conversion
 * happens EXACTLY ONCE, here, at this boundary, via [MarketCalendar.tradingDay] — the SAME
 * market-local (ET) convention every other trading-day computation in this codebase already
 * uses — rather than round-tripping through `java.time`/`ZoneId` arithmetic.
 *
 * STRICTMODE I/O SEAM (M10.3 Global Constraint 1): [fetchPortfolio]'s underlying
 * [PortfolioStore] (`FilePortfolioStore`) already self-wraps `load()` in
 * `withContext(Dispatchers.IO)` — wrapped again here anyway, defensively, matching
 * [makeHomeFeedAssembler]'s own `loadPortfolio` wrap, so this function's own I/O contract
 * never silently depends on an internal detail of a store it doesn't own.
 */
internal suspend fun buildHomeIncomeSummary(
    fetchPortfolio: FetchPortfolio,
    repository: MarketDataRepository,
    calendar: MarketCalendar,
    nowEpochSeconds: () -> Long,
): HomeIncomeSummary {
    val portfolioSnapshot = withContext(Dispatchers.IO) { fetchPortfolio.execute() }
    val asOf = nowEpochSeconds()

    val receivedYTD = IncomeSummaryMath.receivedYTD(portfolioSnapshot.transactions, asOf)

    val nextProjection = IncomeSummaryMath.nextUpcomingDividend(
        positions = portfolioSnapshot.positions,
        dividendEventsFetcher = { symbol, sinceEpochSeconds -> repository.dividendEvents(symbol, sinceEpochSeconds) },
        asOfEpochSeconds = asOf,
        lookbackSeconds = HOME_DIVIDEND_EVENTS_LOOKBACK_SECONDS,
    )

    val nextDividend = nextProjection?.let {
        HomeUpcomingDividend(
            symbol = it.symbol,
            estimatedAmount = it.estimatedAmount,
            estimatedExDate = LocalDate.parse(calendar.tradingDay(it.exDateEpochSeconds)),
        )
    }

    return HomeIncomeSummary(receivedYTD = receivedYTD, nextDividend = nextDividend)
}

/** The portfolio slice of the composition root: everything that depends on the
 *  [PortfolioStore]. Groups the trade + read + performance use cases sharing one store, plus
 *  (M7.3 Task 2) the Investment Plans (Pies) use cases — every mutating one shares the SAME
 *  [portfolioMutex] as [buyAsset]/[sellAsset]/[resetPortfolio] below, mirroring desktop
 *  `AppGraph.kt:119-141`: pie contributions/rebalances/reconciliation and plain buy/sell all
 *  read-modify-write the same [portfolioStore], so one shared lock is what makes them mutually
 *  exclusive rather than only exclusive within their own use case. Pie use cases are grouped
 *  HERE (rather than as top-level `AppGraph` vals, as `loadPies`/`savePie`/etc. are on desktop)
 *  because [portfolioMutex] is only constructible where [portfolioStore] is in scope, and this
 *  class is that scope on Android (`AppGraph.portfolioMutex` has no equivalent — the desktop
 *  `AppGraph` is a single flat class, this one splits the portfolio-dependent slice out). */
class PortfolioGraph(
    // repository/portfolioStore are public vals (M8.3 Task 2): IncomeScreen's viewModel { }
    // factory builds IncomeViewModel straight from portfolio.repository/portfolio.portfolioStore/
    // portfolio.marketCalendar, mirroring desktop AppGraph's flat portfolioStore/repository/
    // marketCalendar vals that makeIncomeViewModel reads from.
    val repository: MarketDataRepository,
    val portfolioStore: PortfolioStore,
    pieStore: PieStore,
    // M8.3 Task 4: [processDueDividends] below needs the scheduler's persisted state (first-run
    // day marker) and a live settings read — both live outside this class, so they arrive as
    // constructor params from AppGraph (which owns `schedulerStateStore`/`settingsStore`),
    // mirroring desktop AppGraph's flat wiring exactly.
    stateStore: SchedulerStateStore,
    isDripEnabled: suspend () -> Boolean,
) {
    val fetchPortfolio = FetchPortfolio(portfolioStore)

    // ONE shared Mutex, handed to BOTH use cases below: BuyAsset and SellAsset each perform
    // a load->validate->save RMW against this SAME portfolioStore, so only a mutex shared
    // between the two serializes a concurrent buy against a concurrent sell (two separate
    // `Mutex()` instances would only serialize buy-vs-buy and sell-vs-sell). See the KDoc on
    // BuyAsset/SellAsset's `portfolioMutex` parameter.
    private val portfolioMutex = Mutex()
    val buyAsset = BuyAsset(repository, portfolioStore, portfolioMutex)
    val sellAsset = SellAsset(repository, portfolioStore, portfolioMutex)
    val resetPortfolio = ResetPortfolio(portfolioStore, portfolioMutex)
    val fetchPortfolioPerformance = FetchPortfolioPerformance(repository, portfolioStore)
    val fetchPerformanceReport = FetchPerformanceReport(repository, fetchPortfolioPerformance)

    // Investment Plans (Pies) — M7.3 Task 2. marketCalendar is stateless (see MarketCalendar's
    // KDoc) — its own instance here, scoped to this graph, shared by every pie use case below
    // exactly like desktop AppGraph's single `marketCalendar` val.
    val marketCalendar = MarketCalendar()
    val loadPies = LoadPies(pieStore)
    val savePie = SavePie(pieStore, portfolioMutex)
    val deletePie = DeletePie(pieStore, portfolioMutex)
    val contributeToPie = ContributeToPie(pieStore, portfolioStore, repository, portfolioMutex)
    // M7.3 Task 3: the coordinator's launch catch-up + ContributionCheckDue handler. Shares
    // THE same portfolioMutex as every other mutating use case above, mirroring desktop
    // AppGraph.kt:138 exactly.
    val executeDueContributions = ExecuteDueContributions(pieStore, portfolioStore, repository, marketCalendar, portfolioMutex)
    val rebalancePie = RebalancePie(pieStore, portfolioStore, repository, portfolioMutex)
    val reconcilePieLedgers = ReconcilePieLedgers(pieStore, portfolioStore, portfolioMutex, marketCalendar)
    val simulateDCA = SimulateDCA(repository, marketCalendar)

    // Dividend crediting (M8.3 Task 4, mirroring desktop's M8.2 Task 10). Shares the SAME
    // portfolioMutex as every other mutating portfolio use case above (BuyAsset's co-holder
    // doc contract) — the engine read-modify-writes portfolioStore just like a buy/sell/
    // contribution does, so one shared lock is what makes them all mutually exclusive.
    // `isDripEnabled` is `suspend` (Recorded divergence, see ProcessDueDividends' KDoc: Swift's
    // `SettingsStore` is sync `UserDefaults`; Kotlin's store is suspend IO) and reads the live
    // `dripEnabled` toggle fresh on every call — never captured once at construction time —
    // since the user can flip it at any point during a run.
    val processDueDividends = ProcessDueDividends(
        portfolioStore = portfolioStore,
        market = repository,
        stateStore = stateStore,
        calendar = marketCalendar,
        portfolioMutex = portfolioMutex,
        isDripEnabled = isDripEnabled,
    )
}
