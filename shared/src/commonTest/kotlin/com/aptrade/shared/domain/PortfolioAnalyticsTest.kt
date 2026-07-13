package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
private val btc = Asset("BTC-USD", "Bitcoin USD", AssetKind.Crypto)
private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)
private fun quote(symbol: String, price: String, prevClose: String) =
    Quote(symbol, Money.usd(price), Money.usd(prevClose), 0.0)

class PortfolioAnalyticsTest {

    @Test
    fun realizedPnLReplaysBuysAndSells() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .selling("AAPL", qty("4"), Money.usd("350"), 2000, "t2")
        assertEquals(BigDecimal.parseString("200"), p.realizedPnL.amount)
    }

    @Test
    fun realizedPnLSurvivesFullyClosedPositions() {
        val transactions = listOf(
            Transaction("t1", "AAPL", TradeSide.Buy, qty("10"), Money.usd("300"), 1000),
            Transaction("t2", "AAPL", TradeSide.Sell, qty("10"), Money.usd("350"), 2000),
        )
        val p = Portfolio(cash = Money.usd("100000"), positions = emptyList(), transactions = transactions)
        assertEquals(BigDecimal.parseString("500"), p.realizedPnL.amount)
    }

    @Test
    fun realizedPnLAveragesAcrossMultipleBuys() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .buying(aapl, qty("10"), Money.usd("400"), 2000, "t2")
            .selling("AAPL", qty("10"), Money.usd("400"), 3000, "t3")
        // average cost after both buys = (300*10+400*10)/20 = 350; realized = (400-350)*10 = 500
        assertEquals(BigDecimal.parseString("500"), p.realizedPnL.amount)
    }

    @Test
    fun allocationByHoldingIsLargestFirstWithFractions() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .buying(btc, qty("0.1"), Money.usd("60000"), 2000, "t2")
        val quotes = mapOf("AAPL" to quote("AAPL", "310", "305"))
        val slices = p.allocationByHolding(quotes)

        assertEquals(listOf("BTC-USD", "AAPL"), slices.map { it.id })
        assertEquals(6000.0, slices[0].value)
        assertEquals(3100.0, slices[1].value)

        val total = 9100.0
        assertTrue(abs(slices[0].fraction - 6000.0 / total) < 1e-9)
        assertTrue(abs(slices[1].fraction - 3100.0 / total) < 1e-9)
    }

    @Test
    fun allocationByKindGroupsAndOmitsZero() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .buying(btc, qty("0.1"), Money.usd("60000"), 2000, "t2")
        val quotes = mapOf("AAPL" to quote("AAPL", "310", "305"))
        val slices = p.allocationByKind(quotes)

        assertEquals(listOf("Stocks", "Crypto"), slices.map { it.label })
        // By-class slices carry their typed kind for render-time localization;
        // by-holding slices (see the test above) leave it null.
        assertEquals(listOf(AssetKind.Stock, AssetKind.Crypto), slices.map { it.kind })
        assertEquals(3100.0, slices[0].value)
        assertEquals(6000.0, slices[1].value)
    }
}
