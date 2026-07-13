package com.aptrade.desktop.detail

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.BookmarkStore
import com.aptrade.shared.application.EmptyEarningsRepository
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchCompanyNews
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.NewsRepository
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun article(id: String) = NewsArticle(
    id = id,
    headline = "H-$id",
    summary = "summary",
    source = "src",
    url = "https://example.com/$id",
    imageUrl = null,
    publishedAtEpochSeconds = 0L,
    category = null,
    relatedSymbol = null,
)

private class FakeNewsRepository(
    private val companyImpl: suspend (String) -> List<NewsArticle> = { emptyList() },
) : NewsRepository {
    val companyLog = mutableListOf<String>()
    override suspend fun marketNews(category: NewsCategory): List<NewsArticle> = emptyList()
    override suspend fun companyNews(symbol: String): List<NewsArticle> {
        companyLog += symbol
        return companyImpl(symbol)
    }
}

private class FakeBookmarkStore(initial: List<NewsArticle> = emptyList()) : BookmarkStore {
    var stored: List<NewsArticle> = initial
    var saveCount = 0
        private set
    override suspend fun load(): List<NewsArticle> = stored
    override suspend fun save(articles: List<NewsArticle>) { stored = articles; saveCount++ }
}

private fun vm(
    symbol: String,
    repo: FakeMarketDataRepository,
    news: FakeNewsRepository?,
    store: FakeBookmarkStore,
    scope: CoroutineScope,
) = DetailViewModel(
    symbol = symbol,
    fetchProfile = FetchProfile(repo),
    fetchMarketQuotes = FetchMarketQuotes(repo),
    fetchHistory = FetchHistory(repo),
    fetchChartWindow = FetchChartWindow(repo),
    // Not under test here (see DetailViewModelEarningsTest) — an always-empty fetch keeps
    // these news-focused tests unaffected by the Next-earnings load.
    fetchEarningsCalendar = FetchEarningsCalendar(EmptyEarningsRepository) { emptySet() },
    scope = scope,
    fetchCompanyNews = news?.let { FetchCompanyNews(it) },
    loadBookmarks = LoadBookmarks(store),
    toggleBookmark = ToggleBookmark(store),
)

class DetailViewModelNewsTest {

    @Test
    fun loadsCompanyNewsOnceCappedAtEightAndLoadsBookmarks() = runTest {
        val news = FakeNewsRepository(companyImpl = { (1..12).map { article("n$it") } })
        val store = FakeBookmarkStore(initial = listOf(article("saved")))
        val vm = vm("AAPL", FakeMarketDataRepository(), news, store, backgroundScope)
        runCurrent()

        val s = vm.state.value
        assertEquals(8, s.newsArticles.size)
        assertEquals("n1", s.newsArticles.first().id)
        assertEquals(false, s.newsLoading)
        assertEquals(false, s.newsKeyMissing)
        assertEquals(listOf("AAPL"), news.companyLog)
        assertEquals(setOf("saved"), s.bookmarkedIds)
    }

    @Test
    fun newsKeyMissingSkipsCompanyNews() = runTest {
        val store = FakeBookmarkStore()
        // No news repository -> fetchCompanyNews null -> newsKeyMissing, no fetch path.
        val vm = vm("AAPL", FakeMarketDataRepository(), news = null, store = store, scope = backgroundScope)
        runCurrent()

        val s = vm.state.value
        assertTrue(s.newsKeyMissing)
        assertTrue(s.newsArticles.isEmpty())
    }

    @Test
    fun companyNewsCancelledWhenSymbolVmDisposed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val news = FakeNewsRepository(companyImpl = { gate.await(); listOf(article("late")) })
        val store = FakeBookmarkStore()
        // Per-symbol VM lifecycle: cancelling the scope (symbol switch) must abort the
        // in-flight company-news load — it never lands on the disposed VM's state.
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val vm = vm("AAPL", FakeMarketDataRepository(), news, store, scope)
        runCurrent()
        assertTrue(vm.state.value.newsLoading)  // still awaiting the gate

        scope.cancel()
        gate.complete(Unit); runCurrent()
        assertTrue(vm.state.value.newsArticles.isEmpty())  // stale result never applied
    }

    @Test
    fun toggleBookmarkUpdatesIdsAndPersists() = runTest {
        val news = FakeNewsRepository()
        val store = FakeBookmarkStore()
        val vm = vm("AAPL", FakeMarketDataRepository(), news, store, backgroundScope)
        runCurrent()

        val a = article("1")
        vm.onToggleBookmark(a); runCurrent()
        assertEquals(setOf("1"), vm.state.value.bookmarkedIds)
        assertEquals(1, store.saveCount)
    }
}
