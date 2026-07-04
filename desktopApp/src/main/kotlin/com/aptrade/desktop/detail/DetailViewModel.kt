package com.aptrade.desktop.detail

import com.aptrade.desktop.designkit.ChartCandle
import com.aptrade.desktop.designkit.kindLabel
import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ChartMode { Line, Candles }

data class DetailUiState(
    val symbol: String,
    val name: String? = null,
    val kindLabel: String? = null,
    val amountText: String? = null,
    val changePercent: Double? = null,
    val previousCloseText: String? = null,
    val profileError: String? = null,
    val timeframe: Timeframe = Timeframe.OneDay,
    val mode: ChartMode = ChartMode.Line,
    val lineValues: List<Double> = emptyList(),
    val candles: List<ChartCandle> = emptyList(),
    /** True while any indicator chip is toggled on. Drives the candle fetch in Line mode
     *  (indicators need OHLCV even when the price chart is drawn from line history). */
    val indicatorsActive: Boolean = false,
    val isLoadingChart: Boolean = true,
    val chartError: String? = null,
)

/** Per-selection asset detail: profile, quote, and the timeframe/mode chart load.
 *  `scope` MUST be single-thread-confined (Dispatchers.Main on desktop): the internal
 *  chartJob var and state updates rely on that confinement instead of locks. */
class DetailViewModel(
    private val symbol: String,
    private val fetchProfile: FetchProfile,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val fetchHistory: FetchHistory,
    private val fetchCandles: FetchCandles,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DetailUiState(symbol = symbol))
    val state: StateFlow<DetailUiState> = _state
    private var chartJob: Job? = null

    /** Timeframe the currently-held `candles` were fetched for, or null if none have been
     *  fetched yet. A plain timeframe change never refetches candles by itself (Line mode
     *  without indicators has no use for them), so this can silently go stale relative to
     *  `state.timeframe`; `candlesStale` below is what actually gates a refetch. */
    private var candlesTimeframe: Timeframe? = null
    private val candlesStale: Boolean
        get() = candlesTimeframe != _state.value.timeframe

    init {
        scope.launch {
            try {
                val asset = fetchProfile.execute(symbol)
                _state.update { it.copy(name = asset.name, kindLabel = kindLabel(asset.kind)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(profileError = e.userMessage()) }
            }
        }
        scope.launch {
            try {
                val quote = fetchMarketQuotes.execute(listOf(symbol)).firstOrNull() ?: return@launch
                _state.update {
                    it.copy(amountText = quote.price.amountText,
                        changePercent = quote.changePercent,
                        previousCloseText = quote.previousClose.amountText)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                // stat tiles stay empty; the chart error path covers messaging
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

    /** The indicator chips are local to the composable; it reports here whether any is on so
     *  the VM can fetch candles in Line mode (indicator math needs OHLCV). Only reloads when
     *  the flag actually flips, and never when Candles mode already has the bars in hand. */
    fun onIndicatorsActiveChange(active: Boolean) {
        if (_state.value.indicatorsActive == active) return
        _state.update { it.copy(indicatorsActive = active) }
        // Candles mode already fetches bars; Line mode only needs a (re)load when we now
        // require candles and either don't have any yet, or the ones we're holding were
        // fetched for a since-changed timeframe (e.g. deactivate -> setTimeframe -> reactivate,
        // which never refetches candles on its own and would otherwise leave them stale).
        if (_state.value.mode == ChartMode.Line && active && candlesStale) {
            loadChart()
        }
    }

    fun retryChart() = loadChart()

    private fun loadChart() {
        // Snapshot before launching so the coroutine renders the selection this call
        // was triggered for, even if state mutates before or while it runs.
        val timeframe = _state.value.timeframe
        val mode = _state.value.mode
        val needsCandles = mode == ChartMode.Candles || _state.value.indicatorsActive
        val shouldFetchCandles = needsCandles && candlesStale
        chartJob?.cancel()
        chartJob = scope.launch {
            _state.update { it.copy(isLoadingChart = true, chartError = null) }
            try {
                // In Line mode the price chart is drawn from history points; candles are
                // fetched additionally (same use case, no second data path) only to seed
                // indicator math when a chip is on.
                val lineValues = if (mode == ChartMode.Line) {
                    fetchHistory.execute(symbol, timeframe).map { p -> p.close.amount.doubleValue(false) }
                } else {
                    _state.value.lineValues
                }
                val candles = if (shouldFetchCandles) {
                    fetchCandles.execute(symbol, timeframe).map { c ->
                        ChartCandle(
                            c.open.amount.doubleValue(false), c.high.amount.doubleValue(false),
                            c.low.amount.doubleValue(false), c.close.amount.doubleValue(false),
                            c.volume,
                        )
                    }.also { candlesTimeframe = timeframe }
                } else {
                    _state.value.candles
                }
                _state.update {
                    it.copy(isLoadingChart = false, lineValues = lineValues, candles = candles)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isLoadingChart = false, chartError = e.userMessage()) }
            }
        }
    }
}
