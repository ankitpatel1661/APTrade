package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.NewsArticle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val finnhubJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * One element of Finnhub's `/news` and `/company-news` JSON arrays. All fields optional —
 * the mapper validates and drops anything unusable.
 */
@Serializable
data class FinnhubArticleDTO(
    val category: String? = null,
    val datetime: Double? = null, // Unix seconds (Finnhub sends this as a number)
    val headline: String? = null,
    val id: Long? = null,
    val image: String? = null,
    val related: String? = null,
    val source: String? = null,
    val summary: String? = null,
    val url: String? = null,
)

object FinnhubNewsMapper {
    fun articles(list: List<FinnhubArticleDTO>): List<NewsArticle> = list.mapNotNull(::article)

    /** Skips any article without a non-blank headline or a non-blank url. */
    fun article(dto: FinnhubArticleDTO): NewsArticle? {
        val headline = dto.headline?.takeIf { it.isNotBlank() } ?: return null
        val url = dto.url?.takeIf { it.isNotBlank() } ?: return null
        val nonEmpty: (String?) -> String? = { value -> value?.takeIf { it.isNotEmpty() } }
        return NewsArticle(
            id = dto.id?.toString() ?: url,
            headline = headline,
            summary = dto.summary ?: "",
            source = dto.source ?: "",
            url = url,
            imageUrl = nonEmpty(dto.image),
            publishedAtEpochSeconds = dto.datetime?.toLong() ?: 0L,
            category = dto.category,
            relatedSymbol = nonEmpty(dto.related),
        )
    }
}
