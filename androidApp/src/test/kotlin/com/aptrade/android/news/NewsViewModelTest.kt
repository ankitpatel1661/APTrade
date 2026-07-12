package com.aptrade.android.news

import com.aptrade.shared.application.BookmarkStore
import com.aptrade.shared.application.FetchMarketNews
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.NewsRepository
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

/** Records market-news fetch categories; company news unused here. Mirrors
 *  desktopApp/src/test/.../news/NewsViewModelTest.kt's fake, package-renamed. */
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

/** In-memory [BookmarkStore] fake — mirrors the desktop test's fake. [failSave] simulates a
 *  persistence failure (e.g. disk full) so the VM's error-surfacing path can be pinned. */
private class FakeBookmarkStore(
    initial: List<NewsArticle> = emptyList(),
    private val failSave: Boolean = false,
) : BookmarkStore {
    var stored: List<NewsArticle> = initial
    var saveCount = 0
        private set
    override suspend fun load(): List<NewsArticle> = stored
    override suspend fun save(articles: List<NewsArticle>) {
        if (failSave) throw IOException("disk full")
        stored = articles
        saveCount++
    }
}

/** [NewsViewModel] is an androidx ViewModel using `viewModelScope` (Dispatchers.Main.immediate)
 *  for every fire-and-forget action, mirroring PortfolioViewModelTest's scheduler discipline:
 *  a [StandardTestDispatcher] installed as Main, with `runCurrent()` after each action that
 *  launches a coroutine. */
class NewsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        repo: FakeNewsRepository? = FakeNewsRepository(),
        store: FakeBookmarkStore = FakeBookmarkStore(),
    ) = NewsViewModel(
        fetchMarketNews = repo?.let { FetchMarketNews(it) },
        loadBookmarks = LoadBookmarks(store),
        toggleBookmark = ToggleBookmark(store),
    )

    @Test
    fun startLoadsBookmarksAndArticlesByCategory() = runTest {
        val repo = FakeNewsRepository(marketImpl = { listOf(article("1"), article("2")) })
        val store = FakeBookmarkStore(initial = listOf(article("saved")))
        val viewModel = vm(repo, store)
        viewModel.start(); runCurrent()

        val s = viewModel.state.value
        assertFalse(s.needsKey)
        assertFalse(s.isLoading)
        assertEquals(listOf("1", "2"), s.articles.map { it.id })
        assertEquals(setOf("saved"), s.bookmarkedIds)
        assertEquals(NewsCategory.General, s.category)
    }

    @Test
    fun keyMissingSkipsFetchButStillLoadsBookmarks() = runTest {
        // fetch-absent VM (null use case) has no network path to touch — structurally proven,
        // reinforced by asserting the fake's marketLog never receives a call.
        val repo = FakeNewsRepository()
        val store = FakeBookmarkStore(initial = listOf(article("saved")))
        val viewModel = vm(repo = null, store = store)
        viewModel.start(); runCurrent()

        val s = viewModel.state.value
        assertTrue(s.needsKey)
        assertTrue(s.articles.isEmpty())
        assertEquals(setOf("saved"), s.bookmarkedIds)
        assertFalse(s.isLoading)
        assertTrue(repo.marketLog.isEmpty())
    }

    @Test
    fun setCategoryRefetchesForNewCategory() = runTest {
        val repo = FakeNewsRepository(marketImpl = { listOf(article(it.name)) })
        val viewModel = vm(repo)
        viewModel.start(); runCurrent()
        assertEquals(listOf(NewsCategory.General), repo.marketLog)

        viewModel.setCategory(NewsCategory.Crypto); runCurrent()
        assertEquals(listOf(NewsCategory.General, NewsCategory.Crypto), repo.marketLog)
        assertEquals(NewsCategory.Crypto, viewModel.state.value.category)

        // Same category is a no-op — no extra fetch.
        viewModel.setCategory(NewsCategory.Crypto); runCurrent()
        assertEquals(listOf(NewsCategory.General, NewsCategory.Crypto), repo.marketLog)
    }

    @Test
    fun toggleBookmarkUpdatesIdsAndPersists() = runTest {
        val store = FakeBookmarkStore()
        val viewModel = vm(FakeNewsRepository(), store)
        viewModel.start(); runCurrent()
        assertTrue(viewModel.state.value.bookmarkedIds.isEmpty())

        val a = article("1")
        viewModel.toggleBookmark(a); runCurrent()
        assertEquals(setOf("1"), viewModel.state.value.bookmarkedIds)
        assertEquals(1, store.saveCount)
        assertEquals(listOf("1"), store.stored.map { it.id })

        // Toggling again removes it and persists the empty list.
        viewModel.toggleBookmark(a); runCurrent()
        assertTrue(viewModel.state.value.bookmarkedIds.isEmpty())
        assertEquals(2, store.saveCount)
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun refreshRefetchesCurrentCategory() = runTest {
        val repo = FakeNewsRepository(marketImpl = { listOf(article("x")) })
        val viewModel = vm(repo)
        viewModel.start(); runCurrent()
        viewModel.setCategory(NewsCategory.Merger); runCurrent()
        repo.marketLog.clear()

        viewModel.refresh(); runCurrent()
        assertEquals(listOf(NewsCategory.Merger), repo.marketLog)
    }

    @Test
    fun toggleBookmarkFailureSurfacesErrorAndKeepsLastGoodIds() = runTest {
        val store = FakeBookmarkStore(failSave = true)
        val viewModel = vm(FakeNewsRepository(), store)
        viewModel.start(); runCurrent()

        viewModel.toggleBookmark(article("1")); runCurrent()

        val s = viewModel.state.value
        assertTrue(s.bookmarkedIds.isEmpty())
        assertNotNull(s.error)
    }

    /** Ported from desktopApp/src/test/.../news/NewsViewModelTest.kt's
     *  `filterMatchesSourceCaseInsensitively` — the live headline filter matches case-
     *  insensitively on headline OR source, and a blank filter restores the unfiltered base. */
    @Test
    fun filterMatchesSourceCaseInsensitively() = runTest {
        val repo = FakeNewsRepository(marketImpl = {
            listOf(
                article("1", headline = "Apple beats", source = "Reuters"),
                article("2", headline = "Market recap", source = "Bloomberg"),
            )
        })
        val viewModel = vm(repo)
        viewModel.start(); runCurrent()

        viewModel.setFilter("bloom")
        assertEquals(listOf("2"), viewModel.state.value.visibleArticles.map { it.id })

        // headline match also works, blank restores base
        viewModel.setFilter("APPLE")
        assertEquals(listOf("1"), viewModel.state.value.visibleArticles.map { it.id })
        viewModel.setFilter("")
        assertEquals(listOf("1", "2"), viewModel.state.value.visibleArticles.map { it.id })
    }

    /** Ported from desktopApp/src/test/.../news/NewsViewModelTest.kt's
     *  `showingSavedSwitchesBaseList` — toggling Saved swaps the filter's base list between
     *  the fetched articles and the bookmarked ones. */
    @Test
    fun showingSavedSwitchesBaseList() = runTest {
        val repo = FakeNewsRepository(marketImpl = { listOf(article("live")) })
        val store = FakeBookmarkStore(initial = listOf(article("saved")))
        val viewModel = vm(repo, store)
        viewModel.start(); runCurrent()

        assertEquals(listOf("live"), viewModel.state.value.visibleArticles.map { it.id })
        viewModel.setShowingSaved(true)
        assertEquals(listOf("saved"), viewModel.state.value.visibleArticles.map { it.id })
        viewModel.setShowingSaved(false)
        assertEquals(listOf("live"), viewModel.state.value.visibleArticles.map { it.id })
    }
}
