package com.aptrade.android.quotes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.QuoteError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuoteRow(
    val symbol: String,
    val priceText: String,
    val changePercent: Double,
)

data class QuotesUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val rows: List<QuoteRow> = emptyList(),
    val error: String? = null,
)

class QuotesViewModel(
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val symbols: List<String>,
) : ViewModel() {

    private val _state = MutableStateFlow(QuotesUiState(isLoading = true))
    val state: StateFlow<QuotesUiState> = _state

    init {
        load(initial = true)
    }

    fun refresh() = load(initial = false)

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = initial, isRefreshing = !initial, error = null) }
            try {
                val quotes = fetchMarketQuotes.execute(symbols)
                _state.update { state ->
                    state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        rows = quotes.map { QuoteRow(it.symbol, it.price.formatted, it.changePercent) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isLoading = false, isRefreshing = false, error = e.userMessage()) }
            }
        }
    }
}
