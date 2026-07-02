package com.aptrade.android.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.label
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.QuoteError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AssetRow(
    val symbol: String,
    val name: String,
    val kindLabel: String,
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<AssetRow> = emptyList(),
    val error: String? = null,
)

class SearchViewModel(
    private val fetchSearch: FetchSearch,
    private val debounceMillis: Long = 300,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state
    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            // Mirrors Swift's SearchAssetsUseCase: blank queries short-circuit locally.
            _state.update { it.copy(isSearching = false, results = emptyList(), error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(debounceMillis)
            _state.update { it.copy(isSearching = true, error = null) }
            try {
                val assets = fetchSearch.execute(trimmed)
                _state.update { state ->
                    state.copy(
                        isSearching = false,
                        results = assets.map { AssetRow(it.symbol, it.name, it.kind.label()) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isSearching = false, error = e.userMessage()) }
            }
        }
    }

    fun retry() = onQueryChange(_state.value.query)
}
