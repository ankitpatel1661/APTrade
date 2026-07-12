package com.aptrade.android.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One rendered watchlist row. [amountText] is the exact-decimal string straight off
 *  [com.aptrade.shared.domain.Money.amountText] — never a Double — null until the first quote
 *  for this symbol lands. [alertCount] is always 0 until Task 6 wires alerts through. */
data class WatchRow(
    val symbol: String,
    val name: String,
    val kind: AssetKind,
    val amountText: String?,
    val changePercent: Double?,
    val alertCount: Int = 0,
)

data class WatchlistUiState(
    val isLoading: Boolean = true,
    val rows: List<WatchRow> = emptyList(),
    val error: String? = null,
)

/** Owns the persisted watchlist plus its live-quote poll — the Android counterpart to
 *  desktop's `WatchlistViewModel` (`desktopApp/.../watchlist/WatchlistViewModel.kt`), trimmed to
 *  what this screen needs: no kind filter, sparklines, or alerts yet (Task 6 layers alerts on top
 *  via [WatchRow.alertCount]). Seeding on first launch is [fetchWatchlist]'s job (`FetchWatchlist`
 *  persists the default entries itself) — this class never seeds.
 *
 *  [load] and [remove] are plain suspend functions rather than [viewModelScope]-launched fire-
 *  and-forget calls: that lets a test drive them directly on its own `runTest` coroutine, with no
 *  `Dispatchers.setMain` ceremony, and lets them complete deterministically before the caller's
 *  next line runs. [start]/[stop] wrap the 15s poll loop for the production, lifecycle-bound path
 *  (mirrors `PortfolioViewModel`'s start()/stop() convention); [refresh] is the fire-and-forget
 *  pull-to-refresh entry point. */
class WatchlistViewModel(
    private val fetchWatchlist: FetchWatchlist,
    private val addToWatchlist: AddToWatchlist,
    private val removeFromWatchlist: RemoveFromWatchlist,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val pollIntervalMs: Long = 15_000,
) : ViewModel() {

    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state

    private var entries: List<WatchlistEntry> = emptyList()
    private var quotes: Map<String, Pair<String, Double>> = emptyMap() // symbol -> (amountText, change%)
    private var pollJob: Job? = null

    /** Loads (and, on first launch, seeds via [fetchWatchlist]) the persisted watchlist, then
     *  fetches quotes for it. Suspend and self-contained — safe to await directly. */
    suspend fun load() {
        entries = fetchWatchlist.execute()
        refreshQuotes()
    }

    /** Starts (idempotently) the 15s live-quote poll: [load] once, then [refreshQuotes] every
     *  tick thereafter. Call from a lifecycle-bound effect (mirrors `PortfolioScreen`'s
     *  `LifecycleStartEffect`); pair with [stop] on stop/dispose. */
    fun start() {
        if (pollJob != null) return
        pollJob = viewModelScope.launch {
            load()
            while (isActive) {
                delay(pollIntervalMs)
                refreshQuotes()
            }
        }
    }

    /** Cancels the poll loop started by [start]; safe to call even if never started. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Pull-to-refresh entry point: re-fetches quotes for the current entries. Fire-and-forget —
     *  the Screen observes [state] rather than awaiting this. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            refreshQuotes()
        }
    }

    /** Removes [symbol] from the persisted watchlist and republishes immediately. Suspend (not
     *  [viewModelScope]-routed) so a swipe-to-remove gesture — or a test — can await the exact
     *  moment the row disappears and the store is durably updated. */
    suspend fun remove(symbol: String) {
        entries = removeFromWatchlist.execute(symbol)
        quotes = quotes - symbol
        publish(loading = false)
    }

    /** Re-adds [entry] — the "Undo" counterpart to [remove] after an accidental swipe — and
     *  refreshes its quote immediately so the row doesn't sit blank until the next poll tick. */
    suspend fun add(entry: WatchlistEntry) {
        entries = addToWatchlist.execute(entry)
        refreshQuotes()
    }

    private suspend fun refreshQuotes() {
        if (entries.isEmpty()) {
            quotes = emptyMap()
            publish(loading = false)
            return
        }
        try {
            val fetched = fetchMarketQuotes.execute(entries.map { it.symbol })
            quotes = fetched.associate { it.symbol to (it.price.amountText to it.changePercent) }
            _state.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuoteError) {
            _state.update { it.copy(error = e.userMessage()) } // keep last-good quotes
        }
        publish(loading = false)
    }

    private fun publish(loading: Boolean) {
        val rows = entries.map { e ->
            val q = quotes[e.symbol]
            WatchRow(
                symbol = e.symbol,
                name = e.name,
                kind = e.kind,
                amountText = q?.first,
                changePercent = q?.second,
            )
        }
        _state.update { it.copy(isLoading = loading, rows = rows) }
    }
}
