package com.aptrade.android.screener

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.android.ui.formatPercent
import com.aptrade.android.ui.money
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.PresetScreen
import com.aptrade.shared.domain.SP500Symbols
import com.aptrade.shared.domain.ScreenSelection
import com.aptrade.shared.domain.ScreenerMetric
import com.aptrade.shared.domain.ScreenerSnapshotRow
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.infrastructure.SP500Names
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - Formatting helpers (transcribed from desktop ScreenerPane.kt's private helpers of
// the same name — `en_US` fixed-locale numeric/date readouts, independent of display language)

private val screenerScanTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(ZoneId.systemDefault())

private fun formatScanTime(epochSeconds: Long): String =
    screenerScanTimeFormatter.format(Instant.ofEpochSecond(epochSeconds))

private fun formatPlain(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

/** Unsigned percent readout for metrics that are always >= 0 (52-week distances) — a forced
 *  "+" (as [formatPercent] would apply) would misread as a directional change these values
 *  never carry. */
private fun formatUnsignedPercent(value: Double): String = "${formatPlain(value, 2)}%"

// MARK: - Active metric column (display-only duplication of the VM's private
// activeMetricValue mapping — see ScreenerViewModel's own KDoc for why this duplication is
// deliberate; transcribed from desktop ScreenerPane.kt's identical private ActiveMetricColumn)

private data class ActiveMetricColumn(
    val titleKey: L10n.Key,
    val value: (ScreenerSnapshotRow) -> Double?,
    val format: (Double) -> String,
)

private fun activeMetricColumn(selection: ScreenSelection): ActiveMetricColumn? = when (selection) {
    is ScreenSelection.Preset -> when (selection.preset) {
        PresetScreen.RsiOversold, PresetScreen.RsiOverbought ->
            ActiveMetricColumn(L10n.Key.MetricRsi, { it.rsi14 }, { formatPlain(it, 1) })
        PresetScreen.MacdBullishCross, PresetScreen.MacdBearishCross ->
            ActiveMetricColumn(L10n.Key.IndicatorMACD, { it.macdHistogram }, { formatPlain(it, 2) })
        PresetScreen.GoldenCross, PresetScreen.DeathCross ->
            ActiveMetricColumn(L10n.Key.MetricVsSma50, { it.pctVsSma50 }, ::formatPercent)
        PresetScreen.BollingerSqueeze ->
            ActiveMetricColumn(L10n.Key.MetricBandwidth, { it.bollingerBandwidth }, { formatPlain(it, 4) })
        PresetScreen.Near52wHigh ->
            ActiveMetricColumn(L10n.Key.MetricTo52wHigh, { it.pctTo52wHigh }, ::formatUnsignedPercent)
        PresetScreen.Near52wLow ->
            ActiveMetricColumn(L10n.Key.MetricTo52wLow, { it.pctTo52wLow }, ::formatUnsignedPercent)
    }
    is ScreenSelection.Custom -> {
        val metric = selection.screen.conditions.firstOrNull()?.metric
        if (metric == null) null else activeMetricColumn(metric)
    }
}

private fun activeMetricColumn(metric: ScreenerMetric): ActiveMetricColumn? = when (metric) {
    ScreenerMetric.price, ScreenerMetric.dayChangePercent -> null
    ScreenerMetric.rsi14 ->
        ActiveMetricColumn(L10n.Key.MetricRsi, { it.rsi14 }, { formatPlain(it, 1) })
    ScreenerMetric.bollingerPercentB ->
        ActiveMetricColumn(L10n.Key.MetricPercentB, { it.bollingerPercentB }, { formatPlain(it, 4) })
    ScreenerMetric.bollingerBandwidth ->
        ActiveMetricColumn(L10n.Key.MetricBandwidth, { it.bollingerBandwidth }, { formatPlain(it, 4) })
    ScreenerMetric.pctTo52wHigh ->
        ActiveMetricColumn(L10n.Key.MetricTo52wHigh, { it.pctTo52wHigh }, ::formatUnsignedPercent)
    ScreenerMetric.pctTo52wLow ->
        ActiveMetricColumn(L10n.Key.MetricTo52wLow, { it.pctTo52wLow }, ::formatUnsignedPercent)
    ScreenerMetric.relativeVolume ->
        ActiveMetricColumn(L10n.Key.MetricRelVolume, { it.relativeVolume }, { formatPlain(it, 2) + "×" })
    ScreenerMetric.pctVsSma50 ->
        ActiveMetricColumn(L10n.Key.MetricVsSma50, { it.pctVsSma50 }, ::formatPercent)
    ScreenerMetric.pctVsSma200 ->
        ActiveMetricColumn(L10n.Key.MetricVsSma200, { it.pctVsSma200 }, ::formatPercent)
}

/** Single-source preset-title mapping (M10.3 Task 3, Global Constraint 5): NOT `private` —
 *  [com.aptrade.android.home.HomeScreen] reuses this ONE definition for both the Today feed's
 *  `.screenerFresh` row and the Screener quick card's subtitle, rather than keeping a second
 *  copy in sync (mirrors desktop's own hoist, `com.aptrade.desktop.screener.presetTitleKey`,
 *  reused verbatim by `HomePane.kt`). */
internal fun presetTitleKey(preset: PresetScreen): L10n.Key = when (preset) {
    PresetScreen.RsiOversold -> L10n.Key.PresetRsiOversold
    PresetScreen.RsiOverbought -> L10n.Key.PresetRsiOverbought
    PresetScreen.MacdBullishCross -> L10n.Key.PresetMacdBullish
    PresetScreen.MacdBearishCross -> L10n.Key.PresetMacdBearish
    PresetScreen.GoldenCross -> L10n.Key.PresetGoldenCross
    PresetScreen.DeathCross -> L10n.Key.PresetDeathCross
    PresetScreen.BollingerSqueeze -> L10n.Key.PresetBollingerSqueeze
    PresetScreen.Near52wHigh -> L10n.Key.PresetNear52wHigh
    PresetScreen.Near52wLow -> L10n.Key.PresetNear52wLow
}

/**
 * Screener tab: nine curated technical presets plus user-saved custom screens, a
 * full-universe scan bar, and sortable results. Fifth bottom-nav destination (M9.3 Task 3),
 * phone-narrow adaptation of the desktop-wide table in
 * `desktopApp/src/main/kotlin/com/aptrade/desktop/screener/ScreenerPane.kt` (behavior
 * reference — chips row, 4-state scan bar, order-sensitive empty-state cascade, header-click
 * sort toggling, per-row watchlist star) and the phone-narrow layout of
 * `Sources/APTradeApp/ScreenerView.swift`'s iOS row (single active-metric column, compact
 * rows) — layout only, no Swift code transcribed.
 *
 * Constructs its own [ScreenerViewModel] via `viewModel { }` from [AppGraph] pieces, per the
 * `DetailScreen.kt` precedent — no key, so this instance is scoped to the shared "shell"
 * NavBackStackEntry and (like the other four tabs) SURVIVES a tab switch away and back.
 *
 * [onOpenDetail] routes through the shared `detail/{symbol}` NavHost destination (carries
 * Buy), matching how `AppNavHost` threads the same callback into Watchlist/Portfolio.
 *
 * The "+" new-screen chip opens [ScreenBuilderSheet] (Task 4) in create mode; each saved
 * custom screen's edit glyph opens the same sheet pre-filled for that screen.
 */
@Composable
fun ScreenerScreen(padding: PaddingValues, onOpenDetail: (String) -> Unit) {
    val viewModel: ScreenerViewModel = viewModel {
        ScreenerViewModel(
            engine = AppGraph.screenerScanEngine,
            snapshotStore = AppGraph.screenerSnapshotStore,
            screenStore = AppGraph.screenStore,
            symbols = SP500Symbols.set.toList(),
            names = SP500Names,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        )
    }

    // The androidx ViewModel above is scoped to the shell back-stack entry, so it OUTLIVES a
    // tab switch away and back (unlike macOS/SwiftUI, where switching tabs tears the whole
    // view — and its @State view model — down). What must still match the iPhone (R4) and
    // desktop twins is that a scan started on this tab doesn't keep running, orphaned, once
    // the user switches away: viewModel.cancelScan() owns and stops that job explicitly on
    // dispose, exactly like ScreenerPane's DisposableEffect and ScreenerView's .onDisappear —
    // the VM survives, the scan does not.
    DisposableEffect(Unit) {
        onDispose { viewModel.cancelScan() }
    }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Seeded from the real watchlist store on first composition, then kept in sync locally as
    // this screen's own add-taps land — mirrors the desktop pane's `addedSymbols` handling,
    // which itself mirrors the Swift `loadWatchlist`/`.task`.
    var addedSymbols by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        addedSymbols = AppGraph.fetchWatchlist.execute().map { it.symbol }.toSet()
    }

    // `null` = no sheet shown; `New` = the "+" chip; `Edit(screen)` = a saved chip's edit
    // glyph. `ScreenBuilderSheet` itself calls `onDismiss` after a successful Save or a
    // confirmed Delete, so both `onSave`/`onDelete` below only need to drive the view model —
    // clearing this state back to `null` is the sheet's job, not theirs. Mirrors desktop
    // `ScreenerPane.kt`'s `ScreenBuilderTarget`.
    var builderTarget by remember { mutableStateOf<ScreenBuilderTarget?>(null) }

    val activeColumn = activeMetricColumn(state.selection)

    Column(Modifier.padding(padding).fillMaxSize()) {
        Column(
            modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChipsRow(
                selection = state.selection,
                savedScreens = state.savedScreens,
                onSelectPreset = { viewModel.select(ScreenSelection.Preset(it)) },
                onSelectCustom = { viewModel.select(ScreenSelection.Custom(it)) },
                onNewScreen = { builderTarget = ScreenBuilderTarget.New },
                onEditScreen = { screen -> builderTarget = ScreenBuilderTarget.Edit(screen) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ScanBar(
                state = state,
                isSnapshotFresh = viewModel.isSnapshotFresh(),
                onScan = { viewModel.startScan() },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Box(Modifier.fillMaxSize()) {
            ScreenerContent(
                state = state,
                activeColumn = activeColumn,
                addedSymbols = addedSymbols,
                onSort = { column ->
                    if (state.sortColumn == column) {
                        viewModel.setSortAscending(!state.sortAscending)
                    } else {
                        viewModel.setSortColumn(column)
                        viewModel.setSortAscending(true)
                    }
                },
                onRowClick = onOpenDetail,
                onAdd = { row ->
                    if (row.symbol !in addedSymbols) {
                        addedSymbols = addedSymbols + row.symbol
                        scope.launch {
                            AppGraph.addToWatchlist.execute(
                                WatchlistEntry(symbol = row.symbol, name = row.name, kind = AssetKind.Stock),
                            )
                        }
                    }
                },
            )
        }
    }

    val target = builderTarget
    if (target != null) {
        ScreenBuilderSheet(
            existingScreen = (target as? ScreenBuilderTarget.Edit)?.screen,
            matchCount = viewModel::matchCount,
            onDismiss = { builderTarget = null },
            onSave = { screen -> viewModel.saveScreen(screen) },
            onDelete = if (target is ScreenBuilderTarget.Edit) {
                { viewModel.deleteScreen(target.screen.id) }
            } else {
                null
            },
        )
    }
}

/** Which mode [ScreenBuilderSheet] is showing, if any — `null` (no sheet), a brand-new screen,
 *  or an edit of an existing saved one. A plain nullable `CustomScreen?` couldn't tell "new
 *  screen" (`existingScreen = null`) apart from "no sheet shown" on its own. Mirrors desktop
 *  `ScreenerPane.kt`'s `ScreenBuilderTarget`. */
private sealed class ScreenBuilderTarget {
    object New : ScreenBuilderTarget()
    data class Edit(val screen: CustomScreen) : ScreenBuilderTarget()
}

// MARK: - Chips row

@Composable
private fun ChipsRow(
    selection: ScreenSelection,
    savedScreens: List<CustomScreen>,
    onSelectPreset: (PresetScreen) -> Unit,
    onSelectCustom: (CustomScreen) -> Unit,
    onNewScreen: () -> Unit,
    onEditScreen: (CustomScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (preset in PresetScreen.entries) {
            FilterChip(
                selected = selection == ScreenSelection.Preset(preset),
                onClick = { onSelectPreset(preset) },
                label = { Text(tr(presetTitleKey(preset))) },
            )
        }
        for (screen in savedScreens) {
            // `screen.name` is user-authored data, not app copy — rendered verbatim, never
            // routed through `tr(_:)`.
            FilterChip(
                selected = selection == ScreenSelection.Custom(screen),
                onClick = { onSelectCustom(screen) },
                label = { Text(screen.name) },
                trailingIcon = {
                    IconButton(onClick = { onEditScreen(screen) }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = tr(L10n.Key.ScreenerEditScreen),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        }
        IconButton(onClick = onNewScreen) {
            Icon(Icons.Filled.Add, contentDescription = tr(L10n.Key.ScreenerNewScreen))
        }
    }
}

// MARK: - Scan bar (4 states — transcribed from desktop ScreenerPane.kt's `ScanBar`)

@Composable
private fun ScanBar(
    state: ScreenerUiState,
    isSnapshotFresh: Boolean,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val scanState = state.scanState) {
        is ScreenerScanState.Scanning -> ScanningBar(done = scanState.done, total = scanState.total, modifier = modifier)
        is ScreenerScanState.Failed -> ScanBarAction(
            icon = Icons.Filled.Warning,
            tint = MaterialTheme.colorScheme.error,
            message = tr(L10n.Key.ScreenerScanFailed),
            actionTitle = tr(L10n.Key.Refresh),
            onAction = onScan,
            modifier = modifier,
        )
        is ScreenerScanState.Idle -> {
            val snapshot = state.snapshot
            if (snapshot != null) {
                // `isSnapshotFresh` (true when the persisted snapshot was scanned on today's
                // trading day) picks between two readings of the SAME idle+snapshot state: a
                // stale snapshot reads as "not yet scanned today" (same icon/label as the
                // never-scanned state below), a fresh one as "up to date, tap to refresh"
                // (checkmark + refresh). No new strings — both label/icon pairs already exist
                // elsewhere in this scan bar.
                Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanBarAction(
                        icon = if (isSnapshotFresh) Icons.Filled.CheckCircle else Icons.AutoMirrored.Filled.List,
                        tint = MaterialTheme.colorScheme.primary,
                        message = trf(
                            L10n.Key.ScreenerLastScanFmt,
                            snapshot.rows.size.toString(),
                            formatScanTime(snapshot.scannedAtEpochSeconds),
                        ),
                        actionTitle = if (isSnapshotFresh) tr(L10n.Key.Refresh) else tr(L10n.Key.ScreenerScan),
                        onAction = onScan,
                    )
                    if (snapshot.failedSymbols.isNotEmpty()) {
                        Text(
                            trf(L10n.Key.ScreenerFailedNoteFmt, snapshot.failedSymbols.size.toString()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            } else {
                ScanBarAction(
                    icon = Icons.AutoMirrored.Filled.List,
                    tint = MaterialTheme.colorScheme.primary,
                    message = tr(L10n.Key.ScreenerNotScanned),
                    actionTitle = tr(L10n.Key.ScreenerScan),
                    onAction = onScan,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun ScanningBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total.toFloat() else 0f },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            trf(L10n.Key.ScreenerScanningFmt, done.toString(), total.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScanBarAction(
    icon: ImageVector,
    tint: Color,
    message: String,
    actionTitle: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(
            message,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onAction) { Text(actionTitle) }
    }
}

// MARK: - Content — three empty states + results (order-sensitive; do not reorder)

@Composable
private fun ScreenerContent(
    state: ScreenerUiState,
    activeColumn: ActiveMetricColumn?,
    addedSymbols: Set<String>,
    onSort: (ScreenerSortColumn) -> Unit,
    onRowClick: (String) -> Unit,
    onAdd: (ScreenerSnapshotRow) -> Unit,
) {
    when {
        state.snapshot != null && state.results.isNotEmpty() -> ResultsList(
            state = state,
            activeColumn = activeColumn,
            addedSymbols = addedSymbols,
            onSort = onSort,
            onRowClick = onRowClick,
            onAdd = onAdd,
        )
        state.scanState is ScreenerScanState.Scanning ->
            // A scan is already visibly in progress via the progress bar above — showing the
            // "Scan the S&P 500…"/no-matches empty-state CTA underneath it here would read as
            // a second, redundant invitation to do the very thing already happening. Render
            // the neutral empty region instead (no icon, no text).
            Box(Modifier.fillMaxSize())
        state.snapshot != null ->
            // Scanned before, but the active screen matches nothing in that snapshot.
            EmptyState(icon = Icons.Filled.Search, text = tr(L10n.Key.ScreenerNoMatches))
        state.scanState is ScreenerScanState.Failed ->
            // Never successfully scanned, and the most recent attempt failed outright.
            EmptyState(icon = Icons.Filled.Warning, text = tr(L10n.Key.ScreenerScanFailed))
        else ->
            // Never scanned at all.
            EmptyState(icon = Icons.AutoMirrored.Filled.List, text = tr(L10n.Key.ScreenerNotScanned))
    }
}

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Results (phone-narrow: compact tappable header strip + single active-metric column)

@Composable
private fun ResultsList(
    state: ScreenerUiState,
    activeColumn: ActiveMetricColumn?,
    addedSymbols: Set<String>,
    onSort: (ScreenerSortColumn) -> Unit,
    onRowClick: (String) -> Unit,
    onAdd: (ScreenerSnapshotRow) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        HeaderStrip(state = state, activeColumn = activeColumn, onSort = onSort)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.results, key = { it.symbol }) { row ->
                ResultRow(
                    row = row,
                    activeColumn = activeColumn,
                    added = row.symbol in addedSymbols,
                    onClick = { onRowClick(row.symbol) },
                    onAdd = { onAdd(row) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

/** Compact tappable header strip standing in for desktop's wide column-header row — phone
 *  width has no room for a full table header, so this is a control-only strip (it doesn't
 *  visually align above the stacked price/change/metric column in [ResultRow]) that still
 *  drives the same header-click sort semantics: same column tapped again flips direction, a
 *  different column resets to ascending. */
@Composable
private fun HeaderStrip(state: ScreenerUiState, activeColumn: ActiveMetricColumn?, onSort: (ScreenerSortColumn) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HeaderLabel(tr(L10n.Key.StatSymbol), ScreenerSortColumn.Symbol, state) { onSort(ScreenerSortColumn.Symbol) }
        HeaderLabel(tr(L10n.Key.MetricPrice), ScreenerSortColumn.Price, state) { onSort(ScreenerSortColumn.Price) }
        HeaderLabel(tr(L10n.Key.MetricDayChange), ScreenerSortColumn.DayChange, state) { onSort(ScreenerSortColumn.DayChange) }
        if (activeColumn != null) {
            HeaderLabel(tr(activeColumn.titleKey), ScreenerSortColumn.ActiveMetric, state) {
                onSort(ScreenerSortColumn.ActiveMetric)
            }
        }
    }
}

@Composable
private fun HeaderLabel(title: String, column: ScreenerSortColumn, state: ScreenerUiState, onClick: () -> Unit) {
    val active = state.sortColumn == column
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            title.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (active) {
            Spacer(Modifier.width(2.dp))
            Text(
                if (state.sortAscending) "↑" else "↓",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ResultRow(
    row: ScreenerSnapshotRow,
    activeColumn: ActiveMetricColumn?,
    added: Boolean,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    val tnum = TextStyle(fontFeatureSettings = "tnum")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    row.symbol,
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium.merge(tnum),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    row.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val dayChange = row.dayChangePercent
                Text(
                    money(BigDecimal.valueOf(row.close).toPlainString()),
                    style = MaterialTheme.typography.bodyLarge.merge(tnum),
                )
                Text(
                    formatPercent(dayChange),
                    style = MaterialTheme.typography.bodySmall.merge(tnum),
                    color = when {
                        dayChange == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        dayChange >= 0 -> GainGreen
                        else -> LossRed
                    },
                )
                if (activeColumn != null) {
                    Text(
                        activeColumn.value(row)?.let(activeColumn.format) ?: "—",
                        style = MaterialTheme.typography.labelSmall.merge(tnum),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        AddButton(added = added, onClick = onAdd)
    }
}

@Composable
private fun AddButton(added: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (added) Icons.Filled.CheckCircle else Icons.Filled.Add,
            contentDescription = tr(L10n.Key.AddToWatchlist),
            tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
