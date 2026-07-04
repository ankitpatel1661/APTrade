package com.aptrade.shared.domain

/**
 * A single news item, normalized from whatever the news source returns. Pure value type.
 *
 * Spec divergence from the macOS original: `url`/`imageUrl` are plain strings rather than
 * a `URL` type, and `publishedAtEpochSeconds` is a `Long` rather than a `Date` — invariants
 * (non-blank headline/url, defaulted summary/source, etc.) live in the mapper, not here.
 */
data class NewsArticle(
    val id: String,
    val headline: String,
    val summary: String,
    val source: String,
    val url: String,
    val imageUrl: String?,
    val publishedAtEpochSeconds: Long,
    val category: String?,
    val relatedSymbol: String?,
)
