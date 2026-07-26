package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M11.2 Task 6. Semantics transcribed from `Sources/APTradeDomain/GoalMath.swift` AS-BUILT, with
 * ONE recorded behavioural divergence pinned below: the minimum-history floor measures ACCOUNT
 * AGE (first transaction -> now), not the price window's span (M11.2 kickoff decision 4a.2).
 */
class GoalMathTest {
    private val day = 86_400L
    private val now = 1_782_000_000L
    private fun usd(s: String): Money = Money.usd(s)

    private fun point(epochSeconds: Long, value: String) =
        PortfolioPerformancePoint(epochSeconds, usd(value), usd("0"))

    /** A curve spanning [days] days that grows from [from] to [to]. */
    private fun curve(days: Long, from: String, to: String) =
        listOf(point(now - days * day, from), point(now, to))

    // MARK: progress

    @Test
    fun progressIsTheFractionOfTarget() {
        assertEquals(0.5, GoalMath.progress(usd("50000"), usd("100000")), 1e-9)
    }

    @Test
    fun progressMayExceedOne() {
        assertTrue(GoalMath.progress(usd("150000"), usd("100000")) > 1.0)
    }

    @Test
    fun progressIsZeroForANonPositiveTargetAndNeverNegative() {
        assertEquals(0.0, GoalMath.progress(usd("50000"), usd("0")), 1e-9)
        assertEquals(0.0, GoalMath.progress(usd("-50000"), usd("100000")), 1e-9)
    }

    // MARK: account-age floor (RECORDED DIVERGENCE)

    @Test
    fun accountAgeIsNullWithNoTransactions() {
        assertNull(GoalMath.accountAgeDays(null, now))
    }

    @Test
    fun accountAgeIsTheSpanFromTheFirstTransaction() {
        assertEquals(200.0, GoalMath.accountAgeDays(now - 200 * day, now)!!, 1e-9)
    }

    @Test
    fun minimumHistoryFloorIsOneHundredEightyDays() {
        assertEquals(180, GoalMath.MINIMUM_HISTORY_DAYS)
    }

    /** THE DIVERGENCE, pinned: a brand-new account holding a SEASONED symbol has a full 365-day
     *  price curve, which is exactly the situation Swift's price-span floor waves through. Kotlin
     *  measures the account, so it honestly reports insufficient history. */
    @Test
    fun aNewAccountHoldingASeasonedSymbolStillReportsInsufficientHistory() {
        val seasonedCurve = curve(365, "100000", "130000")
        val accountAge = GoalMath.accountAgeDays(now - 20 * day, now)
        assertNull(GoalMath.annualGrowthRate(seasonedCurve, accountAge))
        assertEquals(
            GoalProjection.InsufficientHistory,
            GoalMath.valueProjection(usd("130000"), usd("500000"), seasonedCurve, accountAge),
        )
    }

    /** THE CONVERSE OF THE DIVERGENCE, pinned (carry-notes §4a.2 wording-correction addendum):
     *  measuring account age is an ADDITION to the curve-span floor, not a replacement for it. An
     *  account well past the 180-day age floor can still be handed a curve spanning only 30 days —
     *  `performanceSeries`'s all-priced gate truncates the WHOLE curve to the newest-history
     *  symbol's first candle, so an old account that just bought a recently-listed symbol
     *  collapses its own curve span. Without this span gate, a 5% move over 30 days annualizes to
     *  +81.1%/yr (under the +100% clamp) and would render a confident multi-year ETA off ten data
     *  points — the exact fabrication this type promises never to produce. */
    @Test
    fun anOldAccountWithAThinCurveStillReportsInsufficientHistory() {
        val thinCurve = curve(30, "100000", "105000")
        assertNull(GoalMath.annualGrowthRate(thinCurve, 400.0))
        assertEquals(
            GoalProjection.InsufficientHistory,
            GoalMath.valueProjection(usd("105000"), usd("500000"), thinCurve, 400.0),
        )
    }

    @Test
    fun anAccountOlderThanTheFloorMeasuresGrowthOffTheCurve() {
        val rate = GoalMath.annualGrowthRate(curve(365, "100000", "110000"), 400.0)!!
        assertEquals(0.10, rate.doubleValue(false), 1e-3)
    }

