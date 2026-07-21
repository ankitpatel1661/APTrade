package com.aptrade.desktop.screener

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.AppGraph
import com.aptrade.desktop.LocalAppGraph
import com.aptrade.desktop.designkit.ChangePill
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.SuperscriptPrice
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.PresetScreen
import com.aptrade.shared.domain.ScreenSelection
import com.aptrade.shared.domain.ScreenerMetric
import com.aptrade.shared.domain.ScreenerSnapshotRow
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - Formatting helpers

/** `MMM d, h:mm a` in a fixed locale, the device's local zone — matches the app-wide
 *  convention (see `CalendarPane.kt`) of formatting numbers/dates in `en_US` regardless of
 *  the active display language, only the surrounding copy is localized. */
private val screenerScanTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(ZoneId.systemDefault())

private fun formatScanTime(epochSeconds: Long): String =
    screenerScanTimeFormatter.format(Instant.ofEpochSecond(epochSeconds))

/** Fixed-decimal `en_US` readout — RSI/MACD/bandwidth/%B/relative-volume columns all render
 *  the raw number, never routed through a percent formatter. */
private fun formatPlain(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

/** Unsigned percent readout for metrics that are always >= 0 (52-week distances) — a forced
 *  "+" (as [formatPercent] would apply) would misread as a directional change these values
 *  never carry. */
private fun formatUnsignedPercent(value: Double): String = "${formatPlain(value, 2)}%"

// MARK: - Active metric column

/** One extra results-table column beyond the always-present symbol/name/price/day-change
 *  four: the single metric that best characterizes the active screen. This mirrors
 *  `ScreenerViewModel.activeMetricValue` exactly — that mapping is `private` on the view
 *  model (it only needs the raw `Double?` for sorting), so this is a deliberate, documented
 *  duplication for display purposes. Keep both in sync if either changes;
 *  `ScreenerSortColumn.ActiveMetric` sorts by this same field. Transcribed from
 *  `Sources/APTradeApp/ScreenerView.swift`'s `ActiveMetricColumn`/`activeMetricColumn(for:)`. */
private data class ActiveMetricColumn(
    val titleKey: L10n.Key,
    val value: (ScreenerSnapshotRow) -> Double?,
    val format: (Double) -> String,
)

/** Preset mapping — see `ScreenerViewModel.activeMetricValue`'s doc comment for the
 *  rationale behind each choice. */
private fun activeMetricColumn(selection: ScreenSelection): ActiveMetricColumn? = when (selection) {
    is ScreenSelection.Preset -> when (selection.preset) {
        PresetScreen.RsiOversold, PresetScreen.RsiOverbought ->
            ActiveMetricColumn(L10n.Key.MetricRsi, { it.rsi14 }, { formatPlain(it, 1) })
        PresetScreen.MacdBullishCross, PresetScreen.MacdBearishCross ->
            ActiveMetricColumn(L10n.Key.IndicatorMACD, { it.macdHistogram }, { formatPlain(it, 2) })
        PresetScreen.GoldenCross, PresetScreen.DeathCross ->
            ActiveMetricColumn(L10n.Key.MetricVsSma50, { it.pctVsSma50 }, ::formatPercent)
        PresetScreen.BollingerSqueeze ->
            // Bandwidth/%B are RAW fractions everywhere (columns, presets, builder) — M9.2
            // must preserve. See the identical note on the custom-metric mapping below for
            // the full rationale.
            ActiveMetricColumn(L10n.Key.MetricBandwidth, { it.bollingerBandwidth }, { formatPlain(it, 4) })
        PresetScreen.Near52wHigh ->
            ActiveMetricColumn(L10n.Key.MetricTo52wHigh, { it.pctTo52wHigh }, ::formatUnsignedPercent)
        PresetScreen.Near52wLow ->
            ActiveMetricColumn(L10n.Key.MetricTo52wLow, { it.pctTo52wLow }, ::formatUnsignedPercent)
    }
    is ScreenSelection.Custom -> {
        // A zero-condition (unbuilt) screen never has matches to show a column for —
        // `ScreenSelection.Custom.evaluate` already returns `[]` in that case.
        val metric = selection.screen.conditions.firstOrNull()?.metric
        if (metric == null) null else activeMetricColumn(metric)
    }
}

/** Custom-screen mapping — one column definition per [ScreenerMetric] case. `price` and
 *  `dayChangePercent` return `null`: those two metrics are already the always-present
 *  Price/Day % columns, so surfacing them again here would render an identical duplicate
 *  column beside the real one. This is a deliberate asymmetry with sorting — the view
 *  model's `ScreenerSortColumn.ActiveMetric` can still target either field (sorting by price
 *  via "active metric" is indistinguishable from sorting by the Price column itself), only
 *  the redundant *visual* column is suppressed. */
private fun activeMetricColumn(metric: ScreenerMetric): ActiveMetricColumn? = when (metric) {
    ScreenerMetric.price, ScreenerMetric.dayChangePercent -> null
    ScreenerMetric.rsi14 ->
        ActiveMetricColumn(L10n.Key.MetricRsi, { it.rsi14 }, { formatPlain(it, 1) })
    ScreenerMetric.bollingerPercentB ->
        // Bandwidth/%B are RAW fractions everywhere (columns, presets, builder) — M9.2 must
        // preserve. `ScreenSelection`/`ScreenCondition` compare these two metrics as raw
        // fractions (e.g. the bollingerSqueeze preset's `bandwidth < 0.05`), and the builder
        // dialog's free-text threshold field is parsed as a raw `Double` with no ×100/÷100
        // conversion either — so a displayed "0.05" here is directly the number a user types
        // into a custom screen's threshold, with no percent-unit translation in between.
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

private fun presetTitleKey(preset: PresetScreen): L10n.Key = when (preset) {
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

// MARK: - Column widths (shared between header + rows so they stay aligned)

private object ScreenerColumnWidth {
    val symbol: Dp = 76.dp
    val price: Dp = 96.dp
    val dayChange: Dp = 84.dp
    val metric: Dp = 96.dp
    val addButton: Dp = 30.dp
}

/**
 * Screener tab: nine curated technical presets plus user-saved custom screens, a
 * full-universe scan bar, and sortable results. Fifth top-level destination alongside
 * Watchlist/Portfolio/News/Calendar. Compose port of `Sources/APTradeApp/ScreenerView.swift`
 * AS-BUILT, desktop-always-wide (the macOS column-header branch, never the iOS card layout).
 *
 * Owns its own [ScreenerViewModel] instance and single-thread-confined [scope] (mirrors
 * `PlansPane`/`IncomePane`'s per-composable-VM pattern) — reads [AppGraph] via
 * [LocalAppGraph] rather than threading VM state through `Main.kt`/`AppRoot`, since the
 * non-suspend snapshot/screen stores mean the view model needs no async `onAppear()` load
 * (its `init` already reads both synchronously).
 *
 * Row-click detail navigation is DELIBERATELY NOT self-contained (reviewer fix, this task):
 * [onOpenDetail] is the SAME hoisted `openSymbol` setter `Main.kt`'s `AppRoot` already
 * threads into the Watchlist tab (`onOpenDetail = { symbol -> openSymbol = symbol }`) — the
 * caller (`AppRoot`) is responsible for rendering `DetailScreen` when `openSymbol` is
 * non-null, exactly mirroring the Watchlist tab's own `if (openSymbol != null) { DetailScreen
 * } else { WatchlistPane }` branch. An earlier version of this pane rendered `DetailScreen`
 * in-place from pane-local state instead; that meant this pane's detail view had no `onBuy`
 * wired (no `tradeTarget`/`onOpenTrade` reachable from inside a self-contained composable),
 * so a user scanning for a candidate and opening it hit a detail screen with no Buy button —
 * a real gap versus the Swift reference, which shows the Buy button unconditionally for
 * every caller. Routing through the shared path fixes that for free (and picks up
 * `heldPosition` too, same as Watchlist) since `AppRoot` already owns `tradeTarget`/
 * `portfolioState`.
 *
 * Watchlist membership is read/written through the SAME [AppGraph.fetchWatchlist]/
 * [AppGraph.addToWatchlist] use cases the Watchlist tab itself uses, so an add here shows up
 * immediately back on that tab (and vice versa) — this part stays fully self-contained since
 * it needs no state `AppRoot` doesn't already have elsewhere.
 *
 * The "+" new-screen chip is wired with a `null` callback for now (Task 8 adds the builder
 * dialog) — the nullable-callback convention `DetailScreen`'s own `onBuy` already
 * establishes: a `null` handler renders the chip disabled at reduced opacity, never a dead
 * button that silently does nothing on click.
 */
@Composable
fun ScreenerPane(onOpenDetail: (String) -> Unit) {
    val graph: AppGraph = LocalAppGraph.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val viewModel = remember { graph.makeScreenerViewModel(scope) }
    // macOS tab switching destroys the equivalent SwiftUI view's `@State` view model
    // outright; an unstructured scan task used to keep running after that teardown,
    // orphaned, so returning to this tab and rescanning could fire TWO overlapping engines.
    // `viewModel.cancelScan()` owns and stops that job explicitly (the M9.1 orphan-scan
    // lesson) — `scope.cancel()` is the usual PlansPane/IncomePane teardown on top of it.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.cancelScan()
            scope.cancel()
        }
    }

    val state by viewModel.state.collectAsState()

    // Seeded from the real watchlist store on first composition, then kept in sync locally
    // as this pane's own add-taps land — mirrors the Swift twin's `loadWatchlist`/`.task`.
    var addedSymbols by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        addedSymbols = graph.fetchWatchlist.execute().map { it.symbol }.toSet()
    }

    val activeColumn = activeMetricColumn(state.selection)
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                ChipsRow(
                    selection = state.selection,
                    savedScreens = state.savedScreens,
                    onSelectPreset = { viewModel.select(ScreenSelection.Preset(it)) },
                    onSelectCustom = { viewModel.select(ScreenSelection.Custom(it)) },
                    // Task 8 wires a real builder-dialog callback here; until then the chip
                    // renders disabled (reduced opacity), never a dead no-op button.
                    onNewScreen = null,
                )
            }
            Box(Modifier.padding(horizontal = 24.dp)) {
                ScanBar(
                    state = state,
                    isSnapshotFresh = viewModel.isSnapshotFresh(),
                    onScan = { viewModel.startScan() },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
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
                            graph.addToWatchlist.execute(
                                WatchlistEntry(symbol = row.symbol, name = row.name, kind = AssetKind.Stock),
                            )
                        }
                    }
                },
            )
        }
    }
}

