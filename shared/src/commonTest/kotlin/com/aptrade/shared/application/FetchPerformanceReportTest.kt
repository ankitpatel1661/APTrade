package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.RiskMetrics
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class PerfInMemoryPortfolioStore(var stored: Portfolio? = null) : PortfolioStore {
    override suspend fun load(): Portfolio? = stored
    override suspend fun save(portfolio: Portfolio) {
        stored = portfolio
    }
}

private class PerfFakeMarketDataRepository(
    private val historiesBySymbol: Map<String, List<PricePoint>> = emptyMap(),
    private val failingSymbols: Set<String> = emptySet(),
    private val cancellingSymbols: Set<String> = emptySet(),
) : MarketDataRepository {
    override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
        if (symbol in cancellingSymbols) throw kotlinx.coroutines.CancellationException("cancelled")
        if (symbol in failingSymbols) throw QuoteError.RateLimited
        return historiesBySymbol[symbol] ?: emptyList()
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
    override suspend fun search(query: String): List<Asset> = emptyList()
}

private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)

class FetchPerformanceReportTest {

    @Test
    fun reportComputesMetricsFromEquityCurve() = runTest {
        // AAPL history builds a known 3-point equity curve via FetchPortfolioPerformance.
        val portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = PerfInMemoryPortfolioStore(portfolio)
        val history = listOf(
            PricePoint(86_400L * 1, Money.usd("100.00")),
            PricePoint(86_400L * 2, Money.usd("110.00")),
            PricePoint(86_400L * 3, Money.usd("90.00")),
        )
        val repository = PerfFakeMarketDataRepository(historiesBySymbol = mapOf("AAPL" to history))
        val fetchPerformance = FetchPortfolioPerformance(repository, store)

        val report = FetchPerformanceReport(repository, fetchPerformance)
            .execute(Timeframe.OneMonth, benchmark = "SPY", portfolio = portfolio)

        assertEquals(3, report.points.size)
        val values = report.points.map { it.value.amount.doubleValue(false) }

        assertEquals(RiskMetrics.totalReturn(values), report.metrics.totalReturn)
        assertEquals(RiskMetrics.maxDrawdown(values), report.metrics.maxDrawdown)
        assertEquals(RiskMetrics.annualizedReturn(values), report.metrics.annualizedReturn)
        assertEquals(RiskMetrics.annualizedVolatility(values), report.metrics.volatility)
    }

    @Test
    fun benchmarkClosesComeFromRepositoryHistory() = runTest {
        val portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = PerfInMemoryPortfolioStore(portfolio)
        val aaplHistory = listOf(
            PricePoint(86_400L * 1, Money.usd("100.00")),
            PricePoint(86_400L * 2, Money.usd("110.00")),
            PricePoint(86_400L * 3, Money.usd("120.00")),
        )
        val spyHistory = listOf(
            PricePoint(86_400L * 1, Money.usd("400.00")),
            PricePoint(86_400L * 2, Money.usd("410.00")),
            PricePoint(86_400L * 3, Money.usd("405.00")),
        )
        val repository = PerfFakeMarketDataRepository(
            historiesBySymbol = mapOf("AAPL" to aaplHistory, "SPY" to spyHistory),
        )
        val fetchPerformance = FetchPortfolioPerformance(repository, store)

        val report = FetchPerformanceReport(repository, fetchPerformance)
            .execute(Timeframe.OneMonth, benchmark = "SPY", portfolio = portfolio)

        assertNotNull(report.benchmarkCloses)
        assertEquals(spyHistory.map { it.close.amount.doubleValue(false) }, report.benchmarkCloses)
        assertNotNull(report.metrics.beta)
        assertNotNull(report.metrics.alpha)
    }