    @Test
    fun growthRateIsNullForADegenerateCurve() {
        assertNull(GoalMath.annualGrowthRate(emptyList(), 400.0))
        assertNull(GoalMath.annualGrowthRate(listOf(point(now, "100000")), 400.0))
        assertNull(GoalMath.annualGrowthRate(curve(365, "0", "110000"), 400.0))
    }

    /** Carry-notes §3.6: the portfolio clamp is −0.50 … 1.00 and is INDEPENDENT of DividendMath's
     *  −0.20 … 0.25 per-symbol clamp. Both boundaries pinned by exact equality. */
    @Test
    fun portfolioClampBoundsAreExactAndDifferFromThePerSymbolPair() {
        assertEquals(BigDecimal.parseString("-0.5"), GoalMath.MIN_ANNUAL_GROWTH)
        assertEquals(BigDecimal.parseString("1.0"), GoalMath.MAX_ANNUAL_GROWTH)
        assertEquals(GoalMath.MAX_ANNUAL_GROWTH, GoalMath.annualGrowthRate(curve(365, "10000", "100000"), 400.0))
        assertEquals(GoalMath.MIN_ANNUAL_GROWTH, GoalMath.annualGrowthRate(curve(365, "100000", "1000"), 400.0))
    }

    // MARK: valueProjection

    @Test
    fun aMetTargetReportsReached() {
        assertEquals(
            GoalProjection.Reached,
            GoalMath.valueProjection(usd("120000"), usd("100000"), curve(365, "100000", "120000"), 400.0),
        )
    }

    @Test
    fun aNonPositiveTargetReportsNotOnTrackNotReached() {
        assertEquals(
            GoalProjection.NotOnTrack,
            GoalMath.valueProjection(usd("120000"), usd("0"), curve(365, "100000", "120000"), 400.0),
        )
    }

    @Test
    fun aReachableTargetReportsAConcreteEta() {
        val projection = GoalMath.valueProjection(
            usd("110000"), usd("220000"), curve(365, "100000", "110000"), 400.0,
        )
        assertTrue(projection is GoalProjection.Years)
        // ln(2)/ln(1.10) ~= 7.27 years.
        assertEquals(7.27, (projection as GoalProjection.Years).value, 0.1)
    }

    @Test
    fun aFlatCurveReportsNotOnTrackRatherThanAnInfiniteEta() {
        assertEquals(
            GoalProjection.NotOnTrack,
            GoalMath.valueProjection(usd("100000"), usd("200000"), curve(365, "100000", "100000"), 400.0),
        )
    }

    @Test
    fun aTargetFurtherOutThanTheHorizonReportsBeyondHorizon() {
        assertEquals(
            GoalProjection.BeyondHorizon,
            GoalMath.valueProjection(
                usd("100100"), usd("100000000"), curve(365, "100000", "100100"), 400.0,
            ),
        )
    }

    /** A DECLINING curve must never yield a negative or NaN ETA — it reports not-on-track through
     *  the FULL `valueProjection` path (previously only the clamped rate itself was asserted; the
     *  projection outcome is pinned here directly). */
    @Test
    fun aDecliningCurveReportsNotOnTrackThroughValueProjection() {
        assertEquals(
            GoalProjection.NotOnTrack,
            GoalMath.valueProjection(usd("1000"), usd("5000"), curve(365, "100000", "1000"), 400.0),
        )
    }

    // MARK: incomeProjection

    private fun forecast(vararg incomes: String): List<ForecastYear> =
        incomes.mapIndexed { i, v -> ForecastYear(i + 1, usd(v)) }

    @Test
    fun incomeProjectionReportsTheCrossingYear() {
        val projection = GoalMath.incomeProjection(usd("1000"), usd("3000"), forecast("1000", "2000", "3200"))
        assertEquals(GoalProjection.Years(3.0), projection)
    }

    @Test
    fun incomeProjectionReportsReachedWhenCurrentAlreadyMeetsTarget() {
        assertEquals(
            GoalProjection.Reached,
            GoalMath.incomeProjection(usd("5000"), usd("3000"), forecast("5000", "5200")),
        )
    }

