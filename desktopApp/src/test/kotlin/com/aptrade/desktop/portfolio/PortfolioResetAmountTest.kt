package com.aptrade.desktop.portfolio

import com.aptrade.shared.domain.AmountInput
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * M11.2 Task 9. The reset flow now opens the portfolio at a user-chosen, range-validated amount
 * instead of a hardcoded $100,000.
 */
class PortfolioResetAmountTest {

    @Test
    fun resetOpensThePortfolioAtTheSuppliedAmount() = runTest {
        // Build the VM with this package's existing test fixture (see PortfolioViewModelTest).
        val fixture = portfolioViewModelFixture(scope = this)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.reset(Money.usd("25000"))
        runCurrent()
        assertEquals(Money.usd("25000"), fixture.portfolioStore.portfolio?.cash)
        assertEquals(Money.usd("25000"), fixture.portfolioStore.portfolio?.startingCash)
    }

    /** USER RULING 2026-07-27 (M11.1 UAT F1) — the INVERSION of the former
     *  `resetClearsTheValueGoalSoAStaleTargetCannotSurvive`, which asserted `assertNull` here.
     *  Resetting starting capital is "start over with more money", not "abandon my plan".
     *
     *  Rejects — proven RED — `PortfolioViewModel.reset()` setting `valueGoal = null` / publishing
     *  `valueGoal = null`: the goal would then sit intact on disk while vanishing from the screen,
     *  the Swift UAT bug in mirror image. The card must also RECOMPUTE: measured against the fresh
     *  $1,000,000 balance with the pre-reset equity curve discarded, a $120,000 target reads as
     *  reached, never as a percentage of the old curve.
     *
     *  The `goalStore.goals` assertion is a WEAKER guard and is not claimed as proof: it catches a
     *  re-armed `ResetPortfolio` goal-clear only if whoever re-arms it also wires this package's
     *  `goalStore` into the `ResetPortfolio(...)` construction in `PortfolioViewModelTest.vm()`
     *  (plausible — the fixture has one in scope — but not guaranteed). This is nonetheless the
     *  closest thing in the branch to behavioural coverage of goal survival, because it is the one
     *  reset path that runs with a real `GoalStore` in the graph. See
     *  `shared` `ResetPortfolioTest.resetLeavesEveryGoalIntact` for why the use-case-level
     *  guarantee is structural rather than testable. */
    @Test
    fun resetKeepsTheValueGoalAndRecomputesItAgainstTheFreshBalance() = runTest {
        val fixture = portfolioViewModelFixture(scope = this)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.setValueGoal(Money.usd("120000"))
        runCurrent()
        fixture.viewModel.reset(Money.usd("1000000"))
        runCurrent()
        assertEquals(Money.usd("120000"), fixture.goalStore.goals.single().target)
        val card = assertNotNull(fixture.viewModel.state.value.valueGoal)
        assertEquals("$1,000,000.00", card.currentText)
        assertEquals(GoalProjection.Reached, card.projection)
    }

    /** The dialog's Confirm button is gated on exactly this parse, so pin the seam the UI uses
     *  rather than the (untested, waived) composition itself. */
    @Test
    fun theResetFieldRejectsOutOfRangeAmounts() {
        assertNull(AmountInput.parse("999", AmountInput.STARTING_BALANCE_RANGE))
        assertNull(AmountInput.parse("10000001", AmountInput.STARTING_BALANCE_RANGE))
        assertEquals(Money.usd("1000"), AmountInput.parse("1,000", AmountInput.STARTING_BALANCE_RANGE))
    }
}
