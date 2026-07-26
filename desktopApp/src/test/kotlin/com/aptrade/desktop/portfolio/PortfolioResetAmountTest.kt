package com.aptrade.desktop.portfolio

import com.aptrade.shared.domain.AmountInput
import com.aptrade.shared.domain.Money
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // Depends on Task 13's `setValueGoal`/`state.valueGoal`, neither of which exists on the
    // desktop VM/state yet (M11.2 tasks are being executed in order; Task 9 is this one).
    // Per the Task 9 brief: "write that case now and leave it failing until Task 13 ... Do not
    // delete it." A literal failing test isn't possible here because the referenced symbols
    // don't exist at all yet — that's a compile error, not a runtime failure, and would sink
    // the whole module's test compilation. Kept commented out, verbatim, for Task 13 to
    // uncomment once `setValueGoal`/`valueGoal` land.
    //
    // @Test
    // fun resetClearsTheValueGoalSoAStaleTargetCannotSurvive() = runTest {
    //     val fixture = portfolioViewModelFixture(scope = this)
    //     fixture.viewModel.start()
    //     runCurrent()
    //     fixture.viewModel.setValueGoal(Money.usd("500000"))
    //     runCurrent()
    //     fixture.viewModel.reset(Money.usd("25000"))
    //     runCurrent()
    //     assertNull(fixture.viewModel.state.value.valueGoal)
    // }

    /** The dialog's Confirm button is gated on exactly this parse, so pin the seam the UI uses
     *  rather than the (untested, waived) composition itself. */
    @Test
    fun theResetFieldRejectsOutOfRangeAmounts() {
        assertNull(AmountInput.parse("999", AmountInput.STARTING_BALANCE_RANGE))
        assertNull(AmountInput.parse("10000001", AmountInput.STARTING_BALANCE_RANGE))
        assertEquals(Money.usd("1000"), AmountInput.parse("1,000", AmountInput.STARTING_BALANCE_RANGE))
    }
}
