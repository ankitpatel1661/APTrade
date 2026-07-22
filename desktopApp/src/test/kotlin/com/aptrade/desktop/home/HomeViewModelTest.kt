package com.aptrade.desktop.home

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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Transcribes the SEMANTICS this VM must pin per the M10.2 Task 2 brief — a thin desktop
 *  wrapper over the shared [HomeFeedAssembler]. `assembler` is a REAL [HomeFeedAssembler]
 *  built from fake suspend sources (its own commonTest suite, `HomeFeedAssemblerTest`,
 *  already pins every per-source isolation semantic — that's not re-tested here), rather
 *  than a mock: [HomeFeedAssembler] is a concrete class with no port interface to fake
 *  behind, and the assembler's own per-source `try/catch`es mean the ONLY way a
 *  whole-[HomeFeedAssembler.refresh] call fails is a throw from something no per-source
 *  guard wraps — [HomeFeedAssembler.marketStatusItem]'s call to `nowEpochSeconds()`, the
 *  very first line of `refresh()`, is exactly that seam, so a throwing `nowEpochSeconds`
 *  is this file's stand-in for "the whole call breaks." */
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

    // MARK: - refresh() publishes the assembler's fresh HomeState

    @Test
    fun refreshPublishesAssemblerState() = runTest {
        val vm = HomeViewModel(assembler(portfolio = { Portfolio(cash = usd("500")) }), backgroundScope)

        vm.refresh()

        assertEquals(usd("500"), vm.state.value?.cash)
    }

    @Test
    fun refreshOnSuccessAlwaysReplacesPreviousState() = runTest {
        var cash = usd("100")
        val vm = HomeViewModel(assembler(portfolio = { Portfolio(cash = cash) }), backgroundScope)

        vm.refresh()
        assertEquals(usd("100"), vm.state.value?.cash)

        cash = usd("200")
        vm.refresh()
        assertEquals(usd("200"), vm.state.value?.cash)
    }

    // MARK: - A whole-refresh failure (not a per-source one — the assembler already
    // isolates those) keeps the previously published state.

    @Test
    fun refreshFailureKeepsPreviousPublishedState() = runTest {
        var shouldFail = false
        val nowFn: () -> Long = { if (shouldFail) throw RuntimeException("boom") else 1_795_453_200L }
        val vm = HomeViewModel(
            assembler(portfolio = { Portfolio(cash = usd("500")) }, nowEpochSeconds = nowFn),
            backgroundScope,
        )

        vm.refresh()
        assertEquals(usd("500"), vm.state.value?.cash)

        shouldFail = true
        vm.refresh()

        assertEquals(usd("500"), vm.state.value?.cash, "a whole-refresh failure must not clear the prior state")
    }

    // MARK: - CancellationException rethrows out of refresh() rather than being swallowed

    @Test
    fun refreshRethrowsCancellationException() = runTest {
        val vm = HomeViewModel(
            assembler(nowEpochSeconds = { throw CancellationException("cancelled") }),
            backgroundScope,
        )

        assertFailsWith<CancellationException> { vm.refresh() }
    }

    // MARK: - start() fire-and-forget kicks the first refresh

    @Test
    fun startLaunchesInitialRefresh() = runTest {
        val vm = HomeViewModel(assembler(portfolio = { Portfolio(cash = usd("777")) }), backgroundScope)
        assertNull(vm.state.value)

        vm.start()
        runCurrent()

        assertEquals(usd("777"), vm.state.value?.cash)
    }
}
