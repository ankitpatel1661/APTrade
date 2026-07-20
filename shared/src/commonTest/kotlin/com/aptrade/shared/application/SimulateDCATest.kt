package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PieBacktest
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Transcribed from `Tests/APTradeApplicationTests/SimulateDCATests.swift`, with one
 * deliberate Kotlin-native divergence: the Swift `test_simulateDCA_onCancellation_degradesToNil`
 * fixture is REPLACED (not transcribed) by
 * [simulateDCAOnCancellationRethrowsRatherThanDegradingToNull] below, which pins the opposite
 * behavior — see [SimulateDCA]'s class doc comment for the full rationale (Kotlin structured
 * concurrency requires [CancellationException] to propagate; Swift's cooperative `Task`
 * cancellation has no equivalent hazard).
 */
class SimulateDCATest {

    private val calendar = MarketCalendar()
    private val slice1 = PieSlice(symbol = "AAPL", assetKind = AssetKind.Stock, targetWeightPP = BigDecimal.parseString("50"))
    private val slice2 = PieSlice(symbol = "BTC", assetKind = AssetKind.Crypto, targetWeightPP = BigDecimal.parseString("50"))

    private fun usd(s: String): Money = Money(BigDecimal.parseString(s), "USD")

    // -- Independent re-derivation of SimulateDCA's private `yearsAgoDay`, so this test can
    // prove the production result against a parallel computation rather than against itself.
    // Duplicated rather than exposed -- mirrors the precedent every file in this area already
    // follows for its own private civil-date copy (see SimulateDCA's doc comment).

    private fun yearsAgoDay(nowEpochSeconds: Long, years: Int): String {
        val localEpochDay = calendar.localEpochDay(nowEpochSeconds)
        val (year, month, day) = civilFromDays(localEpochDay)
        val newYear = year - years
        val clampedDay = minOf(day, daysInMonth(newYear, month))
        return calendar.dayString(daysFromCivil(newYear, month, clampedDay))
    }

