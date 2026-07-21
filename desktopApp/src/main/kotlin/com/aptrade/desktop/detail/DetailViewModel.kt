package com.aptrade.desktop.detail

import com.aptrade.desktop.designkit.ChartCandle
import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchCompanyNews
import com.aptrade.shared.application.FetchDividendEvents
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendMath
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.NewsArticle
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SECONDS_PER_DAY = 86_400L

enum class ChartMode { Line, Candles }

/** Dividend snapshot for the current symbol, shown on the asset-detail dividend card. `null`
 *  on [DetailUiState.dividendInfo] hides the card entirely — crypto (skipped WITHOUT
 *  fetching), non-payers (zero events in the trailing 2 years), and degraded fetches all
 *  present identically. Transcribed from `AssetDetailViewModel.DividendInfo` (Swift AS-BUILT).
 */
data class DividendInfo(
    val trailingAnnualRate: Money,
    val yieldFraction: Double,
    /** Future-only: the VM already filters out a stale projection (<= now), so the pane only
     *  ever needs a null check, never a re-validation of an already-passed date. */
    val nextEstimatedExDateEpochSeconds: Long? = null,
    /** Up to 8 most recent per-share amounts, oldest first (mini bar chart). */
    val recentAmounts: List<Money> = emptyList(),
)

data class DetailUiState(
    val symbol: String,
    val name: String? = null,
    val kind: AssetKind? = null,
    val amountText: String? = null,
    val changePercent: Double? = null,
    val previousCloseText: String? = null,
    val profileError: String? = null,
    val timeframe: Timeframe = Timeframe.OneDay,
    val mode: ChartMode = ChartMode.Line,
    val lineValues: List<Double> = emptyList(),
    /** FULL candle series: lookback warm-up bars followed by the visible window
     *  (see [visibleStartIndex]). Indicators are computed over this entire list so their
     *  warm-up prefix (SMA/BB ~20 bars, MACD ~26 bars) is consumed by the lookback bars
     *  rather than eating into the visible chart. */
    val candles: List<ChartCandle> = emptyList(),
    /** Index into [candles] where the plain visible window begins — `candles.drop(
     *  visibleStartIndex)` is exactly what a non-indicator chart should render. 0 when there
     *  aren't enough lookback bars ahead of the window (the full list IS the visible list). */
    val visibleStartIndex: Int = 0,
    /** True while any indicator chip is toggled on. Drives the candle fetch in Line mode
     *  (indicators need OHLCV even when the price chart is drawn from line history). */
    val indicatorsActive: Boolean = false,
    val isLoadingChart: Boolean = true,
    val chartError: String? = null,
    /** Up to 8 company-news articles for this symbol (prefix applied in the VM). Empty when
     *  no news key is configured or the fetch yields nothing. */
    val newsArticles: List<NewsArticle> = emptyList(),
    val newsLoading: Boolean = false,
    val newsKeyMissing: Boolean = false,
    /** Bookmarked-article state shared with the News tab (same store). */
    val bookmarks: List<NewsArticle> = emptyList(),
    /** Earliest S&P-500/owned earnings event for this symbol in the next 30 days, or null when
     *  none is scheduled (or the fetch degrades to empty — FetchEarningsCalendar swallows
     *  failures, never crashes). The raw typed event is carried here UNLOCALIZED; the pane
     *  formats/localizes it at render time (Task 7 contract) — mirrors why [kind]/[timeframe]
     *  stay typed rather than pre-rendered strings. */
    val nextEarnings: EarningsEvent? = null,
    /** Null hides the Dividends card — crypto (never fetched), non-payers (zero events in the
     *  trailing 2 years), and fetch failures all degrade here identically; see
     *  `DetailViewModel.loadDividendInfo` for the full semantics. */
    val dividendInfo: DividendInfo? = null,
) {
    /** Ids of bookmarked articles — lets each news row render its bookmark glyph. */
    val bookmarkedIds: Set<String> get() = bookmarks.mapTo(HashSet()) { it.id }
}

/** Per-selection asset detail: profile, quote, and the timeframe/mode chart load.
 *  `scope` MUST be single-thread-confined (Dispatchers.Main on desktop): the internal
 *  chartJob var and state updates rely on that confinement instead of locks. */
