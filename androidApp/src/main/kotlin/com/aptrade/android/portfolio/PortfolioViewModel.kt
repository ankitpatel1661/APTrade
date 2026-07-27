package com.aptrade.android.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.goals.GoalCardUi
import com.aptrade.android.goals.goalCardUi
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.formatPercent
import com.aptrade.android.ui.formatShares
import com.aptrade.android.ui.money
import com.aptrade.android.ui.signedMoney
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchDividendEvents
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchPerformanceReport
import com.aptrade.shared.application.FetchPortfolio
import com.aptrade.shared.application.LoadGoals
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveGoal
import com.aptrade.shared.application.ResetPortfolio
import com.aptrade.shared.application.SaveGoal
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.AllocationSlice
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.DividendMath
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalMath
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioExport
import com.aptrade.shared.domain.PortfolioGoal
import com.aptrade.shared.domain.PortfolioPerformancePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeError
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.allocationByHolding
import com.aptrade.shared.domain.allocationByKind
import com.aptrade.shared.domain.goalCurrentValueFloor
import com.aptrade.shared.domain.realizedPnL
import com.aptrade.shared.domain.renderCsv
import com.aptrade.shared.domain.renderJson
import com.aptrade.shared.l10n.L10n
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

/** How far back to fetch dividend events when projecting annual income: two years covers
 *  the trailing-annual window (365d) plus enough history for cadence inference on
 *  slower-paying assets. Mirrors desktop `PortfolioViewModel`'s
 *  `DIVIDEND_EVENTS_LOOKBACK_SECONDS` and `IncomeViewModel.lookbackStart`. */
private const val DIVIDEND_EVENTS_LOOKBACK_SECONDS = 730L * 86_400L

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
    /** Parallel to [performanceValues] — the crosshair readout's pre-formatted, exact-decimal
     *  price text (via [com.aptrade.android.ui.money] over `Money.amountText`, never the
     *  pixel-math `performanceValues` Double) and each point's raw epoch second. Mirrors
     *  DetailUiState's `lineValueTexts`/`lineDates`, consumed by
     *  [com.aptrade.android.ui.chart.crosshairReadout]. */
    val performanceValueTexts: List<String> = emptyList(),
    val performanceDates: List<Long> = emptyList(),
    val metrics: MetricTexts? = null,
    /** `null` when no value goal is set. The card still RENDERS (carry-notes §1.3) — this only
     *  selects between its progress body and its "Set a goal" affordance. */
    val valueGoal: GoalCardUi? = null,
    val error: String? = null,
    val tradeError: String? = null,
)

