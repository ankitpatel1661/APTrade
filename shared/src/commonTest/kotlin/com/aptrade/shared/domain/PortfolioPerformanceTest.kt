package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
private val btc = Asset("BTC-USD", "Bitcoin USD", AssetKind.Crypto)
private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)
private fun point(epoch: Long, close: String) = PricePoint(epoch, Money.usd(close))

class PortfolioPerformanceTest {

    @Test
    fun seriesValuesHoldingsAgainstEachDate() {
        val position = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        val portfolio = Portfolio(cash = Money.usd("97000"), positions = listOf(position))
        val histories = mapOf(
            "AAPL" to listOf(point(100, "300.00"), point(200, "310.00")),
        )

        val series = portfolio.performanceSeries(histories)

        assertEquals(2, series.size)
        assertEquals(100L, series[0].epochSeconds)
        assertEquals(BigDecimal.parseString("100000"), series[0].value.amount)
        assertEquals(BigDecimal.parseString("0"), series[0].pnl.amount)
        assertEquals(200L, series[1].epochSeconds)
        assertEquals(BigDecimal.parseString("100100"), series[1].value.amount)
        assertEquals(BigDecimal.parseString("100"), series[1].pnl.amount)
    }

    @Test
    fun seriesForwardFillsMissingDates() {
        val aaplPosition = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        val btcPosition = Position(btc, qty("1"), Money.usd("100"), Money.usd("0"))
        val portfolio = Portfolio(cash = Money.usd("0"), positions = listOf(aaplPosition, btcPosition))
        val histories = mapOf(
            "AAPL" to listOf(point(100, "300.00"), point(200, "310.00")),
            "BTC-USD" to listOf(point(200, "150.00")),
        )

        val series = portfolio.performanceSeries(histories)

        assertEquals(2, series.size)
        // date 100: only AAPL priced (close 300*10=3000); BTC contributes nothing yet.
        assertEquals(100L, series[0].epochSeconds)
        assertEquals(BigDecimal.parseString("3000"), series[0].value.amount)
        // date 200: AAPL close 310 (3100) + BTC close 150 (150) = 3250.
        assertEquals(200L, series[1].epochSeconds)
        assertEquals(BigDecimal.parseString("3250"), series[1].value.amount)
    }

    @Test
    fun seriesSkipsDatesWherNothingIsPriced() {
        val position = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        val portfolio = Portfolio(cash = Money.usd("0"), positions = listOf(position))
        // AAPL (held) only has data starting at 200; an unheld symbol has a point at 100,
        // but since iteration is over positions (not the histories map), that date is
        // never considered and does not produce a point.
        val histories = mapOf(
            "AAPL" to listOf(point(200, "310.00")),
            "MSFT" to listOf(point(100, "50.00")),
        )

        val series = portfolio.performanceSeries(histories)

        assertEquals(1, series.size)
        assertEquals(200L, series[0].epochSeconds)
        assertEquals(BigDecimal.parseString("3100"), series[0].value.amount)
    }

    @Test
    fun emptyPositionsGiveEmptySeries() {
        val portfolio = Portfolio(cash = Money.usd("100000"), positions = emptyList())
        val series = portfolio.performanceSeries(mapOf("AAPL" to listOf(point(100, "300.00"))))
        assertTrue(series.isEmpty())
    }
}
