package com.aptrade.desktop.news

import com.aptrade.shared.application.BookmarkStore
import com.aptrade.shared.application.FetchMarketNews
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.NewsRepository
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun article(
    id: String,
    headline: String = "H-$id",
    source: String = "src-$id",
) = NewsArticle(
    id = id,
    headline = headline,
    summary = "summary",
    source = source,
    url = "https://example.com/$id",
    imageUrl = null,
    publishedAtEpochSeconds = 0L,
    category = null,
    relatedSymbol = null,
)

/** Records market-news fetch categories; company news unused here. */
private class FakeNewsRepository(
    private val marketImpl: (NewsCategory) -> List<NewsArticle> = { emptyList() },
) : NewsRepository {
    val marketLog = mutableListOf<NewsCategory>()
    override suspend fun marketNews(category: NewsCategory): List<NewsArticle> {
        marketLog += category
        return marketImpl(category)
    }
    override suspend fun companyNews(symbol: String): List<NewsArticle> = emptyList()
}

private class FakeBookmarkStore(initial: List<NewsArticle> = emptyList()) : BookmarkStore {
    var stored: List<NewsArticle> = initial
    var saveCount = 0
        private set
    override suspend fun load(): List<NewsArticle> = stored
    override suspend fun save(articles: List<NewsArticle>) {
        stored = articles
        saveCount++
    }
}

private fun vm(
    scope: CoroutineScope,
    repo: FakeNewsRepository? = FakeNewsRepository(),
    store: FakeBookmarkStore = FakeBookmarkStore(),
) = NewsViewModel(
    fetchMarketNews = repo?.let { FetchMarketNews(it) },
    loadBookmarks = LoadBookmarks(store),
    toggleBookmark = ToggleBookmark(store),
    scope = scope,
)

class NewsViewModelTest {

    @Test
    fun startLoadsBookmarksAndArticles() = runTest {
        val repo = FakeNewsRepository(marketImpl = { listOf(article("1"), article("2")) })
        val store = FakeBookmarkStore(initial = listOf(article("saved")))
        val vm = vm(backgroundScope, repo, store)
        vm.start(); runCurrent()

        val s = vm.state.value
        assertFalse(s.keyMissing)
        assertFalse(s.isLoading)
        assertEquals(listOf("1", "2"), s.articles.map { it.id })
        assertEquals(listOf("saved"), s.bookmarks.map { it.id })
        assertEquals(setOf("saved"), s.bookmarkedIds)
    }

    @Test
    fun keyMissingSkipsFetchButStillLoadsBookmarks() = runTest {
        // fetch-absent VM (null use case) has no network path to touch — structurally proven.
        val store = FakeBookmarkStore(initial = listOf(article("saved")))
        val vm = vm(backgroundScope, repo = null, store = store)
        vm.start(); runCurrent()

        val s = vm.state.value
        assertTrue(s.keyMissing)
        assertTrue(s.articles.isEmpty())
        assertEquals(listOf("saved"), s.bookmarks.map { it.id })
        assertFalse(s.isLoading)
    }

    @Test
    fun setCategoryRefetchesForNewCategory() = runTest {
        val repo = FakeNewsRepository(marketImpl = { listOf(article(it.name)) })
        val vm = vm(backgroundScope, repo)
        vm.start(); runCurrent()
        assertEquals(listOf(NewsCategory.General), repo.marketLog)

        vm.setCategory(NewsCategory.Crypto); runCurrent()
        assertEquals(listOf(NewsCategory.General, NewsCategory.Crypto), repo.marketLog)
        assertEquals(NewsCategory.Crypto, vm.state.value.category)

        // Same category is a no-op — no extra fetch.
        vm.setCategory(NewsCategory.Crypto); runCurrent()
        assertEquals(listOf(NewsCategory.General, NewsCategory.Crypto), repo.marketLog)
    }

    @Test
    fun filterMatchesSourceCaseInsensitively() = runTest {
        val repo = FakeNewsRepository(marketImpl = {
            listOf(
                article("1", headline = "Apple beats", source = "Reuters"),
                article("2", headline = "Market recap", source = "Bloomberg"),
            )
        })
        val vm = vm(backgroundScope, repo)
        vm.start(); runCurrent()

        vm.setFilter("bloom")
        assertEquals(listOf("2"), vm.state.value.visibleArticles.map { it.id })

        // headline match also works, blank restores base
        vm.setFilter("APPLE")
        assertEquals(listOf("1"), vm.state.value.visibleArticles.map { it.id })
        vm.setFilter("")
        assertEquals(listOf("1", "2"), vm.state.value.visibleArticles.map { it.id })
    }

    @Test
    fun showingSavedSwitchesBaseList() = runTest {
        val repo = FakeNewsRepository(marketImpl = { listOf(article("live")) })
        val store = FakeBookmarkStore(initial = listOf(article("saved")))
        val vm = vm(backgroundScope, repo, store)
        vm.start(); runCurrent()

        assertEquals(listOf("live"), vm.state.value.visibleArticles.map { it.id })
        vm.setShowingSaved(true)
        assertEquals(listOf("saved"), vm.state.value.visibleArticles.map { it.id })
        vm.setShowingSaved(false)
        assertEquals(listOf("live"), vm.state.value.visibleArticles.map { it.id })
    }

    @Test
    fun toggleBookmarkUpdatesIdsAndPersists() = runTest {
        val store = FakeBookmarkStore()
        val vm = vm(backgroundScope, FakeNewsRepository(), store)
        vm.start(); runCurrent()
        assertTrue(vm.state.value.bookmarkedIds.isEmpty())

        val a = article("1")
        vm.toggleBookmark(a); runCurrent()
        assertEquals(setOf("1"), vm.state.value.bookmarkedIds)
        assertEquals(1, store.saveCount)
        assertEquals(listOf("1"), store.stored.map { it.id })

        // Toggling again removes it and persists the empty list.
        vm.toggleBookmark(a); runCurrent()
        assertTrue(vm.state.value.bookmarkedIds.isEmpty())
        assertEquals(2, store.saveCount)
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun refreshRefetchesCurrentCategory() = runTest {
        val repo = FakeNewsRepository(marketImpl = { listOf(article("x")) })
        val vm = vm(backgroundScope, repo)
        vm.start(); runCurrent()
        vm.setCategory(NewsCategory.Merger); runCurrent()
        repo.marketLog.clear()

        vm.refresh(); runCurrent()
        assertEquals(listOf(NewsCategory.Merger), repo.marketLog)
    }
}
