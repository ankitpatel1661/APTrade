package com.aptrade.shared.application

import com.aptrade.shared.domain.MarketStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketActivityPlannerTest {
    private val planner = MarketActivityPlanner()

    // Monday 2024-01-08, 10:00 local (UTC-5) = 1704726000 UTC -> market OPEN.
    private val mondayTenAmOpen = 1_704_726_000L

    // Monday 2024-01-08, 09:00 local = 1704722400 UTC -> market CLOSED (pre-open).
    private val mondayNineAmClosed = 1_704_722_400L

    // Saturday 2024-01-06, 10:00 local = 1704553200 UTC -> market CLOSED (weekend).
    private val saturdayTenAmClosed = 1_704_553_200L

    // Tuesday 2024-01-09, 10:00 local = 1704812400 UTC -> market OPEN (next trading day).
    private val tuesdayTenAmOpen = 1_704_812_400L

    @Test
    fun closedToOpenTransitionFiresMarketOpenedWhenGateEnabled() {
        val seedState = SchedulerState(lastStatus = MarketStatus.CLOSED)

        val (events, newState) = planner.plan(
            nowEpochSeconds = mondayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = true,
            newsDigestEnabled = false,
        )

        assertEquals(listOf(ScheduledNotification.MarketOpened), events)
        assertEquals(MarketStatus.OPEN, newState.lastStatus)
    }

    @Test
    fun openToClosedTransitionFiresMarketClosedWhenGateEnabled() {
        val seedState = SchedulerState(lastStatus = MarketStatus.OPEN)

        val (events, newState) = planner.plan(
            nowEpochSeconds = mondayNineAmClosed,
            state = seedState,
            marketOpenCloseEnabled = true,
            newsDigestEnabled = false,
        )

        assertEquals(listOf(ScheduledNotification.MarketClosed), events)
        assertEquals(MarketStatus.CLOSED, newState.lastStatus)
    }

    @Test
    fun unchangedStatusFiresNoTransitionEvent() {
        val seedState = SchedulerState(lastStatus = MarketStatus.OPEN)

        val (events, newState) = planner.plan(
            nowEpochSeconds = mondayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = true,
            newsDigestEnabled = false,
        )

        assertEquals(emptyList(), events)
        assertEquals(MarketStatus.OPEN, newState.lastStatus)
    }

    @Test
    fun firstObservationSeedsBaselineWithoutFiringASpuriousEvent() {
        // No lastStatus yet (fresh install / first tick) -- must not fire, only seed.
        val seedState = SchedulerState(lastStatus = null)

        val (events, newState) = planner.plan(
            nowEpochSeconds = mondayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = true,
            newsDigestEnabled = false,
        )

        assertEquals(emptyList(), events)
        assertEquals(MarketStatus.OPEN, newState.lastStatus)
    }

    @Test
    fun transitionIsSuppressedWhenMarketOpenCloseGateIsDisabled() {
        val seedState = SchedulerState(lastStatus = MarketStatus.CLOSED)

        val (events, newState) = planner.plan(
            nowEpochSeconds = mondayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = false,
            newsDigestEnabled = false,
        )

        assertEquals(emptyList(), events)
        // The status baseline still advances even when the notification is suppressed.
        assertEquals(MarketStatus.OPEN, newState.lastStatus)
    }

    @Test
    fun digestFiresOnceWhenMarketOpensAndGateEnabled() {
        val seedState = SchedulerState(lastStatus = MarketStatus.CLOSED, lastDigestDay = null)

        val (events, newState) = planner.plan(
            nowEpochSeconds = mondayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = false,
            newsDigestEnabled = true,
        )

        assertTrue(events.contains(ScheduledNotification.DigestDue))
        assertEquals("2024-01-08", newState.lastDigestDay)
    }

    @Test
    fun digestDoesNotFireAgainLaterTheSameTradingDay() {
        // Already fired earlier this trading day -- a later open tick must not repeat it.
        val seedState = SchedulerState(lastStatus = MarketStatus.OPEN, lastDigestDay = "2024-01-08")

        val (events, newState) = planner.plan(
            nowEpochSeconds = mondayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = false,
            newsDigestEnabled = true,
        )

        assertEquals(emptyList(), events)
        assertEquals("2024-01-08", newState.lastDigestDay)
    }

    @Test
    fun digestFiresAgainOnTheNextTradingDayAfterAlreadyFiringForThePriorDay() {
        val seedState = SchedulerState(lastStatus = MarketStatus.CLOSED, lastDigestDay = "2024-01-08")

        val (events, newState) = planner.plan(
            nowEpochSeconds = tuesdayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = false,
            newsDigestEnabled = true,
        )

        assertTrue(events.contains(ScheduledNotification.DigestDue))
        assertEquals("2024-01-09", newState.lastDigestDay)
    }

    @Test
    fun digestDoesNotFireWhenMarketIsClosed() {
        val seedState = SchedulerState(lastStatus = null, lastDigestDay = null)

        val (events, newState) = planner.plan(
            nowEpochSeconds = saturdayTenAmClosed,
            state = seedState,
            marketOpenCloseEnabled = false,
            newsDigestEnabled = true,
        )

        assertEquals(emptyList(), events)
        assertEquals(null, newState.lastDigestDay)
    }

    @Test
    fun digestIsSuppressedWhenNewsDigestGateIsDisabledEvenOnFirstOpenOfTheDay() {
        val seedState = SchedulerState(lastStatus = MarketStatus.CLOSED, lastDigestDay = null)

        val (events, newState) = planner.plan(
            nowEpochSeconds = mondayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = false,
            newsDigestEnabled = false,
        )

        assertEquals(emptyList(), events)
        assertEquals(null, newState.lastDigestDay)
    }

    @Test
    fun bothGatesDisabledProducesNoEventsEvenAcrossATransitionWithDigestDue() {
        val seedState = SchedulerState(lastStatus = MarketStatus.CLOSED, lastDigestDay = null)

        val (events, newState) = planner.plan(
            nowEpochSeconds = mondayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = false,
            newsDigestEnabled = false,
        )

        assertEquals(emptyList(), events)
        // State still advances -- gating affects notification emission only.
        assertEquals(MarketStatus.OPEN, newState.lastStatus)
        assertEquals(null, newState.lastDigestDay)
    }

    @Test
    fun weekendTickProducesNoTransitionAndNoDigestButSeedsClosedBaseline() {
        val seedState = SchedulerState(lastStatus = null, lastDigestDay = null)

        val (events, newState) = planner.plan(
            nowEpochSeconds = saturdayTenAmClosed,
            state = seedState,
            marketOpenCloseEnabled = true,
            newsDigestEnabled = true,
        )

        assertEquals(emptyList(), events)
        assertEquals(MarketStatus.CLOSED, newState.lastStatus)
        assertEquals(null, newState.lastDigestDay)
    }

    @Test
    fun stateRoundTripsThroughAFakeStoreUnchangedWhenNothingIsSaved() {
        val store = FakeSchedulerStateStore(SchedulerState(lastStatus = MarketStatus.OPEN, lastDigestDay = "2024-01-08"))

        val loaded = store.loadBlocking()

        assertEquals(MarketStatus.OPEN, loaded.lastStatus)
        assertEquals("2024-01-08", loaded.lastDigestDay)
    }

    @Test
    fun stateRoundTripsThroughAFakeStoreAfterSavingThePlannersNewState() {
        val store = FakeSchedulerStateStore(SchedulerState())
        val seedState = SchedulerState(lastStatus = MarketStatus.CLOSED, lastDigestDay = null)

        val (_, newState) = planner.plan(
            nowEpochSeconds = mondayTenAmOpen,
            state = seedState,
            marketOpenCloseEnabled = true,
            newsDigestEnabled = true,
        )
        store.saveBlocking(newState)

        val reloaded = store.loadBlocking()
        assertEquals(newState, reloaded)
    }
}

/** In-memory [SchedulerStateStore] fake for tests. `runTest`-free helpers avoid pulling
 *  coroutines-test into a purely synchronous fixture check. */
private class FakeSchedulerStateStore(initial: SchedulerState) : SchedulerStateStore {
    private var stored: SchedulerState = initial

    override suspend fun load(): SchedulerState = stored
    override suspend fun save(state: SchedulerState) {
        stored = state
    }

    fun loadBlocking(): SchedulerState = stored
    fun saveBlocking(state: SchedulerState) {
        stored = state
    }
}
