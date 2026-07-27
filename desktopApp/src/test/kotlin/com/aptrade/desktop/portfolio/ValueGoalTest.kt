package com.aptrade.desktop.portfolio

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M11.2 Task 13. The value goal on Performance, plus the since-inception metric tile. */
class ValueGoalTest {

    @Test
    fun settingAValueGoalPersistsItAndPublishesCardState() = runTest {
        val f = portfolioViewModelFixture(scope = this)
        f.viewModel.start(); runCurrent()
        assertNull(f.viewModel.state.value.valueGoal)
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        assertEquals(GoalKind.Value, f.goalStore.goals.single().kind)
        assertNotNull(f.viewModel.state.value.valueGoal)
    }

    @Test
    fun removingAValueGoalClearsBothStoreAndState() = runTest {
        val f = portfolioViewModelFixture(scope = this)
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        f.viewModel.removeValueGoal(); runCurrent()
        assertTrue(f.goalStore.goals.isEmpty())
        assertNull(f.viewModel.state.value.valueGoal)
    }

    /** BINDING (carry-notes §2.3): an EMPTY equity curve — which any offline or rate-limited
     *  session produces, not just an all-cash portfolio — must never render a fabricated $0
     *  against a real target. It is cash + cost basis of every position. */
    @Test
    fun anEmptyCurveFallsBackToCashPlusCostBasisNotZero() = runTest {
        // Fixture configured so the history fetch fails for every symbol.
        val f = portfolioViewModelFixture(scope = this, failHistory = true)
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal!!
        assertTrue(card.fraction > 0.0, "a portfolio holding real assets must not read 0%")
        assertEquals(f.expectedCostBasisFloorText, card.currentText)
    }

    /** The all-cash reading is preserved exactly by the same expression. */
    @Test
    fun anAllCashPortfolioReadsItsCashAsCurrentValue() = runTest {
        val f = portfolioViewModelFixture(scope = this, holdings = emptyList())
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        assertEquals("\$100,000.00", f.viewModel.state.value.valueGoal!!.currentText)
    }

    /** THE §4a.2 DIVERGENCE, at the view-model seam: a brand-new account holding a seasoned
     *  symbol has a full priced curve but no account history, so the card says "needs more
     *  history" rather than projecting off a 20-day-old account. */
    @Test
    fun aNewAccountHoldingASeasonedSymbolReportsInsufficientHistory() = runTest {
        val f = portfolioViewModelFixture(scope = this, accountAgeDays = 20)
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        assertEquals(GoalProjection.InsufficientHistory, f.viewModel.state.value.valueGoal!!.projection)
    }

    /** FULL STRING, never a suffix. This case used to assert only
     *  `metrics.sinceInception.endsWith("%")`, which "+0.01%" and "+1.00%" both satisfy — a shape
     *  that cannot fail for ANY scaling error, and the reason a 100x-too-small percentage reached
     *  UAT (M11.4). The fixture's arithmetic, all of it fixed and known:
     *    startingCash                 = $100,000
     *    latest curve value           = $100,000 cash + 10 AAPL @ the flat $100 series = $101,000
     *    sinceInceptionReturnFraction = 101,000 / 100,000 - 1 = 0.01  ->  1.00 points
     *  so the tile must read "+1.00%". Passing the FRACTION to `formatPercent` — which takes
     *  percentage POINTS — renders "+0.01%", and this one assertion is the whole difference. */
    @Test
    fun theSinceInceptionTileRendersThePercentFromTheReport() = runTest {
        val f = portfolioViewModelFixture(scope = this)
        f.viewModel.start(); runCurrent()
        val metrics = f.viewModel.state.value.metrics
        assertNotNull(metrics)
        assertEquals("+1.00%", metrics.sinceInception)
    }

    /** The scale defect's OTHER half on the same fixture: the four percent tiles of the
     *  Performance grid, which — because desktop's Home stats card reads this very same
     *  pre-formatted `MetricTexts.totalReturn` string (see `HomePane.StatsCard`) — pins the Home
     *  tile with them. One `x 100`, in `percentMetric`, feeding both surfaces.
     *
     *  The fixture's price series is flat ($100 for 250 days), so these four are exactly 0 and
     *  the case pins the ZERO rendering (unsigned "0.00%"). That is deliberately paired with
     *  [theSinceInceptionTileRendersThePercentFromTheReport] above, which carries the nonzero
     *  scale on the identical fixture: a scaling error is invisible at 0, and only 0 is stable
     *  against a flat curve, so neither case discriminates alone. */
    @Test
    fun theRiskMetricGridRendersEveryPercentTileAsAFullFormattedString() = runTest {
        val f = portfolioViewModelFixture(scope = this)
        f.viewModel.start(); runCurrent()
        val metrics = f.viewModel.state.value.metrics
        assertNotNull(metrics)
        assertEquals("0.00%", metrics.totalReturn)
        assertEquals("0.00%", metrics.annualizedReturn)
        assertEquals("0.00%", metrics.volatility)
        assertEquals("0.00%", metrics.maxDrawdown)
        // Sharpe/beta are dimensionless RATIOS, never percent-formatted — no "%" may appear.
        assertFalse(metrics.sharpe.contains("%"), "sharpe must never be percent-formatted")
        assertFalse(metrics.beta.contains("%"), "beta must never be percent-formatted")
    }

    @Test
    fun theSinceInceptionTileShowsAnEmDashWhenTheMetricIsUnavailable() = runTest {
        val f = portfolioViewModelFixture(scope = this, holdings = emptyList())
        f.viewModel.start(); runCurrent()
        assertEquals("—", f.viewModel.state.value.metrics?.sinceInception ?: "—")
    }
}
