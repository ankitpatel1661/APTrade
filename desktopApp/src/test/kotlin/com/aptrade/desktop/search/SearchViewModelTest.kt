package com.aptrade.desktop.search

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SearchViewModelTest {
    private val apple = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
    private val ampl = Asset("AMPL", "Amplitude Inc.", AssetKind.Stock)

    @Test
    fun debouncesAndSearches() = runTest {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { listOf(apple, ampl) }
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("app")
        advanceTimeBy(299); runCurrent()
        assertEquals(0, repo.searchCallCount)          // still inside the debounce window
        advanceTimeBy(2); runCurrent()
        assertEquals(1, repo.searchCallCount)
        assertEquals(listOf(apple, ampl), vm.state.value.results)
    }

    @Test
    fun retypingRestartsDebounceWithoutExtraCalls() = runTest {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { listOf(apple) }
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("a"); advanceTimeBy(200)
        vm.onQueryChange("ap"); advanceTimeBy(200)
        vm.onQueryChange("app"); advanceTimeBy(301); runCurrent()
        assertEquals(1, repo.searchCallCount)          // only the final query hit the network
    }

    @Test
    fun blankQueryClearsWithoutNetworkCall() = runTest {
        val repo = FakeMarketDataRepository()
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("   "); advanceTimeBy(301); runCurrent()
        assertEquals(0, repo.searchCallCount)
        assertEquals(emptyList(), vm.state.value.results)
    }

    @Test
    fun arrowSelectionClampsAndActivates() = runTest {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { listOf(apple, ampl) }
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("app"); advanceTimeBy(301); runCurrent()
        assertEquals(0, vm.state.value.selectedIndex)
        vm.moveSelection(1)
        assertEquals(1, vm.state.value.selectedIndex)
        vm.moveSelection(1)
        assertEquals(1, vm.state.value.selectedIndex)  // clamped at last result
        assertEquals(ampl, vm.selectedAsset())
        vm.moveSelection(-5)
        assertEquals(0, vm.state.value.selectedIndex)  // clamped at first
    }

    @Test
    fun errorSurfacesAndResetClears() = runTest {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { throw QuoteError.RateLimited }
        val vm = SearchViewModel(FetchSearch(repo), backgroundScope)
        vm.onQueryChange("app"); advanceTimeBy(301); runCurrent()
        assertNotNull(vm.state.value.error)
        vm.reset()
        assertEquals(SearchUiState(), vm.state.value)
        assertNull(vm.state.value.error)
    }
}
