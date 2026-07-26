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

    // M11.2 Task 13: `setValueGoal`/`state.valueGoal` now exist — uncommented verbatim per the
    // Task 9 brief's instruction to leave this failing (as a compile error) until Task 13 lands.
    @Test
    fun resetClearsTheValueGoalSoAStaleTargetCannotSurvive() = runTest {
        val fixture = portfolioViewModelFixture(scope = this)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.setValueGoal(Money.usd("500000"))
        runCurrent()
        fixture.viewModel.reset(Money.usd("25000"))
        runCurrent()
        assertNull(fixture.viewModel.state.value.valueGoal)
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
