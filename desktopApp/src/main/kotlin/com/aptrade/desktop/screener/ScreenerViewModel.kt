package com.aptrade.desktop.screener

import com.aptrade.shared.application.ScreenerScanAborted
import com.aptrade.shared.application.ScreenerScanEngine
import com.aptrade.shared.application.ScreenerSnapshotStore
import com.aptrade.shared.application.ScreenStore
import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.PresetScreen
import com.aptrade.shared.domain.ScreenCondition
import com.aptrade.shared.domain.ScreenSelection
import com.aptrade.shared.domain.ScreenerSnapshot
import com.aptrade.shared.domain.ScreenerSnapshotRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sortable columns for the screener results table.
 *
 *  [ActiveMetric] resolves to whichever field the active selection's "headline" metric is —
 *  see [ScreenerViewModel.activeMetricValue] for the exact preset -> field mapping (a custom
 *  screen uses its first condition's metric, falling back to `.price` when the screen has
 *  zero conditions). Transcribed from the Swift twin's `ScreenerSortColumn`. */
enum class ScreenerSortColumn { Symbol, Price, DayChange, ActiveMetric }

/** Lifecycle of a scan-in-progress. [Scanning]'s `done`/`total` mirror the engine's
 *  `onProgress(completedCount, total)` callback, batch by batch. Transcribed from the Swift
 *  twin's `ScreenerViewModel.ScanState`. */
sealed class ScreenerScanState {
    object Idle : ScreenerScanState()
    data class Scanning(val done: Int, val total: Int) : ScreenerScanState()
    object Failed : ScreenerScanState()
}

/** Everything the Screener pane renders: scan lifecycle, the persisted snapshot, the saved
 *  custom screens, the active selection, the sorted/filtered result rows, and the current
 *  sort. Kept as one immutable snapshot (rather than several independent `StateFlow`s) per
 *  house convention — see [com.aptrade.desktop.plans.PlansUiState] / IncomeUiState. */
data class ScreenerUiState(
    val scanState: ScreenerScanState = ScreenerScanState.Idle,
    val snapshot: ScreenerSnapshot? = null,
    val savedScreens: List<CustomScreen> = emptyList(),
    val selection: ScreenSelection = ScreenSelection.Preset(PresetScreen.RsiOversold),
    val results: List<ScreenerSnapshotRow> = emptyList(),
    val sortColumn: ScreenerSortColumn = ScreenerSortColumn.Symbol,
    val sortAscending: Boolean = true,
)

/**
 * Screener tab: orchestrates full-universe technical scans, holds the active screen
 * selection (preset or custom), and produces the sorted, filtered result rows the view
 * renders. Every scan is throttled and reported incrementally by [ScreenerScanEngine]; this
 * view model's job is purely to drive that engine, persist its output, and turn the
 * persisted snapshot + active selection + sort into `results`.
 *
 * Transcribed from `Sources/APTradeApp/ScreenerViewModel.swift` AS-BUILT (including the
 * final-review fix wave) — every review-earned rule below must survive:
 *  - a VM-owned, cancellable scan [Job] with an ownership-token compare on completion
 *    ([startScan]/[cancelScan]);
 *  - [CancellationException] during a scan -> [ScreenerScanState.Idle], nothing persisted;
 *  - a TOTAL failure (empty rows + non-empty failedSymbols) -> [ScreenerScanState.Failed]
 *    with the PREVIOUS snapshot and results KEPT (no save);
 *  - a partial failure still succeeds and saves;
 *  - the selection re-sync rule in [saveScreen]/[deleteScreen];
 *  - `results` = `selection.evaluate(...)` sorted, with nil-metric rows sorted LAST in BOTH
 *    directions;
 *  - [isSnapshotFresh] via the shared [MarketCalendar] + injected [nowEpochSeconds];
 *  - [matchCount] evaluated ad hoc against the current snapshot;
 *  - the re-entry guard on the stored scan [Job].
 *
 * DOCUMENTED DIVERGENCE — `ScreenerScanAborted`: Task 3's Kotlin
 * [ScreenerScanEngine] additionally throws [ScreenerScanAborted] after 3 CONSECUTIVE
 * rate-limited batches (a mandated engine improvement with no Swift equivalent — the Swift
 * engine has no such ceiling). That exception is mapped to [ScreenerScanState.Failed] here,
 * exactly like any other non-cancellation engine throw — this is the Kotlin twin's EXTRA
 * real path to `Failed` beyond Swift's (whose catch-all `.failed` branch is unreachable
 * today, kept only for a future non-cancellation throw that never materialized on that
 * side).
 *
 * Follows the house desktop-VM convention (see [com.aptrade.desktop.plans.PlansViewModel] /
 * [com.aptrade.desktop.search.SearchViewModel]): a single [MutableStateFlow]-backed [state],
 * public methods are plain (non-suspend) event handlers that internally `scope.launch`
 * (except [scan], which is also exposed directly as a `suspend` entry point — mirroring the
 * Swift twin's `scan()`, callable either directly or via the VM-owned [startScan] task), and
 * [scope] MUST be single-thread-confined (Dispatchers.Main on desktop) — the same contract
 * every other desktop VM in this codebase relies on instead of locks.
 */
