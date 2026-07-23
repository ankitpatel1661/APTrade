package com.aptrade.desktop.alerts

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One symbol's alerts, grouped for the Alerts center list — Kotlin analog of Swift's
 *  `AlertsCenterGroup` (`Sources/APTradeApp/AlertsCenterViewModel.swift`). */
data class AlertsCenterGroup(val symbol: String, val alerts: List<PriceAlert>)

/** [AlertsCenterViewModel]'s published state: symbols sorted alphabetically, alerts within
 *  a symbol kept in stored order. [isEmpty] mirrors Swift's `isEmpty` computed property —
 *  true once loaded and there are no alerts at all, for the empty-state view. */
data class AlertsCenterUiState(val groups: List<AlertsCenterGroup> = emptyList()) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Drives the Alerts center: ALL price alerts across every symbol, grouped, removable, and
 * resolvable back to a full [Asset] for tap-through — reached from Home's bell/card (Task
 * 5). Compose port of `Sources/APTradeApp/AlertsCenterViewModel.swift`.
 *
 * Reuses the SAME load/remove paths [com.aptrade.desktop.watchlist.WatchlistViewModel] and
 * `PriceAlertSheet` already use ([com.aptrade.shared.application.LoadAlerts]/
 * [com.aptrade.shared.application.RemovePriceAlert] over the shared
 * [com.aptrade.shared.application.AlertStore], injected here as plain suspend closures by
 * `AppGraph.makeAlertsCenterViewModel`) — this view model never talks to the store
 * directly.
 *
 * Follows the house desktop-VM convention (see
 * [com.aptrade.desktop.plans.PlansViewModel]/[com.aptrade.desktop.home.HomeViewModel]): a
 * single [MutableStateFlow]-backed [state], public methods are plain (non-suspend) event
 * handlers that internally `scope.launch`. `scope` MUST be single-thread-confined
 * (Dispatchers.Main on desktop) — the internal [watchlist] snapshot below relies on that
 * confinement instead of locks, same as every other desktop VM here.
 */
class AlertsCenterViewModel(
    private val loadAlerts: suspend () -> List<PriceAlert>,
    private val removeAlert: suspend (String) -> List<PriceAlert>,
    private val loadWatchlist: suspend () -> List<WatchlistEntry>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AlertsCenterUiState())
    val state: StateFlow<AlertsCenterUiState> = _state

    /** Watchlist snapshot for [asset]'s tap-through lookups — refreshed alongside every
     *  [load]. Swift's `loadWatchlist: () -> [Asset]` is a synchronous closure re-read on
     *  every `asset(for:)` call; the desktop watchlist read is real file I/O (suspend), so
     *  it is snapshotted here instead and [asset] stays a plain, synchronous lookup the
     *  Compose row-click handler can call directly. */
    private var watchlist: List<WatchlistEntry> = emptyList()

    /** Loads every alert and the watchlist snapshot, then regroups by symbol. A load
     *  failure degrades gracefully to an empty list rather than crashing or leaving stale
     *  state — mirrors Swift's `(try? loadAlerts()) ?? []`. */
    fun load() {
        scope.launch {
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
     *  returned, already-saved list — mirrors Swift's `remove(_:)` /
     *  `WatchlistViewModel.deleteAlert`. A persist failure leaves the row in place rather
     *  than optimistically removing it from state. */
    fun remove(id: String) {
        scope.launch {
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
     *  Swift's `rebuild(from:)`. */
    private fun rebuild(alerts: List<PriceAlert>) {
        val bySymbol = alerts.groupBy { it.symbol }
        _state.value = AlertsCenterUiState(
            bySymbol.keys.sorted().map { symbol -> AlertsCenterGroup(symbol, bySymbol[symbol].orEmpty()) },
        )
    }

    /** Full [Asset] for tap-through to the detail screen. Looked up in the last-loaded
     *  [watchlist] snapshot; falls back to a minimal placeholder (name = symbol, kind
     *  inferred from suffix) if the symbol isn't — or is no longer — on the watchlist, so
     *  navigation never fails outright — mirrors Swift's `asset(for:)`.
     *
     *  Crypto assets use the "-USD" suffix (BTC-USD, ETH-USD) per `AppGraph.defaultEntries`
     *  / `WatchlistViewModel`'s house convention (the M10.1-earned invariant: this exact
     *  suffix-inference gap was found and fixed in the Swift Alerts center — see that
     *  fix's own history — so it is deliberately re-affirmed here rather than re-discovered
     *  the same way on this platform). */
    fun asset(symbol: String): Asset {
        val kind = if (symbol.endsWith("-USD")) AssetKind.Crypto else AssetKind.Stock
        val entry = watchlist.firstOrNull { it.symbol == symbol }
        return entry?.let { Asset(it.symbol, it.name, it.kind) } ?: Asset(symbol, symbol, kind)
    }
}
