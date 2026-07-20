package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Transcribed from `Tests/APTradeDomainTests/PortfolioDividendTests.swift` — semantics must
 *  not drift from the Swift as-built. */
class PortfolioDividendTest {
    private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

    // (a) receivingDividend credits cash, appends txn, leaves positions untouched.
    @Test
    fun receivingDividendCreditsCashAndAppendsTransaction() {
        val p = Portfolio.starting()
            .buying(aapl, qty("10"), Money.usd("100"), epochSeconds = 0, id = "t1")
            .receivingDividend("t2", "AAPL", Money.usd("0.50"), qty("10"), exDateEpochSeconds = 0)

        assertEquals(BigDecimal.parseString("99005"), p.cash.amount)   // 100_000 - 1000 + 5
        assertEquals(2, p.transactions.size)

        val txn = p.transactions.last()
        assertEquals(TradeSide.Dividend, txn.side)
        assertEquals("AAPL", txn.symbol)
        assertEquals(qty("10"), txn.quantity)
        assertEquals(BigDecimal.parseString("0.50"), txn.price.amount)
        assertEquals(0L, txn.epochSeconds)

        // Positions and cost basis untouched.
        assertEquals(qty("10"), p.positionFor("AAPL")?.quantity)
        assertEquals(BigDecimal.parseString("100"), p.positionFor("AAPL")?.averageCost?.amount)
    }

    // (b) invalid inputs throw InvalidQuantity.
    @Test
    fun receivingDividendZeroSharesThrows() {
        assertFailsWith<TradeError.InvalidQuantity> {
            Portfolio.starting().receivingDividend("t1", "AAPL", Money.usd("0.50"), qty("0"), exDateEpochSeconds = 0)
        }
    }

    @Test
    fun receivingDividendNonPositiveAmountThrows() {
        assertFailsWith<TradeError.InvalidQuantity> {
            Portfolio.starting().receivingDividend("t1", "AAPL", Money.usd("0"), qty("10"), exDateEpochSeconds = 0)
        }
        assertFailsWith<TradeError.InvalidQuantity> {
            Portfolio.starting().receivingDividend("t1", "AAPL", Money.usd("-0.50"), qty("10"), exDateEpochSeconds = 0)
        }
    }

    // (c) buying(..., isDrip:) records isDrip on the transaction.
    @Test
    fun buyingIsDripRecordsOnTransaction() {
        val p = Portfolio.starting()
            .buying(aapl, qty("1"), Money.usd("100"), epochSeconds = 0, id = "t1", isDrip = true)
        assertEquals(true, p.transactions.last().isDrip)
    }

    @Test
    fun buyingDefaultIsDripIsFalse() {
        val p = Portfolio.starting()
            .buying(aapl, qty("1"), Money.usd("100"), epochSeconds = 0, id = "t1")
        assertEquals(false, p.transactions.last().isDrip)
    }
}
