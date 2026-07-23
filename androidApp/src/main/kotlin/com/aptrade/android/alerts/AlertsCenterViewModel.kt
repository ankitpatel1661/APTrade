package com.aptrade.android.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One symbol's alerts, grouped for the Alerts center list — Android twin of desktop's
 *  `AlertsCenterGroup` (`desktopApp/.../alerts/AlertsCenterViewModel.kt`), itself the Kotlin
 *  analog of Swift's `AlertsCenterGroup`. */
data class AlertsCenterGroup(val symbol: String, val alerts: List<PriceAlert>)

/** [AlertsCenterViewModel]'s published state: symbols sorted alphabetically, alerts within
 *  a symbol kept in stored order. [isEmpty] mirrors desktop's `isEmpty` computed property —
 *  true once loaded and there are no alerts at all, for the empty-state view. */
data class AlertsCenterUiState(val groups: List<AlertsCenterGroup> = emptyList()) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Drives the Alerts center: ALL price alerts across every symbol, grouped, removable, and
 * resolvable back to a full [Asset] for tap-through — reached from Home's bell/card (Task 3's
 * placeholders). Android twin of desktop
 * `desktopApp/src/main/kotlin/com/aptrade/desktop/alerts/AlertsCenterViewModel.kt`, transcribed
 * near-verbatim — every field/method here mirrors that class's SEMANTICS exactly (see its own
 * KDoc for the full source-by-source rationale).
 *
 * Reuses the SAME load/remove paths [com.aptrade.android.watchlist.WatchlistViewModel]/
 * `PriceAlertSheet` already use ([com.aptrade.shared.application.LoadAlerts]/
 * [com.aptrade.shared.application.RemovePriceAlert] over the shared
 * [com.aptrade.shared.application.AlertStore], injected here as plain suspend closures by
 * `AppGraph.makeAlertsCenterViewModel` — IO-dispatched at that factory seam per M10.3 Global
 * Constraint 1) — this view model never talks to the store directly.
 *
 * The one adaptation from the desktop twin is construction/scope (mirroring the house Android
 * VM convention — see [com.aptrade.android.plans.PlansViewModel]/
 * [com.aptrade.android.home.HomeViewModel]): this VM extends androidx [ViewModel] and uses
 * [viewModelScope] internally (`Dispatchers.Main.immediate`) rather than taking a
 * constructor-injected `CoroutineScope`. Public methods stay plain (non-suspend) event
 * handlers that internally `viewModelScope.launch` — the internal [watchlist] snapshot below
 * relies on `Dispatchers.Main.immediate`'s single-thread confinement instead of locks, same as
 * every other Android VM here.
 */
class AlertsCenterViewModel(
    private val loadAlerts: suspend () -> List<PriceAlert>,
    private val removeAlert: suspend (String) -> List<PriceAlert>,
    private val loadWatchlist: suspend () -> List<WatchlistEntry>,
) : ViewModel() {
    private val _state = MutableStateFlow(AlertsCenterUiState())
    val state: StateFlow<AlertsCenterUiState> = _state

    /** Watchlist snapshot for [asset]'s tap-through lookups — refreshed alongside every
     *  [load]. Snapshotted here (rather than re-read per lookup) so [asset] stays a plain,
     *  synchronous lookup the Compose row-click handler can call directly — mirrors desktop's
     *  identical rationale on its own `watchlist` field. */
    private var watchlist: List<WatchlistEntry> = emptyList()

    /** Loads every alert and the watchlist snapshot, then regroups by symbol. A load
     *  failure degrades gracefully to an empty list rather than crashing or leaving stale
     *  state — mirrors desktop's `load()`. */
    fun load() {
        viewModelScope.launch {
            watchlist = try {
                loadWatchlist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
            val alerts = try {
                loadAlerts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
            rebuild(alerts)
        }
    }

    /** Removes one alert via the injected (persisting) path and updates state from its
     *  returned, already-saved list — mirrors desktop's `remove(id)`. A persist failure leaves
     *  the row in place rather than optimistically removing it from state. */
    fun remove(id: String) {
        viewModelScope.launch {
            val alerts = try {
                removeAlert(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@launch
            }
            rebuild(alerts)
        }
    }

    /** Symbols sorted alphabetically; alerts within a symbol kept in stored order — mirrors
     *  desktop's `rebuild(from:)`. */
    private fun rebuild(alerts: List<PriceAlert>) {
        val bySymbol = alerts.groupBy { it.symbol }
        _state.value = AlertsCenterUiState(
            bySymbol.keys.sorted().map { symbol -> AlertsCenterGroup(symbol, bySymbol[symbol].orEmpty()) },
        )
    }

    /** Full [Asset] for tap-through to the detail screen. Looked up in the last-loaded
     *  [watchlist] snapshot; falls back to a minimal placeholder (name = symbol, kind
     *  inferred from suffix) if the symbol isn't — or is no longer — on the watchlist, so
     *  navigation never fails outright — mirrors desktop's `asset(symbol:)`.
     *
     *  Crypto assets use the "-USD" suffix (BTC-USD, ETH-USD) per `AppGraph.defaultEntries`
     *  / `WatchlistViewModel`'s house convention (the M10.1-earned invariant re-affirmed on
     *  desktop and re-affirmed here again rather than re-discovered per platform). */
    fun asset(symbol: String): Asset {
        val kind = if (symbol.endsWith("-USD")) AssetKind.Crypto else AssetKind.Stock
        val entry = watchlist.firstOrNull { it.symbol == symbol }
        return entry?.let { Asset(it.symbol, it.name, it.kind) } ?: Asset(symbol, symbol, kind)
    }
}
