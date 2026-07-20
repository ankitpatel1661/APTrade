package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transcribed from `Tests/APTradeDomainTests/PieBacktestTests.swift`, byte-value-equal (5
 * fixtures). Fixture dates (2026-01-05 Mon, 2026-02-05 Thu, 2026-03-05 Thu) are plain trading
 * weekdays with no US market holiday nearby, so none of them roll -- the monthly cadence steps
 * land exactly on anchor, anchor+1mo, anchor+2mo with no calendar-rolling side effects to
 * account for by hand.
 */
class PieBacktestTest {
    private val calendar = MarketCalendar()

    private fun money(s: String, currency: String = "USD"): Money = Money(BigDecimal.parseString(s), currency)
    private fun pp(s: String): BigDecimal = BigDecimal.parseString(s)

    private val sixtyForty = listOf(
        PieSlice(symbol = "A", assetKind = AssetKind.Stock, targetWeightPP = pp("60")),
        PieSlice(symbol = "B", assetKind = AssetKind.Stock, targetWeightPP = pp("40")),
    )

    private val allWeightA = listOf(
        PieSlice(symbol = "A", assetKind = AssetKind.Stock, targetWeightPP = pp("100")),
    )

    // MARK: - Flat fixture: DCA return is exactly zero when prices never move

    @Test
    fun testFlatFixture_threeMonthlyContributions_zeroReturn() {
        // A=$10, B=$20 on every due day. 60/40 split means every $100 contribution buys
        // A:$60 (6 shares) and B:$40 (2 shares) each month (distribute() keeps the
        // 60/40 ratio exactly since prices never move the actual weights off target).
        val dailyCloses: Map<String, Map<String, Money>> = mapOf(
            "A" to mapOf("2026-01-05" to money("10"), "2026-02-05" to money("10"), "2026-03-05" to money("10")),
            "B" to mapOf("2026-01-05" to money("20"), "2026-02-05" to money("20"), "2026-03-05" to money("20")),
        )

        val report = PieBacktest.dcaBacktest(
            slices = sixtyForty,
            amount = money("100"),
            cadence = PieCadence.Monthly,
            startDay = "2026-01-05",
            endDay = "2026-03-05",
            dailyCloses = dailyCloses,
            calendar = calendar,
        )

        assertNotNull(report, "expected a report")

        assertEquals(money("300"), report.totalInvested)
        assertEquals(money("300"), report.finalValue)
        assertEquals(pp("0"), report.totalReturnPP)

        // One point per executed contribution (3) + one final valuation point = 4.
        assertEquals(4, report.points.size)
        assertEquals(BacktestPoint(day = "2026-01-05", invested = money("100"), value = money("100")), report.points[0])
        assertEquals(BacktestPoint(day = "2026-02-05", invested = money("200"), value = money("200")), report.points[1])
        assertEquals(BacktestPoint(day = "2026-03-05", invested = money("300"), value = money("300")), report.points[2])
        assertEquals(BacktestPoint(day = "2026-03-05", invested = money("300"), value = money("300")), report.points[3])
    }

    // MARK: - Rising fixture: hand-computed final value; lump-sum beats DCA

    @Test
    fun testRisingFixture_handComputedFinalValue_lumpSumBeatsDCA() {
        // Single 100%-weight slice, price doubling each month: $10 -> $20 -> $40.
        //
        // Day 1 (2026-01-05, close $10): currentValues = {} (empty pie).
        //   distribute($100, {}, [A:100%]): totalAfter = 0+100 = 100, ideal = 100,
        //   deficit = 100-0 = 100 = contribution -> sufficient case, alloc = $100.
        //   shares bought = 100/10 = 10.  Running shares = 10.
        //   value = 10 * $10 = $100.  invested = $100.
        //
        // Day 2 (2026-02-05, close $20): currentValues = {A: 10*$20 = $200}.
        //   distribute($100, {A:$200}, [A:100%]): totalAfter = 200+100 = 300,
        //   ideal = 300, deficit = 300-200 = 100 = contribution -> alloc = $100.
        //   shares bought = 100/20 = 5.  Running shares = 15.
        //   value = 15 * $20 = $300.  invested = $200.
        //
        // Day 3 (2026-03-05, close $40): currentValues = {A: 15*$40 = $600}.
        //   distribute($100, {A:$600}, [A:100%]): totalAfter = 600+100 = 700,
        //   ideal = 700, deficit = 700-600 = 100 = contribution -> alloc = $100.
        //   shares bought = 100/40 = 2.5.  Running shares = 17.5.
        //   value = 17.5 * $40 = $700.  invested = $300.
        //
        // Final point (endDay = 2026-03-05, same close $40): value = 17.5*40 = $700.
        //
        // totalReturn = (700/300 - 1) * 100 = (7/3 - 1) * 100 = (4/3) * 100
        //             = 133.3333...% -> rounded 2dp = 133.33%.
        //
        // Lump sum: distribute($300, {}, [A:100%]) on day 1 (close $10) -> alloc $300,
        //   shares = 300/10 = 30.  Valued at the final close ($40): 30*40 = $1200.
        //   $1200 > $700, so lump sum beats DCA on a monotonically rising price.
        val dailyCloses: Map<String, Map<String, Money>> = mapOf(
            "A" to mapOf(
                "2026-01-05" to money("10"),
                "2026-02-05" to money("20"),
                "2026-03-05" to money("40"),
            ),
        )

        val report = PieBacktest.dcaBacktest(
            slices = allWeightA,
            amount = money("100"),
            cadence = PieCadence.Monthly,
            startDay = "2026-01-05",
            endDay = "2026-03-05",
            dailyCloses = dailyCloses,
            calendar = calendar,
        )

        assertNotNull(report, "expected a report")

        assertEquals(money("300"), report.totalInvested)
        assertEquals(money("700"), report.finalValue)
        assertEquals(pp("133.33"), report.totalReturnPP)
        assertEquals(money("1200"), report.lumpSumFinalValue)
        assertTrue(report.lumpSumFinalValue.amount > report.finalValue.amount)
    }

