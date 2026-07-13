package com.aptrade.android.alerts

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.MarketActivityPlanner
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.SchedulerState
import com.aptrade.shared.application.SchedulerStateStore
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.settings.AppSettings
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class InMemoryWatchlistStore(initial: List<WatchlistEntry> = emptyList()) : WatchlistStore {
    var stored: List<WatchlistEntry> = initial
    override suspend fun load() = stored
    override suspend fun save(entries: List<WatchlistEntry>) { stored = entries }
}

private class InMemorySchedulerStateStore(initial: SchedulerState = SchedulerState()) : SchedulerStateStore {
    var stored: SchedulerState = initial
    var saveCallCount = 0
        private set
    override suspend fun load(): SchedulerState = stored
    override suspend fun save(state: SchedulerState) {
        stored = state
        saveCallCount += 1
    }
}

/**
 * Port of `desktopApp/src/test/kotlin/com/aptrade/desktop/DesktopMarketActivityCoordinatorTest.kt`
 * (package-renamed, notifier closures recorded into lists) — see
 * [AndroidMarketActivityCoordinator]'s KDoc for the transcription source. Plus two
 * earnings-specific cases from the Task 8 brief:
 * [earningsCheckDeliversOneNotificationPerOwnedEvent] and
 * [earningsCheckWithNoOwnedEventsStaysSilent] (equivalent in substance to the desktop
 * suite's own [earningsCheckDueNotifiesOncePerOwnedEvent] /
 * [earningsCheckDueWithNoOwnedEventsNotifiesNothing]).
 */
class AndroidMarketActivityCoordinatorTest {

    // Monday 2024-01-08, 10:00 local (UTC-5) = 1704726000 UTC -> market OPEN.
    // Same reference instants as shared/MarketActivityPlannerTest, so DST/calendar
    // behavior is already covered there — this suite only proves the Android wiring.
    private val mondayTenAmOpen = 1_704_726_000L

    // Monday 2024-01-08, 09:00 local -> market CLOSED (pre-open, same trading day).
    private val mondayNineAmClosed = 1_704_722_400L

    private fun quote(symbol: String, price: String, change: Double) =
        Quote(symbol, Money.usd(price), Money.usd(price), change)

    private fun coordinator(
        stateStore: SchedulerStateStore,
        settings: AppSettings = AppSettings(marketOpenClose = true, newsDigest = true),
        watchlistStore: WatchlistStore = InMemoryWatchlistStore(),
        quotesImpl: suspend (List<String>) -> List<Quote> = { emptyList() },
        notifyMarketStatus: suspend (Boolean) -> Unit = {},
        notifyDigest: suspend (String) -> Unit = {},
        notifyEarnings: suspend (EarningsEvent) -> Unit = {},
        fetchTodaysOwnEarnings: suspend () -> List<EarningsEvent> = { emptyList() },
        nowEpochSeconds: () -> Long,
        scope: kotlinx.coroutines.CoroutineScope,
    ): AndroidMarketActivityCoordinator {
        val repo = FakeMarketDataRepository().apply { this.quotesImpl = quotesImpl }
        return AndroidMarketActivityCoordinator(
            planner = MarketActivityPlanner(),
            stateStore = stateStore,
            loadSettings = { settings },
            notifyMarketStatus = notifyMarketStatus,
            notifyDigest = notifyDigest,
            notifyEarnings = notifyEarnings,
            fetchTodaysOwnEarnings = fetchTodaysOwnEarnings,
            fetchWatchlist = FetchWatchlist(watchlistStore, emptyList()),
            fetchMarketQuotes = FetchMarketQuotes(repo),
            scope = scope,
            nowEpochSeconds = nowEpochSeconds,
        )
    }

