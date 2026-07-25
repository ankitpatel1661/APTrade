package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** What a goal measures. */
enum class GoalKind {
    /** Total portfolio value (holdings + cash). */
    Value,

    /** Projected annual dividend income. */
    Income,
}

/** A user-set target for the WHOLE portfolio. At most one per [GoalKind] — enforcing that is
 *  `SaveGoal`'s job, not the adapter's. The user sets an amount; the app projects when it will
 *  be reached. Transcribed from `Sources/APTradeDomain/PortfolioGoal.swift`; Swift's `createdAt:
 *  Date` becomes epoch-seconds, the convention every other date in the Kotlin core uses. */
data class PortfolioGoal(
    val kind: GoalKind,
    val target: Money,
    val createdAtEpochSeconds: Long,
)

/** Per-kind goal-target validation bounds (carry-notes §1.5, BINDING).
 *
 *  [AmountInput.STARTING_BALANCE_RANGE] describes a portfolio's OPENING CASH. The Swift wave
 *  initially reused it for both goal kinds, which made an ordinary income goal like "$50/month
 *  in dividends" ($600/yr) unsettable and capped a value goal at $10M even though a value goal
 *  is meant to run far past a starting balance. Each kind gets a range sized to what it actually
 *  measures, and each supplies its own hint copy so the field never describes another quantity. */
val GoalKind.targetRange: AmountRange
    get() = when (this) {
        GoalKind.Income -> AmountRange(BigDecimal.parseString("100"), BigDecimal.parseString("1000000"))
        GoalKind.Value -> AmountRange(BigDecimal.parseString("1000"), BigDecimal.parseString("100000000"))
    }
