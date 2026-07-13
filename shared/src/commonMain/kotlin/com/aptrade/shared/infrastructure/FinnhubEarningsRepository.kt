package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.EarningsCalendarRepository
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.EarningsEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DEFAULT_TTL_SECONDS = 6 * 60 * 60L // earnings dates don't move intraday

/**
 * Finnhub-backed [EarningsCalendarRepository]. Reuses the Yahoo adapter's per-platform
 * client ([defaultYahooHttpClient]) and [FinnhubNewsRepository]'s error-mapping idiom
 * (429 -> RateLimited, other non-2xx/malformed -> Network); CancellationException always
 * rethrows first.
 *
 * Responses are cached in-memory per (fromDay, toDay) for [ttlSeconds] (default 6h) so the
 * calendar screen, every detail screen, and the daily notification check share one network
 * call. `nowEpochSeconds` is the same injectable clock seam [FinnhubNewsRepository] uses,
 * defaulting to the real wall clock.
 */
class FinnhubEarningsRepository internal constructor(
    private val apiKey: String,
    private val client: HttpClient,
    private val nowEpochSeconds: () -> Long = { epochSecondsNow() },
    private val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
) : EarningsCalendarRepository, AutoCloseable {

    // Production / Swift-harness entry point: builds the platform HTTP client via the
    // defaultYahooHttpClient expect/actual (Darwin on Apple, CIO on the JVM).
    constructor(apiKey: String) : this(apiKey, defaultYahooHttpClient())

    override fun close() { client.close() }

    private data class CacheEntry(val atEpochSeconds: Long, val events: List<EarningsEvent>)

    private val mutex = Mutex()
    private val cache = HashMap<Pair<String, String>, CacheEntry>()

    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> {
        val key = fromDay to toDay
        mutex.withLock {
            cache[key]?.let { entry ->
                if (nowEpochSeconds() - entry.atEpochSeconds < ttlSeconds) return entry.events
            }
        }

        val response = try {
            client.get("https://finnhub.io/api/v1/calendar/earnings") {
                url {
                    parameters.append("from", fromDay)
                    parameters.append("to", toDay)
                    parameters.append("token", apiKey)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network(e.message ?: "request failed")
        }

        val events = FinnhubEarningsMapper.events(decodeCalendar(response))
        mutex.withLock { cache[key] = CacheEntry(nowEpochSeconds(), events) }
        return events
    }

    private suspend fun decodeCalendar(response: HttpResponse): FinnhubEarningsCalendarDTO {
        if (response.status == HttpStatusCode.TooManyRequests) throw QuoteError.RateLimited
        if (!response.status.isSuccess()) throw QuoteError.Network("HTTP ${response.status.value}")

        return try {
            finnhubJson.decodeFromString(
                FinnhubEarningsCalendarDTO.serializer(),
                response.body<String>(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw QuoteError.Network("malformed response")
        }
    }
}
