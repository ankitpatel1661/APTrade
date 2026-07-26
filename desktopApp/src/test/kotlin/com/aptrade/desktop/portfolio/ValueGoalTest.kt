package com.aptrade.desktop.portfolio

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun theSinceInceptionTileRendersThePercentFromTheReport() = runTest {
        val f = portfolioViewModelFixture(scope = this)
        f.viewModel.start(); runCurrent()
        val metrics = f.viewModel.state.value.metrics
        assertNotNull(metrics)
        assertTrue(metrics.sinceInception.endsWith("%"))
    }

    @Test
    fun theSinceInceptionTileShowsAnEmDashWhenTheMetricIsUnavailable() = runTest {
        val f = portfolioViewModelFixture(scope = this, holdings = emptyList())
        f.viewModel.start(); runCurrent()
        assertEquals("—", f.viewModel.state.value.metrics?.sinceInception ?: "—")
    }
}
