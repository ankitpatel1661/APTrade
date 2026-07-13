package com.aptrade.desktop.detail

import com.aptrade.desktop.designkit.ChartCandle
import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.FetchChartWindow
import com.aptrade.shared.application.FetchCompanyNews
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchProfile
import com.aptrade.shared.application.LoadBookmarks
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ToggleBookmark
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.NewsArticle
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
        scope.launch {
            try {
                val asset = fetchProfile.execute(symbol)
                _state.update { it.copy(name = asset.name, kind = asset.kind) }
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
