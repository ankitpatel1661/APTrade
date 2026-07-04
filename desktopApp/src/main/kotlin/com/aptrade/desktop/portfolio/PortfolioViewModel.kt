package com.aptrade.desktop.portfolio

import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.designkit.kindLabel
import com.aptrade.desktop.designkit.signedMoney
import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPerformanceReport
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.FetchPortfolioPerformance
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AllocationSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioExport
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.RiskMetrics
import com.aptrade.shared.domain.TradeError
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.allocationByHolding
import com.aptrade.shared.domain.allocationByKind
import com.aptrade.shared.domain.realizedPnL
import com.aptrade.shared.domain.renderCsv
import com.aptrade.shared.domain.renderJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** The portfolio P&L chart's time span. Mirrors the asset-detail timeframes but adds a
 *  **Max** option that runs since the portfolio's first purchase. */
enum class PortfolioSpan(val label: String) {
    Day("1D"), Week("1W"), Month("1M"), Year("1Y"), Max("MAX");

    val timeframe: Timeframe
        get() = when (this) {
            Day -> Timeframe.OneDay
            Week -> Timeframe.OneWeek
            Month -> Timeframe.OneMonth
            Year, Max -> Timeframe.OneYear
        }

    /** Max trims the curve to begin at the first transaction (the portfolio's inception). */
    val sinceInception: Boolean get() = this == Max
}

/** Per-field text contract (Task 7/8 implementers: do NOT re-format on either side):
 *  - `marketValueText` and `priceText` are RAW `Money.amountText` — `marketValueText` feeds
 *    `SuperscriptPrice`/`splitPrice` (which performs its own "$" + grouping + cents split),
 *    and `priceText` is handed to `TradeDialog`, which BOTH re-parses it (`Money.usd`) and
 *    renders it via `SuperscriptPrice`. Pre-formatting these breaks parsing and garbles the
 *    render.
 *  - `averageCostText` (plain, `formatMoney`) and `unrealizedText` (signed, `signedMoney`)
 *    are PRE-FORMATTED — render verbatim. */
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
)

/** Percent metrics via `formatPercent` (e.g. "+4.84%", "—" only if a percent field were
 *  nullable — currently all four are always computable, even if 0.0). Sharpe/beta/alpha are
 *  plain 2-decimal text (no "%", no sign forcing) and "—" when the underlying stat is null
 *  (insufficient data / degenerate variance — see RiskMetrics). */
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

/** Per-field money-text contract (Task 7/8 implementers: do NOT re-format on either side):
 *  - `totalValueText` is RAW `Money.amountText` — it feeds `SuperscriptPrice`/`splitPrice`
 *    in PortfolioPane's header, which performs its own "$" + grouping + cents split.
 *  - Every other money text is PRE-FORMATTED and rendered verbatim: `cashText` and
 *    `holdingsValueText` via `formatMoney`; `dayChangeText`, `unrealizedText`, and
 *    `realizedText` via `signedMoney` ("+" when strictly positive).
 *  See `HoldingRowUi` for the per-row contract. */
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
    val performance: List<Double> = emptyList(),
    val isLoadingPerformance: Boolean = false,
    val span: PortfolioSpan = PortfolioSpan.Month,
    val benchmark: String = "SPY",
    val benchmarks: List<String> = listOf("SPY", "QQQ", "VTI"),
    val performanceRebased: List<Double> = emptyList(),
    val benchmarkRebased: List<Double>? = null,
    val metrics: MetricTexts? = null,
    val error: String? = null,
    val tradeError: String? = null,
)

private fun sideLabel(side: TradeSide): String = when (side) {
    TradeSide.Buy -> "Buy"
    TradeSide.Sell -> "Sell"
}

/** Owns the current paper-trading portfolio: valuation, allocation, trade execution, the
 *  performance chart, and export. Polls held symbols' quotes every `tickMillis` (the
 *  standard 15s app-wide cadence) so open positions stay live. `scope` MUST be
 *  single-thread-confined (Dispatchers.Main on desktop): the internal portfolio/quotes vars
 *  rely on that confinement instead of locks. */
