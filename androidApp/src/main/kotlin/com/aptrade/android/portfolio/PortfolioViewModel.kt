package com.aptrade.android.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.formatPercent
import com.aptrade.android.ui.label
import com.aptrade.android.ui.money
import com.aptrade.android.ui.signedMoney
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPerformanceReport
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.AllocationSlice
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioExport
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeError
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.allocationByHolding
import com.aptrade.shared.domain.allocationByKind
import com.aptrade.shared.domain.realizedPnL
import com.aptrade.shared.domain.renderCsv
import com.aptrade.shared.domain.renderJson
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The portfolio P&L chart's time span. Mirrors the desktop `PortfolioSpan`: the asset-detail
 *  timeframes plus a **Max** option that runs since the portfolio's first purchase. */
enum class PortfolioSpan(val label: String) {
    Day("1D"), Week("1W"), Month("1M"), Year("1Y"), Max("MAX");

    val timeframe: Timeframe
        get() = when (this) {
            Day -> Timeframe.OneDay
            Week -> Timeframe.OneWeek
            Month -> Timeframe.OneMonth
            Year, Max -> Timeframe.OneYear
        }
}

/** A single holding row. Unlike the desktop UI (which drives a SuperscriptPrice from RAW
 *  `Money.amountText`), Android renders ONE pre-formatted display string per money field:
 *  `marketValueText`/`priceText`/`averageCostText` via `money`, `unrealizedText` via
 *  `signedMoney`. Render every text field verbatim. */
data class HoldingRowUi(
    val symbol: String,
    val name: String,
    val kindLabel: String,
    val quantityText: String,
    val averageCostText: String,
    val marketValueText: String,
    val unrealizedText: String,
    val unrealizedPositive: Boolean?,
    val priceText: String?,
)

data class TransactionRowUi(
    val id: String,
    val symbol: String,
    val sideLabel: String,
    val isBuy: Boolean,
    val quantityText: String,
    val priceText: String,
    val epochSeconds: Long,
    /** PRE-FORMATTED, display-only — en_US absolute "MMM d, uuuu, h:mm a". Never re-parse. */
    val dateText: String,
)

/** Percent metrics via [formatPercent] (e.g. "+4.84%"); Sharpe/beta/alpha are plain 2-decimal
 *  text ("—" when the underlying stat is null: insufficient data / degenerate variance). */
data class MetricTexts(
    val totalReturn: String,
    val annualizedReturn: String,
    val volatility: String,
    val maxDrawdown: String,
    val sharpe: String,
    val beta: String,
    val alpha: String,
)

private fun plainMetric(value: Double?): String =
    if (value == null) "—" else String.format(Locale.US, "%.2f", value)

/** All money/percent texts here are PRE-FORMATTED, display-only strings (Android has no
 *  SuperscriptPrice). Render verbatim; never re-parse. `dateText` (see [TransactionRowUi]) is
 *  likewise display-only. */
data class PortfolioUiState(
    val isLoading: Boolean = true,
    val totalValueText: String? = null,
    val dayChangeText: String? = null,
    val dayChangePositive: Boolean? = null,
    val cashText: String? = null,
    val holdingsValueText: String? = null,
    val unrealizedText: String? = null,
    val unrealizedPositive: Boolean? = null,
    val realizedText: String? = null,
    val realizedPositive: Boolean? = null,
    val holdings: List<HoldingRowUi> = emptyList(),
    val allocationByHolding: List<AllocationSlice> = emptyList(),
    val allocationByKind: List<AllocationSlice> = emptyList(),
    val transactions: List<TransactionRowUi> = emptyList(),
    val span: PortfolioSpan = PortfolioSpan.Month,
    val benchmark: String = "SPY",
    val benchmarks: List<String> = listOf("SPY", "QQQ", "VTI"),
    val performanceValues: List<Double> = emptyList(),
    val benchmarkTwinValues: List<Double>? = null,
    val metrics: MetricTexts? = null,
    val error: String? = null,
    val tradeError: String? = null,
)

private fun sideLabel(side: TradeSide): String = when (side) {
    TradeSide.Buy -> "Buy"
    TradeSide.Sell -> "Sell"
}

/** Accepts an optional leading '-', digits, and an optional '.' followed by 1-8 fraction
 *  digits — the leading '-' is matched so negative input is rejected explicitly (as ≤ 0)
 *  rather than falling through to "malformed". Mirrors the desktop TradeFormState regex. */
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

/** Owns the current paper-trading portfolio: valuation, allocation, trade execution, the
 *  performance chart, and export. Polls held symbols' quotes every [tickMillis] (the standard
 *  15s app-wide cadence) so open positions stay live while the screen is [start]ed. The
 *  internal `portfolio`/`quotes` vars rely on [viewModelScope]'s single-threaded confinement
 *  (Dispatchers.Main) instead of locks.
 *
 *  [notifyOrderFill] mirrors desktop's `notifyOrderFill` (spec A2 — desktop
 *  `AppGraphNotifyOrderFill`/`PortfolioViewModel.notifyFillSafely`): event-driven, fired only
 *  after a trade actually succeeds, gated upstream by `settings.orderFills`, and never allowed
 *  to fail the trade — CancellationException rethrows, everything else is swallowed. Defaults
 *  to a no-op so existing callers/tests that don't care about notifications keep compiling. */
