package com.aptrade.android.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.label
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeError
import com.ionspin.kotlin.bignum.decimal.BigDecimal
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
    /** Inline BUY/SELL failure text for the TradeSheet; null while no trade error is showing. */
    val tradeError: String? = null,
    /** Count of transactions in the portfolio after the last successful trade from this screen.
     *  Monotonic; the TradeSheet snapshots it on open and dismisses when it grows. */
    val transactionCount: Int = 0,
)

/** Accepts an optional leading '-', digits, and an optional '.' followed by 1-8 fraction digits.
 *  Mirrors the desktop TradeFormState / PortfolioViewModel regex; the leading '-' is matched so
 *  negative input is rejected explicitly (as <= 0) rather than falling through to "malformed". */
private val QUANTITY_PATTERN = Regex("""-?\d+(\.\d{1,8})?""")

private fun parseQuantity(text: String): BigDecimal? {
    val trimmed = text.trim()
    if (!QUANTITY_PATTERN.matches(trimmed)) return null
    val value = try {
        BigDecimal.parseString(trimmed)
    } catch (e: ArithmeticException) {
        return null
    } catch (e: NumberFormatException) {
        return null
    }
    if (value.isZero() || value.isNegative) return null
    return value
}

class DetailViewModel(
    private val symbol: String,
    private val fetchProfile: FetchProfile,
    private val fetchHistory: FetchHistory,
    private val fetchCandles: FetchCandles,
    private val buyAsset: BuyAsset,
    private val sellAsset: SellAsset,
    private val nowEpochSeconds: () -> Long,
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

    /** The [Asset] to buy: this screen's [symbol] plus the name/kind loaded by the profile fetch.
     *  Falls back to `Asset(symbol, symbol, Stock)` ONLY when the profile hasn't resolved (name
     *  genuinely absent — e.g. a profile error) so a BUY is never blocked on the header load. */
    private fun tradeAsset(): Asset {
        val s = _state.value
        val kind = when (s.kindLabel) {
            "ETF" -> AssetKind.Etf
            "Crypto" -> AssetKind.Crypto
            else -> AssetKind.Stock
        }
        return Asset(symbol = symbol, name = s.name ?: symbol, kind = kind)
    }

    /** Store-mediated BUY: [buyAsset] quote-firsts, loads the portfolio fresh from disk, appends,
     *  and saves — so the trade is durable and visible to the Portfolio screen on return. On
     *  success we clear [tradeError] and bump [transactionCount] from the returned portfolio so
     *  the TradeSheet dismisses; on failure the count is unchanged and the sheet shows the error.
     *  CancellationException-first; mirrors PortfolioViewModel.buy. */
    fun buy(quantityText: String) {
        val quantity = parseQuantity(quantityText)
        if (quantity == null) {
            _state.update { it.copy(tradeError = TradeError.InvalidQuantity.userMessage()) }
            return
        }
        val asset = tradeAsset()
        viewModelScope.launch {
            try {
                val portfolio = buyAsset.execute(asset, quantity, nowEpochSeconds())
                _state.update { it.copy(tradeError = null, transactionCount = portfolio.transactions.size) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TradeError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            } catch (e: QuoteError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            }
        }
    }

    /** Store-mediated SELL of this screen's [symbol]. Same success/error contract as [buy]. */
    fun sell(quantityText: String) {
        val quantity = parseQuantity(quantityText)
        if (quantity == null) {
            _state.update { it.copy(tradeError = TradeError.InvalidQuantity.userMessage()) }
            return
        }
        viewModelScope.launch {
            try {
                val portfolio = sellAsset.execute(symbol, quantity, nowEpochSeconds())
                _state.update { it.copy(tradeError = null, transactionCount = portfolio.transactions.size) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TradeError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            } catch (e: QuoteError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            }
        }
    }

    private fun loadChart() {
        // Snapshot before launching so the coroutine renders the selection this call
        // was triggered for, even if state mutates before or while it runs.
        val timeframe = _state.value.timeframe
        val mode = _state.value.mode
        chartJob?.cancel()
        chartJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingChart = true, chartError = null) }
            try {
                when (mode) {
                    ChartMode.Line -> {
                        val points = fetchHistory.execute(symbol, timeframe)
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
                        val candles = fetchCandles.execute(symbol, timeframe)
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
