package com.aptrade.desktop.infra

import com.aptrade.shared.application.SchedulerState
import com.aptrade.shared.domain.MarketStatus
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSchedulerStateStoreTest {

    private fun tempFile() = createTempDirectory("aptrade-scheduler-test").resolve("schedulerState.json")

    @Test
    fun `missing file loads default state`() = runTest {
        val store = FileSchedulerStateStore(tempFile())
        assertEquals(SchedulerState(), store.load())
    }

    @Test
    fun `round-trips a fully populated state`() = runTest {
        val file = tempFile()
        val store = FileSchedulerStateStore(file)
        val state = SchedulerState(lastStatus = MarketStatus.OPEN, lastDigestDay = "2026-07-05")
        store.save(state)
        assertTrue(file.exists())
        assertEquals(state, store.load())
    }

    @Test
    fun `round-trips CLOSED status`() = runTest {
        val file = tempFile()
        val store = FileSchedulerStateStore(file)
        val state = SchedulerState(lastStatus = MarketStatus.CLOSED, lastDigestDay = null)
        store.save(state)
        assertEquals(state, store.load())
    }

    @Test
    fun `corrupt json loads default state`() = runTest {
        val file = tempFile()
        file.writeText("{ not valid json ")
        assertEquals(SchedulerState(), FileSchedulerStateStore(file).load())
    }

    @Test
    fun `unknown market status loads default state, whole blob fallback`() = runTest {
        val file = tempFile()
        file.writeText("""{"lastStatus":"PRE_MARKET","lastDigestDay":"2026-07-05"}""")
        assertEquals(SchedulerState(), FileSchedulerStateStore(file).load())
    }

    @Test
    fun `null lastStatus round-trips as null`() = runTest {
        val file = tempFile()
        val store = FileSchedulerStateStore(file)
        val state = SchedulerState(lastStatus = null, lastDigestDay = "2026-07-05")
        store.save(state)
        assertEquals(state, store.load())
    }

    @Test
    fun `round-trips lastEarningsDay`() = runTest {
        val file = tempFile()
        val store = FileSchedulerStateStore(file)
        val state = SchedulerState(lastStatus = MarketStatus.OPEN, lastDigestDay = "2026-07-05", lastEarningsDay = "2026-07-05")
        store.save(state)
        assertEquals(state, store.load())
    }

    @Test
    fun `old file without lastEarningsDay loads fine with it defaulting to null`() = runTest {
        // Back-compat pin: a schedulerState.json written before the earnings-check field
        // existed has only lastStatus/lastDigestDay. Lenient decode must still succeed and
        // default lastEarningsDay to null rather than failing the whole-blob load.
        val file = tempFile()
        file.writeText("""{"lastStatus":"OPEN","lastDigestDay":"2026-07-05"}""")
        val loaded = FileSchedulerStateStore(file).load()
        assertEquals(MarketStatus.OPEN, loaded.lastStatus)
        assertEquals("2026-07-05", loaded.lastDigestDay)
        assertEquals(null, loaded.lastEarningsDay)
    }
}