class ScreenerViewModel(
    private val engine: ScreenerScanEngine,
    private val snapshotStore: ScreenerSnapshotStore,
    private val screenStore: ScreenStore,
    private val symbols: List<String>,
    private val names: Map<String, String>,
    private val scope: CoroutineScope,
    private val calendar: MarketCalendar = MarketCalendar(),
    private val nowEpochSeconds: () -> Long,
) {
    private val _state: MutableStateFlow<ScreenerUiState>
    val state: StateFlow<ScreenerUiState>

    /** Owns the unstructured scan job started by [startScan]. Holding it here (rather than
     *  letting the view own an unstructured `scope.launch { viewModel.scan() }`) is what
     *  lets [cancelScan] actually stop a scan when the view that started it is torn down —
     *  a view-owned coroutine has no such hook and keeps running orphaned, which is how a
     *  return-and-rescan used to fire two overlapping engines (the bug `startScan`/
     *  `cancelScan` fix). */
    private var scanJob: Job? = null

    /** Identifies which [scanJob] is currently owned, since a [Job] reference alone can be
     *  reassigned out from under a completion callback with no way to tell whether IT is
     *  still the one in play. [startScan]'s completion step captures the token it was
     *  minted with and only clears [scanJob]/[scanJobToken] if the stored token still
     *  matches — i.e. if THIS run is still the one [scanJob] points at.
     *
     *  Why this matters even though [scan] deliberately SWALLOWS
     *  [CancellationException] (see [scan]'s doc): [cancelScan] cancels [scanJob] and
     *  immediately clears both fields SYNCHRONOUSLY, before the cancelled coroutine has
     *  actually unwound (cancellation is cooperative — the engine only notices at its next
     *  suspension point). Because [scan] never rethrows the cancellation, the cancelled
     *  job's own completion step (inside [startScan]'s `launch` block) still runs to
     *  completion afterward and would, without this guard, unconditionally null out
     *  whatever [scanJob] a *subsequent* [startScan] call has since stored — leaving a
     *  live, in-flight scan un-cancellable by a later [cancelScan]. Comparing tokens closes
     *  that window: [cancelScan] already nulled [scanJobToken], so the stale completion's
     *  token comparison fails and it correctly skips clearing the new job. */
    private var scanJobToken: Any? = null

    init {
        val snapshot = snapshotStore.load()
        val savedScreens = screenStore.load()
        val selection: ScreenSelection = ScreenSelection.Preset(PresetScreen.RsiOversold)
        _state = MutableStateFlow(
            ScreenerUiState(
                snapshot = snapshot,
                savedScreens = savedScreens,
                selection = selection,
                results = computeResults(snapshot, selection, ScreenerSortColumn.Symbol, sortAscending = true),
            ),
        )
        state = _state
    }

    /** True when the persisted snapshot was scanned on today's trading day (market-local).
     *  False when there's no snapshot at all. */
    fun isSnapshotFresh(): Boolean {
        val snapshot = _state.value.snapshot ?: return false
        return snapshot.tradingDay == calendar.tradingDay(nowEpochSeconds())
    }

    /**
     * Runs a full-universe scan. Ignored while a scan is already in flight (a same-state
     * guard that covers a caller invoking [scan] directly, independent of the stored-job
     * guard [startScan] adds for view-driven calls). On success, the new snapshot is
     * persisted and `results` are rebuilt.
     *
     * On cancellation — the engine only ever throws [CancellationException], via
     * cooperative cancellation checks between batches; every per-symbol fetch failure is
     * instead recorded in `failedSymbols` and does not fail the scan — `scanState` reverts
     * to [ScreenerScanState.Idle] and nothing is persisted, matching the spec's
     * "interrupted -> nothing persisted." The [CancellationException] is deliberately NOT
     * rethrown: mirroring the Swift twin's `catch is CancellationError` (which also
     * swallows rather than rethrows), this lets [startScan]'s wrapper job always reach its
     * own token-guarded cleanup step afterward instead of being torn down by the
     * exception — see [scanJobToken]'s doc for why that cleanup step still matters even
     * though [cancelScan] already clears the fields synchronously.
     *
     * A total network failure (every symbol fetch failed) surfaces as an engine *success*
     * — the engine only throws for cancellation or [ScreenerScanAborted] — with an empty
     * `rows` and `failedSymbols` covering the whole universe. Persisting that would clobber
     * a good previous snapshot with nothing and show "0 scanned" with no way back. Per
     * spec, that case is treated as a failure instead: `scanState` flips to
     * [ScreenerScanState.Failed] and neither the store nor `snapshot`/`results` are
     * touched, so the prior snapshot (and its results) survive and the view's `.failed`
     * state offers retry. A scan that legitimately returns zero rows with zero failures
     * (e.g. an empty symbol universe) is not this case and still saves normally.
     *
     * [ScreenerScanAborted] — thrown by the engine after 3 consecutive rate-limited
     * batches (a Kotlin-only engine improvement, see this class's divergence doc) — is a
     * genuine failure: it falls through to the same catch-all as any other non-
     * cancellation throw, flipping to [ScreenerScanState.Failed] with the previous
     * snapshot kept.
     */
    suspend fun scan() {
        if (_state.value.scanState is ScreenerScanState.Scanning) return
        _state.update { it.copy(scanState = ScreenerScanState.Scanning(done = 0, total = symbols.size)) }
        try {
            val newSnapshot = engine.scan(
                symbols = symbols,
                names = names,
                nowEpochSeconds = nowEpochSeconds(),
                onProgress = { done, total ->
                    _state.update { it.copy(scanState = ScreenerScanState.Scanning(done, total)) }
                },
            )
            if (newSnapshot.rows.isEmpty() && newSnapshot.failedSymbols.isNotEmpty()) {
                _state.update { it.copy(scanState = ScreenerScanState.Failed) }
                return
            }
            snapshotStore.save(newSnapshot)
            _state.update {
                it.copy(
                    snapshot = newSnapshot,
                    scanState = ScreenerScanState.Idle,
                    results = computeResults(newSnapshot, it.selection, it.sortColumn, it.sortAscending),
                )
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(scanState = ScreenerScanState.Idle) }
        } catch (e: Exception) {
            // Covers ScreenerScanAborted (see class doc) and any other genuine engine
            // failure. No path produces this today besides ScreenerScanAborted, but the
            // branch stays in place for when one does — matching the Swift twin's own
            // catch-all, which is unreachable there for the same reason.
            _state.update { it.copy(scanState = ScreenerScanState.Failed) }
        }
    }

    /** Owns the unstructured scan job so the view never has to. Re-entry guard is the
     *  stored job itself — a second [startScan] call while one is already owned here is
     *  silently ignored — independent of (and in addition to) [scan]'s own state-based
     *  guard. Pair every call site with [cancelScan] when the view driving this tab is torn
     *  down, so a scan started on it can't keep running (and racing a fresh scan)
     *  afterward. */
    fun startScan() {
        if (scanJob != null) return
        val token = Any()
        scanJobToken = token
        scanJob = scope.launch {
            scan()
            // Only clear the stored handle if it's still THIS run's token — see
            // scanJobToken's doc.
            if (scanJobToken === token) {
                scanJob = null
                scanJobToken = null
            }
        }
    }

    /** Cancels the in-flight scan job, if any, and immediately releases ownership of it (so
     *  a subsequent [startScan] isn't blocked by a job that's merely winding down). Clearing
     *  [scanJobToken] here too means the cancelled job's own completion (in [startScan])
     *  will find its token stale once it eventually runs, and will correctly skip clearing
     *  whatever [scanJob] a later [startScan] has since stored. Cancellation itself is
     *  cooperative — the engine only notices at its next suspension point between batches —
     *  so the visible `scanState` reversion to [ScreenerScanState.Idle] happens
     *  asynchronously inside [scan]'s catch clause, not here. */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        scanJobToken = null
    }

    fun select(selection: ScreenSelection) {
        _state.update {
            it.copy(
                selection = selection,
                results = computeResults(it.snapshot, selection, it.sortColumn, it.sortAscending),
            )
        }
    }

    fun setSortColumn(column: ScreenerSortColumn) {
        _state.update {
            it.copy(
                sortColumn = column,
                results = computeResults(it.snapshot, it.selection, column, it.sortAscending),
            )
        }
    }

    fun setSortAscending(ascending: Boolean) {
        _state.update {
            it.copy(
                sortAscending = ascending,
                results = computeResults(it.snapshot, it.selection, it.sortColumn, ascending),
            )
        }
    }

    /**
     * Insert-or-replace by `id`, then persists the full list.
     *
     * Also re-syncs `selection` so it can never point at a stale value: if the screen being
     * saved was ALREADY the active selection (matched by `id` — the old and new
     * [CustomScreen] values are otherwise `!=` the moment a condition changes), the active
     * selection is replaced with the freshly-saved value so `results` reflect the edit
     * immediately rather than showing pre-edit matches against a chip that no longer even
     * renders as selected. Symmetrically, saving a BRAND-NEW screen (one `savedScreens`
     * didn't already contain) auto-selects it, so a user who just built a screen sees its
     * results without an extra tap. Editing some OTHER, non-active screen leaves
     * `selection` untouched in both cases.
     */
    fun saveScreen(screen: CustomScreen) {
        val current = _state.value
        val wasNew = current.savedScreens.none { it.id == screen.id }
        val wasActiveSelection = (current.selection as? ScreenSelection.Custom)?.screen?.id == screen.id

        val updatedScreens = if (current.savedScreens.any { it.id == screen.id }) {
            current.savedScreens.map { if (it.id == screen.id) screen else it }
        } else {
            current.savedScreens + screen
        }
        screenStore.save(updatedScreens)

        val newSelection: ScreenSelection = if (wasNew || wasActiveSelection) {
            ScreenSelection.Custom(screen)
        } else {
            current.selection
        }
        _state.update {
            it.copy(
                savedScreens = updatedScreens,
                selection = newSelection,
                results = computeResults(it.snapshot, newSelection, it.sortColumn, it.sortAscending),
            )
        }
    }

    /** Removes the screen, persists the list, then — if the deleted screen was the active
     *  selection — falls back to the default preset. Leaving `selection` pointed at a
     *  [ScreenSelection.Custom] screen no longer present in `savedScreens` would strand
     *  `results` on a screen with no corresponding chip left to show it was ever active. */
    fun deleteScreen(id: String) {
        val current = _state.value
        val wasActiveSelection = (current.selection as? ScreenSelection.Custom)?.screen?.id == id

        val updatedScreens = current.savedScreens.filterNot { it.id == id }
        screenStore.save(updatedScreens)

        val newSelection: ScreenSelection = if (wasActiveSelection) {
            ScreenSelection.Preset(PresetScreen.RsiOversold)
        } else {
            current.selection
        }
        _state.update {
            it.copy(
                savedScreens = updatedScreens,
                selection = newSelection,
                results = computeResults(it.snapshot, newSelection, it.sortColumn, it.sortAscending),
            )
        }
    }

    /** Live match count for the builder dialog: how many rows in the CURRENT snapshot would
     *  pass `conditions` if saved as a screen right now. Mirrors [ScreenSelection.Custom]'s
     *  "empty conditions match nothing" rule — an unbuilt screen should never claim to match
     *  the whole universe. Zero (not the universe size) when there's no snapshot yet. */
    fun matchCount(conditions: List<ScreenCondition>): Int {
        val snapshot = _state.value.snapshot ?: return 0
        if (conditions.isEmpty()) return 0
        return snapshot.rows.count { row -> conditions.all { it.matches(row) } }
    }

    // MARK: - Results

    private fun computeResults(
        snapshot: ScreenerSnapshot?,
        selection: ScreenSelection,
        sortColumn: ScreenerSortColumn,
        sortAscending: Boolean,
    ): List<ScreenerSnapshotRow> {
        val rows = snapshot?.rows ?: return emptyList()
        return sortRows(selection.evaluate(rows), selection, sortColumn, sortAscending)
    }

    private fun sortRows(
        rows: List<ScreenerSnapshotRow>,
        selection: ScreenSelection,
        sortColumn: ScreenerSortColumn,
        sortAscending: Boolean,
    ): List<ScreenerSnapshotRow> = when (sortColumn) {
        ScreenerSortColumn.Symbol ->
            rows.sortedWith(if (sortAscending) compareBy { it.symbol } else compareByDescending { it.symbol })
        // `close` is never null, so every row sorts as a normal value here.
        ScreenerSortColumn.Price ->
            rows.sortedWith(if (sortAscending) compareBy { it.close } else compareByDescending { it.close })
        ScreenerSortColumn.DayChange, ScreenerSortColumn.ActiveMetric ->
            sortByNullableMetric(rows, selection, sortColumn, sortAscending)
    }

    /** Shared nil-last sort for the two columns backed by a nullable [Double]: rows with a
     *  defined value sort by it (respecting `sortAscending`); rows with a null value always
     *  sort after every defined row, in both directions, in their original relative order. */
    private fun sortByNullableMetric(
        rows: List<ScreenerSnapshotRow>,
        selection: ScreenSelection,
        sortColumn: ScreenerSortColumn,
        sortAscending: Boolean,
    ): List<ScreenerSnapshotRow> {
        val keyed = rows.map { it to metricValue(it, selection, sortColumn) }
        val withValue = keyed.filter { it.second != null }
            .sortedWith(if (sortAscending) compareBy { it.second } else compareByDescending { it.second })
        val withoutValue = keyed.filter { it.second == null }
        return (withValue + withoutValue).map { it.first }
    }

    private fun metricValue(
        row: ScreenerSnapshotRow,
        selection: ScreenSelection,
        sortColumn: ScreenerSortColumn,
    ): Double? = when (sortColumn) {
        ScreenerSortColumn.Symbol, ScreenerSortColumn.Price -> null // unreachable: handled directly in sortRows
        ScreenerSortColumn.DayChange -> row.dayChangePercent
        ScreenerSortColumn.ActiveMetric -> activeMetricValue(row, selection)
    }

    /**
     * The column value implied by the active selection's "headline" metric — the number a
     * user of that screen cares about most.
     *
     * Preset mapping (each is the metric that defines the signal):
     *  - `RsiOversold` / `RsiOverbought` -> `rsi14`
     *  - `MacdBullishCross` / `MacdBearishCross` -> `macdHistogram`
     *  - `GoldenCross` / `DeathCross` -> `pctVsSma50` (the faster average's relation to
     *    price is the more sensitive read on a moving-average cross)
     *  - `BollingerSqueeze` -> `bollingerBandwidth`
     *  - `Near52wHigh` -> `pctTo52wHigh`
     *  - `Near52wLow` -> `pctTo52wLow`
     *
     * Custom screens use their first condition's metric column; a screen with zero
     * conditions (unbuilt) falls back to `.price` rather than an undefined column.
     */
    private fun activeMetricValue(row: ScreenerSnapshotRow, selection: ScreenSelection): Double? = when (selection) {
        is ScreenSelection.Preset -> when (selection.preset) {
            PresetScreen.RsiOversold, PresetScreen.RsiOverbought -> row.rsi14
            PresetScreen.MacdBullishCross, PresetScreen.MacdBearishCross -> row.macdHistogram
            PresetScreen.GoldenCross, PresetScreen.DeathCross -> row.pctVsSma50
            PresetScreen.BollingerSqueeze -> row.bollingerBandwidth
            PresetScreen.Near52wHigh -> row.pctTo52wHigh
            PresetScreen.Near52wLow -> row.pctTo52wLow
        }
        is ScreenSelection.Custom -> {
            val metric = selection.screen.conditions.firstOrNull()?.metric
            if (metric == null) row.close else metric.value(row)
        }
    }
}
