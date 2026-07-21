package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Transcribed from `Tests/APTradeDomainTests/DividendMathTests.swift`, byte-value-equal (14
 * fixtures), plus one Kotlin-only addition exercising the epoch-day civil month-key math
 * directly (CARRY-NOTE 5 — see `DividendMath.monthlyReceived`'s doc comment) since Kotlin
 * `commonMain` has no `DateFormatter`/`Calendar` to diverge from in the first place.
 *
 * Swift's `date(y, m, d)` test helper builds a UTC `Date`; [date] here returns the
 * equivalent UTC epoch-seconds `Long` via the same Hinnant days-from-civil algorithm
 * [DividendMath] itself uses (test-only private copy — mirrors the house precedent of each
 * file keeping its own copy rather than widening visibility, e.g. `PieSchedule.kt`).
 */
class DividendMathTest {
    private fun usd(s: String): Money = Money.usd(s)
    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

    private fun date(y: Int, m: Int, d: Int): Long = daysFromCivil(y.toLong(), m, d) * 86_400L

    private fun floorDiv(x: Long, y: Long): Long {
        val q = x / y
        return if ((x xor y) < 0 && q * y != x) q - 1 else q
    }

    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = floorDiv(y, 400L)
        val yoe = y - era * 400L
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    // MARK: sharesHeld

    @Test
    fun sharesHeld_netsBuysAndSellsBeforeDate() {
        val txns = listOf(
            Transaction("t1", "AAPL", TradeSide.Buy, qty("10"), usd("100"), date(2026, 1, 1)),
            Transaction("t2", "AAPL", TradeSide.Sell, qty("3"), usd("110"), date(2026, 2, 1)),
            Transaction("t3", "AAPL", TradeSide.Buy, qty("2"), usd("120"), date(2026, 3, 1)),
        )
        val held = DividendMath.sharesHeld("AAPL", date(2026, 4, 1), txns)
        assertEquals(qty("9"), held)
    }

    @Test
    fun sharesHeld_excludesTransactionAtExactDateInstant() {
        val txns = listOf(
            Transaction("t1", "AAPL", TradeSide.Buy, qty("10"), usd("100"), date(2026, 1, 1)),
            Transaction("t2", "AAPL", TradeSide.Buy, qty("5"), usd("100"), date(2026, 2, 1)),
        )
        val held = DividendMath.sharesHeld("AAPL", date(2026, 2, 1), txns)
        assertEquals(qty("10"), held)
    }

    @Test
    fun sharesHeld_dripBuysCountLikeAnyBuy() {
        val txns = listOf(
            Transaction("t1", "AAPL", TradeSide.Buy, qty("10"), usd("100"), date(2026, 1, 1)),
            Transaction("t2", "AAPL", TradeSide.Buy, qty("1"), usd("50"), date(2026, 2, 1), isDrip = true),
        )
        val held = DividendMath.sharesHeld("AAPL", date(2026, 3, 1), txns)
        assertEquals(qty("11"), held)
    }

    @Test
    fun sharesHeld_dividendTransactionsContributeNothing() {
        val txns = listOf(
            Transaction("t1", "AAPL", TradeSide.Buy, qty("10"), usd("100"), date(2026, 1, 1)),
            Transaction("t2", "AAPL", TradeSide.Dividend, qty("10"), usd("0.5"), date(2026, 2, 1)),
        )
        val held = DividendMath.sharesHeld("AAPL", date(2026, 3, 1), txns)
        assertEquals(qty("10"), held)
    }

    // MARK: trailingAnnualPerShare

    @Test
    fun trailingAnnualPerShare_sumsExactlyLast365Days() {
        val asOf = date(2026, 7, 20)
        val excludedAtMinus365 = DividendEvent("AAPL", asOf - 365 * 86_400L, usd("0.20"))
        val includedJustInside = DividendEvent("AAPL", asOf - 364 * 86_400L, usd("0.30"))
        val includedAtAsOf = DividendEvent("AAPL", asOf, usd("0.40"))

        val sum = DividendMath.trailingAnnualPerShare(
            listOf(excludedAtMinus365, includedJustInside, includedAtAsOf), asOf,
        )

        assertEquals(usd("0.70"), sum)
    }

    @Test
    fun trailingAnnualPerShare_zeroWhenNoEvents() {
        assertEquals(usd("0"), DividendMath.trailingAnnualPerShare(emptyList(), date(2026, 7, 20)))
    }

    // MARK: inferredCadence

    @Test
    fun inferredCadence_quarterlyFromFourEvents() {
        val events = (0 until 4).map { i ->
            DividendEvent("AAPL", date(2026, 1, 1) + i * 91 * 86_400L, usd("0.5"))
        }
        assertEquals(DividendCadence.Quarterly, DividendMath.inferredCadence(events))
    }

    @Test
    fun inferredCadence_nilWithFewerThanTwoEvents() {
        val events = listOf(DividendEvent("AAPL", date(2026, 1, 1), usd("0.5")))
        assertNull(DividendMath.inferredCadence(events))
    }

    @Test
    fun inferredCadence_monthlySpacing() {
        val events = (0 until 3).map { i ->
            DividendEvent("AAPL", date(2026, 1, 1) + i * 30 * 86_400L, usd("0.1"))
        }
        assertEquals(DividendCadence.Monthly, DividendMath.inferredCadence(events))
    }

    // MARK: nextProjected

    @Test
    fun nextProjected_quarterlyAddsNinetyOneDays() {
        val events = (0 until 4).map { i ->
            DividendEvent("AAPL", date(2026, 1, 1) + i * 91 * 86_400L, usd("0.55"))
        }
        val last = events.maxByOrNull { it.exDateEpochSeconds }!!
        val projected = DividendMath.nextProjected(events)
        assertEquals(last.exDateEpochSeconds + 91 * 86_400L, projected?.exDateEpochSeconds)
        assertEquals(usd("0.55"), projected?.amountPerShare)
        assertEquals("AAPL", projected?.symbol)
    }

    @Test
    fun nextProjected_nilWhenCadenceNil() {
        val events = listOf(DividendEvent("AAPL", date(2026, 1, 1), usd("0.5")))
        assertNull(DividendMath.nextProjected(events))
    }

    // MARK: monthlyReceived

    @Test
    fun monthlyReceived_groupsSameMonthDividendsAndIgnoresBuysSells() {
        val txns = listOf(
            Transaction("t1", "AAPL", TradeSide.Buy, qty("10"), usd("100"), date(2026, 7, 1)),
            Transaction("t2", "AAPL", TradeSide.Dividend, qty("10"), usd("0.5"), date(2026, 7, 5)),
            Transaction("t3", "MSFT", TradeSide.Dividend, qty("4"), usd("1.0"), date(2026, 7, 20)),
            Transaction("t4", "AAPL", TradeSide.Sell, qty("2"), usd("110"), date(2026, 7, 25)),
        )
        val result = DividendMath.monthlyReceived(txns)
        assertEquals(mapOf("2026-07" to usd("9")), result)
    }

    @Test
    fun monthlyReceived_emptyWhenNoDividendTransactions() {
        val txns = listOf(
            Transaction("t1", "AAPL", TradeSide.Buy, qty("10"), usd("100"), date(2026, 7, 1)),
        )
        assertEquals(emptyMap(), DividendMath.monthlyReceived(txns))
    }

    // MARK: monthlyReceived — epoch-day civil month-key math (Kotlin-only, CARRY-NOTE 5)

    @Test
    fun monthlyReceived_separatesAdjacentMonthsAtUtcMidnightBoundary() {
        // date(2026, 1, 31) and date(2026, 2, 1) are exactly one UTC day apart, straddling a
        // month boundary — the key derivation must bucket these into DISTINCT "yyyy-MM" keys
        // purely from epoch-day civil math, with no Calendar/TimeZone/DateFormatter object
        // involved (carry-note 5).
        val txns = listOf(
            Transaction("t1", "AAPL", TradeSide.Dividend, qty("10"), usd("0.10"), date(2026, 1, 31)),
            Transaction("t2", "AAPL", TradeSide.Dividend, qty("10"), usd("0.20"), date(2026, 2, 1)),
        )
        val result = DividendMath.monthlyReceived(txns)
        assertEquals(mapOf("2026-01" to usd("1"), "2026-02" to usd("2")), result)
    }

    // MARK: projectedAnnualIncome

    @Test
    fun projectedAnnualIncome_multipliesTrailingSumsByPositionQuantitiesAndAdds() {
        val asOf = date(2026, 7, 20)
        val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
        val msft = Asset("MSFT", "Microsoft", AssetKind.Stock)
        val positions = listOf(
            Position(aapl, qty("10"), usd("100"), usd("0")),
            Position(msft, qty("5"), usd("200"), usd("0")),
        )
        val eventsBySymbol = mapOf(
            "AAPL" to listOf(DividendEvent("AAPL", asOf, usd("1.00"))),
            // MSFT intentionally absent from the map -> contributes zero
        )
        val income = DividendMath.projectedAnnualIncome(positions, eventsBySymbol, asOf)
        assertEquals(usd("10"), income)
    }
}
