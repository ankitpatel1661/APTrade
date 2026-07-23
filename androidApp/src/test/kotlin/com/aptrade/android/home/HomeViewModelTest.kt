package com.aptrade.android.home

import com.aptrade.shared.application.HomeFeedAssembler
import com.aptrade.shared.application.HomeIncomeSummary
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.ScreenerSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Android twin of desktopApp/src/test/kotlin/com/aptrade/desktop/home/HomeViewModelTest.kt,
 *  transcribing its 5 SEMANTICS pins verbatim — a thin androidx-ViewModel wrapper over the
 *  shared [HomeFeedAssembler]. `assembler` is a REAL [HomeFeedAssembler] built from fake
 *  suspend sources (its own commonTest suite, `HomeFeedAssemblerTest`, already pins every
 *  per-source isolation semantic — that's not re-tested here), rather than a mock: the
 *  assembler's own per-source `try/catch`es mean the ONLY way a whole-
 *  [HomeFeedAssembler.refresh] call fails is a throw from something no per-source guard
 *  wraps — [HomeFeedAssembler]'s call to `nowEpochSeconds()`, the very first line of
 *  `refresh()`, is exactly that seam, so a throwing `nowEpochSeconds` is this file's
 *  stand-in for "the whole call breaks."
 *
 *  [HomeViewModel] is an androidx ViewModel using `viewModelScope`
 *  (Dispatchers.Main.immediate), mirroring [com.aptrade.android.plans.PlansViewModelTest]'s
 *  scheduler discipline: a [StandardTestDispatcher] installed as Main, with `runCurrent()`
 *  after [HomeViewModel.start] (which launches via `viewModelScope`) — direct `suspend`
 *  calls to [HomeViewModel.refresh] need no `runCurrent()`, same as the desktop test. */
private fun usd(v: String) = Money.usd(v)

private fun assembler(
    portfolio: suspend () -> Portfolio = { Portfolio(cash = usd("0")) },
    fetchQuotes: suspend (List<String>) -> Map<String, Quote> = { emptyMap() },
    ownSymbols: suspend () -> Set<String> = { emptySet() },
    incomeSummary: suspend () -> HomeIncomeSummary = { HomeIncomeSummary(usd("0"), null) },
    nextEarnings: suspend () -> EarningsEvent? = { null },
    screenerSnapshot: suspend () -> ScreenerSnapshot? = { null },
    alerts: suspend () -> List<PriceAlert> = { emptyList() },
    nowEpochSeconds: () -> Long = { 1_795_453_200L }, // Monday 2026-11-23 noon ET
): HomeFeedAssembler = HomeFeedAssembler(
    loadPortfolio = portfolio,
    fetchQuotes = fetchQuotes,
    ownSymbols = ownSymbols,
    loadIncomeSummary = incomeSummary,
    fetchNextEarnings = nextEarnings,
    loadScreenerSnapshot = screenerSnapshot,
    loadAlerts = alerts,
    calendar = MarketCalendar(),
    nowEpochSeconds = nowEpochSeconds,
)

class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // MARK: - refresh() publishes the assembler's fresh HomeState

    @Test
    fun refreshPublishesAssemblerState() = runTest(dispatcher.scheduler) {
        val vm = HomeViewModel(assembler(portfolio = { Portfolio(cash = usd("500")) }))

        vm.refresh()

        assertEquals(usd("500"), vm.state.value?.cash)
    }

    @Test
    fun refreshOnSuccessAlwaysReplacesPreviousState() = runTest(dispatcher.scheduler) {
        var cash = usd("100")
        val vm = HomeViewModel(assembler(portfolio = { Portfolio(cash = cash) }))

        vm.refresh()
        assertEquals(usd("100"), vm.state.value?.cash)

        cash = usd("200")
        vm.refresh()
        assertEquals(usd("200"), vm.state.value?.cash)
    }

    // MARK: - A whole-refresh failure (not a per-source one — the assembler already
    // isolates those) keeps the previously published state.

    @Test
    fun refreshFailureKeepsPreviousPublishedState() = runTest(dispatcher.scheduler) {
        var shouldFail = false
        val nowFn: () -> Long = { if (shouldFail) throw RuntimeException("boom") else 1_795_453_200L }
        val vm = HomeViewModel(assembler(portfolio = { Portfolio(cash = usd("500")) }, nowEpochSeconds = nowFn))

        vm.refresh()
        assertEquals(usd("500"), vm.state.value?.cash)

        shouldFail = true
        vm.refresh()

        assertEquals(usd("500"), vm.state.value?.cash, "a whole-refresh failure must not clear the prior state")
    }

    // MARK: - CancellationException rethrows out of refresh() rather than being swallowed

    @Test
    fun refreshRethrowsCancellationException() = runTest(dispatcher.scheduler) {
        val vm = HomeViewModel(assembler(nowEpochSeconds = { throw CancellationException("cancelled") }))

        assertFailsWith<CancellationException> { vm.refresh() }
    }

    // MARK: - start() fire-and-forget kicks the first refresh

    @Test
    fun startLaunchesInitialRefresh() = runTest(dispatcher.scheduler) {
        val vm = HomeViewModel(assembler(portfolio = { Portfolio(cash = usd("777")) }))
        assertNull(vm.state.value)

        vm.start()
        runCurrent()

        assertEquals(usd("777"), vm.state.value?.cash)
    }
}
