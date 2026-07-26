package com.aptrade.desktop.income

import com.aptrade.shared.application.GoalStore
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioGoal
import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * Shared test fixtures for the income-view-model test suite ([IncomeViewModelTest],
 * [IncomeForecastGoalTest]). Carry-notes §4 tracks test-helper duplication in this suite as live
 * debt -- this file is the ONE place a [FakePortfolioStore], [MemoryGoalStore], or a `usd`/`qty`
 * parser lives for both test classes, so a further copy never gets added. File-level `internal`
 * rather than members of either class, since Kotlin has no way to "inherit" instance members
 * across two unrelated top-level test classes.
 */

internal fun usd(s: String): Money = Money(BigDecimal.parseString(s), "USD")
internal fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

/** In-memory [PortfolioStore]: returns whatever was last [save]d, seeded with [initial]. */
internal class FakePortfolioStore(initial: Portfolio) : PortfolioStore {
    var portfolio: Portfolio = initial
    override suspend fun load(): Portfolio = portfolio
    override suspend fun save(portfolio: Portfolio) {
        this.portfolio = portfolio
    }
}

/** In-memory [GoalStore]: returns whatever was last [save]d, seeded with [goals]. */
internal class MemoryGoalStore(var goals: List<PortfolioGoal> = emptyList()) : GoalStore {
    override suspend fun load(): List<PortfolioGoal> = goals
    override suspend fun save(goals: List<PortfolioGoal>) {
        this.goals = goals
    }
}
