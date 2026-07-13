package com.aptrade.android

import android.content.Context
import com.aptrade.android.alerts.AndroidAlertNotifier
import com.aptrade.android.alerts.AndroidMarketActivityCoordinator
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.AlertNotifier
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.CreatePriceAlert
import com.aptrade.shared.application.EarningsCalendarRepository
import com.aptrade.shared.application.EmptyEarningsRepository
import com.aptrade.shared.application.EvaluateAlerts
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
import com.aptrade.shared.application.LoadAlerts
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.MarketActivityPlanner
import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.NewsRepository
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.RemovePriceAlert
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SchedulerStateStore
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.infrastructure.FileAlertStore
import com.aptrade.shared.infrastructure.FileBookmarkStore
import com.aptrade.shared.infrastructure.FilePortfolioStore
import com.aptrade.shared.infrastructure.FileSchedulerStateStore
import com.aptrade.shared.infrastructure.FileSettingsStore
import com.aptrade.shared.infrastructure.FileWatchlistStore
import com.aptrade.shared.infrastructure.FinnhubEarningsRepository
import com.aptrade.shared.infrastructure.FinnhubKeyConfig
import com.aptrade.shared.infrastructure.FinnhubNewsRepository
import com.aptrade.shared.infrastructure.YahooMarketDataRepository
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
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
     *  Shares the single process-wide [repository]. */
    val portfolio: PortfolioGraph by lazy {
        val dir = requireNotNull(filesDir) {
            "AppGraph.initialize(context) must be called before accessing AppGraph.portfolio"
        }
        PortfolioGraph(repository, FilePortfolioStore(File(dir, "portfolio.json").toPath()))
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
        fetchWatchlist = fetchWatchlist,
        fetchMarketQuotes = fetchMarketQuotes,
        scope = scope,
        nowEpochSeconds = nowEpochSeconds,
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

/**
 * [EarningsSession] -> localized label (Task 8). Direct port of desktop's
 * `sessionLabel` (`desktopApp/src/main/kotlin/com/aptrade/desktop/calendar/CalendarPane.kt`),
 * used there by both the Calendar tab's earnings rows and Main.kt's earnings-notification
 * wiring. Android has no Calendar tab yet (a later task), so this copy exists solely for
 * [AppGraph.marketActivityCoordinator]'s `notifyEarnings` closure — when the Calendar tab
 * lands on Android, move this into that screen's own file and share the one definition,
 * mirroring how desktop consolidated it into CalendarPane.kt.
 */
internal fun sessionLabel(session: EarningsSession): String = when (session) {
    EarningsSession.BeforeOpen -> tr(L10n.Key.SessionBeforeOpen)
    EarningsSession.AfterClose -> tr(L10n.Key.SessionAfterClose)
    EarningsSession.DuringMarket -> tr(L10n.Key.SessionDuringMarket)
    EarningsSession.Unknown -> ""
}

/** The portfolio slice of the composition root: everything that depends on the
 *  [PortfolioStore]. Groups the trade + read + performance use cases sharing one store. */
class PortfolioGraph(
    repository: MarketDataRepository,
    portfolioStore: PortfolioStore,
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
    val resetPortfolio = ResetPortfolio(portfolioStore)
    val fetchPortfolioPerformance = FetchPortfolioPerformance(repository, portfolioStore)
    val fetchPerformanceReport = FetchPerformanceReport(repository, fetchPortfolioPerformance)
}
