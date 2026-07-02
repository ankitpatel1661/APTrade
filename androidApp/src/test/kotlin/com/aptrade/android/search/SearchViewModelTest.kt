package com.aptrade.android.search

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
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

class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun emptyOrBlankQueryNeverHitsRepository() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        val vm = SearchViewModel(FetchSearch(repo))

        vm.onQueryChange("")
        vm.onQueryChange("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, repo.searchCallCount)
        assertEquals(0, vm.state.value.results.size)
    }

    @Test
    fun rapidTypingIsDebouncedToASingleSearch() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { q -> listOf(Asset("AAPL", "Apple Inc. ($q)", AssetKind.Stock)) }
        val vm = SearchViewModel(FetchSearch(repo))

        vm.onQueryChange("a")
        dispatcher.scheduler.advanceTimeBy(100)
        vm.onQueryChange("ap")
        dispatcher.scheduler.advanceTimeBy(100)
        vm.onQueryChange("apple")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repo.searchCallCount)
        assertEquals("Apple Inc. (apple)", vm.state.value.results[0].name)
    }

    @Test
    fun mapsResultsWithKindLabels() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = {
            listOf(
                Asset("AAPL", "Apple Inc.", AssetKind.Stock),
                Asset("SPY", "SPDR S&P 500", AssetKind.Etf),
                Asset("BTC-USD", "Bitcoin", AssetKind.Crypto),
            )
        }
        val vm = SearchViewModel(FetchSearch(repo))

        vm.onQueryChange("a")
        dispatcher.scheduler.advanceUntilIdle()

        val rows = vm.state.value.results
        assertEquals(listOf("Stock", "ETF", "Crypto"), rows.map { it.kindLabel })
        assertEquals("AAPL", rows[0].symbol)
    }

    @Test
    fun mapsQuoteErrorToErrorState() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { throw QuoteError.Network("boom") }
        val vm = SearchViewModel(FetchSearch(repo))

        vm.onQueryChange("apple")
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.state.value.error)
        assertEquals(false, vm.state.value.isSearching)
    }
}
