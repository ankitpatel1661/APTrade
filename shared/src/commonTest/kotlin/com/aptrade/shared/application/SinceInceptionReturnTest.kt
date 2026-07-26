package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M11.2 Task 7 — the "since inception" return (kickoff decision 4a.1). This metric is the reader
 * that stops `Portfolio.startingCash` from being dead persisted state: return is measured against
 * the balance the USER CHOSE, not against whatever value the priced curve happened to open at.
 *
 * Reuses [PerfFakeMarketDataRepository] / [PerfInMemoryPortfolioStore] from
 * `FetchPerformanceReportTest.kt` (promoted to `internal` there) rather than declaring a second
 * pair of fakes — carry-notes §4 lists test-double duplication as live debt.
 */
class SinceInceptionReturnTest {
    private val day = 86_400L
    private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
    private fun qty(s: String) = BigDecimal.parseString(s)

    @Test
    fun sinceInceptionMeasuresAgainstStartingCashNotTheCurvesFirstPoint() = runTest {
        // Opened at $50,000; bought 100 AAPL at $100 (cash 40,000). Latest close $200 ->
        // total value 40,000 + 20,000 = 60,000 -> since-inception return = 60,000/50,000 - 1 = 0.20.
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("100"), Money.usd("100"), 1_000_000L, "txn-1")
        val history = listOf(
            PricePoint(1_000_000L, Money.usd("100")),
            PricePoint(1_000_000L + 200 * day, Money.usd("200")),
        )
        val repository = PerfFakeMarketDataRepository(historiesBySymbol = mapOf("AAPL" to history))
        val store = PerfInMemoryPortfolioStore(portfolio)
        val performance = FetchPortfolioPerformance(repository, store)
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        assertEquals(0.20, result.metrics.sinceInceptionReturn!!, 1e-9)
    }

    @Test
    fun theSameCurveAgainstADifferentOpeningBalanceGivesADifferentReturn() = runTest {
        val portfolio = Portfolio.starting(Money.usd("100000"))
            .buying(aapl, qty("100"), Money.usd("100"), 1_000_000L, "txn-1")
        val history = listOf(
            PricePoint(1_000_000L, Money.usd("100")),
            PricePoint(1_000_000L + 200 * day, Money.usd("200")),
        )
        val repository = PerfFakeMarketDataRepository(historiesBySymbol = mapOf("AAPL" to history))
        val store = PerfInMemoryPortfolioStore(portfolio)
        val performance = FetchPortfolioPerformance(repository, store)
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        // 100,000 cash - 10,000 spent + 20,000 holdings = 110,000 -> +10%.
        assertEquals(0.10, result.metrics.sinceInceptionReturn!!, 1e-9)
    }

    @Test
    fun sinceInceptionIsNullWhenThereIsNoCurveToReadTheLatestValueFrom() = runTest {
        val portfolio = Portfolio.starting(Money.usd("50000"))
        val repository = PerfFakeMarketDataRepository(historiesBySymbol = emptyMap())
        val store = PerfInMemoryPortfolioStore(portfolio)
        val performance = FetchPortfolioPerformance(repository, store)
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        assertNull(result.metrics.sinceInceptionReturn)
    }

    @Test
    fun sinceInceptionIsNullForANonPositiveStartingBalance() = runTest {
        // Only reachable through a hand-built Portfolio -- `starting(cash)` always records a
        // matching startingCash -- but the guard must exist so no division ever fabricates a figure.
        val portfolio = Portfolio(cash = Money.usd("10000"), startingCash = Money.usd("0"))
            .buying(aapl, qty("10"), Money.usd("100"), 1_000_000L, "txn-1")
        val history = listOf(
            PricePoint(1_000_000L, Money.usd("100")),
            PricePoint(1_000_000L + 200 * day, Money.usd("120")),
        )
        val repository = PerfFakeMarketDataRepository(historiesBySymbol = mapOf("AAPL" to history))
        val store = PerfInMemoryPortfolioStore(portfolio)
        val performance = FetchPortfolioPerformance(repository, store)
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        assertNull(result.metrics.sinceInceptionReturn)
    }

    /** The `sinceInception` flag was implemented but had ZERO callers passing `true`. This is its
     *  first real caller: the trim drops the pre-inception lead-in from the curve. */
    @Test
    fun forwardingSinceInceptionTrimsTheCurveToTheFirstTransactionDay() = runTest {
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("100"), Money.usd("100"), 1_000_000L + 100 * day, "txn-1")
        val history = listOf(
            PricePoint(1_000_000L, Money.usd("100")),
            PricePoint(1_000_000L + 50 * day, Money.usd("110")),
            PricePoint(1_000_000L + 150 * day, Money.usd("120")),
        )
        val repository = PerfFakeMarketDataRepository(historiesBySymbol = mapOf("AAPL" to history))
        val store = PerfInMemoryPortfolioStore(portfolio)
        val performance = FetchPortfolioPerformance(repository, store)
        val untrimmed = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio, sinceInception = false)
        val trimmed = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio, sinceInception = true)
        assertTrue(trimmed.points.size < untrimmed.points.size)
        assertTrue(trimmed.points.all { it.epochSeconds >= (1_000_000L + 100 * day) / day * day })
        // The metric is span-independent: it reads the LATEST value either way.
        assertEquals(untrimmed.metrics.sinceInceptionReturn, trimmed.metrics.sinceInceptionReturn)
    }
}
