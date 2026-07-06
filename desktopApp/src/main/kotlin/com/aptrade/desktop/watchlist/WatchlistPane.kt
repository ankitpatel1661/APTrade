package com.aptrade.desktop.watchlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.TooltipArea
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.ChangePill
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.KindToggle
import com.aptrade.desktop.designkit.LiveBadge
import com.aptrade.desktop.designkit.MagnifierIcon
import com.aptrade.desktop.designkit.PulseBar
import com.aptrade.desktop.designkit.Sparkline
import com.aptrade.desktop.designkit.SuperscriptPrice
import com.aptrade.desktop.designkit.ExpandedValueCard
import com.aptrade.desktop.designkit.chipLabel
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.l10n.L10n
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.WatchlistEntry
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

/** Watchlist tab: a full-width single column. A macOS-style header (average day-change
 *  figure + clickable sparkline that toggles an expandable value card, over a PulseBar)
 *  sits above the KindToggle/LiveBadge row, add-field, and list. All state comes from
 *  `WatchlistViewModel`; the search-suggestions VM is passed in so the pane never owns
 *  fetch logic. Row selection (`onSelect`) opens the full-window detail. */
@Composable
fun WatchlistPane(
    state: WatchlistUiState,
    onKindSelect: (com.aptrade.shared.domain.AssetKind) -> Unit,
    onSelect: (String) -> Unit,
    onAdd: (WatchlistEntry) -> Unit,
    onRemove: (String) -> Unit,
    onSetAlert: (String) -> Unit,
    suggestQuery: String,
    suggestResults: List<Asset>,
    onSuggestQueryChange: (String) -> Unit,
    onSuggestReset: () -> Unit,
) {
    var chartExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatPercent(state.averageChange),
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DK.changeColor(state.averageChange),
                        fontFeatureSettings = "tnum",
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    tr(L10n.Key.AvgDayChange),
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = DK.textSecondary,
                    ),
                )
                Spacer(Modifier.weight(1f))
                if (state.averageSpark.size > 1) {
                    Sparkline(
                        values = state.averageSpark,
                        color = DK.changeColor(state.averageChange),
                        modifier = Modifier
                            .size(width = 140.dp, height = 36.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { chartExpanded = !chartExpanded },
                    )
                }
            }
            // Also gated on spark data: a poll can empty averageSpark while the card is open.
            AnimatedVisibility(
                visible = chartExpanded && state.averageSpark.size > 1,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    ExpandedValueCard(
                        title = tr(L10n.Key.AvgDayChangeTitle),
                        values = state.averageSpark,
                        onClose = { chartExpanded = false },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            PulseBar(
                advancers = state.advancers,
                decliners = state.decliners,
                modifier = Modifier.width(180.dp),
            )
        }
        MasterPane(
            state = state,
            onKindSelect = onKindSelect,
            onSelect = onSelect,
            onAdd = onAdd,
            onRemove = onRemove,
            onSetAlert = onSetAlert,
            suggestQuery = suggestQuery,
            suggestResults = suggestResults,
            onSuggestQueryChange = onSuggestQueryChange,
            onSuggestReset = onSuggestReset,
        )
    }
}

@Composable
private fun MasterPane(
    state: WatchlistUiState,
    onKindSelect: (com.aptrade.shared.domain.AssetKind) -> Unit,
    onSelect: (String) -> Unit,
    onAdd: (WatchlistEntry) -> Unit,
    onRemove: (String) -> Unit,
    onSetAlert: (String) -> Unit,
    suggestQuery: String,
    suggestResults: List<Asset>,
    onSuggestQueryChange: (String) -> Unit,
    onSuggestReset: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindToggle(selection = state.kind, counts = state.counts, onSelect = onKindSelect)
            LiveBadge()
        }
        Spacer(Modifier.height(14.dp))
        AddField(
            query = suggestQuery,
            results = suggestResults,
            onQueryChange = onSuggestQueryChange,
            onAdd = { asset ->
                onAdd(WatchlistEntry(asset.symbol, asset.name, asset.kind))
                onSuggestReset()
            },
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxSize()) {
            when {
                state.isLoading && state.rows.isEmpty() ->
                    CircularProgressIndicator(
                        color = DK.gold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.symbol }) { row ->
                        WatchlistRow(
                            row = row,
                            selected = row.symbol == state.selectedSymbol,
                            onClick = { onSelect(row.symbol) },
                            onRemove = { onRemove(row.symbol) },
                            onSetAlert = { onSetAlert(row.symbol) },
                        )
                    }
                }
            }
        }
    }
}

