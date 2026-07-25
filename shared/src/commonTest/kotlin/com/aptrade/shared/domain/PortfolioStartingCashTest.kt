package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M11.2 Task 1. Transcribed from the Swift as-built (`Sources/APTradeDomain/Portfolio.swift`),
 * plus the Kotlin-only `inceptionEpochSeconds` helper that Tasks 6 and 7 both consume so the
 * account-age floor and the since-inception metric cannot drift apart.
 */
class PortfolioStartingCashTest {
    private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

    @Test
    fun startingDefaultsToTheOnePermittedLiteral() {
        assertEquals(Money.usd("100000"), Portfolio.starting().cash)
        assertEquals(Money.usd("100000"), Portfolio.starting().startingCash)
        assertEquals(Money.usd("100000"), Portfolio.DEFAULT_STARTING_CASH)
    }

    @Test
    fun startingRecordsTheCallerSuppliedAmountAsBothCashAndStartingCash() {
        val portfolio = Portfolio.starting(Money.usd("25000"))
        assertEquals(Money.usd("25000"), portfolio.cash)
        assertEquals(Money.usd("25000"), portfolio.startingCash)
    }

    @Test
    fun startingCashDefaultsToCashWhenNotSuppliedToTheConstructor() {
        assertEquals(Money.usd("7500"), Portfolio(Money.usd("7500")).startingCash)
    }

    @Test
    fun buyingCarriesStartingCashForwardUnchanged() {
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("10"), Money.usd("100"), 1_000L, "txn-1")
        assertEquals(Money.usd("49000"), portfolio.cash)
        assertEquals(Money.usd("50000"), portfolio.startingCash)
    }

    @Test
    fun sellingCarriesStartingCashForwardUnchanged() {
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("10"), Money.usd("100"), 1_000L, "txn-1")
            .selling("AAPL", qty("10"), Money.usd("120"), 2_000L, "txn-2")
        assertEquals(Money.usd("50000"), portfolio.startingCash)
    }

    @Test
    fun receivingDividendCarriesStartingCashForwardUnchanged() {
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("10"), Money.usd("100"), 1_000L, "txn-1")
            .receivingDividend("txn-2", "AAPL", Money.usd("0.25"), qty("10"), 3_000L)
        assertEquals(Money.usd("50000"), portfolio.startingCash)
    }

    @Test
    fun inceptionEpochSecondsIsNullForAnUntradedPortfolio() {
        assertNull(Portfolio.starting().inceptionEpochSeconds())
    }

    @Test
    fun inceptionEpochSecondsIsTheEarliestTransactionEpoch() {
        val portfolio = Portfolio.starting()
            .buying(aapl, qty("1"), Money.usd("100"), 5_000L, "txn-1")
            .buying(aapl, qty("1"), Money.usd("100"), 2_000L, "txn-2")
        assertEquals(2_000L, portfolio.inceptionEpochSeconds())
    }
}
