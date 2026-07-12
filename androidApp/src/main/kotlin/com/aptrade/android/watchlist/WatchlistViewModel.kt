package com.aptrade.android.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.ui.userMessage
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.CreatePriceAlert
import com.aptrade.shared.application.EvaluateAlerts
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.LoadAlerts
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.RemovePriceAlert
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote
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
 *  for this symbol lands. [alertCount] is the row's untriggered-alert count, driving the bell's
 *  filled/outline state and badge (Task 6). */
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
    /** Untriggered-alert count per symbol, for the row bells. Symbols with no alerts (or
     *  only triggered ones) are simply absent — callers should default to 0. */
    val alertCounts: Map<String, Int> = emptyMap(),
)

/** Owns the persisted watchlist plus its live-quote poll — the Android counterpart to
 *  desktop's `WatchlistViewModel` (`desktopApp/.../watchlist/WatchlistViewModel.kt`).
 *
 *  Every quote refresh additionally runs [evaluateAlerts] over the freshly-fetched quotes —
 *  parity with desktop's `refreshAlerts()`: inline, every call, with no market-hours gate. A
 *  failure inside `EvaluateAlerts.execute` (persistence or the notifier) must never break the
 *  poll, so it is isolated the same way quote-fetch failures are: CancellationException
 *  rethrown, everything else swallowed (the row's alert badge simply keeps its last-good count
 *  for that tick).
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
    private val evaluateAlerts: EvaluateAlerts,
    private val loadAlerts: LoadAlerts,
    private val createPriceAlert: CreatePriceAlert,
    private val removePriceAlert: RemovePriceAlert,
    private val pollIntervalMs: Long = 15_000,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private val _state = MutableStateFlow(WatchlistUiState())
    val state: StateFlow<WatchlistUiState> = _state

    private var entries: List<WatchlistEntry> = emptyList()
    private var quotes: Map<String, Pair<String, Double>> = emptyMap() // symbol -> (amountText, change%)
    private var alertCounts: Map<String, Int> = emptyMap()
    // The full persisted alert list, kept for the sheet's existing-alerts panel — alertCounts
    // (above) is derived from EvaluateAlerts' per-tick pass and only tracks untriggered counts,
    // not the full per-symbol history the sheet needs to render/delete from.
    private var alerts: List<PriceAlert> = emptyList()
    private var pollJob: Job? = null

    /** Loads (and, on first launch, seeds via [fetchWatchlist]) the persisted watchlist, then
     *  fetches quotes and evaluates alerts for it. Suspend and self-contained — safe to await
     *  directly. */
    suspend fun load() {
        entries = fetchWatchlist.execute()
        refreshQuotes()
        refreshAlerts()
        publish(loading = false)
    }

    /** Starts (idempotently) the 15s live-quote poll: [load] once, then quotes/alerts refresh
     *  every tick thereafter. Call from a lifecycle-bound effect (mirrors `PortfolioScreen`'s
     *  `LifecycleStartEffect`); pair with [stop] on stop/dispose. */
    fun start() {
        if (pollJob != null) return
        pollJob = viewModelScope.launch {
            load()
            while (isActive) {
                delay(pollIntervalMs)
                refreshQuotes()
                refreshAlerts()
                publish(loading = false)
            }
        }
    }

    /** Cancels the poll loop started by [start]; safe to call even if never started. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Pull-to-refresh entry point: re-fetches quotes/alerts for the current entries. Fire-and-
     *  forget — the Screen observes [state] rather than awaiting this. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            refreshQuotes()
            refreshAlerts()
            publish(loading = false)
        }
    }

    /** Reloads the untriggered-alert counts without touching quotes — for the create/remove
     *  alert sheet to call after it mutates the alert list, so row bells update immediately
     *  rather than waiting for the next poll tick. */
    fun reloadAlerts() {
        viewModelScope.launch {
            refreshAlerts()
            publish(loading = _state.value.isLoading)
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
        refreshAlerts()
        publish(loading = false)
    }

    /** Alerts for one symbol, most-recently-created first — for the sheet's existing-alerts
     *  panel. Symbols with none simply return empty. */
    fun alertsFor(symbol: String): List<PriceAlert> =
        alerts.filter { it.symbol == symbol }.sortedByDescending { it.createdAtEpochSeconds }

    /** Creates a new alert via the shared use case, then reloads the alert list/counts on this
     *  same VM so the sheet's existing-alerts panel and the row bell both update immediately. */
    fun createAlert(symbol: String, condition: AlertCondition) {
        viewModelScope.launch {
            try {
                createPriceAlert.execute(symbol, condition, nowEpochSeconds())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Persist failure: the sheet simply won't show the new alert; nothing else in
                // the app depends on this succeeding.
            }
            reloadAlerts()
        }
    }

    /** Removes an alert by id via the shared use case, then reloads. */
    fun deleteAlert(id: String) {
        viewModelScope.launch {
            try {
                removePriceAlert.execute(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // keep the alert around in the UI on a failed delete; user can retry
            }
            reloadAlerts()
        }
    }

    private suspend fun refreshQuotes() {
        if (entries.isEmpty()) {
            quotes = emptyMap()
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
    }

    /** Runs [EvaluateAlerts] against this tick's freshly-fetched quotes (whatever
     *  [refreshQuotes] last populated `quotes` with — a poll failure already left that map at
     *  its last-good value, so alerts still evaluate against last-good prices rather than going
     *  blind for a tick). A failure here (persistence or the notifier) must never break the
     *  poll: CancellationException rethrows, everything else keeps the last-good
     *  `alertCounts`/`alerts`.
     *
     *  When `quotes` is empty (no watchlist entries yet, or the very first tick before a quote
     *  lands) this still loads the persisted alert list via [loadAlerts] — the sheet needs
     *  `alerts` populated (for its existing-alerts panel) even before any evaluation has run;
     *  only the untriggered-count derivation needs live quotes. Mirrors desktop's
     *  `WatchlistViewModel.refreshAlerts()` exactly. */
    private suspend fun refreshAlerts() {
        if (quotes.isEmpty()) {
            try {
                alerts = loadAlerts.execute()
                alertCounts = alerts.filter { !it.isTriggered }.groupingBy { it.symbol }.eachCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // keep last-good alerts/alertCounts
            }
            return
        }
        try {
            // EvaluateAlerts only reads `price`/`changePercent` (PriceAlert.isMet) — this map's
            // `quotes` cache doesn't retain `previousClose`, so it's set equal to `price` here;
            // it is inert for every current AlertCondition variant.
            val quoteMap = quotes.mapValues { (symbol, pair) ->
                val price = Money.usd(pair.first)
                Quote(symbol = symbol, price = price, previousClose = price, changePercent = pair.second)
            }
            val evaluated = evaluateAlerts.execute(quoteMap)
            alerts = evaluated
            alertCounts = evaluated.filter { !it.isTriggered }.groupingBy { it.symbol }.eachCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Alert evaluation is additive to the poll — never surface its failure as a
            // watchlist error banner; keep the last-good counts for this tick.
        }
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
                alertCount = alertCounts[e.symbol] ?: 0,
            )
        }
        _state.update { it.copy(isLoading = loading, rows = rows, alertCounts = alertCounts) }
    }
}
