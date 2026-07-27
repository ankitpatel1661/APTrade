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
        assertEquals(0.20, result.metrics.sinceInceptionReturnFraction!!, 1e-9)
    }

    /** M11.2 Task 7 review Finding 1 (Critical): the ORIGINAL version of this fixture bought at
     *  exactly the same price as the first historical close ($100 == $100), which makes the
     *  curve's own opening value collapse algebraically to `startingCash` (cash-after-buy + qty *
     *  first-close == startingCash whenever first-close == trade price). Under that fixture, a
     *  `portfolio.startingCash`-based implementation and a `points.first().value`-based one are
     *  BIT-IDENTICAL — the test could not tell them apart, and every other test in this file has
     *  the same collapse, so NONE of them discriminated.
     *
     *  Fixed by making the first historical close ($80) differ from the trade price ($100), so the
     *  curve's opening value and `startingCash` are genuinely different numbers:
     *    cash after buy               = 100,000 - 100*100            = 90,000
     *    curve's OWN opening value     = 90,000 + 100 * 80            = 98,000   (<> startingCash)
     *    curve's latest value          = 90,000 + 100 * 200           = 110,000
     *    CORRECT (from startingCash):  110,000 / 100,000 - 1          = 0.10
     *    WRONG (from curve origin):    110,000 /  98,000 - 1          = 0.12244897959183673...
     *  A silent revert to `points.first().value` would produce ~0.1224 here, not 0.10 -- far
     *  outside the 1e-9 tolerance below, so this test now genuinely reddens on that regression
     *  (verified by temporarily swapping the implementation and re-running; see task-7-report.md). */
    @Test
    fun theSameCurveAgainstADifferentOpeningBalanceGivesADifferentReturn() = runTest {
        val portfolio = Portfolio.starting(Money.usd("100000"))
            .buying(aapl, qty("100"), Money.usd("100"), 1_000_000L, "txn-1")
        val history = listOf(
            PricePoint(1_000_000L, Money.usd("80")),
            PricePoint(1_000_000L + 200 * day, Money.usd("200")),
        )
        val repository = PerfFakeMarketDataRepository(historiesBySymbol = mapOf("AAPL" to history))
        val store = PerfInMemoryPortfolioStore(portfolio)
        val performance = FetchPortfolioPerformance(repository, store)
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        // 100,000 cash - 10,000 spent + 20,000 holdings (100 * $200) = 110,000 -> +10% from
        // startingCash. (From the curve's own $80 opening it would instead be +12.24%.)
        assertEquals(0.10, result.metrics.sinceInceptionReturnFraction!!, 1e-9)
    }

    @Test
    fun sinceInceptionIsNullWhenThereIsNoCurveToReadTheLatestValueFrom() = runTest {
        val portfolio = Portfolio.starting(Money.usd("50000"))
        val repository = PerfFakeMarketDataRepository(historiesBySymbol = emptyMap())
        val store = PerfInMemoryPortfolioStore(portfolio)
        val performance = FetchPortfolioPerformance(repository, store)
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        assertNull(result.metrics.sinceInceptionReturnFraction)
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
        assertNull(result.metrics.sinceInceptionReturnFraction)
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
        assertEquals(untrimmed.metrics.sinceInceptionReturnFraction, trimmed.metrics.sinceInceptionReturnFraction)
    }
}
