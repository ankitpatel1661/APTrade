package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.QuoteRepository
import com.aptrade.shared.domain.Quote
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class YahooMarketDataRepository internal constructor(
    private val client: HttpClient,
) : QuoteRepository {

    // Production / Swift-harness entry point: builds the default CIO client.
    constructor() : this(defaultYahooHttpClient())

    override suspend fun quotes(symbols: List<String>): List<Quote> = coroutineScope {
        symbols.map { symbol -> async { fetchOne(symbol) } }.awaitAll()
    }

    private suspend fun fetchOne(symbol: String): Quote {
        val response = try {
            client.get("https://query1.finance.yahoo.com/v8/finance/chart/$symbol") {
                header("User-Agent", "Mozilla/5.0")
                url {
                    parameters.append("range", "1d")
                    parameters.append("interval", "1d")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network(e.message ?: "request failed")
        }

        if (response.status == HttpStatusCode.TooManyRequests) throw QuoteError.RateLimited
        if (!response.status.isSuccess()) throw QuoteError.Network("HTTP ${response.status.value}")

        val parsed = try {
            response.body<YahooChartResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network("malformed response")
        }
        return YahooQuoteMapper.quote(parsed) // may throw QuoteError.NotFound
    }
}
