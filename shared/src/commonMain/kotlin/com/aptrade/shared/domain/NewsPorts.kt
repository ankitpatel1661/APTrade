package com.aptrade.shared.domain

/** Fetches news articles from a remote news source. */
interface NewsRepository {
    suspend fun marketNews(category: NewsCategory): List<NewsArticle>
    suspend fun companyNews(symbol: String): List<NewsArticle>
}

/** Persists a user's bookmarked articles. */
interface BookmarkStore {
    suspend fun load(): List<NewsArticle>
    suspend fun save(articles: List<NewsArticle>)
}