// MARK: - Chips row

@Composable
private fun ChipsRow(
    selection: ScreenSelection,
    savedScreens: List<CustomScreen>,
    onSelectPreset: (PresetScreen) -> Unit,
    onSelectCustom: (CustomScreen) -> Unit,
    onNewScreen: (() -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in PresetScreen.entries) {
            ScreenerChip(
                title = tr(presetTitleKey(preset)),
                selected = selection == ScreenSelection.Preset(preset),
            ) { onSelectPreset(preset) }
        }
        for (screen in savedScreens) {
            // `screen.name` is user-authored data, not app copy — rendered verbatim, never
            // routed through `tr(_:)`.
            ScreenerChip(
                title = screen.name,
                selected = selection == ScreenSelection.Custom(screen),
            ) { onSelectCustom(screen) }
        }
        NewScreenChip(onClick = onNewScreen)
    }
}

@Composable
private fun ScreenerChip(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (selected) {
                    Modifier
                        .background(DK.surfaceHi)
                        .border(1.dp, DK.gold.copy(alpha = 0.4f), RoundedCornerShape(50))
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            title,
            maxLines = 1,
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) DK.textPrimary else DK.textSecondary,
            ),
        )
    }
}

/** Nullable-callback pattern (mirroring `DetailScreen`'s `onBuy`): a `null` [onClick] renders
 *  the "+" chip disabled at reduced opacity rather than a dead button that silently no-ops. */
