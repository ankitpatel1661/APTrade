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
     *  ⚠️ WHAT THIS TEST DOES AND DOES NOT GUARD — read before trusting it.
     *
     *  It does NOT reject `goalStore.save(emptyList())` being put back. It cannot:
     *  [ResetPortfolio] no longer TAKES a [GoalStore], so this test never hands it one, and
     *  re-adding that line alone leaves the goal assertion green. That was verified in review —
     *  the literal old implementation was restored and this test still passed.
     *
     *  The real guarantee is STRUCTURAL: the dependency is gone, so the clearing has nothing to
     *  call, and re-arming it means re-adding a constructor parameter and re-wiring three graphs
     *  (desktop, Android, and the test fixtures) — deliberate edits, not accidents. That is why
     *  the plan required deleting the parameter rather than leaving it unused.
     *
     *  What this test IS: the executable record of the ruling, plus a real check that the reset
     *  still performs its own job — the portfolio assertions below fail if [ResetPortfolio] is
     *  gutted, so it cannot pass by merely observing that an untouched object was untouched. The
     *  behaviourally discriminating goal-survival coverage lives one layer up, where a reset does
     *  flow past a real `GoalStore`: `desktopApp`'s
     *  `PortfolioResetAmountTest.resetKeepsTheValueGoalAndRecomputesItAgainstTheFreshBalance`. */
    @Test
    fun resetLeavesEveryGoalIntact() = runTest {
        val goals = MemoryGoalStore(
            listOf(
                PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1L),
                PortfolioGoal(GoalKind.Income, Money.usd("6000"), 2L),
            ),
        )
        val store = MemoryPortfolioStore()
        val fresh = ResetPortfolio(store, Mutex()).execute(Money.usd("25000"))
        assertEquals(
            listOf(Money.usd("500000"), Money.usd("6000")),
            goals.goals.map { it.target },
        )
        // The reset itself still happened — this test must not be able to pass by doing nothing.
        assertEquals(Money.usd("25000"), fresh.cash)
        assertEquals(Money.usd("25000"), fresh.startingCash)
        assertEquals(Money.usd("25000"), store.portfolio?.cash)
    }
}
