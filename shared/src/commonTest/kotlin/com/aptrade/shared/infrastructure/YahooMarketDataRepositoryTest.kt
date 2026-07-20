package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class YahooMarketDataRepositoryTest {

    private fun clientReturning(status: HttpStatusCode, body: String): HttpClient =
        HttpClient(MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { installYahoo() }

    @Test
    fun returnsExactQuoteOnSuccess() = runTest {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "regularMarketPrice":229.35,"chartPreviousClose":227.45}}]}}
        """.trimIndent()
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, body))

        val quotes = repo.quotes(listOf("AAPL"))

        assertEquals(1, quotes.size)
        assertEquals("AAPL", quotes[0].symbol)
        assertEquals(Money(BigDecimal.parseString("229.35"), "USD"), quotes[0].price)
        assertTrue(quotes[0].changePercent > 0.0)
    }

    @Test
    fun mapsHttp429ToRateLimited() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.quotes(listOf("AAPL")) }
        assertTrue(ex is QuoteError.RateLimited)
    }

    @Test
    fun mapsMalformedBodyToNetwork() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, "not json"))
        val ex = assertFailsWith<QuoteError> { repo.quotes(listOf("AAPL")) }
        assertTrue(ex is QuoteError.Network)
    }

    @Test
    fun returnsHistoryClampedToWindow() = runTest {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
            "timestamp":[1000,1500,2000],
            "indicators":{"quote":[{"close":[100.00,101.00,102.00]}]}}]}}
        """.trimIndent()
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, body))

        val points = repo.history("AAPL", Timeframe.OneDay)

        // OneDay's windowDurationSeconds (86400s) is far larger than these deltas, so all 3 survive.
        assertEquals(3, points.size)
        assertEquals(Money(BigDecimal.parseString("102.00"), "USD"), points.last().close)
    }

    @Test
    fun mapsCandlesHttp429ToRateLimited() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.candles("AAPL", Timeframe.OneDay) }
        assertTrue(ex is QuoteError.RateLimited)
    }

    // Moved-clamp counterpart: `candles()` now returns the RAW mapped bars, UNCLAMPED — the
    // window clamp lives in FetchCandles (application layer) so FetchChartWindow can instead
    // clamp to a wider (window + lookback pad) window. A bar far outside OneDay's
    // windowDurationSeconds must still come back here; only the use-case layer trims it.
    @Test
    fun returnsCandlesUnclamped() = runTest {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
            "timestamp":[1000,500000,600000],
            "indicators":{"quote":[{"open":[100.00,101.00,102.00],
            "high":[100.00,101.00,102.00],"low":[100.00,101.00,102.00],
            "close":[100.00,101.00,102.00],"volume":[10,20,30]}]}}]}}
        """.trimIndent()
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, body))

        val candles = repo.candles("AAPL", Timeframe.OneDay)

        // All 3 bars survive despite the first being ~599000s before the newest (far outside
        // OneDay's 86400s window) — proving the repository itself no longer clamps.
        assertEquals(3, candles.size)
        assertEquals(1000L, candles.first().epochSeconds)
    }

    @Test
    fun returnsProfileFromMeta() = runTest {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD",
            "instrumentType":"EQUITY","longName":"Apple Inc."}}]}}
        """.trimIndent()
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, body))

        val asset = repo.profile("AAPL")

        assertEquals("AAPL", asset.symbol)
        assertEquals("Apple Inc.", asset.name)
        assertEquals(AssetKind.Stock, asset.kind)
    }

    @Test
    fun returnsMappedSearchResults() = runTest {
        val body = """
            {"quotes":[{"symbol":"AAPL","shortname":"Apple Inc.","quoteType":"EQUITY"},
            {"symbol":"^GSPC","shortname":"S&P 500","quoteType":"INDEX"}]}
        """.trimIndent()
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, body))

        val assets = repo.search("apple")

        assertEquals(listOf("AAPL"), assets.map { it.symbol })
    }

    @Test
    fun mapsSearchHttp429ToRateLimited() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.search("apple") }
        assertTrue(ex is QuoteError.RateLimited)
    }

    @Test
    fun mapsSearchMalformedBodyToNetwork() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.OK, "not json"))
        val ex = assertFailsWith<QuoteError> { repo.search("apple") }
        assertTrue(ex is QuoteError.Network)
    }

    @Test
    fun fetchesDividendEventsWithMaxRangeMonthlyIntervalAndDivEvents() = runTest {
        val body = """
            {"chart":{"result":[{"meta":{"symbol":"AAPL","currency":"USD"},
            "events":{"dividends":{"1000":{"amount":0.24,"date":1000}}}}]}}
        """.trimIndent()
        var capturedUrl: String? = null
        val client = HttpClient(MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { installYahoo() }
        val repo = YahooMarketDataRepository(client)

        val events = repo.dividendEvents("AAPL", fromEpochSeconds = 0L)

        assertTrue(capturedUrl!!.contains("range=max"))
        assertTrue(capturedUrl!!.contains("interval=1mo"))
        assertTrue(capturedUrl!!.contains("events=div"))
        assertEquals(1, events.size)
        assertEquals("AAPL", events[0].symbol)
        assertEquals(1000L, events[0].exDateEpochSeconds)
        assertEquals(Money(BigDecimal.parseString("0.24"), "USD"), events[0].amountPerShare)
    }

    @Test
    fun mapsDividendEventsHttp429ToRateLimited() = runTest {
        val repo = YahooMarketDataRepository(clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.dividendEvents("AAPL", fromEpochSeconds = 0L) }
        assertTrue(ex is QuoteError.RateLimited)
    }
}
