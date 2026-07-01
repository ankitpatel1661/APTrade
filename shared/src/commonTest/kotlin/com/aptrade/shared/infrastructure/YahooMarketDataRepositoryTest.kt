package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Money
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
}
