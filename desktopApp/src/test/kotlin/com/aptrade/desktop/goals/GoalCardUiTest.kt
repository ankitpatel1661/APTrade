package com.aptrade.desktop.goals

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M11.2 Task 10. The card composition itself ships under the standing UI waiver; the VM→UI
 * mapping does not — it is where a wrong money string or a fabricated progress bar would come
 * from, and both goal surfaces share it.
 */
class GoalCardUiTest {

    @Test
    fun anUnsetGoalMapsToNullSoTheCardRendersItsSetAGoalAffordance() {
        assertNull(goalCardUi(goal = null, current = Money.usd("1000"), projection = null))
    }

    @Test
    fun aSetGoalCarriesFormattedMoneyAndAFraction() {
        val ui = goalCardUi(
            goal = PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1L),
            current = Money.usd("125000"),
            projection = GoalProjection.Years(7.3),
        )!!
        assertEquals("\$125,000.00", ui.currentText)
        assertEquals("\$500,000.00", ui.targetText)
        assertEquals(0.25, ui.fraction, 1e-9)
        assertEquals(GoalProjection.Years(7.3), ui.projection)
    }

    /** The edit field must round-trip the RAW amount, never the grouped display string — feeding
     *  "$500,000.00" back into the parser would fail on the "$". */
    @Test
    fun targetAmountTextIsRawForRefillingTheEditField() {
        val ui = goalCardUi(
            goal = PortfolioGoal(GoalKind.Income, Money.usd("6000"), 1L),
            current = Money.usd("0"),
            projection = null,
        )!!
        assertEquals("6000", ui.targetAmountText)
    }

    /** Carry-notes §3.5: a null projection is "not computed yet", which reads as the same honest
     *  "needs more history" sentence — never silence, never a fabricated ETA. */
    @Test
    fun aNullProjectionMapsToInsufficientHistory() {
        val ui = goalCardUi(
            goal = PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1L),
            current = Money.usd("125000"),
            projection = null,
        )!!
        assertEquals(GoalProjection.InsufficientHistory, ui.projection)
    }

    @Test
    fun fractionIsClampedAtZeroButMayExceedOne() {
        val over = goalCardUi(
            goal = PortfolioGoal(GoalKind.Value, Money.usd("100000"), 1L),
            current = Money.usd("150000"),
            projection = GoalProjection.Reached,
        )!!
        assertEquals(1.5, over.fraction, 1e-9)
    }
}
