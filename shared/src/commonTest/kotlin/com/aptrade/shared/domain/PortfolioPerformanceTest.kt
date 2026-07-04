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
    fun seriesForwardFillsMissingDatesAfterGateOpens() {
        // BTC (24/7) only starts at date 200; AAPL (market-hours) already has a close at
        // date 100. Per the all-priced gate, date 100 is skipped (BTC not priced yet); the
        // curve starts at date 200 where both are priced, and forward-fill continues to
        // hold BTC's date-200 close steady through date 300 where only AAPL moves.
        val aaplPosition = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        val btcPosition = Position(btc, qty("1"), Money.usd("100"), Money.usd("0"))
        val portfolio = Portfolio(cash = Money.usd("0"), positions = listOf(aaplPosition, btcPosition))
        val histories = mapOf(
            "AAPL" to listOf(point(100, "300.00"), point(200, "310.00"), point(300, "320.00")),
            "BTC-USD" to listOf(point(200, "150.00")),
        )

        val series = portfolio.performanceSeries(histories)

        assertEquals(2, series.size)
        // date 200 (gate opens): AAPL close 310 (3100) + BTC close 150 (150) = 3250.
        assertEquals(200L, series[0].epochSeconds)
        assertEquals(BigDecimal.parseString("3250"), series[0].value.amount)
        // date 300: AAPL close 320 (3200) + BTC forward-filled 150 (150) = 3350.
        assertEquals(300L, series[1].epochSeconds)
        assertEquals(BigDecimal.parseString("3350"), series[1].value.amount)
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

    @Test
    fun seriesStartsAtFirstDateWhereAllHeldSymbolsArePriced() {
        // Symbol A (AAPL, equity-hours) is priced from t1; symbol B (BTC, 24/7) only
        // starts at t3. The old behavior would emit a cash+A-only point at t1/t2, then a
        // cliff when B joins at t3. The gate must skip t1/t2 entirely and start at t3.
        val aaplPosition = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        val btcPosition = Position(btc, qty("2"), Money.usd("100"), Money.usd("0"))
        val portfolio = Portfolio(cash = Money.usd("1000"), positions = listOf(aaplPosition, btcPosition))
        val histories = mapOf(
            "AAPL" to listOf(point(100, "300.00"), point(200, "305.00"), point(300, "310.00")),
            "BTC-USD" to listOf(point(300, "150.00")),
        )

        val series = portfolio.performanceSeries(histories)

        assertEquals(1, series.size)
        assertEquals(300L, series[0].epochSeconds)
    }

    @Test
    fun seriesValueAtGateOpenEqualsCashPlusAllHeldSymbolsPriced() {
        val aaplPosition = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        val btcPosition = Position(btc, qty("2"), Money.usd("100"), Money.usd("0"))
        val portfolio = Portfolio(cash = Money.usd("1000"), positions = listOf(aaplPosition, btcPosition))
        val histories = mapOf(
            "AAPL" to listOf(point(100, "300.00"), point(200, "305.00"), point(300, "310.00")),
            "BTC-USD" to listOf(point(300, "150.00")),
        )

        val series = portfolio.performanceSeries(histories)

        // cash 1000 + AAPL 310*10=3100 + BTC 150*2=300 = 4400 exactly (BigDecimal).
        assertEquals(1, series.size)
        assertEquals(BigDecimal.parseString("4400"), series[0].value.amount)
    }

    @Test
    fun singleSymbolSeriesUnchangedByGate() {
        val position = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        val portfolio = Portfolio(cash = Money.usd("97000"), positions = listOf(position))
        val histories = mapOf(
            "AAPL" to listOf(point(100, "300.00"), point(200, "310.00")),
        )

        val series = portfolio.performanceSeries(histories)

        assertEquals(2, series.size)
        assertEquals(100L, series[0].epochSeconds)
        assertEquals(200L, series[1].epochSeconds)
    }

    @Test
    fun positionWithNoHistoryAtAllLeavesRemainingSymbolsCurveIntact() {
        // MSFT is held but has zero history points; it must stay excluded from `sorted`
        // (existing behavior) and must NOT gate/blank the AAPL curve.
        val aaplPosition = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        val msft = Asset("MSFT", "Microsoft Corp.", AssetKind.Stock)
        val msftPosition = Position(msft, qty("5"), Money.usd("50"), Money.usd("0"))
        val portfolio = Portfolio(cash = Money.usd("0"), positions = listOf(aaplPosition, msftPosition))
        val histories = mapOf(
            "AAPL" to listOf(point(100, "300.00"), point(200, "310.00")),
        )

        val series = portfolio.performanceSeries(histories)

        assertEquals(2, series.size)
        assertEquals(100L, series[0].epochSeconds)
        assertEquals(BigDecimal.parseString("3000"), series[0].value.amount)
        assertEquals(200L, series[1].epochSeconds)
        assertEquals(BigDecimal.parseString("3100"), series[1].value.amount)
    }
}
