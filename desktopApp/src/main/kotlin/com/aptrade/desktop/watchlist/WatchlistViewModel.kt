package com.aptrade.desktop.watchlist

import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.EvaluateAlerts
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WatchRow(
    val symbol: String,
    val name: String,
    val kind: AssetKind,
    val amountText: String?,      // exact decimal string; null until first quote lands
    val changePercent: Double?,
    val spark: List<Double> = emptyList(),
)

data class WatchlistUiState(
    val isLoading: Boolean = true,
    val kind: AssetKind = AssetKind.Stock,
    val rows: List<WatchRow> = emptyList(),          // filtered to `kind`, watchlist order
    val counts: Map<AssetKind, Int> = emptyMap(),
    val advancers: Int = 0,
    val decliners: Int = 0,
    val selectedSymbol: String? = null,
    val error: String? = null,
    val averageChange: Double? = null,        // mean change% across all entries; null when no quotes
    val averageSpark: List<Double> = emptyList(),  // per-index mean of percent-normalized row sparks
    /** Untriggered-alert count per symbol, for the row bells. Symbols with no alerts (or
     *  only triggered ones) are simply absent — callers should default to 0. */
    val alertCounts: Map<String, Int> = emptyMap(),
)

/** Owns the watchlist + 15s polling loop (quotes every tick, sparklines every
 *  `sparkEveryTicks`-th — the macOS cadence). Poll failures keep the last good
 *  rows and surface a banner; sparkline failures are silently tolerated.
 *
 *  Every tick additionally runs [evaluateAlerts] over the freshly-fetched quotes — macOS
 *  parity (Sources/APTradeApp/WatchlistViewModel.swift's `refresh(showIndicator:)`):
 *  inline, every call, with no market-hours gate. A notifier failure inside
 *  `EvaluateAlerts.execute` would otherwise propagate to this poll loop, so it is
 *  isolated the same way `refreshQuotes`/`refreshSparks` isolate repository failures:
 *  CancellationException rethrown, everything else swallowed (the row's alert badge
 *  simply keeps its last-good count for that tick).
 *
 *  `scope` MUST be single-thread-confined (Dispatchers.Main on desktop): the internal
 *  entries/quotes/sparks vars rely on that confinement instead of locks. */
