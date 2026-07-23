package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.Transaction
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [IncomeSummaryMath]'s two hoisted functions against the SAME semantics desktop
 * `AppGraph.buildHomeIncomeSummary`/both platforms' private `IncomeViewModel.receivedYTD`/
 * `buildUpcoming` proved equivalent to (M10.2 final review / M10.3 plan's recorded chip) —
 * this is what closes that "equivalent-but-unguarded" gap: one pinned implementation instead
 * of three silently-agreeing copies.
 *
 * `date(y, m, d)` mirrors the house test-only private copy of the Hinnant days-from-civil
 * algorithm every date-math test file in this codebase already keeps (see
 * `DividendMathTest.kt`'s identical helper) rather than depending on a shared test util.
 */
class IncomeSummaryMathTest {
    private fun usd(s: String): Money = Money.usd(s)
    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

    /** UTC epoch-seconds for `y-m-d 00:00:00`, plus optional seconds-into-day. */
    private fun date(y: Int, m: Int, d: Int, secondsIntoDay: Long = 0L): Long =
        daysFromCivil(y.toLong(), m, d) * 86_400L + secondsIntoDay

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

    private fun asset(symbol: String, kind: AssetKind = AssetKind.Stock) = Asset(symbol, symbol, kind)

    private fun position(symbol: String, quantity: String, kind: AssetKind = AssetKind.Stock): Position =
        Position(asset(symbol, kind), qty(quantity), usd("100"), usd("0"))

    // MARK: - receivedYTD: year filter + Jan 1 / Dec 31 UTC boundary

    @Test
    fun receivedYTD_includesOnlyDividendTransactionsInCurrentUtcYear() {
        val asOf = date(2026, 6, 15)
        val txns = listOf(
            // In-year dividend — counted.
            Transaction("t1", "AAPL", TradeSide.Dividend, qty("10"), usd("2"), date(2026, 3, 1)),
            // Prior-year dividend — excluded.
            Transaction("t2", "AAPL", TradeSide.Dividend, qty("10"), usd("5"), date(2025, 12, 31)),
            // In-year but NOT a dividend (a Buy) — excluded regardless of year.
            Transaction("t3", "AAPL", TradeSide.Buy, qty("10"), usd("100"), date(2026, 2, 1)),
        )

        val received = IncomeSummaryMath.receivedYTD(txns, asOf)

        assertEquals(usd("20"), received) // 10 shares * $2 from t1 only
    }

    @Test
    fun receivedYTD_boundaryJan1AndDec31UtcAreBothIncluded() {
        val asOf = date(2026, 6, 15)
        val txns = listOf(
            // First instant of the year, UTC — included.
            Transaction("t1", "AAPL", TradeSide.Dividend, qty("1"), usd("3"), date(2026, 1, 1, secondsIntoDay = 0)),
            // Last instant of the year, UTC — included.
            Transaction("t2", "AAPL", TradeSide.Dividend, qty("1"), usd("4"), date(2026, 12, 31, secondsIntoDay = 86_399)),
            // One second into the NEXT year — excluded.
            Transaction("t3", "AAPL", TradeSide.Dividend, qty("1"), usd("999"), date(2027, 1, 1, secondsIntoDay = 0)),
        )

        val received = IncomeSummaryMath.receivedYTD(txns, asOf)

        assertEquals(usd("7"), received) // $3 (Jan 1) + $4 (Dec 31), never the next-year txn
    }

    @Test
    fun receivedYTD_emptyTransactions_returnsZero() {
        val received = IncomeSummaryMath.receivedYTD(emptyList(), date(2026, 6, 15))

        assertEquals(usd("0"), received)
    }

    // MARK: - nextUpcomingDividend: soonest-across-holdings selection, first-on-tie

    @Test
    fun nextUpcomingDividend_selectsSoonestAcrossHoldings_firstOnTie() = runTest {
        val asOf = date(2026, 6, 1)
        val positions = listOf(position("AAPL", "10"), position("MSFT", "5"))
        // Both AAPL and MSFT project to the SAME next ex-date (a tie) — AAPL, encountered
        // first in `positions`, must win (matching DividendMath.nextProjected's own
        // maxByOrNull first-on-tie semantics: only replaced on a strict `<`, never `<=`).
        val tieDate = date(2026, 7, 1)
        // Two events, 30 days apart (Monthly cadence, <= 45d gap), ending 30 days before
        // `tieDate` for BOTH symbols — DividendMath.nextProjected's `lastExDate + cadence
        // interval` (30d for Monthly) then lands both projections exactly on `tieDate`,
        // a genuine tie.
        val aaplEvents = listOf(
            DividendEvent("AAPL", date(2026, 5, 2), usd("1")),
            DividendEvent("AAPL", tieDate - 30L * 86_400L, usd("1")),
        )
        val msftEvents = listOf(
            DividendEvent("MSFT", date(2026, 5, 2), usd("2")),
            DividendEvent("MSFT", tieDate - 30L * 86_400L, usd("2")),
        )
        val fetcher: suspend (String, Long) -> List<DividendEvent> = { symbol, _ ->
            when (symbol) {
                "AAPL" -> aaplEvents
                "MSFT" -> msftEvents
                else -> emptyList()
            }
        }

        val next = IncomeSummaryMath.nextUpcomingDividend(
            positions = positions,
            dividendEventsFetcher = fetcher,
            asOfEpochSeconds = asOf,
            lookbackSeconds = 730L * 86_400L,
        )

        requireNotNull(next)
        assertEquals("AAPL", next.symbol, "on a tied ex-date, the FIRST holding encountered wins")
        assertEquals(tieDate, next.exDateEpochSeconds)
    }

    @Test
    fun nextUpcomingDividend_excludesCryptoPositions() = runTest {
        val asOf = date(2026, 6, 1)
        val positions = listOf(position("BTC-USD", "1", kind = AssetKind.Crypto))
        var fetchCount = 0
        val fetcher: suspend (String, Long) -> List<DividendEvent> = { _, _ ->
            fetchCount++
            listOf(
                DividendEvent("BTC-USD", date(2026, 4, 1), usd("100")),
                DividendEvent("BTC-USD", date(2026, 5, 1), usd("100")),
            )
        }

        val next = IncomeSummaryMath.nextUpcomingDividend(
            positions = positions,
            dividendEventsFetcher = fetcher,
            asOfEpochSeconds = asOf,
            lookbackSeconds = 730L * 86_400L,
        )

        assertNull(next, "crypto holdings must never contribute a dividend projection")
        assertEquals(0, fetchCount, "a crypto-only position list should never even fetch dividend events")
    }

    @Test
    fun nextUpcomingDividend_excludesStaleProjectionAtOrBeforeAsOf() = runTest {
        // A projection that lands exactly at (or before) `asOf` must be discarded — mirrors
        // DividendMath.nextProjected's own "no now-awareness, callers filter" contract.
        val lastExDate = date(2026, 5, 3)
        val asOf = lastExDate + 30L * 86_400L // exactly the projected next ex-date
        val positions = listOf(position("AAPL", "10"))
        val events = listOf(
            DividendEvent("AAPL", date(2026, 4, 3), usd("1")),
            DividendEvent("AAPL", lastExDate, usd("1")),
        )

        val next = IncomeSummaryMath.nextUpcomingDividend(
            positions = positions,
            dividendEventsFetcher = { _, _ -> events },
            asOfEpochSeconds = asOf,
            lookbackSeconds = 730L * 86_400L,
        )

        assertNull(next, "a projection landing at or before asOf must never surface as upcoming")
    }

    @Test
    fun nextUpcomingDividend_emptyPositions_returnsNull() = runTest {
        val next = IncomeSummaryMath.nextUpcomingDividend(
            positions = emptyList(),
            dividendEventsFetcher = { _, _ -> emptyList() },
            asOfEpochSeconds = date(2026, 6, 1),
            lookbackSeconds = 730L * 86_400L,
        )

        assertNull(next)
    }
}