/** Magnifier + borderless field on `DK.surface`, rounded 10dp, with an inline
 *  suggestions dropdown. Adding via click or Enter is delegated to `onAdd`. */
@Composable
private fun AddField(
    query: String,
    results: List<Asset>,
    onQueryChange: (String) -> Unit,
    onAdd: (Asset) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            MagnifierIcon(tint = DK.textSecondary, modifier = Modifier.size(15.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        tr(L10n.Key.SearchTickerPlaceholder),
                        style = TextStyle(
                            fontFamily = InterFamily,
                            fontSize = 14.sp,
                            color = DK.textTertiary,
                        ),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    cursorBrush = SolidColor(DK.gold),
                    textStyle = LocalTextStyle.current.merge(
                        TextStyle(
                            fontFamily = InterFamily,
                            fontSize = 14.sp,
                            color = DK.textPrimary,
                        ),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (event.type == KeyEventType.KeyDown && isEnter) {
                                results.firstOrNull()?.let(onAdd)
                                results.isNotEmpty()
                            } else {
                                false
                            }
                        },
                )
            }
        }
        if (query.isNotBlank() && results.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DK.surface)
                    .border(1.dp, DK.hairline, RoundedCornerShape(10.dp))
                    .padding(vertical = 4.dp),
            ) {
                for (asset in results.take(6)) {
                    SuggestionRow(asset = asset, onClick = { onAdd(asset) })
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(asset: Asset, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                asset.name,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textPrimary,
                ),
            )
            Text(
                asset.symbol,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textSecondary,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
        Text(
            chipLabel(asset.kind),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = DK.textTertiary,
                letterSpacing = 0.8.sp,
            ),
        )
    }
}

/** A single watchlist row: name over symbol, sparkline, price over change pill.
 *  Hover reveals a ✕ remove button and a `DK.surfaceHi` background. The alert bell (macOS
 *  anatomy: `Sources/APTradeApp/WatchlistView.swift`'s `alertButton`) sits between the
 *  sparkline and the price column, visible on hover OR whenever `alertCount > 0`. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WatchlistRow(
    row: WatchRow,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onSetAlert: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    val background = when {
        selected -> DK.surfaceHi
        hovered -> DK.surfaceHi
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 10.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DK.textPrimary,
                ),
                maxLines = 1,
            )
            Text(
                row.symbol,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textSecondary,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
        Sparkline(
            values = row.spark,
            color = DK.changeColor(row.changePercent),
            modifier = Modifier.size(width = 72.dp, height = 32.dp),
        )
        if (hovered || row.alertCount > 0) {
            Spacer(Modifier.width(10.dp))
            AlertBell(alertCount = row.alertCount, onClick = onSetAlert)
        }
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (row.amountText != null) {
                SuperscriptPrice(amountText = row.amountText, size = 18.sp)
            } else {
                Text(
                    "—",
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DK.textSecondary,
                    ),
                )
            }
            Spacer(Modifier.height(4.dp))
            ChangePill(changePercent = row.changePercent)
        }
        if (hovered) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "✕",
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = DK.textSecondary,
                    ),
                )
            }
        }
    }
}

/** The row's alert-bell affordance: gold filled when `alertCount > 0`, tertiary outline
 *  otherwise — the macOS anatomy (`WatchlistView.swift`'s `alertButton`: `bell.fill`/gold
 *  vs `bell`/tertiary). The tooltip text is a verbatim transcription of the macOS `.help(...)`
 *  strings (L10n.swift's `.activeAlertsFormat` / `.setAPriceAlert`). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlertBell(alertCount: Int, onClick: () -> Unit) {
    val tooltipText = if (alertCount > 0) {
        trf(L10n.Key.ActiveAlertsFormat, alertCount)
    } else {
        tr(L10n.Key.SetAPriceAlert)
    }
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(DK.surfaceHi)
                    .border(1.dp, DK.hairline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    tooltipText,
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 11.sp,
                        fontWeight = FontWeight.Medium, color = DK.textPrimary,
                    ),
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(DK.surfaceHi)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // No distinct outline-bell glyph is used here (unlike SF Symbols' bell/
                // bell.fill pair) — the gold-vs-tertiary color swap below carries the same
                // filled/outline distinction the macOS anatomy conveys.
                "🔔",
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    color = if (alertCount > 0) DK.gold else DK.textTertiary,
                ),
            )
        }
    }
}