    @Test
    fun tickFiresMarketOpenedTransitionAndPersistsState() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.CLOSED))
        val statusEvents = mutableListOf<Boolean>()
        var now = mondayTenAmOpen
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = true, newsDigest = false),
            notifyMarketStatus = { opened -> statusEvents += opened },
            nowEpochSeconds = { now },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        assertEquals(listOf(true), statusEvents)
        assertEquals(com.aptrade.shared.domain.MarketStatus.OPEN, stateStore.stored.lastStatus)
        assertEquals(1, stateStore.saveCallCount)
    }

    @Test
    fun onlyOneDigestFiresPerTradingDayAcrossTicks() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val watchlistStore = InMemoryWatchlistStore(listOf(WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)))
        val digests = mutableListOf<String>()
        var now = mondayTenAmOpen
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = true),
            watchlistStore = watchlistStore,
            quotesImpl = { symbols -> symbols.map { quote(it, "150.00", 3.0) } },
            notifyDigest = { summary -> digests += summary },
            nowEpochSeconds = { now },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()
        assertEquals(1, digests.size)
        assertTrue(digests.single().startsWith("Today's movers"))

        // Same trading day, next tick: no second digest.
        advanceTimeBy(60_001); runCurrent()
        assertEquals(1, digests.size)
    }

    @Test
    fun digestBodyListsTopThreeMoversByAbsoluteChange() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val watchlistStore = InMemoryWatchlistStore(
            listOf(
                WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
                WatchlistEntry("SPY", "SPDR S&P 500 ETF Trust", AssetKind.Etf),
                WatchlistEntry("BTC-USD", "Bitcoin USD", AssetKind.Crypto),
                WatchlistEntry("TSLA", "Tesla Inc.", AssetKind.Stock),
            ),
        )
        val digests = mutableListOf<String>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = true),
            watchlistStore = watchlistStore,
            quotesImpl = { symbols ->
                symbols.map {
                    when (it) {
                        "AAPL" -> quote(it, "150.00", 1.0)
                        "SPY" -> quote(it, "400.00", -8.0)
                        "BTC-USD" -> quote(it, "60000.00", 5.0)
                        else -> quote(it, "200.00", -6.0)
                    }
                }
            },
            notifyDigest = { summary -> digests += summary },
            nowEpochSeconds = { mondayTenAmOpen },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        val body = digests.single()
        assertTrue(body.contains("SPY"))
        assertTrue(body.contains("BTC-USD"))
        assertTrue(body.contains("TSLA"))
        assertTrue(!body.contains("AAPL"))   // smallest mover, excluded from top 3
    }

    @Test
    fun noTransitionEventWhenMarketOpenCloseDisabled() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.CLOSED))
        val statusEvents = mutableListOf<Boolean>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = false),
            notifyMarketStatus = { opened -> statusEvents += opened },
            nowEpochSeconds = { mondayTenAmOpen },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        assertTrue(statusEvents.isEmpty())
        assertEquals(com.aptrade.shared.domain.MarketStatus.OPEN, stateStore.stored.lastStatus)  // baseline still updates
    }

    @Test
    fun digestFetchFailureFallsBackToUpdatingMessageWithoutBreakingTheTick() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val watchlistStore = InMemoryWatchlistStore(listOf(WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)))
        val digests = mutableListOf<String>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = true),
            watchlistStore = watchlistStore,
            quotesImpl = { throw QuoteError.Network("down") },
            notifyDigest = { summary -> digests += summary },
            nowEpochSeconds = { mondayTenAmOpen },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        assertEquals(listOf("Market data is still updating."), digests)
        assertEquals(1, stateStore.saveCallCount)   // state still persisted despite the fetch failure
    }

    @Test
    fun closedToOpenThenOpenToClosedFiresBothTransitionsAcrossTicks() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.CLOSED))
        val statusEvents = mutableListOf<Boolean>()
        var now = mondayNineAmClosed
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = true, newsDigest = false),
            notifyMarketStatus = { opened -> statusEvents += opened },
            nowEpochSeconds = { now },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()
        assertTrue(statusEvents.isEmpty())   // still closed on first tick, just seeds baseline

        now = mondayTenAmOpen
        advanceTimeBy(60_001); runCurrent()
        assertEquals(listOf(true), statusEvents)

        now = mondayNineAmClosed + 24 * 3_600  // next day pre-open -> closed again
        advanceTimeBy(60_001); runCurrent()
        assertEquals(listOf(true, false), statusEvents)
    }

    @Test
    fun earningsCheckDueNotifiesOncePerOwnedEvent() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val ownedEvents = listOf(
            EarningsEvent("AAPL", "Apple Inc.", "2024-01-08", EarningsSession.AfterClose, null, null),
            EarningsEvent("MSFT", "Microsoft Corp.", "2024-01-08", EarningsSession.BeforeOpen, null, null),
        )
        val notified = mutableListOf<EarningsEvent>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = false, earningsReports = true),
            notifyEarnings = { event -> notified += event },
            fetchTodaysOwnEarnings = { ownedEvents },
            nowEpochSeconds = { mondayTenAmOpen },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        assertEquals(ownedEvents, notified)
    }

    @Test
    fun earningsFetchFailureDropsOnlyEarningsAndTheLoopSurvives() = runTest {
        // Regression guard for the review-found Critical: an uncaught QuoteError from the
        // earnings fetch used to kill the tick coroutine, silently disabling ALL scheduled
        // notifications until relaunch. Failure must drop only this tick's earnings
        // notifications; later ticks (here: the open->closed transition) still fire.
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val notified = mutableListOf<EarningsEvent>()
        val statusEvents = mutableListOf<Boolean>()
        var now = mondayTenAmOpen
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = true, newsDigest = false, earningsReports = true),
            notifyMarketStatus = { opened -> statusEvents += opened },
            notifyEarnings = { event -> notified += event },
            fetchTodaysOwnEarnings = { throw QuoteError.Network("down") },
            nowEpochSeconds = { now },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()
        assertTrue(notified.isEmpty())             // failure -> no earnings notification
        assertEquals(1, stateStore.saveCallCount)  // tick completed, state persisted

        now = mondayNineAmClosed + 24 * 3_600      // next day pre-open -> market closed
        advanceTimeBy(60_001); runCurrent()
        assertEquals(listOf(false), statusEvents)  // loop survived: transition still delivered
        assertTrue(notified.isEmpty())
    }

    @Test
    fun earningsCheckDueWithNoOwnedEventsNotifiesNothing() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val notified = mutableListOf<EarningsEvent>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = false, earningsReports = true),
            notifyEarnings = { event -> notified += event },
            fetchTodaysOwnEarnings = { emptyList() },
            nowEpochSeconds = { mondayTenAmOpen },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        assertTrue(notified.isEmpty())
    }

    // --- Task 8 brief's two named earnings cases (equivalent in substance to the two above,
    // kept as separate named tests per the brief's explicit TDD step) -----------------------

    @Test
    fun earningsCheckDeliversOneNotificationPerOwnedEvent() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val ownedEvents = listOf(
            EarningsEvent("AAPL", "Apple Inc.", "2024-01-08", EarningsSession.AfterClose, null, null),
            EarningsEvent("MSFT", "Microsoft Corp.", "2024-01-08", EarningsSession.BeforeOpen, null, null),
        )
        val notified = mutableListOf<EarningsEvent>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = false, earningsReports = true),
            notifyEarnings = { event -> notified += event },
            fetchTodaysOwnEarnings = { ownedEvents },
            nowEpochSeconds = { mondayTenAmOpen },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        assertEquals(2, notified.size)
        assertEquals(ownedEvents, notified)
    }

    @Test
    fun earningsCheckWithNoOwnedEventsStaysSilent() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val notified = mutableListOf<EarningsEvent>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = false, earningsReports = true),
            notifyEarnings = { event -> notified += event },
            fetchTodaysOwnEarnings = { emptyList() },
            nowEpochSeconds = { mondayTenAmOpen },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        assertTrue(notified.isEmpty())
    }
}