@Composable
private fun NewScreenChip(onClick: (() -> Unit)?) {
    val enabled = onClick != null
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(DK.surface)
            .border(1.dp, DK.gold.copy(alpha = if (enabled) 0.4f else 0.15f), CircleShape)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClick?.invoke() }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+",
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = DK.gold.copy(alpha = if (enabled) 1f else 0.35f),
            ),
        )
    }
}

// MARK: - Scan bar

@Composable
private fun ScanBar(state: ScreenerUiState, isSnapshotFresh: Boolean, onScan: () -> Unit) {
    when (val scanState = state.scanState) {
        is ScreenerScanState.Scanning -> ScanningBar(done = scanState.done, total = scanState.total)
        is ScreenerScanState.Failed -> ScanBarAction(
            icon = { WarningGlyph(tint = DK.down) },
            message = tr(L10n.Key.ScreenerScanFailed),
            actionTitle = tr(L10n.Key.Refresh),
            onAction = onScan,
        )
        is ScreenerScanState.Idle -> {
            val snapshot = state.snapshot
            if (snapshot != null) {
                // `isSnapshotFresh` (true when the persisted snapshot was scanned on today's
                // trading day) picks between two readings of the SAME idle+snapshot state: a
                // stale snapshot reads as "not yet scanned today" (the scan icon + the same
                // action label used in the never-scanned state below), a fresh one as
                // "up to date, tap to refresh" (checkmark + refresh). No new strings — both
                // label/icon pairs already exist elsewhere in this scan bar.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanBarAction(
                        icon = {
                            if (isSnapshotFresh) {
                                Text(
                                    "✓",
                                    style = TextStyle(
                                        fontFamily = InterFamily,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = DK.gold,
                                    ),
                                )
                            } else {
                                FilterGlyph(tint = DK.gold)
                            }
                        },
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
                            style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = DK.textTertiary),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            } else {
                ScanBarAction(
                    icon = { FilterGlyph(tint = DK.gold) },
                    message = tr(L10n.Key.ScreenerNotScanned),
                    actionTitle = tr(L10n.Key.ScreenerScan),
                    onAction = onScan,
                )
            }
        }
    }
}

