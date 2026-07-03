package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
private val btc = Asset("BTC-USD", "Bitcoin USD", AssetKind.Crypto)
private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)
private fun quote(symbol: String, price: String, prevClose: String) =
    Quote(symbol, Money.usd(price), Money.usd(prevClose), 0.0)

class PortfolioTest {

    @Test
    fun startingPortfolioHas100kCashAndNothingElse() {
        val p = Portfolio.starting()
        assertEquals(BigDecimal.parseString("100000"), p.cash.amount)
        assertTrue(p.positions.isEmpty())
        assertTrue(p.transactions.isEmpty())
    }

    @Test
    fun buyingOpensAPositionAtThePaidPrice() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), epochSeconds = 1000, id = "t1")
        val pos = p.positionFor("AAPL")!!
        assertEquals(qty("10"), pos.quantity)
        assertEquals(BigDecimal.parseString("300"), pos.averageCost.amount)
        assertEquals(BigDecimal.parseString("97000"), p.cash.amount)   // 100000 - 3000
        assertEquals(1, p.transactions.size)
        assertEquals(TradeSide.Buy, p.transactions[0].side)
        assertEquals("t1", p.transactions[0].id)
    }

    @Test
    fun buyingMoreAveragesTheCost() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .buying(aapl, qty("10"), Money.usd("400"), 2000, "t2")
        val pos = p.positionFor("AAPL")!!
        assertEquals(qty("20"), pos.quantity)
        assertEquals(BigDecimal.parseString("350"), pos.averageCost.amount) // (300*10+400*10)/20
    }

    @Test
    fun nonTerminatingAverageDoesNotThrow() {
        // avg = (100*1 + 100*2)/3 = 100, then (100*3 + 50*1)/4 ... build a true 1/3 case:
        // buy 1 @ 100, buy 2 @ 250 → avg = (100 + 500)/3 = 200 exactly; instead force 1/3:
        val p = Portfolio.starting()
            .buying(aapl, qty("3"), Money.usd("100"), 1000, "t1")       // avg 100
            .buying(aapl, qty("1"), Money.usd("1"), 2000, "t2")         // (300+1)/4 = 75.25
            .buying(aapl, qty("2"), Money.usd("1"), 3000, "t3")         // (301+2)/6 = 50.5
            .buying(aapl, qty("1"), Money.usd("2"), 4000, "t4")         // (303+2)/7 → non-terminating
        val avg = p.positionFor("AAPL")!!.averageCost.amount
        // 305/7 = 43.571428... — pinned by MONEY_MATH (38 digits, half-away). Assert the prefix.
        assertTrue(avg.toStringExpanded().startsWith("43.5714285714"))
    }

    @Test
    fun buyingZeroQuantityIsInvalid() {
        assertFailsWith<TradeError.InvalidQuantity> {
            Portfolio.starting().buying(aapl, qty("0"), Money.usd("300"), 1000, "t1")
        }
    }

    @Test
    fun buyingBeyondCashIsInsufficientFunds() {
        assertFailsWith<TradeError.InsufficientFunds> {
            Portfolio.starting().buying(aapl, qty("1000"), Money.usd("300"), 1000, "t1") // 300k > 100k
        }
    }

    @Test
    fun validationOrderChecksQuantityBeforeFunds() {
        // zero quantity AND absurd price: must throw InvalidQuantity (Swift checks quantity first)
        assertFailsWith<TradeError.InvalidQuantity> {
            Portfolio.starting().buying(aapl, qty("0"), Money.usd("999999999"), 1000, "t1")
        }
    }

    @Test
    fun sellingRealizesPnLAndFreesCash() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .selling("AAPL", qty("4"), Money.usd("350"), 2000, "t2")
        val pos = p.positionFor("AAPL")!!
        assertEquals(qty("6"), pos.quantity)
        assertEquals(BigDecimal.parseString("300"), pos.averageCost.amount)   // avg unchanged on sell
        assertEquals(BigDecimal.parseString("200"), pos.realizedPnL.amount)   // (350-300)*4
        assertEquals(BigDecimal.parseString("98400"), p.cash.amount)          // 97000 + 1400
        assertEquals(2, p.transactions.size)
    }

    @Test
    fun sellingWholePositionRemovesIt() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .selling("AAPL", qty("10"), Money.usd("310"), 2000, "t2")
        assertNull(p.positionFor("AAPL"))
        assertEquals(BigDecimal.parseString("100100"), p.cash.amount)  // +100 realized
    }

    @Test
    fun sellingMoreThanHeldIsInsufficientShares() {
        val p = Portfolio.starting().buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
        assertFailsWith<TradeError.InsufficientShares> {
            p.selling("AAPL", qty("11"), Money.usd("300"), 2000, "t2")
        }
    }

    @Test
    fun sellingUnknownSymbolIsInsufficientShares() {
        assertFailsWith<TradeError.InsufficientShares> {
            Portfolio.starting().selling("ZZZZ", qty("1"), Money.usd("1"), 1000, "t1")
        }
    }

    @Test
    fun fractionalCryptoQuantitiesStayExact() {
        val p = Portfolio.starting()
            .buying(btc, qty("0.05"), Money.usd("60000"), 1000, "t1")   // cost 3000
        assertEquals(qty("0.05"), p.positionFor("BTC-USD")!!.quantity)
        assertEquals(BigDecimal.parseString("97000"), p.cash.amount)
    }

    @Test
    fun valuationValuesAgainstQuotesWithCostBasisFallback() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), 1000, "t1")
            .buying(btc, qty("0.1"), Money.usd("60000"), 2000, "t2")    // cost 6000
        val v = p.valuation(mapOf("AAPL" to quote("AAPL", "310", "305")))
        // AAPL priced: 310*10 = 3100; BTC missing → cost basis 60000*0.1 = 6000
        assertEquals(BigDecimal.parseString("9100"), v.holdingsValue.amount)
        assertEquals(BigDecimal.parseString("91000"), v.cash.amount)
        assertEquals(BigDecimal.parseString("100100"), v.totalValue.amount)
        assertEquals(BigDecimal.parseString("100"), v.unrealizedPnL.amount)   // (310-300)*10, BTC excluded
        assertEquals(BigDecimal.parseString("50"), v.dayChange.amount)        // (310-305)*10
    }

    @Test
    fun positionHelpersComputeMarketValueAndUnrealized() {
        val pos = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
        assertEquals(BigDecimal.parseString("3100"), pos.marketValue(Money.usd("310")).amount)
        assertEquals(BigDecimal.parseString("100"), pos.unrealizedPnL(Money.usd("310")).amount)
    }

    @Test
    fun generatedTradeIdsAreUnique() {
        val ids = (1..100).map { generateTradeId() }.toSet()
        assertEquals(100, ids.size)
    }
}
