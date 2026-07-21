package com.aptrade.android.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.formatShares
import com.aptrade.android.ui.money
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.BuyAsset
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchDividendEvents
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.SellAsset
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendMath
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeError
import com.aptrade.shared.domain.TradeSide
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SECONDS_PER_DAY = 86_400L

enum class ChartMode { Line, Candles }

/** Dividend snapshot for the current symbol, shown on the asset-detail dividend card. `null`
 *  on [DetailUiState.dividendInfo] hides the card entirely — crypto (skipped WITHOUT fetching),
 *  non-payers (zero events in the trailing 2 years), and degraded fetches all present
 *  identically. Desktop parity ([com.aptrade.desktop.detail.DividendInfo]), transcribed from
 *  `AssetDetailViewModel.DividendInfo` (Swift AS-BUILT). */
data class DividendInfo(
    val trailingAnnualRate: Money,
    val yieldFraction: Double,
    /** Future-only: the VM already filters out a stale projection (<= now), so the screen only
     *  ever needs a null check, never a re-validation of an already-passed date. */
    val nextEstimatedExDateEpochSeconds: Long? = null,
    /** Up to 8 most recent per-share amounts, oldest first (mini bar chart). */
    val recentAmounts: List<Money> = emptyList(),
)

/** One OHLCV bar. [volume] defaults to 0.0 (mirrors [com.aptrade.shared.domain.Candle]'s own
 *  default) so existing 4-arg call sites (tests, any code built before indicators needed
 *  volume for VWAP) keep compiling; every real candle from [FetchChartWindow] carries its
 *  actual volume through. */
data class CandleBar(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double = 0.0)

