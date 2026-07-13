package com.aptrade.desktop

import androidx.compose.ui.window.TrayState
import com.aptrade.desktop.infra.AppSettings
import com.aptrade.desktop.infra.FileAlertStore
import com.aptrade.desktop.infra.FileBookmarkStore
import com.aptrade.desktop.infra.FileSchedulerStateStore
import com.aptrade.desktop.infra.FileSettingsStore
import com.aptrade.desktop.infra.FileWatchlistStore
import com.aptrade.desktop.infra.FinnhubKeyConfig
import com.aptrade.desktop.infra.TrayNotifier
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
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.infrastructure.FilePortfolioStore
import com.aptrade.shared.infrastructure.FinnhubEarningsRepository
import com.aptrade.shared.infrastructure.FinnhubNewsRepository
import com.aptrade.shared.infrastructure.YahooMarketDataRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Composition root. A plain CLASS constructed exactly once in main() — deliberately
 *  NOT an `object` (increment-5 review: don't copy the Android singleton to desktop).
 *  Exactly ONE YahooMarketDataRepository (one Ktor client) exists per process. */
class AppGraph(
    private val repository: MarketDataRepository = YahooMarketDataRepository(),
    store: WatchlistStore = FileWatchlistStore(resolveConfigDir().resolve("watchlist.json")),
    portfolioStore: PortfolioStore = FilePortfolioStore(resolveConfigDir().resolve("portfolio.json")),
    val settingsStore: FileSettingsStore = FileSettingsStore(resolveConfigDir().resolve("settings.json")),
    val bookmarkStore: BookmarkStore = FileBookmarkStore(resolveConfigDir().resolve("bookmarks.json")),
    alertStore: AlertStore = FileAlertStore(resolveConfigDir().resolve("alerts.json")),
    val schedulerStateStore: SchedulerStateStore = FileSchedulerStateStore(resolveConfigDir().resolve("schedulerState.json")),
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
    val resetPortfolio = ResetPortfolio(portfolioStore)
    val fetchPortfolioPerformance = FetchPortfolioPerformance(repository, portfolioStore)
    val fetchPerformanceReport = FetchPerformanceReport(repository, fetchPortfolioPerformance)

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
    val fetchEarningsCalendar: FetchEarningsCalendar = FetchEarningsCalendar(earningsRepository) {
        // watchlist ∪ portfolio symbols, read fresh per call — same two use cases the
        // coordinator's digestSummary() reads from (fetchWatchlist.execute()) plus the
        // portfolio store's holdings, mirroring PortfolioViewModel's own symbol source.
        val watchlistSymbols = fetchWatchlist.execute().map { it.symbol }
        val portfolioSymbols = fetchPortfolio.execute().positions.map { it.asset.symbol }
        (watchlistSymbols + portfolioSymbols).toSet()
    }

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
