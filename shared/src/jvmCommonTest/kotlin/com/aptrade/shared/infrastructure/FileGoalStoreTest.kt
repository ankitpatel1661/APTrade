package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M11.2 Task 3. Mirrors `FilePortfolioStore`'s atomic-write / lenient-fallback discipline. */
class FileGoalStoreTest {

    @Test
    fun missingFileLoadsAnEmptyList() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        assertTrue(FileGoalStore(file).load().isEmpty())
    }

    @Test
    fun goalsRoundTrip() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        val store = FileGoalStore(file)
        val goals = listOf(
            PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_700_000_000L),
            PortfolioGoal(GoalKind.Income, Money.usd("6000"), 1_700_000_100L),
        )
        store.save(goals)
        assertEquals(goals, store.load())
    }

    @Test
    fun corruptFileLoadsAnEmptyListRatherThanThrowing() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        file.writeText("{ not json at all")
        assertTrue(FileGoalStore(file).load().isEmpty())
    }

    @Test
    fun anUnknownGoalKindLoadsAnEmptyList() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        file.writeText("""[{"kind":"Retirement","target":{"amount":"1","currency":"USD"},"createdAtEpochSeconds":1}]""")
        assertTrue(FileGoalStore(file).load().isEmpty())
    }

    @Test
    fun savingAnEmptyListClearsPersistedGoals() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        val store = FileGoalStore(file)
        store.save(listOf(PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1L)))
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }
}