class PortfolioViewModel(
    private val fetchPortfolio: FetchPortfolio,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val buyAsset: BuyAsset,
    private val sellAsset: SellAsset,
    private val resetPortfolio: ResetPortfolio,
    private val fetchPortfolioPerformance: FetchPortfolioPerformance,
    private val fetchPerformanceReport: FetchPerformanceReport,
    private val scope: CoroutineScope,
    private val tickMillis: Long = 15_000,
    private val nowEpochSeconds: () -> Long,
) {
    private val _state = MutableStateFlow(PortfolioUiState())
    val state: StateFlow<PortfolioUiState> = _state

    private var portfolio: Portfolio = Portfolio.starting()
    private var quotes: Map<String, Quote> = emptyMap()
    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            portfolio = fetchPortfolio.execute()
            var tick = 0
            while (isActive) {
                refreshQuotes()
                publish(loading = false)
                if (tick == 0) {
                    loadPerformance()
                    loadPerformanceReport()
                }
                tick++
                delay(tickMillis)
            }
        }
    }

    fun refresh() {
        scope.launch { refreshQuotes(); publish(loading = false) }
    }

    fun setSpan(span: PortfolioSpan) {
        if (_state.value.span == span) return
        _state.update { it.copy(span = span) }
        loadPerformance()
        loadPerformanceReport()
    }

    /** Switches the benchmark overlay (SPY/QQQ/VTI) and refetches the performance report —
     *  a one-shot fetch, same cadence discipline as span changes (never on a poll tick). */
    fun setBenchmark(symbol: String) {
        if (_state.value.benchmark == symbol) return
        _state.update { it.copy(benchmark = symbol) }
        loadPerformanceReport()
    }

    fun buy(asset: Asset, quantityText: String) {
        val form = TradeFormState(TradeSide.Buy, quotes[asset.symbol]?.price?.amountText, quantityText)
        val quantity = form.parsedQuantity()
        if (quantity == null) {
            _state.update { it.copy(tradeError = TradeError.InvalidQuantity.userMessage()) }
            return
        }
        scope.launch {
            try {
                portfolio = buyAsset.execute(asset, quantity, nowEpochSeconds())
                refreshQuotes()
                _state.update { it.copy(tradeError = null) }
                publish(loading = false)
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
        val form = TradeFormState(TradeSide.Sell, quotes[symbol]?.price?.amountText, quantityText)
        val quantity = form.parsedQuantity()
        if (quantity == null) {
            _state.update { it.copy(tradeError = TradeError.InvalidQuantity.userMessage()) }
            return
        }
        scope.launch {
            try {
                portfolio = sellAsset.execute(symbol, quantity, nowEpochSeconds())
                refreshQuotes()
                _state.update { it.copy(tradeError = null) }
                publish(loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TradeError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            } catch (e: QuoteError) {
                _state.update { it.copy(tradeError = e.userMessage()) }
            }
        }
    }

    fun reset() {
        scope.launch {
            portfolio = resetPortfolio.execute()
            quotes = emptyMap()
            _state.update {
                it.copy(
                    performance = emptyList(),
                    performanceRebased = emptyList(),
                    benchmarkRebased = null,
                    metrics = null,
                )
            }
            publish(loading = false)
        }
    }

    fun exportCsv(): String = exportSnapshot().renderCsv()

    fun exportJson(): String = exportSnapshot().renderJson()

    /** The current portfolio valued against the last-good quotes, as a [PortfolioExport]
     *  snapshot. The single source for all three export formats: CSV/JSON render it to text
     *  (above), and the desktop host hands it to `renderPortfolioPdf` for the PDF path. */
    fun exportSnapshot(): PortfolioExport =
        PortfolioExport.from(portfolio, quotes, "APTrade", nowEpochSeconds())

    /** Merges per-symbol instead of replacing wholesale: a poll that returns a SUBSET of held
     *  symbols (e.g. a transient upstream omission) keeps the last-good quote for the symbols
     *  missing from this tick, rather than dropping them back to an averageCost-implied price. */
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

    private fun loadPerformance() {
        val span = _state.value.span
        scope.launch {
            _state.update { it.copy(isLoadingPerformance = true) }
            val points = fetchPortfolioPerformance.execute(span.timeframe, span.sinceInception)
            _state.update { it.copy(isLoadingPerformance = false, performance = points.map { p -> p.value.amount.doubleValue(false) }) }
        }
    }

    /** One-shot per span/benchmark change (mirrors `loadPerformance`'s cadence discipline) —
     *  NEVER refetched on a poll tick. Rebases the portfolio curve and (when available) the
     *  benchmark curve to a common 100-basis start so they're directly comparable on the
     *  overlay chart, and renders the risk metrics to display text (percent fields via
     *  `formatPercent`; sharpe/beta/alpha as plain 2-decimal text, "—" when null). */
    private fun loadPerformanceReport() {
        val span = _state.value.span
        val benchmark = _state.value.benchmark
        scope.launch {
            try {
                val report = fetchPerformanceReport.execute(span.timeframe, benchmark)
                val values = report.points.map { it.value.amount.doubleValue(false) }
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
                        performanceRebased = RiskMetrics.rebase(values),
                        benchmarkRebased = report.benchmarkCloses?.let { closes -> RiskMetrics.rebase(closes) },
                        metrics = metrics,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                // Portfolio-side history failure (distinct from the benchmark-only swallow
                // inside FetchPerformanceReport): leave prior report state as last-good.
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
                    kindLabel = kindLabel(position.asset.kind),
                    quantityText = position.quantity.toStringExpanded(),
                    averageCostText = formatMoney(position.averageCost.amountText),
                    marketValueText = marketValue.amountText,          // RAW — SuperscriptPrice consumer
                    unrealizedText = signedMoney(unrealized.amountText),
                    unrealizedPositive = quote?.let { unrealized.amount.doubleValue(false) >= 0.0 },
                    priceText = quote?.price?.amountText,              // RAW — TradeDialog re-parses + SuperscriptPrice
                )
            }

        val realized = portfolio.realizedPnL
        val transactionsUi = portfolio.transactions
            .sortedByDescending { it.epochSeconds }
            .map { txn ->
                TransactionRowUi(
                    id = txn.id,
                    symbol = txn.symbol,
                    sideLabel = sideLabel(txn.side),
                    isBuy = txn.side == TradeSide.Buy,
                    quantityText = txn.quantity.toStringExpanded(),
                    priceText = formatMoney(txn.price.amountText),
                    epochSeconds = txn.epochSeconds,
                )
            }

        _state.update {
            it.copy(
                isLoading = loading,
                totalValueText = valuation.totalValue.amountText,      // RAW — SuperscriptPrice consumer
                dayChangeText = signedMoney(valuation.dayChange.amountText),
                dayChangePositive = if (portfolio.positions.isEmpty()) null else valuation.dayChange.amount.doubleValue(false) >= 0.0,
                cashText = formatMoney(valuation.cash.amountText),
                holdingsValueText = formatMoney(valuation.holdingsValue.amountText),
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