    @Test
    fun benchmarkPointsBeforeCurveStartAreExcluded() = runTest {
        // Portfolio curve starts at day 2 (points.first()); benchmark has an extra earlier
        // point at day 1 that must be dropped before mapping to benchmarkCloses so the two
        // curves describe the same window.
        val portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = PerfInMemoryPortfolioStore(portfolio)
        val aaplHistory = listOf(
            PricePoint(86_400L * 2, Money.usd("100.00")),
            PricePoint(86_400L * 3, Money.usd("110.00")),
        )
        val spyHistory = listOf(
            PricePoint(86_400L * 1, Money.usd("390.00")),
            PricePoint(86_400L * 2, Money.usd("400.00")),
            PricePoint(86_400L * 3, Money.usd("405.00")),
        )
        val repository = PerfFakeMarketDataRepository(
            historiesBySymbol = mapOf("AAPL" to aaplHistory, "SPY" to spyHistory),
        )
        val fetchPerformance = FetchPortfolioPerformance(repository, store)

        val report = FetchPerformanceReport(repository, fetchPerformance)
            .execute(Timeframe.OneMonth, benchmark = "SPY", portfolio = portfolio)

        assertNotNull(report.benchmarkCloses)
        assertEquals(2, report.benchmarkCloses!!.size)
        assertEquals(400.0, report.benchmarkCloses!!.first())
    }

    @Test
    fun benchmarkEntirelyInsideWindowIsUntouched() = runTest {
        val portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = PerfInMemoryPortfolioStore(portfolio)
        val aaplHistory = listOf(
            PricePoint(86_400L * 1, Money.usd("100.00")),
            PricePoint(86_400L * 2, Money.usd("110.00")),
            PricePoint(86_400L * 3, Money.usd("120.00")),
        )
        val spyHistory = listOf(
            PricePoint(86_400L * 1, Money.usd("400.00")),
            PricePoint(86_400L * 2, Money.usd("410.00")),
            PricePoint(86_400L * 3, Money.usd("405.00")),
        )
        val repository = PerfFakeMarketDataRepository(
            historiesBySymbol = mapOf("AAPL" to aaplHistory, "SPY" to spyHistory),
        )
        val fetchPerformance = FetchPortfolioPerformance(repository, store)

        val report = FetchPerformanceReport(repository, fetchPerformance)
            .execute(Timeframe.OneMonth, benchmark = "SPY", portfolio = portfolio)

        assertNotNull(report.benchmarkCloses)
        assertEquals(spyHistory.map { it.close.amount.doubleValue(false) }, report.benchmarkCloses)
    }

    @Test
    fun benchmarkFailureIsSwallowed() = runTest {
        val portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = PerfInMemoryPortfolioStore(portfolio)
        val aaplHistory = listOf(
            PricePoint(86_400L * 1, Money.usd("100.00")),
            PricePoint(86_400L * 2, Money.usd("110.00")),
        )
        val repository = PerfFakeMarketDataRepository(
            historiesBySymbol = mapOf("AAPL" to aaplHistory),
            failingSymbols = setOf("SPY"),
        )
        val fetchPerformance = FetchPortfolioPerformance(repository, store)

        val report = FetchPerformanceReport(repository, fetchPerformance)
            .execute(Timeframe.OneMonth, benchmark = "SPY", portfolio = portfolio)

        assertNull(report.benchmarkCloses)
        assertNull(report.metrics.beta)
        assertNull(report.metrics.alpha)
        assertEquals(2, report.points.size)
        val values = report.points.map { it.value.amount.doubleValue(false) }
        assertEquals(RiskMetrics.totalReturn(values), report.metrics.totalReturn)
        assertEquals(RiskMetrics.maxDrawdown(values), report.metrics.maxDrawdown)
    }

