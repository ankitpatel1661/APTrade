package com.aptrade.desktop.portfolio

import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.designkit.signedMoney
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.l10n.L10n
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPerformanceReport
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.AllocationSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioExport
import com.aptrade.shared.domain.PortfolioPerformancePoint
import com.aptrade.shared.domain.Quote
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val kind: AssetKind,
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

/** One point on the P&L chart scrubber, derived from the same `PerformanceReport`/`points`
 *  list that feeds `performanceValues`. `valueText` and `deltaText` are PRE-FORMATTED,
 *  display-only strings (same contract as the rest of this file) — NEVER feed them into
 *  `splitPrice`/`SuperscriptPrice`/`Money.usd` or any other numeric parsing. */
data class PerfPointUi(
    val epochSeconds: Long,
    val valueText: String,
    val deltaText: String,
    val isUp: Boolean,
    val tooltipDateText: String,
)

/** Per-field money-text contract (Task 7/8 implementers: do NOT re-format on either side):
 *  - `totalValueText` is RAW `Money.amountText` — it feeds `SuperscriptPrice`/`splitPrice`
 *    in PortfolioPane's header, which performs its own "$" + grouping + cents split.
 *  - Every other money text is PRE-FORMATTED and rendered verbatim: `cashText` and
 *    `holdingsValueText` via `formatMoney`; `dayChangeText`, `unrealizedText`, and
 *    `realizedText` via `signedMoney` ("+" when strictly positive).
 *  - `performancePoints`' `valueText`/`deltaText` (see [PerfPointUi]) and `transactions`'
 *    `dateText` (see [TransactionRowUi]) are likewise PRE-FORMATTED, display-only strings —
 *    NEVER feed them into `splitPrice`/`SuperscriptPrice`/`Money.usd` or any numeric parsing.
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
    val span: PortfolioSpan = PortfolioSpan.Month,
    val benchmark: String = "SPY",
    val benchmarks: List<String> = listOf("SPY", "QQQ", "VTI"),
    val performanceValues: List<Double> = emptyList(),
    val performancePoints: List<PerfPointUi> = emptyList(),
    val benchmarkTwinValues: List<Double>? = null,
    val metrics: MetricTexts? = null,
    val error: String? = null,
    val tradeError: String? = null,
)

// Buy/Sell stay hardcoded English pending a broader retrofit (pre-existing since the 6e
// wave, and out of scope for M8.2 Task 5); Dividend is wired to the localized chip key here
// since M8.2 Task 5 adds it, so at least the newest side doesn't ship as English-only.
private fun sideLabel(side: TradeSide): String = when (side) {
    TradeSide.Buy -> "Buy"
    TradeSide.Sell -> "Sell"
    TradeSide.Dividend -> tr(L10n.Key.ActivityDividend)
}

/** Owns the current paper-trading portfolio: valuation, allocation, trade execution, the
 *  performance chart, and export. Polls held symbols' quotes every `tickMillis` (the
 *  standard 15s app-wide cadence) so open positions stay live. `scope` MUST be
 *  single-thread-confined (Dispatchers.Main on desktop): the internal portfolio/quotes vars
 *  rely on that confinement instead of locks.
 *
 *  [notifyOrderFill] mirrors macOS's `NotifyOrderFillUseCase`
 *  (Sources/APTradeApplication/SettingsUseCases.swift): event-driven, fired only after a
 *  trade actually succeeds, gated by `settings.orderFills`, and never allowed to fail the
 *  trade — CancellationException rethrows, everything else is swallowed. Defaults to a
 *  no-op so existing callers/tests that don't care about notifications keep compiling. */
