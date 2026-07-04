package com.aptrade.desktop

import com.aptrade.desktop.infra.FileBookmarkStore
import com.aptrade.desktop.infra.FilePortfolioStore
import com.aptrade.desktop.infra.FileSettingsStore
import com.aptrade.desktop.infra.FileWatchlistStore
import com.aptrade.desktop.infra.FinnhubKeyConfig
import com.aptrade.desktop.infra.resolveConfigDir
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.BookmarkStore
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchCompanyNews
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketNews
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPerformanceReport
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.FetchPortfolioPerformance
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.NewsRepository
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.infrastructure.FinnhubNewsRepository
import com.aptrade.shared.infrastructure.YahooMarketDataRepository

/** Composition root. A plain CLASS constructed exactly once in main() — deliberately
 *  NOT an `object` (increment-5 review: don't copy the Android singleton to desktop).
 *  Exactly ONE YahooMarketDataRepository (one Ktor client) exists per process. */
class AppGraph(
    private val repository: MarketDataRepository = YahooMarketDataRepository(),
    store: WatchlistStore = FileWatchlistStore(resolveConfigDir().resolve("watchlist.json")),
    portfolioStore: PortfolioStore = FilePortfolioStore(resolveConfigDir().resolve("portfolio.json")),
    val settingsStore: FileSettingsStore = FileSettingsStore(resolveConfigDir().resolve("settings.json")),
    val bookmarkStore: BookmarkStore = FileBookmarkStore(resolveConfigDir().resolve("bookmarks.json")),
    finnhubApiKey: String? = FinnhubKeyConfig().finnhubApiKey(),
) {
    val fetchMarketQuotes = FetchMarketQuotes(repository)
    val fetchSearch = FetchSearch(repository)
    val fetchProfile = FetchProfile(repository)
    val fetchHistory = FetchHistory(repository)
    val fetchCandles = FetchCandles(repository)

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
    val buyAsset = BuyAsset(repository, portfolioStore)
    val sellAsset = SellAsset(repository, portfolioStore)
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

    // Only the production Yahoo repository and (when configured) the Finnhub news
    // repository own closeable Ktor clients; test doubles passed via the constructor
    // typically aren't AutoCloseable and are safely skipped.
    fun close() {
        (repository as? AutoCloseable)?.close()
        (newsRepository as? AutoCloseable)?.close()
    }
}