@Composable
private fun ScanningBar(done: Int, total: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total.toFloat() else 0f },
            color = DK.gold,
            trackColor = DK.surfaceHi,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            trf(L10n.Key.ScreenerScanningFmt, done.toString(), total.toString()),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = DK.textSecondary,
            ),
        )
    }
}

@Composable
private fun ScanBarAction(
    icon: @Composable () -> Unit,
    message: String,
    actionTitle: String,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(
            message,
            maxLines = 2,
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = DK.textPrimary,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(DK.goldGradient)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onAction() }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                actionTitle,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = DK.bgBottom,
                ),
            )
        }
    }
}

/** Three descending bars — a font-free stand-in for the SF Symbol
 *  `line.3.horizontal.decrease.circle` used for the "scan"/"not yet scanned today" states,
 *  drawn with plain `Box` backgrounds (the same primitive `PulseBar`/`KindToggle` already
 *  rely on) rather than a glyph that might not be present in every fallback font. */
@Composable
private fun FilterGlyph(tint: Color, modifier: Modifier = Modifier) {
    Column(modifier.size(width = 14.dp, height = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(tint))
        Box(Modifier.fillMaxWidth(0.66f).height(2.dp).background(tint))
        Box(Modifier.fillMaxWidth(0.33f).height(2.dp).background(tint))
    }
}

/** A bordered "!" — a font-safe stand-in for the SF Symbol `exclamationmark.triangle` used
 *  for the failed-scan state, built the same "plain glyph in a shape" way the app already
 *  draws its ✓/✕ affordances (see `WatchlistPane`'s remove button). */
@Composable
private fun WarningGlyph(tint: Color, size: Dp = 15.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, tint.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "!",
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = (size.value * 0.65f).sp,
                fontWeight = FontWeight.Bold,
                color = tint,
            ),
        )
    }
}

/** A hollow rounded rectangle — a font-free stand-in for the SF Symbol `tray` used for the
 *  no-matches empty state, drawn the same "plain shape via Box" way as [FilterGlyph]. */
@Composable
private fun TrayGlyph(tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 26.dp, height = 18.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(1.5.dp, tint, RoundedCornerShape(3.dp)),
    )
}

// MARK: - Content — three empty states + results

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
            EmptyState(icon = { TrayGlyph(tint = DK.textTertiary) }, text = tr(L10n.Key.ScreenerNoMatches))
        state.scanState is ScreenerScanState.Failed ->
            // Never successfully scanned, and the most recent attempt failed outright.
            EmptyState(
                icon = { WarningGlyph(tint = DK.textTertiary, size = 28.dp) },
                text = tr(L10n.Key.ScreenerScanFailed),
            )
        else ->
            // Never scanned at all.
            EmptyState(
                icon = { FilterGlyph(tint = DK.textTertiary, modifier = Modifier.size(width = 28.dp, height = 28.dp)) },
                text = tr(L10n.Key.ScreenerNotScanned),
            )
    }
}