class PortfolioViewModel(
    private val fetchPortfolio: FetchPortfolio,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val buyAsset: BuyAsset,
    private val sellAsset: SellAsset,
    private val resetPortfolio: ResetPortfolio,
    private val fetchPerformanceReport: FetchPerformanceReport,
    private val scope: CoroutineScope,
    private val tickMillis: Long = 15_000,
    private val nowEpochSeconds: () -> Long,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notifyOrderFill: suspend (TradeSide, String, String, String) -> Unit = { _, _, _, _ -> },
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

    /** Fires the order-fill notification for the just-completed trade's own transaction
     *  (the most recent one for `symbol`/`side` — `buy`/`sell` above just persisted it via
     *  `buyAsset`/`sellAsset`). A notifier failure must never surface as a trade error: this
     *  runs strictly after the trade's own state update, isolated in its own try/catch with
     *  CancellationException rethrown and everything else swallowed. */
    private suspend fun notifyFillSafely(side: TradeSide, symbol: String) {
        val txn = portfolio.transactions.lastOrNull { it.symbol == symbol && it.side == side } ?: return
        try {
            val amountText = (txn.price.amount * txn.quantity).toStringExpanded()
            notifyOrderFill(side, symbol, txn.quantity.toStringExpanded(), formatMoney(amountText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Notification delivery is best-effort — never let it fail a completed trade.
        }
    }

    fun reset() {
        scope.launch {
            portfolio = resetPortfolio.execute()
            quotes = emptyMap()
            _state.update {
                it.copy(
                    performanceValues = emptyList(),
                    performancePoints = emptyList(),
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

    /** One-shot per span/benchmark change — NEVER refetched on a poll tick. Feeds the overlay
     *  chart the two DOLLAR-valued series: the portfolio equity curve (`performanceValues`) and,
     *  when a benchmark twin is available, the cash-flow-replay twin (`benchmarkTwinValues`) —
     *  what the same trades would be worth had the cash gone into the benchmark instead. Both are
     *  `value.amount.doubleValue(false)` (the sanctioned pixels-only Double), aligned 1:1 by Task
     *  1 construction. Renders the risk metrics to display text (percent fields via
     *  `formatPercent`; sharpe/beta/alpha as plain 2-decimal text, "—" when null), and derives the
     *  scrubber's [PerfPointUi] list from the SAME `report.points` that feed `performanceValues`.
     *
     *  The `portfolio` passed to `execute` is this VM's already-loaded copy: trades/reset update
     *  it (and persist) before this runs, so it is coherent with disk at report time. */
    private fun loadPerformanceReport() {
        val span = _state.value.span
        val benchmark = _state.value.benchmark
        val portfolioSnapshot = portfolio
        scope.launch {
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
                        performancePoints = perfPointsUi(report.points),
                        benchmarkTwinValues = report.benchmarkTwinValues
                            ?.map { m -> m.amount.doubleValue(false) },
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

    /** Derives the P&L chart scrubber's per-point display strings from the performance
     *  report's raw points. Money deltas are BigDecimal subtraction on the underlying amounts
     *  (never Double); the percent delta is the one sanctioned Double ratio, formatted via
     *  `formatPercent` (which takes a percentage-point value, hence the ×100). */
    private fun perfPointsUi(points: List<PortfolioPerformancePoint>): List<PerfPointUi> {
        if (points.isEmpty()) return emptyList()
        val first = points.first()
        val dateFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
        return points.mapIndexed { index, point ->
            val tooltipDateText = Instant.ofEpochSecond(point.epochSeconds).atZone(zoneId).format(dateFormatter)
            if (index == 0) {
                PerfPointUi(
                    epochSeconds = point.epochSeconds,
                    valueText = formatMoney(point.value.amountText),
                    deltaText = "+\$0.00 (+0.00%)",
                    isUp = true,
                    tooltipDateText = tooltipDateText,
                )
            } else {
                val deltaAmount = point.value.amount - first.value.amount
                val deltaMoneyText = signedMoney(deltaAmount.toStringExpanded())
                val v0 = first.value.amount.doubleValue(false)
                val percentRatio = if (v0 != 0.0) {
                    (point.value.amount.doubleValue(false) - v0) / v0 * 100.0
                } else {
                    0.0
                }
                val deltaText = "$deltaMoneyText (${formatPercent(percentRatio)})"
                PerfPointUi(
                    epochSeconds = point.epochSeconds,
                    valueText = formatMoney(point.value.amountText),
                    deltaText = deltaText,
                    isUp = deltaAmount.signum() >= 0,
                    tooltipDateText = tooltipDateText,
                )
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
                    kind = position.asset.kind,
                    quantityText = position.quantity.toStringExpanded(),
                    averageCostText = formatMoney(position.averageCost.amountText),
                    marketValueText = marketValue.amountText,          // RAW — SuperscriptPrice consumer
                    unrealizedText = signedMoney(unrealized.amountText),
                    unrealizedPositive = quote?.let { unrealized.amount.doubleValue(false) >= 0.0 },
                    priceText = quote?.price?.amountText,              // RAW — TradeDialog re-parses + SuperscriptPrice
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
                    priceText = formatMoney(txn.price.amountText),
                    epochSeconds = txn.epochSeconds,
                    dateText = Instant.ofEpochSecond(txn.epochSeconds).atZone(zoneId).format(transactionDateFormatter),
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
