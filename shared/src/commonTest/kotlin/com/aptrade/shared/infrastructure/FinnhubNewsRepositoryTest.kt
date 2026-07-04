package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.NewsCategory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinnhubNewsRepositoryTest {

    private fun clientCapturing(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "[]",
        onRequest: (HttpRequestData) -> Unit = {},
    ): HttpClient =
        HttpClient(MockEngine { request ->
            onRequest(request)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { installYahoo() }

    private fun clientReturning(status: HttpStatusCode, body: String): HttpClient =
        HttpClient(MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) { installYahoo() }

    @Test
    fun marketNewsAssemblesExactUrlAndParams() = runTest {
        var captured: HttpRequestData? = null
        val client = clientCapturing(body = "[]", onRequest = { captured = it })
        val repo = FinnhubNewsRepository("secret-token", client)

        repo.marketNews(NewsCategory.Crypto)

        val request = captured!!
        assertEquals("https", request.url.protocol.name)
        assertEquals("finnhub.io", request.url.host)
        assertEquals("/api/v1/news", request.url.encodedPath)
        assertEquals("crypto", request.url.parameters["category"])
        assertEquals("secret-token", request.url.parameters["token"])
    }

    @Test
    fun companyNewsUppercasesSymbolAndSetsSevenDayWindow() = runTest {
        var captured: HttpRequestData? = null
        val client = clientCapturing(body = "[]", onRequest = { captured = it })
        // 2024-03-10T12:00:00Z. Chosen to straddle a leap-year boundary (2024 is a leap year)
        // when the 7-day lookback crosses from March into... well within March, but the point
        // is the epoch math must be exact through Feb/Mar in a leap year.
        val fixedNowEpochSeconds = 1_710_072_000L // 2024-03-10T12:00:00Z
        val repo = FinnhubNewsRepository("secret-token", client, nowEpochSeconds = { fixedNowEpochSeconds })

        repo.companyNews("aapl")

        val request = captured!!
        assertEquals("/api/v1/company-news", request.url.encodedPath)
        assertEquals("AAPL", request.url.parameters["symbol"])
        assertEquals("2024-03-10", request.url.parameters["to"])
        assertEquals("2024-03-03", request.url.parameters["from"])
        assertEquals("secret-token", request.url.parameters["token"])
    }

    @Test
    fun companyNewsWindowCrossesLeapYearFebruaryBoundary() = runTest {
        var captured: HttpRequestData? = null
        val client = clientCapturing(body = "[]", onRequest = { captured = it })
        // 2024-03-04T00:00:00Z minus 7 days lands on 2024-02-26, inside the leap-year February.
        val fixedNowEpochSeconds = 1_709_510_400L // 2024-03-04T00:00:00Z
        val repo = FinnhubNewsRepository("secret-token", client, nowEpochSeconds = { fixedNowEpochSeconds })

        repo.companyNews("tsla")

        val request = captured!!
        assertEquals("2024-03-04", request.url.parameters["to"])
        assertEquals("2024-02-26", request.url.parameters["from"])
    }

    @Test
    fun marketNewsMapsHttp429ToRateLimited() = runTest {
        val repo = FinnhubNewsRepository("token", clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.marketNews(NewsCategory.General) }
        assertTrue(ex is QuoteError.RateLimited)
    }

    @Test
    fun companyNewsMapsHttp429ToRateLimited() = runTest {
        val repo = FinnhubNewsRepository("token", clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.companyNews("AAPL") }
        assertTrue(ex is QuoteError.RateLimited)
    }

    @Test
    fun marketNewsMapsMalformedBodyToNetwork() = runTest {
        val repo = FinnhubNewsRepository("token", clientReturning(HttpStatusCode.OK, "not json"))
        val ex = assertFailsWith<QuoteError> { repo.marketNews(NewsCategory.General) }
        assertTrue(ex is QuoteError.Network)
    }

    @Test
    fun marketNewsMapsOtherNonSuccessStatusToNetwork() = runTest {
        val repo = FinnhubNewsRepository("token", clientReturning(HttpStatusCode.InternalServerError, ""))
        val ex = assertFailsWith<QuoteError> { repo.marketNews(NewsCategory.General) }
        assertTrue(ex is QuoteError.Network)
    }

    @Test
    fun marketNewsReturnsMappedArticlesOnSuccess() = runTest {
        val body = """
            [{"category":"general","datetime":1700000000,"headline":"Headline",
              "id":7,"source":"Src","summary":"Sum","url":"https://example.com/a"}]
        """.trimIndent()
        val repo = FinnhubNewsRepository("token", clientReturning(HttpStatusCode.OK, body))

        val articles = repo.marketNews(NewsCategory.General)

        assertEquals(1, articles.size)
        assertEquals("Headline", articles[0].headline)
    }
}