    // MARK: - Missing close on a due day: that contribution is skipped entirely

    @Test
    fun testMissingCloseOnDueDay_contributionSkippedEntirely_noPartialBuy() {
        // Same 60/40 flat-price setup as the zero-return test, but the 2026-02-05 close
        // for B is absent, so the whole February contribution is skipped (no A-only
        // partial buy). Only Jan 5 and Mar 5 execute: invested = $100 + $100 = $200.
        //
        // Day 1 (Jan 5): A gets $60 (6 shares @ $10), B gets $40 (2 shares @ $20).
        // Day 2 (Feb 5): SKIPPED (B has no close this day).
        // Day 3 (Mar 5): currentValues A=6*10=$60, B=2*20=$40, total=$100.
        //   totalAfter=200, ideal A=120,B=80, deficit A=60,B=40=contrib -> alloc A=$60,B=$40.
        //   shares bought: A += 60/10 = 6 (total 12), B += 40/20 = 2 (total 4).
        //   value = 12*$10 + 4*$20 = $120 + $80 = $200 (equal to invested, since prices
        //   never moved for either symbol on any executed day).
        val dailyCloses: Map<String, Map<String, Money>> = mapOf(
            "A" to mapOf("2026-01-05" to money("10"), "2026-02-05" to money("10"), "2026-03-05" to money("10")),
            "B" to mapOf("2026-01-05" to money("20"), "2026-03-05" to money("20")),
            // B has no 2026-02-05 entry -> that due day is skipped entirely.
        )

        val report = PieBacktest.dcaBacktest(
            slices = sixtyForty,
            amount = money("100"),
            cadence = PieCadence.Monthly,
            startDay = "2026-01-05",
            endDay = "2026-03-05",
            dailyCloses = dailyCloses,
            calendar = calendar,
        )

        assertNotNull(report, "expected a report")

        assertEquals(money("200"), report.totalInvested)
        assertEquals(money("200"), report.finalValue)
        assertEquals(3, report.points.size) // 2 executed contributions + final point
    }

    // MARK: - Every due day missing closes: insufficient history -> nil

    @Test
    fun testAllDueDaysMissingCloses_returnsNil() {
        val report = PieBacktest.dcaBacktest(
            slices = sixtyForty,
            amount = money("100"),
            cadence = PieCadence.Monthly,
            startDay = "2026-01-05",
            endDay = "2026-03-05",
            dailyCloses = emptyMap(), // no price data at all
            calendar = calendar,
        )

        assertNull(report)
    }

    // MARK: - startDay itself is tradeable: it must count as the first due day

    @Test
    fun testStartDayItselfTradeable_countsAsFirstDueDay() {
        // dueDays() alone never treats its own anchor as a candidate (stepping starts
        // at anchor + 1*cadence). The backtest must still execute on startDay itself
        // when it's tradeable, via nextDueDay's step-0 eligibility -- otherwise a
        // single-day window like this one would wrongly report "insufficient history".
        val dailyCloses: Map<String, Map<String, Money>> = mapOf(
            "A" to mapOf("2026-01-05" to money("10")),
        )

        val report = PieBacktest.dcaBacktest(
            slices = allWeightA,
            amount = money("100"),
            cadence = PieCadence.Monthly,
            startDay = "2026-01-05",
            endDay = "2026-01-05",
            dailyCloses = dailyCloses,
            calendar = calendar,
        )

        assertNotNull(report, "expected a report -- startDay itself should be the first due day")

        assertEquals(money("100"), report.totalInvested)
        assertEquals(money("100"), report.finalValue)
        // Only one contribution ever happens, so lump sum buys identically -> equal.
        assertEquals(money("100"), report.lumpSumFinalValue)
        assertEquals(2, report.points.size) // 1 executed contribution + final point
    }
}
