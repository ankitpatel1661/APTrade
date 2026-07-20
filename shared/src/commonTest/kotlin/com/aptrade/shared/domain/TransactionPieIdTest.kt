package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Transcribed from Tests/APTradeDomainTests/TransactionPieIdTests.swift (M7.1 Swift
 *  reference). Kotlin has no Codable round-trip test here — persistence round-tripping
 *  lives in FilePortfolioStoreTest (jvmCommonTest); this file covers the pure domain
 *  semantics: Transaction carries an optional pieId, buying/selling thread it through,
 *  and portfolio cash/position math is byte-identical with and without the tag. */
private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

class TransactionPieIdTest {

    @Test
    fun transactionDefaultsToNullPieId() {
        val txn = Transaction(
            id = "t1",
            symbol = "AAPL",
            side = TradeSide.Buy,
            quantity = qty("5"),
            price = Money.usd("100"),
            epochSeconds = 0,
        )
        assertNull(txn.pieId)
    }

    @Test
    fun buyingWithPieIdCreatesTaggedTransaction() {
        val p = Portfolio.starting()
            .buying(aapl, qty("2"), Money.usd("100"), epochSeconds = 1000, id = "t1", pieId = "p1")
        val txn = p.transactions.last()
        assertEquals("p1", txn.pieId)
        assertEquals("AAPL", txn.symbol)
        assertEquals(TradeSide.Buy, txn.side)
    }

    @Test
    fun buyingWithoutPieIdCreatesUntaggedTransaction() {
        val p = Portfolio.starting()
            .buying(aapl, qty("2"), Money.usd("100"), epochSeconds = 1000, id = "t1")
        val txn = p.transactions.last()
        assertNull(txn.pieId)
        assertEquals("AAPL", txn.symbol)
    }

    @Test
    fun sellingWithPieIdCreatesTaggedTransaction() {
        val p = Portfolio.starting()
            .buying(aapl, qty("4"), Money.usd("100"), epochSeconds = 1000, id = "t1")
            .selling("AAPL", qty("2"), Money.usd("150"), epochSeconds = 2000, id = "t2", pieId = "p1")
        val txn = p.transactions.last()
        assertEquals("p1", txn.pieId)
        assertEquals("AAPL", txn.symbol)
        assertEquals(TradeSide.Sell, txn.side)
    }

    @Test
    fun sellingWithoutPieIdCreatesUntaggedTransaction() {
        val p = Portfolio.starting()
            .buying(aapl, qty("4"), Money.usd("100"), epochSeconds = 1000, id = "t1")
            .selling("AAPL", qty("2"), Money.usd("150"), epochSeconds = 2000, id = "t2")
        val txn = p.transactions.last()
        assertNull(txn.pieId)
        assertEquals("AAPL", txn.symbol)
        assertEquals(TradeSide.Sell, txn.side)
    }

    @Test
    fun portfolioCashAndPositionMathIsIdenticalWithAndWithoutPieIdTag() {
        val tagged = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), epochSeconds = 1000, id = "t1", pieId = "p1")
            .selling("AAPL", qty("4"), Money.usd("350"), epochSeconds = 2000, id = "t2", pieId = "p1")
        val untagged = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("300"), epochSeconds = 1000, id = "t1")
            .selling("AAPL", qty("4"), Money.usd("350"), epochSeconds = 2000, id = "t2")

        // Cash and position outcomes must be byte-identical regardless of pieId tagging —
        // pieId is pure attribution metadata, never an input to portfolio math.
        assertEquals(untagged.cash, tagged.cash)
        assertEquals(untagged.positionFor("AAPL")!!.quantity, tagged.positionFor("AAPL")!!.quantity)
        assertEquals(untagged.positionFor("AAPL")!!.averageCost, tagged.positionFor("AAPL")!!.averageCost)
        assertEquals(untagged.positionFor("AAPL")!!.realizedPnL, tagged.positionFor("AAPL")!!.realizedPnL)

        // Only the tag itself differs between the two transaction logs.
        assertEquals("p1", tagged.transactions[0].pieId)
        assertEquals("p1", tagged.transactions[1].pieId)
        assertNull(untagged.transactions[0].pieId)
        assertNull(untagged.transactions[1].pieId)
    }
}
