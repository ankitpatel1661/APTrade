package com.aptrade.shared.application

import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun article(id: String, headline: String = "H-$id") = NewsArticle(
    id = id,
    headline = headline,
    summary = "summary",
    source = "source",
    url = "https://example.com/$id",
    imageUrl = null,
    publishedAtEpochSeconds = 0L,
    category = null,
    relatedSymbol = null,
)

private class FakeNewsRepository(
    private val marketResult: (() -> List<NewsArticle>)? = null,
    private val marketError: Throwable? = null,
    private val companyResult: (() -> List<NewsArticle>)? = null,
    private val companyError: Throwable? = null,
) : NewsRepository {
    override suspend fun marketNews(category: NewsCategory): List<NewsArticle> {
        marketError?.let { throw it }
        return marketResult?.invoke() ?: emptyList()
    }

    override suspend fun companyNews(symbol: String): List<NewsArticle> {
        companyError?.let { throw it }
        return companyResult?.invoke() ?: emptyList()
    }
}

private class FakeBookmarkStore(initial: List<NewsArticle> = emptyList()) : BookmarkStore {
    var saved: List<NewsArticle>? = null
        private set
    private var stored: List<NewsArticle> = initial

    override suspend fun load(): List<NewsArticle> = stored

    override suspend fun save(articles: List<NewsArticle>) {
        stored = articles
        saved = articles
    }
}

class NewsUseCasesTest {

    @Test
    fun fetchMarketNewsReturnsArticlesOnSuccess() = runTest {
        val expected = listOf(article("1"), article("2"))
        val useCase = FetchMarketNews(FakeNewsRepository(marketResult = { expected }))

        val result = useCase.execute(NewsCategory.General)

        assertEquals(expected, result)
    }

    @Test
    fun fetchMarketNewsSwallowsNetworkErrorToEmptyList() = runTest {
        val useCase = FetchMarketNews(FakeNewsRepository(marketError = QuoteError.Network("boom")))

        val result = useCase.execute(NewsCategory.General)

        assertEquals(emptyList(), result)
    }

    @Test
    fun fetchMarketNewsRethrowsCancellationException() = runTest {
        val useCase = FetchMarketNews(FakeNewsRepository(marketError = CancellationException("cancelled")))

        assertFailsWith<CancellationException> { useCase.execute(NewsCategory.General) }
    }

    @Test
    fun fetchCompanyNewsReturnsArticlesOnSuccess() = runTest {
        val expected = listOf(article("3"))
        val useCase = FetchCompanyNews(FakeNewsRepository(companyResult = { expected }))

        val result = useCase.execute("AAPL")

        assertEquals(expected, result)
    }

    @Test
    fun fetchCompanyNewsSwallowsNetworkErrorToEmptyList() = runTest {
        val useCase = FetchCompanyNews(FakeNewsRepository(companyError = QuoteError.Network("boom")))

        val result = useCase.execute("AAPL")

        assertEquals(emptyList(), result)
    }

    @Test
    fun fetchCompanyNewsSwallowsRateLimitedErrorToEmptyList() = runTest {
        val useCase = FetchCompanyNews(FakeNewsRepository(companyError = QuoteError.RateLimited))

        val result = useCase.execute("AAPL")

        assertEquals(emptyList(), result)
    }

    @Test
    fun fetchCompanyNewsRethrowsCancellationException() = runTest {
        val useCase = FetchCompanyNews(FakeNewsRepository(companyError = CancellationException("cancelled")))

        assertFailsWith<CancellationException> { useCase.execute("AAPL") }
    }

    @Test
    fun loadBookmarksDelegatesToStore() = runTest {
        val expected = listOf(article("1"))
        val useCase = LoadBookmarks(FakeBookmarkStore(initial = expected))

        assertEquals(expected, useCase.execute())
    }

    @Test
    fun toggleBookmarkRemovesArticleAlreadyPresentById() = runTest {
        val existing = article("1")
        val other = article("2")
        val store = FakeBookmarkStore()
        val useCase = ToggleBookmark(store)

        val result = useCase.execute(existing, listOf(existing, other))

        assertEquals(listOf(other), result)
        assertEquals(listOf(other), store.saved)
    }

    @Test
    fun toggleBookmarkInsertsNewArticleAtIndexZeroAndSaves() = runTest {
        val existing = article("1")
        val incoming = article("2")
        val store = FakeBookmarkStore()
        val useCase = ToggleBookmark(store)

        val result = useCase.execute(incoming, listOf(existing))

        assertEquals(listOf(incoming, existing), result)
        assertEquals(listOf(incoming, existing), store.saved)
        assertTrue(store.saved!!.first() === incoming)
    }
}
