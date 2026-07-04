package com.aptrade.shared.application

import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import kotlin.coroutines.cancellation.CancellationException

/** Fetches market news for a category. Failures are swallowed to an empty list (macOS
 *  `try?` parity) since news is supplementary; CancellationException always rethrows. */
class FetchMarketNews(private val repository: NewsRepository) {
    @Throws(CancellationException::class)
    suspend fun execute(category: NewsCategory): List<NewsArticle> =
        try {
            repository.marketNews(category)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
}

/** Fetches company-specific news for a symbol. Same swallow-to-empty-list contract as
 *  [FetchMarketNews]. */
class FetchCompanyNews(private val repository: NewsRepository) {
    @Throws(CancellationException::class)
    suspend fun execute(symbol: String): List<NewsArticle> =
        try {
            repository.companyNews(symbol)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
}

/** Loads the user's bookmarked articles. */
class LoadBookmarks(private val store: BookmarkStore) {
    suspend fun execute(): List<NewsArticle> = store.load()
}

/** Toggles an article's bookmark state against the given current list, persists the
 *  result, and returns it: present (by id) -> removed; absent -> inserted at index 0. */
class ToggleBookmark(private val store: BookmarkStore) {
    suspend fun execute(article: NewsArticle, current: List<NewsArticle>): List<NewsArticle> {
        val result = if (current.any { it.id == article.id }) {
            current.filter { it.id != article.id }
        } else {
            listOf(article) + current
        }
        store.save(result)
        return result
    }
}
