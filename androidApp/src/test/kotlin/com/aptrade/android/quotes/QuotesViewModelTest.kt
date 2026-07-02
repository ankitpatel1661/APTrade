package com.aptrade.android.quotes

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuotesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun quote(symbol: String, price: String, change: Double) =
        Quote(symbol, Money.usd(price), Money.usd(price), change)

    @Test
    fun loadsQuotesOnInit() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "229.35", 0.84), quote("SPY", "512.17", -0.21)) }
        val vm = QuotesViewModel(FetchMarketQuotes(repo), listOf("AAPL", "SPY"))

        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.isLoading)
        assertEquals(2, state.rows.size)
        assertEquals("AAPL", state.rows[0].symbol)
        assertEquals("$229.35", state.rows[0].priceText)
        assertEquals(0.84, state.rows[0].changePercent)
        assertNull(state.error)
    }

    @Test
    fun mapsQuoteErrorToErrorState() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { throw QuoteError.RateLimited }
        val vm = QuotesViewModel(FetchMarketQuotes(repo), listOf("AAPL"))

        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals(false, state.isLoading)
        assertNotNull(state.error)
        assertEquals(0, state.rows.size)
    }

    @Test
    fun refreshReplacesRows() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { listOf(quote("AAPL", "229.35", 0.84)) }
        val vm = QuotesViewModel(FetchMarketQuotes(repo), listOf("AAPL"))
        dispatcher.scheduler.advanceUntilIdle()

        repo.quotesImpl = { listOf(quote("AAPL", "231.47", 1.51)) }
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertEquals("$231.47", state.rows[0].priceText)
        assertEquals(false, state.isRefreshing)
    }
}
