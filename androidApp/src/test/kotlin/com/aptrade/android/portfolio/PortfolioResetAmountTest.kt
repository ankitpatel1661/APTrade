package com.aptrade.android.portfolio

import com.aptrade.shared.domain.AmountInput
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * M11.3 Task 7 — Android's reset opens the portfolio at the user's CONFIGURED starting balance
 * instead of the hardcoded `Portfolio.DEFAULT_STARTING_CASH` it used through Task 6 (carry-notes
 * §4b: Windows honoured the setting and Android silently used $100,000). Android twin of desktop
 * `PortfolioResetAmountTest`.
 *
 * TWO CONVENTIONS, both load-bearing (see `ValueGoalTest`'s header for the full rationale):
 *
 * 1. STOP BEFORE ASSERTING. The VM's poll loop is an infinite `while (isActive)` on the SAME
 *    `TestDispatcher` `runTest` drains, so a case that asserts before `stop()` HANGS the build
 *    on failure instead of going red. Every case here captures state, calls `stop()`, then
 *    asserts. (The older cases in `PortfolioViewModelTest` predate this rule — do not copy them.)
 * 2. DISCRIMINATING FIXTURES. Every amount below is deliberately NOT $100,000, so the old
 *    hardcode is distinguishable from an honoured argument; and the goal-survival case starts
 *    from a goal that is genuinely SET, so "survived" and "cleared" produce different output.
 *
 * ONE STATEMENT IN `reset` IS NOT COVERED HERE, AND CANNOT BE — recorded rather than faked.
 * `equityCurve = emptyList()` is state hygiene with no reachable observable effect: the curve
 * feeds only `refreshValueProjection`, and a just-reset portfolio has NO transactions, so
 * `Portfolio.inceptionEpochSeconds()` is always null and `GoalMath.valueProjection`'s account-age
 * gate short-circuits to `InsufficientHistory` before it ever reads the curve. Deleting the line
 * was tried and left all four cases below GREEN. It stays because it matches desktop's `reset`
 * statement-for-statement and because the next `loadPerformanceReport` should overwrite a cleared
 * field, not a stale one — but no assertion here should be read as proving it.
 */
class PortfolioResetAmountTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** THE §4b ASSERTION the carry-notes name explicitly: a configured, non-default balance must
     *  actually reach `resetPortfolio.execute`. $25,000 is chosen precisely because it is neither
     *  the fixture's seeded $100,000 cash nor `Portfolio.DEFAULT_STARTING_CASH` — restoring the
     *  old `resetPortfolio.execute(Portfolio.DEFAULT_STARTING_CASH)` hardcode produces $100,000
     *  here and fails both assertions. */
    @Test
    fun resetOpensThePortfolioAtTheSuppliedAmount() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture()
        f.viewModel.start(); runCurrent()

        f.viewModel.reset(Money.usd("25000")); runCurrent()
        val saved = f.portfolioStore.saved
        f.viewModel.stop()

        assertEquals(Money.usd("25000"), saved?.cash)
        assertEquals(Money.usd("25000"), saved?.startingCash)
    }

    /** USER RULING 2026-07-27 (M11.1 UAT F1): resetting starting capital is "start over with more
     *  money", not "abandon my plan". So the goal must SURVIVE — clearing it would hide from the
     *  screen a goal still sitting intact on disk, the Swift twin's UAT bug in mirror image.
     *
     *  The fixture seeds a REAL $120,000 goal before the reset, so a cleared goal (`null` card)
     *  and a surviving one are plainly distinguishable — a null-to-begin-with fixture would prove
     *  nothing here. And the card must RECOMPUTE, not merely persist: measured against the fresh
     *  $1,000,000 balance with the pre-reset equity curve discarded, the $120,000 target reads as
     *  `Reached` at "$1,000,000.00", never as a percentage of the old $101,000 curve. That pins
     *  all four of the ported statements at once — the `currentValue` recompute, the curve clear,
     *  the `loadGoals` re-read, and the `refreshValueProjection()` call. */
    @Test
    fun resetKeepsTheValueGoalAndRecomputesItAgainstTheFreshBalance() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture()
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("120000")); runCurrent()
        val beforeReset = f.viewModel.state.value.valueGoal

        f.viewModel.reset(Money.usd("1000000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal
        val stored = f.goalStore.goals
        f.viewModel.stop()

        // The fixture really did have a goal to lose — otherwise "it survived" is vacuous.
        assertNotNull(beforeReset)
        assertEquals("$101,000.00", beforeReset.currentText)

        assertEquals(Money.usd("120000"), stored.single().target)
        assertNotNull(card, "the goal must survive the reset, not vanish from the screen")
        assertEquals("$1,000,000.00", card.currentText)
        assertEquals(GoalProjection.Reached, card.projection)
    }

    /** The re-read is from the STORE, not from memory — another surface (or another session) may
     *  have changed the goal while this screen was up, and the reset is a reload point. Pins the
     *  `loadGoals.execute()` call specifically: an implementation that kept `valueGoal` in memory
     *  would publish the stale $120,000 target here instead of the $300,000 now on disk. */
    @Test
    fun resetRepublishesTheGoalAsItStandsInTheStoreNotAsItStoodInMemory() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture()
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("120000")); runCurrent()
        // Another surface re-targets the goal behind this VM's back.
        f.goalStore.goals = listOf(PortfolioGoal(GoalKind.Value, Money.usd("300000"), 1_700_000_000L))

        f.viewModel.reset(Money.usd("200000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal
        f.viewModel.stop()

        assertNotNull(card)
        assertEquals("$300,000.00", card.targetText)
        assertEquals("$200,000.00", card.currentText)
    }

    /** The dialog's Confirm button is gated on exactly this parse against exactly this shared
     *  range, so pin the seam the UI uses rather than the (waived, untested) composition. Restating
     *  the bounds in `PortfolioScreen.kt` instead of reusing
     *  [AmountInput.STARTING_BALANCE_RANGE] would leave this passing while the dialog drifted —
     *  which is why the dialog references the shared constant by name. */
    @Test
    fun theResetFieldRejectsOutOfRangeAmounts() {
        assertNull(AmountInput.parse("999", AmountInput.STARTING_BALANCE_RANGE))
        assertNull(AmountInput.parse("10000001", AmountInput.STARTING_BALANCE_RANGE))
        assertEquals(Money.usd("1000"), AmountInput.parse("1,000", AmountInput.STARTING_BALANCE_RANGE))
        assertEquals(Money.usd("10000000"), AmountInput.parse("10,000,000", AmountInput.STARTING_BALANCE_RANGE))
    }
}