    @Test
    fun cancellationRethrownFromBenchmarkFetch() = runTest {
        val portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = PerfInMemoryPortfolioStore(portfolio)
        val aaplHistory = listOf(
            PricePoint(86_400L * 1, Money.usd("100.00")),
            PricePoint(86_400L * 2, Money.usd("110.00")),
        )
        val repository = PerfFakeMarketDataRepository(
            historiesBySymbol = mapOf("AAPL" to aaplHistory),
            cancellingSymbols = setOf("SPY"),
        )
        val fetchPerformance = FetchPortfolioPerformance(repository, store)

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            FetchPerformanceReport(repository, fetchPerformance).execute(Timeframe.OneMonth, benchmark = "SPY", portfolio = portfolio)
        }
    }

    @Test
    fun emptyPortfolioYieldsEmptyReport() = runTest {
        val portfolio = Portfolio.starting()
        val store = PerfInMemoryPortfolioStore(portfolio)
        var benchmarkFetched = false
        val repository = object : MarketDataRepository {
            override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
            override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
                benchmarkFetched = true
                return emptyList()
            }
            override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
            override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
            override suspend fun search(query: String): List<Asset> = emptyList()
        }
        val fetchPerformance = FetchPortfolioPerformance(repository, store)

        val report = FetchPerformanceReport(repository, fetchPerformance)
            .execute(Timeframe.OneMonth, benchmark = "SPY", portfolio = portfolio)

        assertEquals(emptyList(), report.points)
        assertNull(report.benchmarkCloses)
        assertEquals(0.0, report.metrics.totalReturn)
        assertEquals(0.0, report.metrics.annualizedReturn)
        assertEquals(0.0, report.metrics.volatility)
        assertEquals(0.0, report.metrics.maxDrawdown)
        assertNull(report.metrics.sharpe)
        assertNull(report.metrics.beta)
        assertNull(report.metrics.alpha)
        assertTrue(!benchmarkFetched, "empty portfolio must short-circuit before fetching the benchmark")
    }

    @Test
    fun benchmarkTwinValuesUseUntrimmedBenchmarkHistory() = runTest {
        // Portfolio curve starts at day 2 (AAPL's first candle). The trade itself happened
        // at day 1 (epoch 86_400), before the curve start — the twin must still see the
        // benchmark candle at day 1 (pre-head-trim) to value that trade correctly, even
        // though benchmarkCloses (post head-trim) excludes it.
        val portfolio = Portfolio.starting()
            .buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 86_400L, "txn-1")
        val store = PerfInMemoryPortfolioStore(portfolio)
        val aaplHistory = listOf(
            PricePoint(86_400L * 2, Money.usd("100.00")),
            PricePoint(86_400L * 3, Money.usd("110.00")),
        )
        val spyHistory = listOf(
            PricePoint(86_400L * 1, Money.usd("400.00")),
            PricePoint(86_400L * 2, Money.usd("400.00")),
            PricePoint(86_400L * 3, Money.usd("440.00")),
        )
        val repository = PerfFakeMarketDataRepository(
            historiesBySymbol = mapOf("AAPL" to aaplHistory, "SPY" to spyHistory),
        )
        val fetchPerformance = FetchPortfolioPerformance(repository, store)

        val report = FetchPerformanceReport(repository, fetchPerformance)
            .execute(Timeframe.OneMonth, benchmark = "SPY", portfolio = portfolio)

        assertNotNull(report.benchmarkTwinValues)
        assertEquals(report.points.size, report.benchmarkTwinValues!!.size)
        // Trade at day 1: $100 spent, benchmark close at day 1 = 400 -> U = 0.25.
        // Portfolio cash after the buy is 100000 - 100 = 99900.
        // At day 2 (curve start), benchmark close = 400 -> value = 99900 + 0.25*400 = 100000.
        assertEquals(BigDecimal.parseString("100000"), report.benchmarkTwinValues!![0].amount)
        // At day 3, benchmark close = 440 -> value = 99900 + 0.25*440 = 100010.
        assertEquals(BigDecimal.parseString("100010"), report.benchmarkTwinValues!![1].amount)
    }

    @Test
    fun benchmarkTwinValuesAreNullWhenBenchmarkHistoryIsEmpty() = runTest {
        // No SPY history to replay against -> twin (like benchmarkCloses) is null. This is the
        // "Benchmark unavailable" path the desktop overlay reads.
        val portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = PerfInMemoryPortfolioStore(portfolio)
        val aaplHistory = listOf(
            PricePoint(86_400L * 1, Money.usd("100.00")),
            PricePoint(86_400L * 2, Money.usd("110.00")),
        )
        val repository = PerfFakeMarketDataRepository(
            historiesBySymbol = mapOf("AAPL" to aaplHistory),   // no "SPY" -> empty benchmark history
        )
        val fetchPerformance = FetchPortfolioPerformance(repository, store)

        val report = FetchPerformanceReport(repository, fetchPerformance)
            .execute(Timeframe.OneMonth, benchmark = "SPY", portfolio = portfolio)

        assertNull(report.benchmarkCloses)
        assertNull(report.benchmarkTwinValues)
    }
}