@Composable
private fun EmptyState(icon: @Composable () -> Unit, text: String) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            icon()
            Text(
                text,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textSecondary,
                ),
            )
        }
    }
}

// MARK: - Results

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
        ColumnHeader(state = state, activeColumn = activeColumn, onSort = onSort)
        Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(state.results, key = { it.symbol }) { row ->
                ResultRow(
                    row = row,
                    activeColumn = activeColumn,
                    added = row.symbol in addedSymbols,
                    onClick = { onRowClick(row.symbol) },
                    onAdd = { onAdd(row) },
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
            }
        }
    }
}

@Composable
private fun ColumnHeader(state: ScreenerUiState, activeColumn: ActiveMetricColumn?, onSort: (ScreenerSortColumn) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        HeaderButton(
            title = tr(L10n.Key.StatSymbol),
            column = ScreenerSortColumn.Symbol,
            state = state,
            width = ScreenerColumnWidth.symbol,
            arrangement = Arrangement.Start,
            onClick = { onSort(ScreenerSortColumn.Symbol) },
        )
        Text(
            tr(L10n.Key.Name),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = DK.textTertiary,
            ),
            modifier = Modifier.weight(1f),
        )
        HeaderButton(
            title = tr(L10n.Key.MetricPrice),
            column = ScreenerSortColumn.Price,
            state = state,
            width = ScreenerColumnWidth.price,
            arrangement = Arrangement.End,
            onClick = { onSort(ScreenerSortColumn.Price) },
        )
        HeaderButton(
            title = tr(L10n.Key.MetricDayChange),
            column = ScreenerSortColumn.DayChange,
            state = state,
            width = ScreenerColumnWidth.dayChange,
            arrangement = Arrangement.End,
            onClick = { onSort(ScreenerSortColumn.DayChange) },
        )
        if (activeColumn != null) {
            HeaderButton(
                title = tr(activeColumn.titleKey),
                column = ScreenerSortColumn.ActiveMetric,
                state = state,
                width = ScreenerColumnWidth.metric,
                arrangement = Arrangement.End,
                onClick = { onSort(ScreenerSortColumn.ActiveMetric) },
            )
        }
        Spacer(Modifier.width(ScreenerColumnWidth.addButton))
    }
}

@Composable
private fun HeaderButton(
    title: String,
    column: ScreenerSortColumn,
    state: ScreenerUiState,
    width: Dp,
    arrangement: Arrangement.Horizontal,
    onClick: () -> Unit,
) {
    val active = state.sortColumn == column
    Row(
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(width)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
    ) {
        Text(
            title.uppercase(Locale.US),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = if (active) DK.textPrimary else DK.textTertiary,
            ),
        )
        if (active) {
            Spacer(Modifier.width(3.dp))
            Text(
                if (state.sortAscending) "↑" else "↓",
                style = TextStyle(fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = DK.textPrimary),
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        ) {
            Text(
                row.symbol,
                maxLines = 1,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DK.textPrimary,
                    fontFeatureSettings = "tnum",
                ),
                modifier = Modifier.width(ScreenerColumnWidth.symbol),
            )
            Text(
                row.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = DK.textSecondary),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Box(Modifier.width(ScreenerColumnWidth.price), contentAlignment = Alignment.CenterEnd) {
                SuperscriptPrice(amountText = formatMoney(BigDecimal.valueOf(row.close).toPlainString()), size = 14.sp)
            }
            Box(Modifier.width(ScreenerColumnWidth.dayChange), contentAlignment = Alignment.CenterEnd) {
                ChangePill(changePercent = row.dayChangePercent)
            }
            if (activeColumn != null) {
                Box(Modifier.width(ScreenerColumnWidth.metric), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        activeColumn.value(row)?.let(activeColumn.format) ?: "—",
                        style = TextStyle(
                            fontFamily = InterFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = DK.textSecondary,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }
        }
        AddButton(added = added, onClick = onAdd)
    }
}

@Composable
private fun AddButton(added: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(ScreenerColumnWidth.addButton)
            .clip(CircleShape)
            .background(DK.surfaceHi)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (added) "✓" else "+",
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (added) DK.gold else DK.textTertiary,
            ),
        )
    }
}
