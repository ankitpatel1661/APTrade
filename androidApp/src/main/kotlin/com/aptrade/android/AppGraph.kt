package com.aptrade.android

import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPerformanceReport
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.FetchPortfolioPerformance
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.infrastructure.FileAlertStore
import com.aptrade.shared.infrastructure.FileBookmarkStore
import com.aptrade.shared.infrastructure.FilePortfolioStore
import com.aptrade.shared.infrastructure.FileSettingsStore
import com.aptrade.shared.infrastructure.FileWatchlistStore
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
    val fetchCandles = FetchCandles(repository)

    // The macOS app's seed watchlist.
    val defaultSymbols = listOf("AAPL", "SPY", "BTC-USD", "ETH-USD")

    private var filesDir: File? = null

    /** Supply the app-private storage directory. Call once from MainActivity.onCreate
     *  BEFORE setContent, so [portfolio] is ready by the time any screen composes. */
    fun initialize(filesDir: File) {
        this.filesDir = filesDir
    }

    /** Portfolio use cases, built lazily from the [filesDir] provided by [initialize].
     *  Shares the single process-wide [repository]. */
    val portfolio: PortfolioGraph by lazy {
        val dir = requireNotNull(filesDir) {
            "AppGraph.initialize(filesDir) must be called before accessing AppGraph.portfolio"
        }
        PortfolioGraph(repository, FilePortfolioStore(File(dir, "portfolio.json").toPath()))
    }

    /** App-private config directory (`filesDir/aptrade`), mirroring the desktop
     *  ConfigDir wiring but rooted in Android's sandbox rather than a platform-specific
     *  user config path. Lazily-backed stores below resolve their JSON file against it. */
    private fun configDir(): Path {
        val dir = requireNotNull(filesDir) { "AppGraph.initialize(filesDir) must run before stores are touched" }
        return dir.toPath().resolve("aptrade")
    }

    // Watchlist + alerts + news + settings — all against app-private filesDir, mirroring
    // the desktop ConfigDir wiring but rooted in Android's sandbox. Alert/news use cases
    // are wired in their own tasks (6/7); the stores are created now so those tasks only
    // need to add use-case vals against them.
    val watchlistStore by lazy { FileWatchlistStore(configDir().resolve("watchlist.json")) }
    val alertStore by lazy { FileAlertStore(configDir().resolve("alerts.json")) }
    val settingsStore by lazy { FileSettingsStore(configDir().resolve("settings.json")) }
    val bookmarkStore by lazy { FileBookmarkStore(configDir().resolve("bookmarks.json")) }

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