class PortfolioViewModel(
    private val fetchPortfolio: FetchPortfolio,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val buyAsset: BuyAsset,
    private val sellAsset: SellAsset,
    private val resetPortfolio: ResetPortfolio,
    private val fetchPerformanceReport: FetchPerformanceReport,
    private val nowEpochSeconds: () -> Long,
    private val tickMillis: Long = 15_000,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notifyOrderFill: suspend (TradeSide, String, String, String) -> Unit = { _, _, _, _ -> },
) : ViewModel() {

    private val _state = MutableStateFlow(PortfolioUiState())
    val state: StateFlow<PortfolioUiState> = _state

    private var portfolio: Portfolio = Portfolio.starting()
    private var quotes: Map<String, Quote> = emptyMap()
    private var pollJob: Job? = null

    /** Starts the load + 15s quote poll. Idempotent: a second call while already running is a
     *  no-op. The performance report is a ONE-SHOT on tick 0 — never refetched on later ticks. */
    fun start() {
        if (pollJob != null) return
        pollJob = viewModelScope.launch {
            portfolio = fetchPortfolio.execute()
            var tick = 0
            while (isActive) {
                refreshQuotes()
                publish(loading = false)
                if (tick == 0) {
                    loadPerformanceReport()
                }
                tick++
                delay(tickMillis)
            }
        }
    }

    /** Stops the quote poll (screen left the foreground / lifecycle stop). [start] re-arms it. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh() {
        viewModelScope.launch { refreshQuotes(); publish(loading = false) }
    }

    fun setSpan(span: PortfolioSpan) {
        if (_state.value.span == span) return
        _state.update { it.copy(span = span) }
        loadPerformanceReport()
    }

    /** Switches the benchmark overlay (SPY/QQQ/VTI) and refetches the performance report — a
     *  one-shot fetch, same cadence discipline as span changes (never on a poll tick). */
    fun setBenchmark(symbol: String) {
        if (_state.value.benchmark == symbol) return
        _state.update { it.copy(benchmark = symbol) }
        loadPerformanceReport()
    }

    fun buy(asset: Asset, quantityText: String) {
        val quantity = parseQuantity(quantityText)
        if (quantity == null) {
            _state.update { it.copy(tradeError = TradeError.InvalidQuantity.userMessage()) }
            return
        }
        viewModelScope.launch {
            try {
                portfolio = buyAsset.execute(asset, quantity, nowEpochSeconds())
                refreshQuotes()
                _state.update { it.copy(tradeError = null) }
                publish(loading = false)
                notifyFillSafely(TradeSide.Buy, asset.symbol)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TradeError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            } catch (e: QuoteError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            }
        }
    }

    fun sell(symbol: String, quantityText: String) {
        val quantity = parseQuantity(quantityText)
        if (quantity == null) {
            _state.update { it.copy(tradeError = TradeError.InvalidQuantity.userMessage()) }
            return
        }
        viewModelScope.launch {
            try {
                portfolio = sellAsset.execute(symbol, quantity, nowEpochSeconds())
                refreshQuotes()
                _state.update { it.copy(tradeError = null) }
                publish(loading = false)
                notifyFillSafely(TradeSide.Sell, symbol)
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
     *  most recent one for `symbol`/`side` — `buy`/`sell` above just persisted it via
     *  `buyAsset`/`sellAsset`). A notifier failure must never surface as a trade error: this
     *  runs strictly after the trade's own state update, isolated in its own try/catch with
     *  CancellationException rethrown and everything else swallowed. Mirrors desktop
     *  PortfolioViewModel.notifyFillSafely exactly. */
    private suspend fun notifyFillSafely(side: TradeSide, symbol: String) {
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

    fun reset() {
        viewModelScope.launch {
            portfolio = resetPortfolio.execute()
            quotes = emptyMap()
            _state.update {
                it.copy(
                    performanceValues = emptyList(),
                    benchmarkTwinValues = null,
                    metrics = null,
                )
            }
            publish(loading = false)
        }
    }

    fun exportCsv(): String = exportSnapshot().renderCsv()

    fun exportJson(): String = exportSnapshot().renderJson()

    /** The current portfolio valued against the last-good quotes, as a [PortfolioExport]
     *  snapshot — the single source for CSV/JSON export. */
    fun exportSnapshot(): PortfolioExport =
        PortfolioExport.from(portfolio, quotes, "APTrade", nowEpochSeconds())

    /** Merges per-symbol instead of replacing wholesale: a poll returning a SUBSET of held
     *  symbols keeps the last-good quote for the missing ones (right-biased merge), rather than
     *  dropping them back to an averageCost-implied price. */
    private suspend fun refreshQuotes() {
        val symbols = portfolio.positions.map { it.asset.symbol }
        if (symbols.isEmpty()) { quotes = emptyMap(); return }
        try {
            val fetched = fetchMarketQuotes.execute(symbols)
            quotes = quotes + fetched.associateBy { it.symbol }
            _state.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuoteError) {
            _state.update { it.copy(error = e.userMessage()) }   // keep last-good quotes
        }
    }

    /** One-shot per span/benchmark change — NEVER refetched on a poll tick. Feeds the chart the
     *  two dollar-valued series (portfolio equity curve + the cash-flow-replay benchmark twin)
     *  as pixels-only Doubles, and renders the risk metrics to display text. The `portfolio`
     *  passed to `execute` is this VM's already-loaded copy (trades/reset persist before this
     *  runs, so it is coherent with disk at report time). */
    private fun loadPerformanceReport() {
        val span = _state.value.span
        val benchmark = _state.value.benchmark
        val portfolioSnapshot = portfolio
        viewModelScope.launch {
            try {
                val report = fetchPerformanceReport.execute(span.timeframe, benchmark, portfolioSnapshot)
                val metrics = MetricTexts(
                    totalReturn = formatPercent(report.metrics.totalReturn),
                    annualizedReturn = formatPercent(report.metrics.annualizedReturn),
                    volatility = formatPercent(report.metrics.volatility),
                    maxDrawdown = formatPercent(report.metrics.maxDrawdown),
                    sharpe = plainMetric(report.metrics.sharpe),
                    beta = plainMetric(report.metrics.beta),
                    alpha = plainMetric(report.metrics.alpha),
                )
                _state.update {
                    it.copy(
                        performanceValues = report.points.map { p -> p.value.amount.doubleValue(false) },
                        benchmarkTwinValues = report.benchmarkTwinValues
                            ?.map { m -> m.amount.doubleValue(false) },
                        metrics = metrics,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                // Portfolio-side history failure: leave prior report state as last-good.
            }
        }
    }

    private fun publish(loading: Boolean) {
        val valuation = portfolio.valuation(quotes)
        val holdingsUi = portfolio.positions
            .sortedByDescending { position ->
                val quote = quotes[position.asset.symbol]
                position.marketValue(quote?.price ?: position.averageCost).amount.doubleValue(false)
            }
            .map { position ->
                val quote = quotes[position.asset.symbol]
                val marketValue = position.marketValue(quote?.price ?: position.averageCost)
                val unrealized = position.unrealizedPnL(quote?.price ?: position.averageCost)
                HoldingRowUi(
                    symbol = position.asset.symbol,
                    name = position.asset.name,
                    kindLabel = position.asset.kind.label(),
                    quantityText = position.quantity.toStringExpanded(),
                    averageCostText = money(position.averageCost.amountText),
                    marketValueText = money(marketValue.amountText),
                    unrealizedText = signedMoney(unrealized.amountText),
                    unrealizedPositive = quote?.let { unrealized.amount.doubleValue(false) >= 0.0 },
                    priceText = quote?.price?.amountText?.let { money(it) },
                )
            }

        val realized = portfolio.realizedPnL
        val transactionDateFormatter = DateTimeFormatter.ofPattern("MMM d, uuuu, h:mm a", Locale.US)
        val transactionsUi = portfolio.transactions
            .sortedByDescending { it.epochSeconds }
            .map { txn ->
                TransactionRowUi(
                    id = txn.id,
                    symbol = txn.symbol,
                    sideLabel = sideLabel(txn.side),
                    isBuy = txn.side == TradeSide.Buy,
                    quantityText = txn.quantity.toStringExpanded(),
                    priceText = money(txn.price.amountText),
                    epochSeconds = txn.epochSeconds,
                    dateText = Instant.ofEpochSecond(txn.epochSeconds).atZone(zoneId).format(transactionDateFormatter),
                )
            }

        _state.update {
            it.copy(
                isLoading = loading,
                totalValueText = money(valuation.totalValue.amountText),
                dayChangeText = signedMoney(valuation.dayChange.amountText),
                dayChangePositive = if (portfolio.positions.isEmpty()) null else valuation.dayChange.amount.doubleValue(false) >= 0.0,
                cashText = money(valuation.cash.amountText),
                holdingsValueText = money(valuation.holdingsValue.amountText),
                unrealizedText = signedMoney(valuation.unrealizedPnL.amountText),
                unrealizedPositive = if (portfolio.positions.isEmpty()) null else valuation.unrealizedPnL.amount.doubleValue(false) >= 0.0,
                realizedText = signedMoney(realized.amountText),
                realizedPositive = if (portfolio.transactions.isEmpty()) null else realized.amount.doubleValue(false) >= 0.0,
                holdings = holdingsUi,
                allocationByHolding = portfolio.allocationByHolding(quotes),
                allocationByKind = portfolio.allocationByKind(quotes),
                transactions = transactionsUi,
            )
        }
    }
}
