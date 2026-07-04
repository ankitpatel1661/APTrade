package com.aptrade.shared.application

import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Toggles an article's bookmark state, persists the result, and returns it: present
 *  (by id) -> removed; absent -> inserted at index 0.
 *
 *  The read-modify-write runs under a [Mutex]: the current list is re-loaded from the store
 *  *inside* the lock (never taken from a possibly-stale caller snapshot), so a single shared
 *  instance serializes concurrent toggles — the second caller sees the first's write. This is
 *  why [AppGraph] hands the same instance to every view model. */
class ToggleBookmark(private val store: BookmarkStore) {
    private val mutex = Mutex()

    suspend fun execute(article: NewsArticle): List<NewsArticle> = mutex.withLock {
        val current = store.load()
        val result = if (current.any { it.id == article.id }) {
            current.filter { it.id != article.id }
        } else {
            listOf(article) + current
        }
        store.save(result)
        result
    }
}