// Buy/Sell stay literal English here — only Dividend routes through tr(), matching desktop
// PortfolioViewModel's own sideLabel (M8.2 Task 5): Dividend is the one side introduced after
// the L10n catalog existed, so it's the only one keyed so far.
private fun sideLabel(side: TradeSide): String = when (side) {
    TradeSide.Buy -> "Buy"
    TradeSide.Sell -> "Sell"
    TradeSide.Dividend -> tr(L10n.Key.ActivityDividend)
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
 *  to a no-op so existing callers/tests that don't care about notifications keep compiling.
 *
 *  [fetchDividendEvents] mirrors desktop `PortfolioViewModel`'s optional
 *  `FetchDividendEvents? = null` DI seam (itself transcribed from Swift's
 *  `ExportPortfolioUseCase`'s `dividendEventsRepository: DividendEventsRepository? = nil`,
 *  M8.2 Task 11): a nil-safe seam so `exportSnapshot`'s `projectedAnnualIncome` degrades to
 *  zero rather than failing when no dividend-events source is wired for a given build. */
class PortfolioViewModel(
    private val fetchPortfolio: FetchPortfolio,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val buyAsset: BuyAsset,
    private val sellAsset: SellAsset,
    private val resetPortfolio: ResetPortfolio,
    private val fetchPerformanceReport: FetchPerformanceReport,
    private val loadGoals: LoadGoals,
    private val saveGoal: SaveGoal,
    private val removeGoal: RemoveGoal,
    private val nowEpochSeconds: () -> Long,
    private val tickMillis: Long = 15_000,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val notifyOrderFill: suspend (TradeSide, String, String, String) -> Unit = { _, _, _, _ -> },
    private val fetchDividendEvents: FetchDividendEvents? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(PortfolioUiState())
    val state: StateFlow<PortfolioUiState> = _state

    private var portfolio: Portfolio = Portfolio.starting()
    private var quotes: Map<String, Quote> = emptyMap()
    private var pollJob: Job? = null

    // Value-goal inputs, kept off [PortfolioUiState] because they are model values, not display
    // text: the state exposes only the mapped [GoalCardUi]. Refreshed together by
    // [loadPerformanceReport] so a projection is never computed from a mix of snapshots.
    private var equityCurve: List<PortfolioPerformancePoint> = emptyList()
    private var currentValue: Money = Money.usd("0")
    private var valueGoal: PortfolioGoal? = null

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

    /** Sets (or replaces) the whole-portfolio value goal shown on Performance. Persists FIRST,
     *  then recomputes the card's projection against the CURRENT curve/current-value snapshot —
     *  the same order desktop `PortfolioViewModel.setValueGoal` uses, so a save failure can never
     *  leave the screen showing a goal that isn't on disk. */
    fun setValueGoal(target: Money) {
        val goal = PortfolioGoal(GoalKind.Value, target, nowEpochSeconds())
        viewModelScope.launch {
            saveGoal.execute(goal)
            valueGoal = goal
            refreshValueProjection()
        }
    }

    fun removeValueGoal() {
        viewModelScope.launch {
            removeGoal.execute(GoalKind.Value)
            valueGoal = null
            _state.update { it.copy(valueGoal = null) }
        }
    }

    /** Recomputes the value-goal card's progress/projection from the CURRENT [valueGoal] /
     *  [currentValue] / [equityCurve] snapshot. Called after every load, set and remove so the
     *  card never shows a projection computed against stale inputs. */
    private fun refreshValueProjection() {
        val goal = valueGoal
        if (goal == null) {
            _state.update { it.copy(valueGoal = null) }
            return
        }
        // ACCOUNT AGE, not the price window's span (M11.2 kickoff decision 4a.2): fed from the ONE
        // named derivation `Portfolio.inceptionEpochSeconds()`, the same signal
        // FetchPortfolioPerformance's sinceInception trim uses, so the metric and the floor cannot
        // drift apart. A brand-new account holding a seasoned symbol therefore honestly reports
        // insufficient history instead of extrapolating three weeks of price movement.
        val accountAgeDays = GoalMath.accountAgeDays(portfolio.inceptionEpochSeconds(), nowEpochSeconds())
        val projection = GoalMath.valueProjection(currentValue, goal.target, equityCurve, accountAgeDays)
        _state.update { it.copy(valueGoal = goalCardUi(goal, currentValue, projection)) }
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
            notifyOrderFill(side, symbol, formatShares(txn.quantity), money(amountText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Notification delivery is best-effort — never let it fail a completed trade.
        }
    }

    /** Opens a fresh portfolio at [startingCash] — the reset dialog's validated amount, seeded
     *  from `AppSettings.defaultStartingCash`. Byte-for-byte the desktop `PortfolioViewModel.reset`
     *  body (M11.3 Task 7, closing carry-notes §4b): until this task Android ignored the
     *  configured balance entirely and hardcoded `Portfolio.DEFAULT_STARTING_CASH`, so a user who
     *  chose $250,000 saw Windows honour it and Android silently open at $100,000.
     *
     *  Goals SURVIVE (M11.1 UAT F1, user ruling 2026-07-27): resetting starting capital is "start
     *  over with more money", not "abandon my plan". So this must not null [valueGoal] out — that
     *  would hide from the screen a goal still sitting intact on disk, the Swift twin's exact UAT
     *  bug in mirror image. The goal is RE-READ from the store rather than kept from memory, for
     *  the same reason [loadPerformanceReport] re-reads it: another surface may have changed it.
     *
     *  What the card MUST do is recompute against the fresh snapshot — current value from the new
     *  balance, the pre-reset equity curve discarded — so a $120,000 target after a reset to
     *  $1,000,000 reads as reached, never as a percentage of a curve that no longer applies.
     *  [refreshValueProjection] is what makes both goal surfaces (the Performance card and the
     *  header strip) recompute immediately instead of holding their pre-reset numbers until the
     *  next report load. */
    fun reset(startingCash: Money) {
        viewModelScope.launch {
            portfolio = resetPortfolio.execute(startingCash)
            quotes = emptyMap()
            equityCurve = emptyList()
            currentValue = portfolio.goalCurrentValueFloor()
            valueGoal = loadGoals.execute().firstOrNull { it.kind == GoalKind.Value }
            _state.update {
                it.copy(
                    performanceValues = emptyList(),
                    performanceValueTexts = emptyList(),
                    performanceDates = emptyList(),
                    benchmarkTwinValues = null,
                    metrics = null,
                )
            }
            refreshValueProjection()
            publish(loading = false)
        }
    }

    suspend fun exportCsv(): String = exportSnapshot().renderCsv()

    suspend fun exportJson(): String = exportSnapshot().renderJson()

    /** The current portfolio valued against the last-good quotes, as a [PortfolioExport]
     *  snapshot — the single source for CSV/JSON export.
     *
     *  `suspend` (M8.3 final-review fix, mirroring desktop `PortfolioViewModel`'s M8.2 Task
     *  11 change): `projectedAnnualIncome` fetches per-symbol dividend events. */
    suspend fun exportSnapshot(): PortfolioExport {
        val asOf = nowEpochSeconds()
        val income = projectedAnnualIncome(portfolio, asOf)
        return PortfolioExport.from(portfolio, quotes, "APTrade", asOf, income)
    }

    /** Forward 12-month dividend income from held, non-crypto positions. Returns zero when
     *  there's no [fetchDividendEvents] to source events from (e.g. export is used before
     *  the shared dividend-events facet is wired for a given build). A per-symbol fetch
     *  failure degrades only that symbol to zero events — it never blocks the others.
     *  Byte-transcribed from desktop `PortfolioViewModel.projectedAnnualIncome`. */
    private suspend fun projectedAnnualIncome(portfolio: Portfolio, asOfEpochSeconds: Long): BigDecimal {
        val fetchDividendEvents = fetchDividendEvents ?: return BigDecimal.ZERO
        val nonCryptoPositions = portfolio.positions.filter { it.asset.kind != AssetKind.Crypto }
        if (nonCryptoPositions.isEmpty()) return BigDecimal.ZERO

        val since = asOfEpochSeconds - DIVIDEND_EVENTS_LOOKBACK_SECONDS
        val eventsBySymbol = mutableMapOf<String, List<DividendEvent>>()
        for (position in nonCryptoPositions) {
            val symbol = position.asset.symbol
            eventsBySymbol[symbol] = try {
                fetchDividendEvents.execute(symbol, since)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                emptyList()
            }
        }
        return DividendMath.projectedAnnualIncome(portfolio.positions, eventsBySymbol, asOfEpochSeconds).amount
    }

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
                equityCurve = report.points
                // The curve's LAST point is the true current total account value (cash +
                // holdings). When there is no curve at all, fall back to cash + every position's
                // OWN cost basis — never a hardcoded zero (carry-notes §2.3). The curve is empty
                // in two distinct situations and only one is exotic: genuinely all-cash
                // (FetchPortfolioPerformance returns emptyList() for a position-less portfolio),
                // and positions-exist-but-history-failed, which every offline or rate-limited
                // session hits. Neither may fabricate a dollar figure nobody's portfolio holds.
                currentValue = report.points.lastOrNull()?.value ?: portfolioSnapshot.goalCurrentValueFloor()
                valueGoal = loadGoals.execute().firstOrNull { it.kind == GoalKind.Value }
                _state.update {
                    it.copy(
                        performanceValues = report.points.map { p -> p.value.amount.doubleValue(false) },
                        performanceValueTexts = report.points.map { p -> money(p.value.amountText) },
                        performanceDates = report.points.map { p -> p.epochSeconds },
                        benchmarkTwinValues = report.benchmarkTwinValues
                            ?.map { m -> m.amount.doubleValue(false) },
                        metrics = metrics,
                    )
                }
                refreshValueProjection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                // Portfolio-side history failure: leave prior report state as last-good, but the
                // goal card must still show an honest current value rather than nothing.
                if (equityCurve.isEmpty()) currentValue = portfolioSnapshot.goalCurrentValueFloor()
                valueGoal = loadGoals.execute().firstOrNull { it.kind == GoalKind.Value }
                refreshValueProjection()
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
                    quantityText = formatShares(position.quantity),
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
                    quantityText = formatShares(txn.quantity),
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
