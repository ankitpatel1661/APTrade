package com.aptrade.android

import android.content.Context
import com.aptrade.android.alerts.AndroidAlertNotifier
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.AlertNotifier
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.CreatePriceAlert
import com.aptrade.shared.application.EvaluateAlerts
import com.aptrade.shared.application.FetchChartWindow
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
import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.NewsRepository
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.RemovePriceAlert
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.infrastructure.FileAlertStore
import com.aptrade.shared.infrastructure.FileBookmarkStore
import com.aptrade.shared.infrastructure.FilePortfolioStore
import com.aptrade.shared.infrastructure.FileSettingsStore
import com.aptrade.shared.infrastructure.FileWatchlistStore
import com.aptrade.shared.infrastructure.FinnhubKeyConfig
import com.aptrade.shared.infrastructure.FinnhubNewsRepository
import com.aptrade.shared.infrastructure.YahooMarketDataRepository
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
