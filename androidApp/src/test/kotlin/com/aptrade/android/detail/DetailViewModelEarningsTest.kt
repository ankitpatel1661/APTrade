package com.aptrade.android.detail

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.android.FakePortfolioStore
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.EarningsCalendarRepository
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun event(symbol: String, day: String) =
    EarningsEvent(symbol = symbol, companyName = "$symbol Inc.", day = day, session = EarningsSession.AfterClose, epsEstimate = 1.5, epsActual = null)

/** Records nothing beyond the fixed [events]/[error] it was built with — mirrors
 *  desktopApp/src/test/.../detail/DetailViewModelEarningsTest.kt's fake, package-renamed. */
private class FakeEarningsCalendarRepository(
    private val events: List<EarningsEvent> = emptyList(),
    private val error: Throwable? = null,
) : EarningsCalendarRepository {
    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> {
        error?.let { throw it }
        return events
    }
}

/** Android counterpart to desktop's `DetailViewModelEarningsTest` (Task 7/9): the Next-earnings
 *  KEY STATS row is fed by an isolated init coroutine on [DetailViewModel] (Task 9, Step 5) that
 *  never lets a missing/errored earnings source crash construction or block the chart/trade
 *  paths — same silent-failure contract as `priceText`. */
class DetailViewModelEarningsTest {
    private val dispatcher = StandardTestDispatcher()

    // Monday 2026-11-23 local epoch day: same reference instant CalendarViewModelTest /
    // desktop's DetailViewModelEarningsTest use.
    private val nowEpochSeconds = 1_795_453_200L

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(
        symbol: String = "AAPL",
        repo: FakeMarketDataRepository = FakeMarketDataRepository(),
        earningsRepo: EarningsCalendarRepository = FakeEarningsCalendarRepository(),
        store: FakePortfolioStore = FakePortfolioStore(),
    ) = DetailViewModel(
        symbol = symbol,
        fetchProfile = FetchProfile(repo),
        fetchHistory = FetchHistory(repo),
        fetchChartWindow = FetchChartWindow(repo),
        fetchMarketQuotes = FetchMarketQuotes(repo),
        buyAsset = BuyAsset(repo, store, Mutex()),
        sellAsset = SellAsset(repo, store, Mutex()),
        nowEpochSeconds = { nowEpochSeconds },
        fetchEarnings = FetchEarningsCalendar(earningsRepo) { emptySet() },
    )

    @Test
    fun nextEarningsPopulatesFromFakeRepo() = runTest(dispatcher.scheduler) {
        val earningsRepo = FakeEarningsCalendarRepository(
            events = listOf(event("AAPL", "2026-11-24"), event("AAPL", "2026-12-20")),
        )
        val viewModel = vm(earningsRepo = earningsRepo)
        dispatcher.scheduler.advanceUntilIdle()

        // Earliest of the two AAPL events within the 30-day window wins.
        assertEquals("2026-11-24", viewModel.state.value.nextEarnings?.day)
    }

    @Test
    fun failingRepoDegradesToNullWithoutCrashing() = runTest(dispatcher.scheduler) {
        val earningsRepo = FakeEarningsCalendarRepository(error = QuoteError.Network("boom"))
        val viewModel = vm(earningsRepo = earningsRepo)
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.state.value.nextEarnings)
    }
}