class DetailViewModel(
    private val symbol: String,
    private val fetchProfile: FetchProfile,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val fetchHistory: FetchHistory,
    private val fetchChartWindow: FetchChartWindow,
    /** Always present (AppGraph falls back to EmptyEarningsRepository when no key is
     *  configured, never null) — unlike [fetchCompanyNews] below, there is no "absent" state
     *  to model, so this is required like [fetchProfile]/[fetchMarketQuotes]. */
    private val fetchEarningsCalendar: FetchEarningsCalendar,
    /** Always present (the repository backing it is never null), like
     *  [fetchEarningsCalendar] above — there is no "absent" state to model here. */
    private val fetchDividendEvents: FetchDividendEvents,
    private val scope: CoroutineScope,
    /** Null when no news key is configured — surfaces as [DetailUiState.newsKeyMissing] and
     *  the company-news section stays empty. */
    private val fetchCompanyNews: FetchCompanyNews? = null,
    private val loadBookmarks: LoadBookmarks? = null,
    private val toggleBookmark: ToggleBookmark? = null,
    private val calendar: MarketCalendar = MarketCalendar(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _state = MutableStateFlow(
        DetailUiState(symbol = symbol, newsKeyMissing = fetchCompanyNews == null),
    )
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
        // Profile and quote each fetch exactly once per symbol, shared (via `async`, which
        // caches its result/exception) between the coroutine that renders them into state and
        // the dividend-info coroutine below — which needs the SAME asset kind (crypto guard)
        // and quote price (yield denominator) without triggering a second network call for
        // either. Backed by a SupervisorJob scope (see `DetailScreen`), so one branch's failure
        // never cancels the others.
        val profileDeferred = scope.async { fetchProfile.execute(symbol) }
        val quoteDeferred = scope.async { fetchMarketQuotes.execute(listOf(symbol)).firstOrNull() }

        scope.launch {
            try {
                val asset = profileDeferred.await()
                _state.update { it.copy(name = asset.name, kind = asset.kind) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                _state.update { it.copy(profileError = e.userMessage()) }
            }
        }
        scope.launch {
            try {
                val quote = quoteDeferred.await() ?: return@launch
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
        // Dividend info: awaits the SAME profile/quote fetches above (no duplicate network
        // calls). Crypto is skipped WITHOUT ever calling fetchDividendEvents. A profile failure
        // (kind unknown) does not itself skip the fetch — only a POSITIVELY known crypto kind
        // does, mirroring the Swift reference's unconditional (kind-is-always-known) guard as
        // closely as this platform's async profile fetch allows. A quote failure/absence
        // degrades the yield denominator to zero, same fallback `AssetDetailViewModel.swift`
        // uses (`quote?.price.amount ?? 0`).
        scope.launch {
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
            loadDividendInfo(priceAmount)
        }
        // Next-earnings loads exactly once per symbol in its own isolated coroutine (like
        // profile/quote above), a 30-day window from "today" in market-local terms.
        // FetchEarningsCalendar.nextEarnings already swallows repository failures to null (see
        // its KDoc), but mirrors the Android twin's belt-and-suspenders catch(Exception) here
        // for symmetry — only CancellationException must propagate so scope teardown isn't
        // swallowed.
        scope.launch {
            try {
                val startDay = calendar.localEpochDay(nowEpochSeconds())
                val today = calendar.dayString(startDay)
                val toDay = calendar.dayString(startDay + 30)
                val next = fetchEarningsCalendar.nextEarnings(symbol, today, toDay)
                _state.update { it.copy(nextEarnings = next) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silent: nextEarnings stays null.
            }
        }
        loadChart()
        // Company news loads exactly once per symbol in its own isolated coroutine (like
        // profile/quote/chart) and dies with this VM instance when the symbol changes — no
        // stale guard needed. The use case already swallows failures to an empty list.
        fetchCompanyNews?.let { fetch ->
            scope.launch {
                _state.update { it.copy(newsLoading = true) }
                try {
                    val articles = fetch.execute(symbol).take(8)
                    _state.update { it.copy(newsArticles = articles, newsLoading = false) }
                } catch (e: CancellationException) {
                    throw e
                }
            }
        }
        loadBookmarks?.let { load ->
            scope.launch { _state.update { it.copy(bookmarks = load.execute()) } }
        }
    }

    /** Fetches dividend events over a trailing-2-year window and derives [DetailUiState.
     *  dividendInfo]. Zero events in the window (non-payer) and any fetch failure both degrade
     *  to null identically: this stat is never worth surfacing as an error state. [priceAmount]
     *  is the current quote price (or [BigDecimal.ZERO] when unavailable) — the yield fraction
     *  guards against a zero/missing price by degrading to 0 rather than dividing by zero, same
     *  choice `IncomeViewModel` makes for its yield fields. Transcribed from
     *  `AssetDetailViewModel.loadDividendInfo` (Swift AS-BUILT), including its 730-day window
     *  and future-only `nextEstimatedExDate` guard. */
    private suspend fun loadDividendInfo(priceAmount: BigDecimal) {
        val asOf = nowEpochSeconds()
        val windowStart = asOf - 730 * SECONDS_PER_DAY
        try {
            val events = fetchDividendEvents.execute(symbol, windowStart)
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

    /** Toggles the article's bookmark against the shared store; no-op if this VM was built
     *  without a bookmark use case (news key absent). */
    fun onToggleBookmark(article: NewsArticle) {
        val toggle = toggleBookmark ?: return
        scope.launch {
            try {
                val updated = toggle.execute(article)
                _state.update { it.copy(bookmarks = updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best-effort persistence; keep the last-good bookmark list
            }
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
                val chartWindow = if (shouldFetchCandles) {
                    fetchChartWindow.execute(symbol, timeframe).also { candlesTimeframe = timeframe }
                } else {
                    null
                }
                val candles = chartWindow?.candles?.map { c ->
                    ChartCandle(
                        c.open.amount.doubleValue(false), c.high.amount.doubleValue(false),
                        c.low.amount.doubleValue(false), c.close.amount.doubleValue(false),
                        c.volume,
                    )
                } ?: _state.value.candles
                val visibleStartIndex = chartWindow?.visibleStartIndex ?: _state.value.visibleStartIndex
                _state.update {
                    it.copy(
                        isLoadingChart = false, lineValues = lineValues,
                        candles = candles, visibleStartIndex = visibleStartIndex,
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
