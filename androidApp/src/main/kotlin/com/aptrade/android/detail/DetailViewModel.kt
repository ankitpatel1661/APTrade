package com.aptrade.android.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.label
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChartMode { Line, Candles }

data class CandleBar(val open: Double, val high: Double, val low: Double, val close: Double)

data class DetailUiState(
    val symbol: String,
    val name: String? = null,
    val kindLabel: String? = null,
    val profileError: String? = null,
    val timeframe: Timeframe = Timeframe.OneDay,
    val mode: ChartMode = ChartMode.Line,
    val lineValues: List<Double> = emptyList(),
    val candles: List<CandleBar> = emptyList(),
    val isLoadingChart: Boolean = true,
    val chartError: String? = null,
)

class DetailViewModel(
    private val symbol: String,
    private val fetchProfile: FetchProfile,
    private val fetchHistory: FetchHistory,
    private val fetchCandles: FetchCandles,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState(symbol = symbol))
    val state: StateFlow<DetailUiState> = _state
    private var chartJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val asset = fetchProfile.execute(symbol)
                _state.update { it.copy(name = asset.name, kindLabel = asset.kind.label()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(profileError = e.userMessage()) }
            }
        }
        loadChart()
    }

    fun onTimeframeChange(timeframe: Timeframe) {
        _state.update { it.copy(timeframe = timeframe) }
        loadChart()
    }

    fun onModeChange(mode: ChartMode) {
        _state.update { it.copy(mode = mode) }
        loadChart()
    }

    fun retryChart() = loadChart()

    private fun loadChart() {
        chartJob?.cancel()
        chartJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingChart = true, chartError = null) }
            try {
                when (_state.value.mode) {
                    ChartMode.Line -> {
                        val points = fetchHistory.execute(symbol, _state.value.timeframe)
                        _state.update { state ->
                            state.copy(
                                isLoadingChart = false,
                                // Pixel math only — money display always goes through
                                // Money.formatted/amountText, never this Double.
                                lineValues = points.map { it.close.amount.doubleValue(false) },
                            )
                        }
                    }
                    ChartMode.Candles -> {
                        val candles = fetchCandles.execute(symbol, _state.value.timeframe)
                        _state.update { state ->
                            state.copy(
                                isLoadingChart = false,
                                candles = candles.map {
                                    CandleBar(
                                        it.open.amount.doubleValue(false),
                                        it.high.amount.doubleValue(false),
                                        it.low.amount.doubleValue(false),
                                        it.close.amount.doubleValue(false),
                                    )
                                },
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isLoadingChart = false, chartError = e.userMessage()) }
            }
        }
    }
}
