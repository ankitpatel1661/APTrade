package com.aptrade.desktop.search

import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Asset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<Asset> = emptyList(),
    val selectedIndex: Int = 0,
    val error: String? = null,
)

/** Ctrl+K palette state: 300ms debounce, blank queries short-circuit locally,
 *  keyboard selection clamped to the result range. */
class SearchViewModel(
    private val fetchSearch: FetchSearch,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 300,
) {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state
    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(isSearching = false, results = emptyList(), selectedIndex = 0, error = null) }
            return
        }
        searchJob = scope.launch {
            delay(debounceMillis)
            _state.update { it.copy(isSearching = true, error = null) }
            try {
                val assets = fetchSearch.execute(trimmed)
                _state.update { it.copy(isSearching = false, results = assets, selectedIndex = 0) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isSearching = false, error = e.userMessage()) }
            }
        }
    }

    fun moveSelection(delta: Int) = _state.update {
        val last = (it.results.size - 1).coerceAtLeast(0)
        it.copy(selectedIndex = (it.selectedIndex + delta).coerceIn(0, last))
    }

    fun selectedAsset(): Asset? = _state.value.results.getOrNull(_state.value.selectedIndex)

    fun reset() {
        searchJob?.cancel()
        _state.value = SearchUiState()
    }
}
