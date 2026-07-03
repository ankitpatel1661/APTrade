package com.aptrade.desktop.watchlist

import com.aptrade.desktop.ui.userMessage
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.domain.AssetKind
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
)

/** Owns the watchlist + 15s polling loop (quotes every tick, sparklines every
 *  `sparkEveryTicks`-th — the macOS cadence). Poll failures keep the last good
 *  rows and surface a banner; sparkline failures are silently tolerated. */
class WatchlistViewModel(
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val fetchHistory: FetchHistory,
    private val fetchWatchlist: FetchWatchlist,
    private val addToWatchlist: AddToWatchlist,
    private val removeFromWatchlist: RemoveFromWatchlist,
    private val scope: CoroutineScope,
    private val tickMillis: Long = 15_000,
    private val sparkEveryTicks: Int = 4,
) {
    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state

    private var entries: List<WatchlistEntry> = emptyList()
    private var quotes: Map<String, Pair<String, Double>> = emptyMap()  // symbol -> (amountText, change%)
    private var sparks: Map<String, List<Double>> = emptyMap()
    private var pollJob: Job? = null

    fun start() {
        if (pollJob != null) return
        pollJob = scope.launch {
            entries = fetchWatchlist.execute()
            var tick = 0
            while (isActive) {
                refreshQuotes()
                if (tick % sparkEveryTicks == 0) refreshSparks()
                publish(loading = false)
                tick++
                delay(tickMillis)
            }
        }
    }

    fun refresh() {
        scope.launch { refreshQuotes(); refreshSparks(); publish(loading = false) }
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
            )
        }
    }
}
