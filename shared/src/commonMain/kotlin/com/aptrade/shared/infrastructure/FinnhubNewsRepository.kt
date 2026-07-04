package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.NewsRepository
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.NewsCategory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

private const val SECONDS_PER_DAY = 86_400L
private const val LOOKBACK_DAYS = 7L

/**
 * Finnhub-backed [NewsRepository]. Reuses the Yahoo adapter's per-platform client
 * ([defaultYahooHttpClient]) and its error-mapping idiom (429 -> RateLimited, other
 * non-2xx/malformed -> Network); CancellationException always rethrows first.
 *
 * `nowEpochSeconds` is an injectable clock seam for company-news' 7-day lookback
 * window, defaulting to the real wall clock.
 */
class FinnhubNewsRepository internal constructor(
    private val apiKey: String,
    private val client: HttpClient,
    private val nowEpochSeconds: () -> Long = { epochSecondsNow() },
) : NewsRepository, AutoCloseable {

    // Production / Swift-harness entry point: builds the platform HTTP client via the
    // defaultYahooHttpClient expect/actual (Darwin on Apple, CIO on the JVM).
    constructor(apiKey: String) : this(apiKey, defaultYahooHttpClient())

    override fun close() { client.close() }

    override suspend fun marketNews(category: NewsCategory): List<NewsArticle> {
        val response = try {
            client.get("https://finnhub.io/api/v1/news") {
                url {
                    parameters.append("category", category.finnhubValue)
                    parameters.append("token", apiKey)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network(e.message ?: "request failed")
        }

        return FinnhubNewsMapper.articles(decodeArticles(response))
    }

    override suspend fun companyNews(symbol: String): List<NewsArticle> {
        val to = nowEpochSeconds()
        val from = to - LOOKBACK_DAYS * SECONDS_PER_DAY

        val response = try {
            client.get("https://finnhub.io/api/v1/company-news") {
                url {
                    parameters.append("symbol", symbol.uppercase())
                    parameters.append("from", formatUtcDate(from))
                    parameters.append("to", formatUtcDate(to))
                    parameters.append("token", apiKey)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network(e.message ?: "request failed")
        }

        return FinnhubNewsMapper.articles(decodeArticles(response))
    }

    private suspend fun decodeArticles(response: HttpResponse): List<FinnhubArticleDTO> {
        if (response.status == HttpStatusCode.TooManyRequests) throw QuoteError.RateLimited
        if (!response.status.isSuccess()) throw QuoteError.Network("HTTP ${response.status.value}")

        return try {
            response.body<List<FinnhubArticleDTO>>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network("malformed response")
        }
    }
}
