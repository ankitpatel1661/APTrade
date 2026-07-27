package com.aptrade.android.goals

import com.aptrade.android.ui.money
import com.aptrade.shared.domain.GoalMath
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal

/**
 * One goal's worth of card state, pre-formatted per this codebase's VM→UI contract.
 *
 * PORT, NOT A HOIST — and deliberately so (M11.3 Task 4 decision). The desktop twin is
 * `desktopApp/.../goals/GoalCard.kt`; the two must stay in step, so any change to the mapping
 * rules below belongs in BOTH files.
 *
 * The alternative considered was hoisting this type and [goalCardUi] into `:shared` so both apps
 * consume one copy (the precedent being `com.aptrade.shared.l10n.L10n`, hoisted out of desktop for
 * exactly that reason). It was rejected because the money formatting cannot travel with it:
 *  - Desktop's `goalCardUi` renders through `com.aptrade.desktop.designkit.formatMoney`, which is
 *    implemented on `java.math.BigDecimal`. `shared/commonMain` compiles to the iOS/macOS
 *    xcframework and has no `java.math`, so that function cannot live there at all. (It could
 *    physically go in `shared/jvmCommonMain`, the jvm+android-only source set — but that set is
 *    documented as the java.nio infrastructure seam, not a presentation one.)
 *  - More decisively, Android already has its own money formatter — [money], `NumberFormat`-based
 *    — and EVERY Android money string in the app goes through it. Hoisting desktop's formatter
 *    would make this card the one Android surface formatted by a different function than the rows
 *    beside it. Hoisting the mapper while injecting a `(String) -> String` formatter would avoid
 *    that, but it would churn a signature four shipped desktop files depend on, mid-milestone, to
 *    deduplicate three lines whose actual business content ([GoalMath.progress] and
 *    [GoalProjection]) is ALREADY shared.
 *
 * That matches how the rest of this view-model layer is already split: `HoldingRowUi`,
 * `TransactionRowUi` and `MetricTexts` are each per-platform for the same reason — Android emits
 * ONE pre-formatted display string per money field, where desktop emits RAW `Money.amountText` for
 * `SuperscriptPrice` to split. The shareable part of a goal card is the math, and that is in
 * `:shared` already.
 *
 * [projection] stays a raw [GoalProjection] rather than a rendered sentence so the copy resolves
 * at render time and re-renders when the user changes language — and so no caller has to
 * string-match to tell "already met" from "unreachable" (carry-notes §3.5).
 *
 * [targetAmountText] is the RAW `Money.amountText`, used ONLY to refill the edit field. Never feed
 * [currentText]/[targetText] into a parser — they carry "$" and grouping separators.
 */
data class GoalCardUi(
    val currentText: String,
    val targetText: String,
    val targetAmountText: String,
    val fraction: Double,
    val projection: GoalProjection,
)

/** Maps a (possibly absent) goal to card state. `null` means "no goal set" — the card still
 *  RENDERS, showing its "Set a goal" affordance; it is never hidden (carry-notes §1.3). */
fun goalCardUi(goal: PortfolioGoal?, current: Money, projection: GoalProjection?): GoalCardUi? {
    if (goal == null) return null
    return GoalCardUi(
        currentText = money(current.amountText),
        targetText = money(goal.target.amountText),
        targetAmountText = goal.target.amountText,
        fraction = GoalMath.progress(current, goal.target),
        // "Not computed yet" and "not enough history" read identically to a user, and both are
        // honest; collapsing them here keeps every downstream branch exhaustive over five cases.
        projection = projection ?: GoalProjection.InsufficientHistory,
    )
}
