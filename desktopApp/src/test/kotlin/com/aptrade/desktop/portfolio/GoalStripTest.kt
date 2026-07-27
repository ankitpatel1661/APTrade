package com.aptrade.desktop.portfolio

import com.aptrade.desktop.goals.goalCardUi
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * M11.3 Task 3 — the portfolio header's goal strip (Windows desktop twin of
 * `PortfolioSummaryHeaderGoalStripTests.swift`, M11.3 Task 2).
 *
 * [goalStrip] is a pure function of a [com.aptrade.desktop.goals.GoalCardUi]? — GC2 binding: the
 * fraction it reads is the ONE `GoalMath.progress` result [goalCardUi] already computed, never a
 * second raw current/target division. Taking `GoalCardUi` instead of `(PortfolioGoal?, Money)`
 * (Swift's twin signature) makes that structurally true here: there is no raw current/target
 * pair in this function's scope to re-derive from.
 */
class GoalStripTest {

    private fun cardUi(current: String, target: String) =
        goalCardUi(
            goal = PortfolioGoal(GoalKind.Value, Money.usd(target), 1L),
            current = Money.usd(current),
            projection = null,
        )

    /** The design decision: **hidden entirely when no goal is set** — no empty bar, no "Set a
     *  goal" prompt, no click target. ONE fixture drives both readings (GC3's trap for this
     *  milestone): a "no strip when no goal" assertion is worthless if the fixture never managed
     *  to produce a strip in the first place, so the visible case is asserted first, from the
     *  same [cardUi] mapping the hidden case starts from before its goal is dropped.
     *
     *  Rejects: `goalStrip` returning a non-null "empty" strip (e.g.
     *  `GoalStrip(barFraction = 0.0, percent = 0, targetText = "—")`) when handed `null`,
     *  instead of `null` itself.
     */
    @Test
    fun sameFixture_stripAppearsWithAGoalAndVanishesWithoutOne() {
        val ui = cardUi(current = "42000", target = "100000")
        assertNotNull(goalStrip(ui), "a set value goal must produce a strip")

        // ONLY the goal changes — same current value, same target, just dropped.
        assertNull(goalStrip(null), "no goal, no strip: not an empty bar, not a placeholder")
    }

    /** The payload, and the rounding half of GC2: at $6,667 of $10,000 the fraction is 0.6667 —
     *  rounding gives 67, truncating gives 66. `GoalCard.kt`'s progress row renders
     *  `(ui.fraction * 100).roundToInt()`, so a strip reading 66% beside a card reading 67%
     *  would be a defect. Whole-struct equality (not field-by-field) so a fourth field — e.g. a
     *  current-value text, which this design deliberately omits — could not be added silently.
     *
     *  Rejects: `(fraction * 100).toInt()` (truncation, reads 66).
     */
    @Test
    fun goalSet_stripCarriesTargetAndRoundedPercent() {
        val ui = cardUi(current = "6667", target = "10000")!!
        val strip = goalStrip(ui)
        assertEquals(GoalStrip(barFraction = ui.fraction, percent = 67, targetText = ui.targetText), strip)
    }

    /** The case that found this milestone (Swift Task 2's motivating example): a $1,000,000
     *  portfolio against a $120,000 goal. The percentage must read 833%; the bar must still fill
     *  exactly to the end, never past it.
     *
     *  Rejects: `percent = (fraction.coerceAtMost(1.0) * 100).roundToInt()` — clamping the
     *  PERCENT as well as the bar, which would read a flat, wrong 100%. Also rejects the
     *  mirror-image error, an unclamped `barFraction = fraction` (8.33), which would overflow
     *  the bar were [com.aptrade.desktop.goals.ProgressBar] not itself defensive.
     */
    @Test
    fun goalExceeded_percentPasses100_whileTheBarClampsAtFull() {
        val ui = cardUi(current = "1000000", target = "120000")!!
        val strip = goalStrip(ui)
        assertEquals(833, strip?.percent, "833%, not a clamped 100%")
        assertEquals(1.0, strip?.barFraction, "the bar fills, but never overflows")
    }
}