class WatchlistViewModel(
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val fetchHistory: FetchHistory,
    private val fetchWatchlist: FetchWatchlist,
    private val addToWatchlist: AddToWatchlist,
    private val removeFromWatchlist: RemoveFromWatchlist,
    private val evaluateAlerts: EvaluateAlerts,
    private val scope: CoroutineScope,
    private val tickMillis: Long = 15_000,
    private val sparkEveryTicks: Int = 4,
) {
    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state

    private var entries: List<WatchlistEntry> = emptyList()
    private var quotes: Map<String, Pair<String, Double>> = emptyMap()  // symbol -> (amountText, change%)
    private var sparks: Map<String, List<Double>> = emptyMap()
    private var alertCounts: Map<String, Int> = emptyMap()
    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            entries = fetchWatchlist.execute()
            var tick = 0
            while (isActive) {
                refreshQuotes()
                if (tick % sparkEveryTicks == 0) refreshSparks()
                refreshAlerts()
                publish(loading = false)
                tick++
                delay(tickMillis)
            }
        }
    }

    fun refresh() {
        scope.launch { refreshQuotes(); refreshSparks(); refreshAlerts(); publish(loading = false) }
    }

    /** Reloads the untriggered-alert counts without touching quotes/sparks — for the
     *  create/remove alert sheet to call after it mutates the alert list, so row bells
     *  update immediately rather than waiting for the next poll tick. */
    fun reloadAlerts() {
        scope.launch { refreshAlerts(); publish(loading = _state.value.isLoading) }
    }

    fun onKindSelect(kind: AssetKind) {
        _state.update { it.copy(kind = kind) }
        publish(loading = _state.value.isLoading)
    }

    fun onSelect(symbol: String) = _state.update { it.copy(selectedSymbol = symbol) }

    fun onAdd(entry: WatchlistEntry) {
        scope.launch {
            entries = addToWatchlist.execute(entry)
            refreshQuotes()
            publish(loading = false)
        }
    }

    fun onRemove(symbol: String) {
        scope.launch {
            entries = removeFromWatchlist.execute(symbol)
            quotes = quotes - symbol
            sparks = sparks - symbol
            _state.update { if (it.selectedSymbol == symbol) it.copy(selectedSymbol = null) else it }
            publish(loading = false)
        }
    }

    private suspend fun refreshQuotes() {
        if (entries.isEmpty()) { quotes = emptyMap(); return }
        try {
            val fetched = fetchMarketQuotes.execute(entries.map { it.symbol })
            quotes = fetched.associate { it.symbol to (it.price.amountText to it.changePercent) }
            _state.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuoteError) {
            _state.update { it.copy(error = e.userMessage()) }   // keep last-good quotes
        }
    }

    /** Runs [EvaluateAlerts] against this tick's freshly-fetched quotes (whatever
     *  `refreshQuotes` last populated `quotes` with — a poll failure already left that
     *  map at its last-good value, so alerts still evaluate against last-good prices
     *  rather than going blind for a tick). A failure here (persistence or the notifier)
     *  must never break the poll: CancellationException rethrows, everything else keeps
     *  the last-good `alertCounts`. */
    private suspend fun refreshAlerts() {
        if (quotes.isEmpty()) return
        try {
            // EvaluateAlerts only reads `price`/`changePercent` (PriceAlert.isMet) — this
            // map's `quotes` cache doesn't retain `previousClose`, so it's set equal to
            // `price` here; it is inert for every current AlertCondition variant.
            val quoteMap = quotes.mapValues { (symbol, pair) ->
                val price = Money.usd(pair.first)
                Quote(symbol = symbol, price = price, previousClose = price, changePercent = pair.second)
            }
            val alerts = evaluateAlerts.execute(quoteMap)
            alertCounts = alerts.filter { !it.isTriggered }.groupingBy { it.symbol }.eachCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Alert evaluation is additive to the poll — never surface its failure as a
            // watchlist error banner; keep the last-good counts for this tick.
        }
    }

    private suspend fun refreshSparks() {
        val updated = sparks.toMutableMap()
        for (entry in entries) {
            try {
                updated[entry.symbol] = fetchHistory.execute(entry.symbol, Timeframe.OneDay)
                    .map { it.close.amount.doubleValue(false) }   // pixel math only
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                // sparklines are decoration — never fail the list for one
            }
        }
        sparks = updated
    }

    /** Each spark normalized to percent-change-from-first, then averaged per index across
     *  the series that reach that index. Raw prices can't be averaged across symbols. */
    private fun averageSpark(): List<Double> {
        val normalized = entries.mapNotNull { e ->
            val s = sparks[e.symbol] ?: return@mapNotNull null
            val first = s.firstOrNull()?.takeIf { it != 0.0 } ?: return@mapNotNull null
            s.map { (it / first - 1) * 100 }
        }
        val maxLen = normalized.maxOfOrNull { it.size } ?: 0
        if (maxLen < 2) return emptyList()
        return (0 until maxLen).map { i -> normalized.mapNotNull { it.getOrNull(i) }.average() }
    }

    private fun publish(loading: Boolean) {
        val current = _state.value
        val rows = entries.filter { it.kind == current.kind }.map { e ->
            val q = quotes[e.symbol]
            WatchRow(e.symbol, e.name, e.kind, q?.first, q?.second, sparks[e.symbol] ?: emptyList())
        }
        val changes = entries.mapNotNull { quotes[it.symbol]?.second }
        _state.update {
            it.copy(
                isLoading = loading,
                rows = rows,
                counts = entries.groupingBy { e -> e.kind }.eachCount(),
                advancers = changes.count { c -> c > 0 },
                decliners = changes.count { c -> c < 0 },
                averageChange = changes.takeIf { c -> c.isNotEmpty() }?.average(),
                averageSpark = averageSpark(),
                alertCounts = alertCounts,
            )
        }
    }
}