data class DetailUiState(
    val symbol: String,
    val name: String? = null,
    val kind: AssetKind? = null,
    val profileError: String? = null,
    /** True once the profile request has RESOLVED — success or error, doesn't matter which.
     *  Gates the BUY/SELL entry point so a trade can never fire while [kind] is still
     *  unset (the window in which a crypto/ETF asset would be misclassified as Stock). */
    val profileResolved: Boolean = false,
    /** Pre-formatted live price for the TradeSheet's AssistChip (≡ portfolio holding row's
     *  `priceText`, via [com.aptrade.android.ui.money] over `Quote.price.amountText`). Fetched
     *  in its own isolated coroutine; stays null on failure — this NEVER gates trading. */
    val priceText: String? = null,
    val timeframe: Timeframe = Timeframe.OneDay,
    val mode: ChartMode = ChartMode.Line,
    val lineValues: List<Double> = emptyList(),
    /** FULL candle series: indicator warm-up lookback bars followed by the visible window
     *  (see [visibleStartIndex]) — desktop parity (`DetailUiState.candles` KDoc). Indicators
     *  are computed over this entire list so their warm-up prefix (SMA/BB ~20 bars, MACD ~26
     *  bars) is consumed by the lookback bars rather than eating into the visible chart;
     *  `candles.drop(visibleStartIndex)` is exactly what a plain (non-indicator) candle chart
     *  should render. */
    val candles: List<CandleBar> = emptyList(),
    /** Index into [candles] where the plain visible window begins. 0 when there aren't enough
     *  lookback bars ahead of the window (the full list IS the visible list). */
    val visibleStartIndex: Int = 0,
    /** True while any indicator chip is toggled on. Drives the candle fetch in Line mode
     *  (indicators need OHLCV even when the price chart is normally drawn from line history). */
    val indicatorsActive: Boolean = false,
    /** Parallel to [lineValues] — the crosshair readout's pre-formatted price text (via
     *  [com.aptrade.android.ui.money], never the pixel-math Double) and the point's raw epoch
     *  second, consumed by [com.aptrade.android.ui.chart.crosshairReadout]. */
    val lineValueTexts: List<String> = emptyList(),
    val lineDates: List<Long> = emptyList(),
    /** Parallel to `candles.drop(visibleStartIndex)` — i.e. the VISIBLE candle slice only,
     *  same length as what any candle-sourced chart (plain or with overlays) actually renders. */
    val candleCloseTexts: List<String> = emptyList(),
    val candleDates: List<Long> = emptyList(),
    val isLoadingChart: Boolean = true,
    val chartError: String? = null,
    /** Inline BUY/SELL failure text for the TradeSheet; null while no trade error is showing. */
    val tradeError: String? = null,
    /** Count of transactions in the portfolio after the last successful trade from this screen.
     *  Monotonic; the TradeSheet snapshots it on open and dismisses when it grows. */
    val transactionCount: Int = 0,
    /** Earliest upcoming earnings event for this symbol within the next 30 days, or null when
     *  none is scheduled, the fetch failed, or no earnings source is available. Fetched in its
     *  own isolated coroutine (Task 9) — mirrors [priceText]'s silent-failure contract; a
     *  missing/errored fetch never blocks trading or the chart. */
    val nextEarnings: EarningsEvent? = null,
    /** Null hides the Dividends card — crypto (never fetched), non-payers (zero events in the
     *  trailing 2 years), and fetch failures all degrade here identically; see
     *  `DetailViewModel`'s dividend-info init coroutine for the full semantics. Desktop parity
     *  (`DetailUiState.dividendInfo` KDoc). */
    val dividendInfo: DividendInfo? = null,
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
    private val fetchChartWindow: FetchChartWindow,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val buyAsset: BuyAsset,
    private val sellAsset: SellAsset,
    private val nowEpochSeconds: () -> Long,
    private val notifyOrderFill: suspend (TradeSide, String, String, String) -> Unit = { _, _, _, _ -> },
    // Nullable = keyless (mirrors NewsViewModel's fetchMarketNews provider convention): in
    // practice AppGraph.fetchEarningsCalendar is never actually null (it falls back to
    // EmptyEarningsRepository with no Finnhub key configured, Task 8), but keeping this
    // nullable lets a caller/test opt out of the earnings fetch entirely rather than having to
    // wire an EmptyEarningsRepository-backed instance just to get a no-op.
    private val fetchEarnings: FetchEarningsCalendar? = null,
    // Nullable for the same reason as [fetchEarnings] above: in production DetailScreen always
    // wires the real use case (AppGraph.repository backs it, never absent), but a nullable
    // default lets tests unrelated to dividends (e.g. DetailViewModelEarningsTest) keep
    // constructing this VM without wiring one.
    private val fetchDividendEvents: FetchDividendEvents? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState(symbol = symbol))
    val state: StateFlow<DetailUiState> = _state
    private var chartJob: Job? = null

    /** Timeframe the currently-held `candles` were fetched for, or null if none have been
     *  fetched yet. Desktop parity (`DetailViewModel.candlesTimeframe` KDoc): a plain
     *  timeframe change never refetches candles by itself (Line mode without indicators has
     *  no use for them), so this can silently go stale relative to `state.timeframe` —
     *  [candlesStale] below is what actually gates a refetch. */
    private var candlesTimeframe: Timeframe? = null
    private val candlesStale: Boolean
        get() = candlesTimeframe != _state.value.timeframe

    init {
        // Profile and quote each fetch exactly once per symbol, shared (via `async`, which
        // caches its result/exception) between the coroutine that renders them into state and
        // the dividend-info coroutine below — which needs the SAME asset kind (crypto guard)
        // and quote price (yield denominator) without triggering a second network call for
        // either. Desktop parity (`DetailViewModel.init` KDoc): `viewModelScope` is backed by a
        // SupervisorJob (the androidx.lifecycle contract), so one branch's failure never
        // cancels the others.
        val profileDeferred = viewModelScope.async { fetchProfile.execute(symbol) }
        val quoteDeferred = viewModelScope.async {
            fetchMarketQuotes.execute(listOf(symbol)).firstOrNull { it.symbol == symbol }
        }

        viewModelScope.launch {
            try {
                val asset = profileDeferred.await()
                _state.update {
                    it.copy(name = asset.name, kind = asset.kind, profileResolved = true)
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
                val quote = quoteDeferred.await()
                if (quote != null) {
                    _state.update { it.copy(priceText = money(quote.price.amountText)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                // Silent: priceText stays null, trading remains available.
            }
        }
        // Dividend info: awaits the SAME profile/quote fetches above (no duplicate network
        // calls). Crypto is skipped WITHOUT ever calling fetchDividendEvents. A profile FAILURE
        // (kind unknown) does NOT itself skip the fetch — only a POSITIVELY known crypto kind
        // does; see DetailViewModelTest.profileFailureForCryptoSymbolStillProceedsWithDividendFetch,
        // which pins this exact edge (flagged during the M8.2 desktop review — Swift has no
        // equivalent in-flight/failed window, since its asset kind arrives already resolved at
        // construction). A quote failure/absence degrades the yield denominator to zero, same
        // fallback desktop's `DetailViewModel.loadDividendInfo` KDoc documents. No-op entirely
        // when this VM was built without a dividend use case (mirrors [fetchEarnings]'s nullable
        // convention above — DetailScreen always wires the real one in production).
        fetchDividendEvents?.let { fetchDividends ->
            viewModelScope.launch {
                val kind = try {
                    profileDeferred.await().kind
                } catch (e: CancellationException) {
                    throw e
                } catch (e: QuoteError) {
                    null
                }
                if (kind == AssetKind.Crypto) return@launch
                val priceAmount = try {
                    quoteDeferred.await()?.price?.amount
                } catch (e: CancellationException) {
                    throw e
                } catch (e: QuoteError) {
                    null
                } ?: BigDecimal.ZERO
                loadDividendInfo(fetchDividends, priceAmount)
            }
        }
        // Isolated from every other init coroutine, same silent-failure contract as priceText
        // above: a missing key, network failure, or empty calendar all leave nextEarnings null
        // and the KEY STATS row simply omits itself (Step 5, DetailScreen).
        viewModelScope.launch {
            try {
                val cal = MarketCalendar()
                val start = cal.localEpochDay(System.currentTimeMillis() / 1000)
                val next = fetchEarnings?.nextEarnings(symbol, cal.dayString(start), cal.dayString(start + 30))
                if (next != null) _state.update { it.copy(nextEarnings = next) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silent: nextEarnings stays null.
            }
        }
        loadChart()
    }

    /** Fetches dividend events over a trailing-2-year window and derives [DetailUiState.
     *  dividendInfo]. Zero events in the window (non-payer) and any fetch failure both degrade
     *  to null identically: this stat is never worth surfacing as an error state. [priceAmount]
     *  is the current quote price (or [BigDecimal.ZERO] when unavailable) — the yield fraction
     *  guards against a zero/missing price by degrading to 0 rather than dividing by zero, same
     *  choice `IncomeViewModel` makes for its yield fields. Desktop parity
     *  (`DetailViewModel.loadDividendInfo` KDoc), including its 730-day window and future-only
     *  `nextEstimatedExDate` guard. */
    private suspend fun loadDividendInfo(fetchDividends: FetchDividendEvents, priceAmount: BigDecimal) {
        val asOf = nowEpochSeconds()
        val windowStart = asOf - 730 * SECONDS_PER_DAY
        try {
            val events = fetchDividends.execute(symbol, windowStart)
            if (events.isEmpty()) return

            val rate = DividendMath.trailingAnnualPerShare(events, asOf)
            val yieldFraction = if (priceAmount > BigDecimal.ZERO) {
                rate.amount.divide(priceAmount, MONEY_MATH).doubleValue(false)
            } else {
                0.0
            }
            val recentAmounts = events.sortedBy { it.exDateEpochSeconds }.takeLast(8).map { it.amountPerShare }
            // Future-only guard, mirroring `IncomeViewModel.buildUpcoming`: a projection that
            // lands before "now" must not surface a past date under the "Est." badge — the row
            // simply hides, the rest of DividendInfo still shows.
            val projected = DividendMath.nextProjected(events)
            val nextEstimatedExDate = projected?.exDateEpochSeconds?.takeIf { it > asOf }

            _state.update {
                it.copy(
                    dividendInfo = DividendInfo(
                        trailingAnnualRate = rate,
                        yieldFraction = yieldFraction,
                        nextEstimatedExDateEpochSeconds = nextEstimatedExDate,
                        recentAmounts = recentAmounts,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuoteError) {
            // dividendInfo stays null — never an error state.
        }
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
     *  the VM can fetch candles in Line mode (indicator math needs OHLCV). Desktop parity
     *  (`DetailViewModel.onIndicatorsActiveChange` KDoc): only reloads when the flag actually
     *  flips, and never when Candles mode already has the bars in hand. */
    fun onIndicatorsActiveChange(active: Boolean) {
        if (_state.value.indicatorsActive == active) return
        _state.update { it.copy(indicatorsActive = active) }
        if (_state.value.mode == ChartMode.Line && active && candlesStale) {
            loadChart()
        }
    }

    fun retryChart() = loadChart()

    /** The [Asset] to buy: this screen's [symbol] plus the name/kind loaded by the profile fetch.
     *  Falls back to `Asset(symbol, symbol, Stock)` ONLY when the profile resolved with an ERROR
     *  (kind genuinely absent) — the UI gates BUY/SELL entry on [DetailUiState.profileResolved]
     *  so this is never reached while the profile fetch is still in flight; a successfully-loaded
     *  crypto or ETF asset is therefore never misclassified as Stock. */
    private fun tradeAsset(): Asset {
        val s = _state.value
        return Asset(symbol = symbol, name = s.name ?: symbol, kind = s.kind ?: AssetKind.Stock)
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
            notifyOrderFill(side, symbol, formatShares(txn.quantity), money(amountText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Notification delivery is best-effort — never let it fail a completed trade.
        }
    }

    /** Desktop parity (`DetailViewModel.loadChart` KDoc): Line mode always fetches price
     *  history for the plain line values; candles (via [FetchChartWindow], NOT the plain
     *  window-only fetch — indicators need the warm-up lookback pad) are fetched additionally,
     *  in EITHER mode, whenever Candles mode is selected or an indicator is active, and only
     *  when [candlesStale] (so reactivating an indicator after a timeframe change correctly
     *  refetches for the new timeframe, and a plain timeframe change with no indicator active
     *  never fetches candles it doesn't need). */
    private fun loadChart() {
        // Snapshot before launching so the coroutine renders the selection this call
        // was triggered for, even if state mutates before or while it runs.
        val timeframe = _state.value.timeframe
        val mode = _state.value.mode
        val needsCandles = mode == ChartMode.Candles || _state.value.indicatorsActive
        val shouldFetchCandles = needsCandles && candlesStale
        chartJob?.cancel()
        chartJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingChart = true, chartError = null) }
            try {
                val lineValues: List<Double>
                val lineValueTexts: List<String>
                val lineDates: List<Long>
                if (mode == ChartMode.Line) {
                    val points = fetchHistory.execute(symbol, timeframe)
                    // Pixel math only — money display always goes through
                    // Money.formatted/amountText, never this Double.
                    lineValues = points.map { it.close.amount.doubleValue(false) }
                    lineValueTexts = points.map { money(it.close.amountText) }
                    lineDates = points.map { it.epochSeconds }
                } else {
                    lineValues = _state.value.lineValues
                    lineValueTexts = _state.value.lineValueTexts
                    lineDates = _state.value.lineDates
                }

                val chartWindow = if (shouldFetchCandles) {
                    fetchChartWindow.execute(symbol, timeframe).also { candlesTimeframe = timeframe }
                } else {
                    null
                }
                val candles = chartWindow?.candles?.map { c ->
                    CandleBar(
                        c.open.amount.doubleValue(false), c.high.amount.doubleValue(false),
                        c.low.amount.doubleValue(false), c.close.amount.doubleValue(false),
                        c.volume,
                    )
                } ?: _state.value.candles
                val visibleStartIndex = chartWindow?.visibleStartIndex ?: _state.value.visibleStartIndex
                val visibleWindowCandles = chartWindow?.candles?.drop(chartWindow.visibleStartIndex)
                val candleCloseTexts = visibleWindowCandles?.map { money(it.close.amountText) }
                    ?: _state.value.candleCloseTexts
                val candleDates = visibleWindowCandles?.map { it.epochSeconds } ?: _state.value.candleDates

                _state.update {
                    it.copy(
                        isLoadingChart = false,
                        lineValues = lineValues, lineValueTexts = lineValueTexts, lineDates = lineDates,
                        candles = candles, visibleStartIndex = visibleStartIndex,
                        candleCloseTexts = candleCloseTexts, candleDates = candleDates,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(isLoadingChart = false, chartError = e.userMessage()) }
            }
        }
    }
}
