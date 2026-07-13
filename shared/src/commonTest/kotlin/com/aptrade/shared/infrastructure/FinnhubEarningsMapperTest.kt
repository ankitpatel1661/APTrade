package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.EarningsSession
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

class FinnhubEarningsMapperTest {

    private val sample = """
        {"earningsCalendar":[
          {"date":"2026-07-24","epsActual":null,"epsEstimate":1.52,"hour":"amc","quarter":3,
           "revenueActual":null,"revenueEstimate":90000000000,"symbol":"AAPL","year":2026},
          {"date":"2026-07-21","epsActual":2.11,"epsEstimate":2.05,"hour":"bmo","symbol":"KO"},
          {"date":"2026-07-22","hour":"dmh","symbol":"XYZ"},
          {"hour":"amc","symbol":"NODATE"},
          {"date":"2026-07-23","hour":"weird","symbol":"ODD"}
        ]}
    """.trimIndent()

    @Test
    fun mapsFieldsAndSessions() {
        val dto = finnhubJson.decodeFromString(FinnhubEarningsCalendarDTO.serializer(), sample)
        val events = FinnhubEarningsMapper.events(dto)
        val bySymbol = events.associateBy { it.symbol }
        assertEquals(4, events.size) // NODATE dropped (no date)
        assertEquals(EarningsSession.AfterClose, bySymbol.getValue("AAPL").session)
        assertEquals(1.52, bySymbol.getValue("AAPL").epsEstimate)
        assertEquals(EarningsSession.BeforeOpen, bySymbol.getValue("KO").session)
        assertEquals(2.11, bySymbol.getValue("KO").epsActual)
        assertEquals(EarningsSession.DuringMarket, bySymbol.getValue("XYZ").session)
        assertEquals(EarningsSession.Unknown, bySymbol.getValue("ODD").session)
        assertEquals("2026-07-24", bySymbol.getValue("AAPL").day)
        assertEquals("", bySymbol.getValue("AAPL").companyName) // endpoint carries no name
    }

    @Test
    fun emptyPayloadMapsToEmpty() {
        val dto = finnhubJson.decodeFromString(FinnhubEarningsCalendarDTO.serializer(), "{}")
        assertEquals(emptyList(), FinnhubEarningsMapper.events(dto))
    }

    // --- Repository / cache -------------------------------------------------

    private fun clientCapturing(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"earningsCalendar":[]}""",
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
    fun earningsAssemblesExactUrlAndParams() = runTest {
        var captured: HttpRequestData? = null
        val client = clientCapturing(onRequest = { captured = it })
        val repo = FinnhubEarningsRepository("secret-token", client)

        repo.earnings("2026-07-20", "2026-07-27")

        val request = captured!!
        assertEquals("https", request.url.protocol.name)
        assertEquals("finnhub.io", request.url.host)
        assertEquals("/api/v1/calendar/earnings", request.url.encodedPath)
        assertEquals("2026-07-20", request.url.parameters["from"])
        assertEquals("2026-07-27", request.url.parameters["to"])
        assertEquals("secret-token", request.url.parameters["token"])
    }

    @Test
    fun earningsMapsHttp429ToRateLimited() = runTest {
        val repo = FinnhubEarningsRepository("token", clientReturning(HttpStatusCode.TooManyRequests, ""))
        val ex = assertFailsWith<QuoteError> { repo.earnings("2026-07-20", "2026-07-27") }
        assertTrue(ex is QuoteError.RateLimited)
    }

    @Test
    fun earningsMapsMalformedBodyToNetwork() = runTest {
        val repo = FinnhubEarningsRepository("token", clientReturning(HttpStatusCode.OK, "not json"))
        val ex = assertFailsWith<QuoteError> { repo.earnings("2026-07-20", "2026-07-27") }
        assertTrue(ex is QuoteError.Network)
    }

    @Test
    fun earningsMapsOtherNonSuccessStatusToNetwork() = runTest {
        val repo = FinnhubEarningsRepository("token", clientReturning(HttpStatusCode.InternalServerError, ""))
        val ex = assertFailsWith<QuoteError> { repo.earnings("2026-07-20", "2026-07-27") }
        assertTrue(ex is QuoteError.Network)
    }

    @Test
    fun secondCallWithinTtlServesCache() = runTest {
        var hitCount = 0
        val client = clientCapturing(onRequest = { hitCount++ })
        var fixedNow = 1_000_000L
        val repo = FinnhubEarningsRepository(
            apiKey = "secret-token",
            client = client,
            nowEpochSeconds = { fixedNow },
        )

        repo.earnings("2026-07-20", "2026-07-27")
        repo.earnings("2026-07-20", "2026-07-27")

        assertEquals(1, hitCount)
    }

    @Test
    fun callAfterTtlExpiryRefetches() = runTest {
        var hitCount = 0
        val client = clientCapturing(onRequest = { hitCount++ })
        var fixedNow = 1_000_000L
        val repo = FinnhubEarningsRepository(
            apiKey = "secret-token",
            client = client,
            nowEpochSeconds = { fixedNow },
        )

        repo.earnings("2026-07-20", "2026-07-27")
        fixedNow += 6 * 60 * 60 + 1 // just past the 6h TTL
        repo.earnings("2026-07-20", "2026-07-27")

        assertEquals(2, hitCount)
    }

    @Test
    fun differentRangesCacheIndependently() = runTest {
        var hitCount = 0
        val client = clientCapturing(onRequest = { hitCount++ })
        val repo = FinnhubEarningsRepository(apiKey = "secret-token", client = client)

        repo.earnings("2026-07-20", "2026-07-27")
        repo.earnings("2026-08-01", "2026-08-08")

        assertEquals(2, hitCount)
    }
}
