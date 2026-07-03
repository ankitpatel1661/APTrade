package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.MarketDataRepository
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
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
) : MarketDataRepository, AutoCloseable {

    // Production / Swift-harness entry point: builds the default CIO client.
    constructor() : this(defaultYahooHttpClient())

    override fun close() { client.close() }

    override suspend fun quotes(symbols: List<String>): List<Quote> = coroutineScope {
        symbols.map { symbol ->
            async { YahooQuoteMapper.quote(fetchChart(symbol, "1d", "1d")) }
        }.awaitAll()
    }

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
        val response = fetchChart(symbol, timeframe.yahooRange, timeframe.yahooInterval)
        val points = YahooQuoteMapper.history(response)
        return clampToWindow(points, timeframe.windowDurationSeconds) { it.epochSeconds }
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> {
        val response = fetchChart(symbol, timeframe.yahooRange, timeframe.yahooInterval)
        val candles = YahooQuoteMapper.candles(response)
        return clampToWindow(candles, timeframe.windowDurationSeconds) { it.epochSeconds }
    }

    override suspend fun profile(symbol: String): Asset =
        YahooQuoteMapper.asset(fetchChart(symbol, "1d", "1d"))

    override suspend fun search(query: String): List<Asset> =
        YahooSearchMapper.assets(fetchSearchResponse(query))

    private suspend fun fetchSearchResponse(query: String): YahooSearchResponse {
        val response = try {
            client.get("https://query1.finance.yahoo.com/v1/finance/search") {
                header("User-Agent", "Mozilla/5.0")
                url {
                    parameters.append("q", query)
                    parameters.append("quotesCount", "8")
                    parameters.append("newsCount", "0")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network(e.message ?: "request failed")
        }

        if (response.status == HttpStatusCode.TooManyRequests) throw QuoteError.RateLimited
        if (!response.status.isSuccess()) throw QuoteError.Network("HTTP ${response.status.value}")

        return try {
            response.body<YahooSearchResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network("malformed response")
        }
    }

    private suspend fun fetchChart(symbol: String, range: String, interval: String): YahooChartResponse {
        val response = try {
            client.get("https://query1.finance.yahoo.com/v8/finance/chart/$symbol") {
                header("User-Agent", "Mozilla/5.0")
                url {
                    parameters.append("range", range)
                    parameters.append("interval", interval)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network(e.message ?: "request failed")
        }

        if (response.status == HttpStatusCode.TooManyRequests) throw QuoteError.RateLimited
        if (!response.status.isSuccess()) throw QuoteError.Network("HTTP ${response.status.value}")

        return try {
            response.body<YahooChartResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network("malformed response")
        }
    }
}