    /** Carry-notes §2.4, BINDING: a brand-new user with NO holdings has an all-zero forecast. That
     *  is an ABSENCE of data, not a failing rate — and the value goal in the identical situation
     *  says "needs more history". The two cards are deliberately symmetric and always visible, so
     *  the copy must agree. Checked BEFORE the not-on-track fallthrough. */
    @Test
    fun anAllZeroForecastReportsInsufficientHistoryNotNotOnTrack() {
        assertEquals(
            GoalProjection.InsufficientHistory,
            GoalMath.incomeProjection(usd("0"), usd("6000"), forecast("0", "0", "0")),
        )
    }

    @Test
    fun anEmptyForecastReportsInsufficientHistory() {
        assertEquals(
            GoalProjection.InsufficientHistory,
            GoalMath.incomeProjection(usd("0"), usd("6000"), emptyList()),
        )
    }

    @Test
    fun aGrowingButUncrossedForecastReportsBeyondHorizon() {
        assertEquals(
            GoalProjection.BeyondHorizon,
            GoalMath.incomeProjection(usd("1000"), usd("999999"), forecast("1000", "1100", "1200")),
        )
    }

    @Test
    fun aShrinkingForecastReportsNotOnTrack() {
        assertEquals(
            GoalProjection.NotOnTrack,
            GoalMath.incomeProjection(usd("1000"), usd("5000"), forecast("1000", "900", "800")),
        )
    }

    /** Carry-notes §3.5: `beyondHorizon` renders by INTERPOLATING the horizon constant. This test
     *  derives its expectation from the same constant so the two cannot drift. */
    @Test
    fun horizonConstantIsThirtyYears() {
        assertEquals(30.0, GoalMath.HORIZON_YEARS, 1e-9)
        val justOver = GoalMath.yearsToTarget(
            current = BigDecimal.parseString("1"),
            target = BigDecimal.parseString("2"),
            annualRate = BigDecimal.fromDouble(
                2.0.pow(1.0 / (GoalMath.HORIZON_YEARS + 1.0)) - 1.0,
            ),
        )
        assertEquals(GoalProjection.BeyondHorizon, justOver)
    }

    /** The boundary is INCLUSIVE: exactly 30.0 years reports a concrete ETA, not
     *  [GoalProjection.BeyondHorizon]. Only "just over" (31 yrs) was previously pinned; this locks
     *  the other side of the same `years > HORIZON_YEARS` comparison. */
    @Test
    fun exactlyThirtyYearsIsInsideTheHorizon() {
        val exactlyAtHorizon = GoalMath.yearsToTarget(
            current = BigDecimal.parseString("1"),
            target = BigDecimal.parseString("2"),
            annualRate = BigDecimal.fromDouble(2.0.pow(1.0 / GoalMath.HORIZON_YEARS) - 1.0),
        )
        assertTrue(exactlyAtHorizon is GoalProjection.Years)
        assertEquals(30.0, (exactlyAtHorizon as GoalProjection.Years).value, 1e-6)
    }

    // MARK: end-to-end account-age wiring

    /** The KDoc on [GoalMath.MINIMUM_HISTORY_DAYS] claims [Portfolio.inceptionEpochSeconds] is the
     *  one shared derivation feeding this floor. Pin that relationship end-to-end, through a real
     *  `Portfolio`, rather than only through the hand-built `Long?` the other tests pass directly. */
    @Test
    fun accountAgeDaysWiresThroughARealPortfoliosInception() {
        val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
        val portfolio = Portfolio.starting(usd("50000"))
            .buying(aapl, BigDecimal.parseString("10"), usd("100"), now - 200 * day, "txn-1")
        assertEquals(200.0, GoalMath.accountAgeDays(portfolio.inceptionEpochSeconds(), now)!!, 1e-9)

        val untraded = Portfolio.starting(usd("50000"))
        assertNull(GoalMath.accountAgeDays(untraded.inceptionEpochSeconds(), now))
    }

    // MARK: current-value floor

    /** Carry-notes §2.3: never a fabricated $0. Collapses to exactly `cash` when there are no
     *  positions, so the all-cash reading is preserved exactly. */
    @Test
    fun currentValueFloorIsCashPlusCostBasis() {
        val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
        val portfolio = Portfolio.starting(usd("50000"))
            .buying(aapl, BigDecimal.parseString("100"), usd("100"), 1_000L, "txn-1")
        assertEquals(usd("50000"), portfolio.goalCurrentValueFloor())
        assertEquals(usd("50000"), Portfolio.starting(usd("50000")).goalCurrentValueFloor())
    }
}
