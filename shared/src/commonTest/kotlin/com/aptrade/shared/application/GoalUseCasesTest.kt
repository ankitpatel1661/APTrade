package com.aptrade.shared.application

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M11.2 Task 3. Semantics transcribed from `Sources/APTradeApplication/GoalUseCases.swift`. */
class GoalUseCasesTest {

    private class MemoryGoalStore(var goals: List<PortfolioGoal> = emptyList()) : GoalStore {
        var saveCount = 0
        override suspend fun load(): List<PortfolioGoal> = goals
        override suspend fun save(goals: List<PortfolioGoal>) {
            saveCount += 1
            this.goals = goals
        }
    }

    @Test
    fun loadReturnsWhateverTheStoreHolds() = runTest {
        val goal = PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_000L)
        assertEquals(listOf(goal), LoadGoals(MemoryGoalStore(listOf(goal))).execute())
    }

    @Test
    fun saveUpsertsByKindSoOnlyOneGoalPerKindSurvives() = runTest {
        val store = MemoryGoalStore()
        SaveGoal(store).execute(PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_000L))
        SaveGoal(store).execute(PortfolioGoal(GoalKind.Value, Money.usd("750000"), 2_000L))
        assertEquals(1, store.goals.size)
        assertEquals(Money.usd("750000"), store.goals.single().target)
    }

    @Test
    fun saveKeepsTheOtherKindIntact() = runTest {
        val store = MemoryGoalStore()
        SaveGoal(store).execute(PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_000L))
        SaveGoal(store).execute(PortfolioGoal(GoalKind.Income, Money.usd("6000"), 2_000L))
        assertEquals(2, store.goals.size)
        assertTrue(store.goals.any { it.kind == GoalKind.Value })
        assertTrue(store.goals.any { it.kind == GoalKind.Income })
    }

    @Test
    fun removeDropsOnlyTheNamedKind() = runTest {
        val store = MemoryGoalStore(
            listOf(
                PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_000L),
                PortfolioGoal(GoalKind.Income, Money.usd("6000"), 2_000L),
            ),
        )
        RemoveGoal(store).execute(GoalKind.Value)
        assertEquals(GoalKind.Income, store.goals.single().kind)
    }
}