    private fun daysInMonth(year: Long, month: Int): Int {
        val thisMonthFirst = daysFromCivil(year, month, 1)
        val nextMonthFirst = if (month == 12) daysFromCivil(year + 1, 1, 1) else daysFromCivil(year, month + 1, 1)
        return (nextMonthFirst - thisMonthFirst).toInt()
    }

    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = floorDiv(y, 400L)
        val yoe = y - era * 400L
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = if (m <= 2) y + 1 else y
        return Triple(year, m, d)
    }

    private fun floorDiv(x: Long, y: Long): Long {
        val q = x / y
        return if ((x xor y) < 0 && q * y != x) q - 1 else q
    }

    // MARK: - Happy Path: Report Matches dcaBacktest

    @Test
    fun simulateDCAWithValidHistoryReturnsReportMatchingDcaBacktest() = runTest {
        val startEpoch = 1_609_459_200L // 2021-01-01 00:00:00 UTC
        val points1 = (0 until 252).map { i ->
            PricePoint(epochSeconds = startEpoch + i * 86_400L, close = Money(BigDecimal.fromInt(150 + i), "USD"))
        }
        val points2 = (0 until 252).map { i ->
            PricePoint(epochSeconds = startEpoch + i * 86_400L, close = Money(BigDecimal.fromInt(30_000 + i * 10), "USD"))
        }

        val market = FakeDCAMarket(mapOf("AAPL" to points1, "BTC" to points2))
        val simulate = SimulateDCA(market, calendar)

        val slices = listOf(slice1, slice2)
        val amount = usd("1000")
        val cadence = PieCadence.Monthly
        val years = 1
        val now = 1_641_038_400L // 2022-01-01 UTC

        val report = simulate.execute(slices, amount, cadence, years, now)

        assertNotNull(report)
        assertEquals(2, market.historyFetchCount)

        val dailyCloses = mutableMapOf<String, MutableMap<String, Money>>()
        for (point in points1) dailyCloses.getOrPut("AAPL") { mutableMapOf() }[calendar.tradingDay(point.epochSeconds)] = point.close
        for (point in points2) dailyCloses.getOrPut("BTC") { mutableMapOf() }[calendar.tradingDay(point.epochSeconds)] = point.close

        val startDay = yearsAgoDay(now, years)
        val endDay = calendar.tradingDay(now)

        val expected = PieBacktest.dcaBacktest(
            slices = slices, amount = amount, cadence = cadence,
            startDay = startDay, endDay = endDay, dailyCloses = dailyCloses, calendar = calendar,
        )

        assertEquals(expected, report)
    }

    // MARK: - Degrade: Network Failure Returns Null

    @Test
    fun simulateDCAOnNetworkFailureReturnsNull() = runTest {
        val market = FakeDCAMarket(failureError = QuoteError.RateLimited)
        val simulate = SimulateDCA(market, calendar)

        val report = simulate.execute(listOf(slice1, slice2), usd("1000"), PieCadence.Monthly, 1, 0L)

        assertNull(report)
    }

    @Test
    fun simulateDCAOnFailureForOneSymbolReturnsNull() = runTest {
        val points = (0 until 252).map { i -> PricePoint(1_609_459_200L + i * 86_400L, Money(BigDecimal.fromInt(150 + i), "USD")) }
        val market = FakeDCAMarket(
            historyBySymbol = mapOf("AAPL" to points, "BTC" to points),
            failureBySymbol = mapOf("BTC" to QuoteError.RateLimited),
        )
        val simulate = SimulateDCA(market, calendar)

        val report = simulate.execute(listOf(slice1, slice2), usd("1000"), PieCadence.Monthly, 1, 0L)

        assertNull(report, "any symbol failure should cause degrade to null")
    }

    // MARK: - Cancellation: CancellationException RETHROWS (Kotlin house rule -- see class doc)

    @Test
    fun simulateDCAOnCancellationRethrowsRatherThanDegradingToNull() = runTest {
        val market = FakeDCAMarket(failureError = CancellationException("cancelled"))
        val simulate = SimulateDCA(market, calendar)

        assertFailsWith<CancellationException> {
            simulate.execute(listOf(slice1, slice2), usd("1000"), PieCadence.Monthly, 1, 0L)
        }
    }

    // MARK: - Edge Cases

    @Test
    fun simulateDCAWithInsufficientHistoryReturnsNullFromBacktest() = runTest {
        // One symbol has full history, but BTC has NO history.
        val points = (0 until 252).map { i -> PricePoint(1_609_459_200L + i * 86_400L, Money(BigDecimal.fromInt(150 + i), "USD")) }
        val market = FakeDCAMarket(mapOf("AAPL" to points, "BTC" to emptyList()))
        val simulate = SimulateDCA(market, calendar)

        val report = simulate.execute(listOf(slice1, slice2), usd("1000"), PieCadence.Monthly, 1, 1_641_038_400L)

        // dcaBacktest returns null if no due day is executable (missing closes for ANY symbol).
        assertNull(report)
    }

    @Test
    fun simulateDCA3YearsCoversThreeYearsOfHistory() = runTest {
        // ~3 years of trading days.
        val points = (0 until 756).map { i -> PricePoint(1_609_459_200L + i * 86_400L, Money(BigDecimal.fromInt(150 + i), "USD")) }
        val market = FakeDCAMarket(mapOf("AAPL" to points, "BTC" to points))
        val simulate = SimulateDCA(market, calendar)

        val report = simulate.execute(listOf(slice1, slice2), usd("1000"), PieCadence.Monthly, 3, 1_672_531_200L)

        assertNotNull(report)
        assertEquals(2, market.historyFetchCount)
    }

    @Test
    fun simulateDCA5YearsWithLimitedHistoryDegradesGracefully() = runTest {
        // History port only supports .oneYear, so a 5-year request only gets 1 year;
        // dcaBacktest should handle the missing days gracefully rather than throw.
        val points = (0 until 252).map { i -> PricePoint(1_609_459_200L + i * 86_400L, Money(BigDecimal.fromInt(150 + i), "USD")) }
        val market = FakeDCAMarket(mapOf("AAPL" to points, "BTC" to points))
        val simulate = SimulateDCA(market, calendar)

        // Result depends on what dcaBacktest can execute with 1 year of history covering a
        // 5-year window (might be null, or a report showing only what's coverable); the only
        // thing pinned here is that it must not throw.
        simulate.execute(listOf(slice1, slice2), usd("1000"), PieCadence.Monthly, 5, 1_672_531_200L)
    }

    @Test
    fun simulateDCANonPositiveYearsReturnsNull() = runTest {
        val simulate = SimulateDCA(FakeDCAMarket(), calendar)

        val report = simulate.execute(listOf(slice1, slice2), usd("1000"), PieCadence.Monthly, 0, 0L)

        assertNull(report)
    }
}

private class FakeDCAMarket(
    private val historyBySymbol: Map<String, List<PricePoint>> = emptyMap(),
    private val failureError: Throwable? = null,
    private val failureBySymbol: Map<String, Throwable> = emptyMap(),
) : MarketDataRepository {
    var historyFetchCount = 0
        private set

    override suspend fun quotes(symbols: List<String>): List<Quote> = error("not needed for SimulateDCA test")

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
        historyFetchCount += 1
        failureBySymbol[symbol]?.let { throw it }
        failureError?.let { throw it }
        return historyBySymbol[symbol] ?: emptyList()
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = error("not needed for SimulateDCA test")
    override suspend fun profile(symbol: String): Asset = error("not needed for SimulateDCA test")
    override suspend fun search(query: String): List<Asset> = error("not needed for SimulateDCA test")
}
