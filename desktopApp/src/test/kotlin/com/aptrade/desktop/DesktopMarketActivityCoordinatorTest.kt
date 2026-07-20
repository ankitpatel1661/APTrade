package com.aptrade.desktop

import com.aptrade.desktop.infra.AppSettings
import com.aptrade.shared.application.ContributionOutcome
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.MarketActivityPlanner
import com.aptrade.shared.application.PieRunResult
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.SchedulerState
import com.aptrade.shared.application.SchedulerStateStore
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.WatchlistEntry
import com.ionspin.kotlin.bignum.decimal.BigDecimal
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

class DesktopMarketActivityCoordinatorTest {

    // Monday 2024-01-08, 10:00 local (UTC-5) = 1704726000 UTC -> market OPEN.
    // Same reference instants as shared/MarketActivityPlannerTest, so DST/calendar
    // behavior is already covered there — this suite only proves the desktop wiring.
    private val mondayTenAmOpen = 1_704_726_000L

    // Monday 2024-01-08, 09:00 local -> market CLOSED (pre-open, same trading day).
    private val mondayNineAmClosed = 1_704_722_400L

    private fun quote(symbol: String, price: String, change: Double) =
        Quote(symbol, Money.usd(price), Money.usd(price), change)

    private fun pieFixture(name: String = "Core Growth") = Pie.create(
        name = name,
        slices = listOf(PieSlice("AAPL", AssetKind.Stock, BigDecimal.parseString("100"))),
        schedule = null,
        createdDay = "2024-01-01",
    )

    private fun coordinator(
        stateStore: SchedulerStateStore,
        settings: AppSettings = AppSettings(marketOpenClose = true, newsDigest = true),
        watchlistStore: WatchlistStore = InMemoryWatchlistStore(),
        quotesImpl: suspend (List<String>) -> List<Quote> = { emptyList() },
        notifyMarketStatus: suspend (Boolean) -> Unit = {},
        notifyDigest: suspend (String) -> Unit = {},
        notifyEarnings: suspend (EarningsEvent) -> Unit = {},
        fetchTodaysOwnEarnings: suspend () -> List<EarningsEvent> = { emptyList() },
        executeDueContributions: suspend (Long) -> List<PieRunResult> = { emptyList() },
        notifyPieContribution: suspend (ContributionOutcome) -> Unit = {},
        nowEpochSeconds: () -> Long,
        scope: kotlinx.coroutines.CoroutineScope,
    ): DesktopMarketActivityCoordinator {
        val repo = FakeMarketDataRepository().apply { this.quotesImpl = quotesImpl }
        return DesktopMarketActivityCoordinator(
            planner = MarketActivityPlanner(),
            stateStore = stateStore,
            loadSettings = { settings },
            notifyMarketStatus = notifyMarketStatus,
            notifyDigest = notifyDigest,
            notifyEarnings = notifyEarnings,
            fetchTodaysOwnEarnings = fetchTodaysOwnEarnings,
            executeDueContributions = executeDueContributions,
            notifyPieContribution = notifyPieContribution,
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

    // MARK: - Pie contributions (M7.2 Task 12)

    @Test
    fun launchCatchUpFiresOnceBeforeTheFirstTickWhenPieContributionsEnabled() = runTest {
        // Market CLOSED at this instant (mondayNineAmClosed), so the tick loop's own
        // `ContributionCheckDue` path (gated on `status == OPEN`) never fires here — isolating
        // this assertion to the launch catch-up alone (Global Constraints correction 6: the
        // launch-catch-up path is gated ONLY on `settings.pieContributions`, not market status).
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.CLOSED))
        val pie = pieFixture()
        val outcome: ContributionOutcome = ContributionOutcome.SkippedInsufficientCash(pie)
        var catchUpCallCount = 0
        val notified = mutableListOf<ContributionOutcome>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = false, pieContributions = true),
            executeDueContributions = { catchUpCallCount += 1; listOf(PieRunResult(pie, listOf(outcome))) },
            notifyPieContribution = { o -> notified += o },
            nowEpochSeconds = { mondayNineAmClosed },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        assertEquals(1, catchUpCallCount)
        assertEquals(listOf(outcome), notified)
    }

    @Test
    fun launchCatchUpNeverRunsWhenPieContributionsDisabled() = runTest {
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        var catchUpCallCount = 0
        val notified = mutableListOf<ContributionOutcome>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = false, pieContributions = false),
            executeDueContributions = { catchUpCallCount += 1; listOf(PieRunResult(pieFixture(), listOf(ContributionOutcome.SkippedInsufficientCash(pieFixture())))) },
            notifyPieContribution = { o -> notified += o },
            nowEpochSeconds = { mondayTenAmOpen },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        // Neither the launch catch-up NOR the first tick's ContributionCheckDue path (the
        // planner itself never emits the event when `pieContributionsEnabled` is false) ever
        // calls the catch-up engine.
        assertEquals(0, catchUpCallCount)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun contributionCheckDueOnTheFirstOpenTickExecutesCatchUpAndNotifiesPerOutcome() = runTest {
        // No prior lastContributionDay recorded, market OPEN at this instant: both the launch
        // catch-up (unconditional on `pieContributions`) AND the tick's `ContributionCheckDue`
        // handler (fired on the first tick that observes the market open) call
        // `executeDueContributions` — mirroring real behavior where the SECOND call finds
        // nothing left due (the first call already advanced past it) and returns no results.
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val pie = pieFixture()
        val outcome: ContributionOutcome = ContributionOutcome.SkippedInsufficientCash(pie)
        var catchUpCallCount = 0
        val notified = mutableListOf<ContributionOutcome>()
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = false, newsDigest = false, pieContributions = true),
            executeDueContributions = { _ ->
                catchUpCallCount += 1
                if (catchUpCallCount == 1) listOf(PieRunResult(pie, listOf(outcome))) else emptyList()
            },
            notifyPieContribution = { o -> notified += o },
            nowEpochSeconds = { mondayTenAmOpen },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()

        assertEquals(2, catchUpCallCount)      // launch catch-up + the first tick's ContributionCheckDue
        assertEquals(listOf(outcome), notified) // only the first (due-work) call produced an outcome
        assertEquals(1, stateStore.saveCallCount)
    }

    @Test
    fun contributionCatchUpFailureDropsOnlyThisTickAndTheLoopSurvives() = runTest {
        // Regression guard mirroring earningsFetchFailureDropsOnlyEarningsAndTheLoopSurvives:
        // an uncaught failure from executeDueContributions must not kill the tick coroutine.
        val stateStore = InMemorySchedulerStateStore(initial = SchedulerState(lastStatus = com.aptrade.shared.domain.MarketStatus.OPEN))
        val statusEvents = mutableListOf<Boolean>()
        var now = mondayTenAmOpen
        val coordinator = coordinator(
            stateStore = stateStore,
            settings = AppSettings(marketOpenClose = true, newsDigest = false, pieContributions = true),
            notifyMarketStatus = { opened -> statusEvents += opened },
            executeDueContributions = { throw QuoteError.Network("down") },
            nowEpochSeconds = { now },
            scope = backgroundScope,
        )

        coordinator.start(); runCurrent()
        assertEquals(1, stateStore.saveCallCount)  // tick completed despite the catch-up failure

        now = mondayNineAmClosed + 24 * 3_600      // next day pre-open -> market closed
        advanceTimeBy(60_001); runCurrent()
        assertEquals(listOf(false), statusEvents)  // loop survived: transition still delivered
    }
}
