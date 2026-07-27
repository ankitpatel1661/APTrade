package com.aptrade.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [formatPercent]'s display contract. Android had ZERO coverage of this helper before M11.4 —
 * no test file under `androidApp/src/test` referenced it — while desktop's twin was pinned by
 * `designkit/FormattingTest.kt`. That asymmetry is why the unit mismatch behind M11.4 was only
 * ever visible on one side of the codebase.
 *
 * THE CONTRACT, AND THE WHOLE POINT OF THIS FILE: [formatPercent] takes PERCENTAGE POINTS, not
 * a fraction. `formatPercent(4.84)` is "+4.84%", NOT "+484.00%". Callers holding a fraction —
 * every `PerformanceMetrics.…Fraction` field — owe the `x 100` themselves; `PortfolioViewModel`'s
 * `percentMetric` is the one place that happens. Callers holding an already-points value (the
 * `…PP` suffix: `PieSlice.targetWeightPP`, `BacktestReport.totalReturnPP`) pass it straight in,
 * and the Pie wizard depends on exactly that — so this contract must NOT be "fixed" by folding
 * a x 100 into the formatter.
 *
 * Every assertion here is a FULL string. A suffix check like `endsWith("%")` is satisfied by
 * both "0.05%" and "5.00%" and so cannot fail for any scaling error at all.
 *
 * Transcribed from desktop `designkit/FormattingTest.kt` — same helper, same expectations.
 */
class PercentFormattingTest {

    @Test
    fun formatsPositivePercentWithSign() = assertEquals("+4.84%", formatPercent(4.84))

    @Test
    fun formatsNegativePercent() = assertEquals("-0.13%", formatPercent(-0.13))

    @Test
    fun formatsZeroPercentWithoutSign() = assertEquals("0.00%", formatPercent(0.0))

    @Test
    fun formatsNullPercentAsDash() = assertEquals("—", formatPercent(null))

    /** The argument is POINTS. Fifty percent is `50.0`, and a caller that hands over the
     *  FRACTION `0.5` instead gets "+0.50%" — 100x too small, silently. That pair is the M11.4
     *  defect in two lines. */
    @Test
    fun theArgumentIsPercentagePointsNotAFraction() {
        assertEquals("+50.00%", formatPercent(50.0))
        assertEquals("+0.50%", formatPercent(0.5))
    }

    /** Two decimals always, with thousands grouping in the whole part — so a large return reads
     *  "+1,234.50%" rather than losing its separators.
     *
     *  Rounding is `kotlin.math.round(value * 100) / 100`, i.e. binary-double rounding, NOT
     *  decimal half-up. Exact `.xx5` inputs are therefore not a contract: `-12.345 * 100` is
     *  `-1234.4999999999998` in IEEE-754 and renders "-12.34". Deliberately unasserted rather
     *  than pinned — these are display percentages a couple of significant figures wide, money
     *  never travels this path (that is `Money`/BigDecimal), and pinning a value the arithmetic
     *  does not actually guarantee would make this test a liar. Cases below stay clear of the
     *  boundary. */
    @Test
    fun roundsToTwoDecimalsAndGroupsThousands() {
        assertEquals("+1,234.50%", formatPercent(1234.5))
        assertEquals("+4.84%", formatPercent(4.8449))
        assertEquals("-12.35%", formatPercent(-12.348))
    }
}
