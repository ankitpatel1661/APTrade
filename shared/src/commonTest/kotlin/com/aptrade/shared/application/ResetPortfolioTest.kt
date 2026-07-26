package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioGoal
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResetPortfolioTest {

    private class MemoryPortfolioStore(var portfolio: Portfolio? = null) : PortfolioStore {
        override suspend fun load(): Portfolio? = portfolio
        override suspend fun save(portfolio: Portfolio) { this.portfolio = portfolio }
    }

    private class MemoryGoalStore(var goals: List<PortfolioGoal> = emptyList()) : GoalStore {
        override suspend fun load(): List<PortfolioGoal> = goals
        override suspend fun save(goals: List<PortfolioGoal>) { this.goals = goals }
    }

    @Test
    fun resetsToTheCallerSuppliedBalanceAndRecordsItAsStartingCash() = runTest {
        val store = MemoryPortfolioStore(
            Portfolio.starting().buying(
                Asset("AAPL", "Apple Inc.", AssetKind.Stock),
                BigDecimal.parseString("1"), Money.usd("100"), 1_000L, "txn-1",
            ),
        )
        val fresh = ResetPortfolio(store, Mutex()).execute(Money.usd("25000"))
        assertEquals(Money.usd("25000"), fresh.cash)
        assertEquals(Money.usd("25000"), fresh.startingCash)
        assertTrue(fresh.positions.isEmpty())
        assertTrue(fresh.transactions.isEmpty())
    }

    @Test
    fun resetPersistsTheFreshPortfolio() = runTest {
        val store = MemoryPortfolioStore()
        ResetPortfolio(store, Mutex()).execute(Money.usd("25000"))
        assertEquals(Money.usd("25000"), store.portfolio?.cash)
    }

    /** USER RULING 2026-07-27 — this test is the INVERSION of `resetClearsEveryGoal`, which
     *  asserted the opposite until M11.1 UAT F1. Resetting starting capital is "start over with
     *  more money", not "abandon my plan": a $120,000 value goal set before a reset to $1,000,000
     *  must survive and simply recompute as reached.
     *
     *  Rejects the shipped `goalStore.save(emptyList())` inside [ResetPortfolio.execute]. Note
     *  [ResetPortfolio] no longer TAKES a [GoalStore] at all — the dependency was removed rather
     *  than left unused, so goal clearing can no longer be re-armed by accident; this test is the
     *  behavioural record of why, and the goal store here is deliberately never handed to it. */
    @Test
    fun resetLeavesEveryGoalIntact() = runTest {
        val goals = MemoryGoalStore(
            listOf(
                PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1L),
                PortfolioGoal(GoalKind.Income, Money.usd("6000"), 2L),
            ),
        )
        ResetPortfolio(MemoryPortfolioStore(), Mutex()).execute(Money.usd("25000"))
        assertEquals(
            listOf(Money.usd("500000"), Money.usd("6000")),
            goals.goals.map { it.target },
        )
    }
}
