package com.aptrade.android.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.label
import com.aptrade.android.ui.money
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchCandles
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeError
import com.aptrade.shared.domain.TradeSide
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
    /** True once the profile request has RESOLVED — success or error, doesn't matter which.
     *  Gates the BUY/SELL entry point so a trade can never fire while [kindLabel] is still
     *  unset (the window in which a crypto/ETF asset would be misclassified as Stock). */
    val profileResolved: Boolean = false,
    /** Pre-formatted live price for the TradeSheet's AssistChip (≡ portfolio holding row's
     *  `priceText`, via [com.aptrade.android.ui.money] over `Quote.price.amountText`). Fetched
     *  in its own isolated coroutine; stays null on failure — this NEVER gates trading. */
    val priceText: String? = null,
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

/** [notifyOrderFill] mirrors [com.aptrade.android.portfolio.PortfolioViewModel]'s own
 *  notifyOrderFill param (spec A2 — desktop `AppGraphNotifyOrderFill` pattern): event-driven,
 *  fired only after a trade actually succeeds, gated upstream by `settings.orderFills`, and
 *  never allowed to fail the trade. Defaults to a no-op so existing callers/tests that don't
 *  care about notifications keep compiling. This screen's own store-mediated `buy`/`sell` is a
 *  second, independent trade-execution path from the Portfolio screen's `PortfolioViewModel`
 *  — Android, unlike desktop's single shared PortfolioViewModel instance, has two real trade
 *  call sites — so it needs its own [notifyOrderFill] wiring rather than sharing one closure
 *  instance end-to-end. */
class DetailViewModel(
    private val symbol: String,
    private val fetchProfile: FetchProfile,
    private val fetchHistory: FetchHistory,
    private val fetchCandles: FetchCandles,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val buyAsset: BuyAsset,
    private val sellAsset: SellAsset,
    private val nowEpochSeconds: () -> Long,
    private val notifyOrderFill: suspend (TradeSide, String, String, String) -> Unit = { _, _, _, _ -> },
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState(symbol = symbol))
    val state: StateFlow<DetailUiState> = _state
    private var chartJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val asset = fetchProfile.execute(symbol)
                _state.update {
                    it.copy(name = asset.name, kindLabel = asset.kind.label(), profileResolved = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(profileError = e.userMessage(), profileResolved = true) }
            }
        }
        // Isolated from the profile/chart coroutines: a quote failure must never disable
        // trading, so it is swallowed silently here (macOS parity) — priceText simply stays null.
        viewModelScope.launch {
            try {
                val quote = fetchMarketQuotes.execute(listOf(symbol)).firstOrNull { it.symbol == symbol }
                if (quote != null) {
                    _state.update { it.copy(priceText = money(quote.price.amountText)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                // Silent: priceText stays null, trading remains available.
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
     *  Falls back to `Asset(symbol, symbol, Stock)` ONLY when the profile resolved with an ERROR
     *  (kindLabel genuinely absent) — the UI gates BUY/SELL entry on [DetailUiState.profileResolved]
     *  so this is never reached while the profile fetch is still in flight; a successfully-loaded
     *  crypto or ETF asset is therefore never misclassified as Stock. */
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
                notifyFillSafely(portfolio, TradeSide.Buy)
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
                notifyFillSafely(portfolio, TradeSide.Sell)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TradeError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            } catch (e: QuoteError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            }
        }
    }

    /** Fires the order-fill notification for the just-completed trade's own transaction (the
     *  most recent one for [symbol]/`side` on the just-returned, already-persisted
     *  [com.aptrade.shared.domain.Portfolio]). A notifier failure must never surface as a trade
     *  error: isolated in its own try/catch with CancellationException rethrown and everything
     *  else swallowed. Mirrors PortfolioViewModel.notifyFillSafely exactly. */
    private suspend fun notifyFillSafely(portfolio: Portfolio, side: TradeSide) {
        val txn = portfolio.transactions.lastOrNull { it.symbol == symbol && it.side == side } ?: return
        try {
            val amountText = (txn.price.amount * txn.quantity).toStringExpanded()
            notifyOrderFill(side, symbol, txn.quantity.toStringExpanded(), money(amountText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Notification delivery is best-effort — never let it fail a completed trade.
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
