package com.aptrade.desktop.detail

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.AppGraph
import com.aptrade.desktop.LocalAppGraph
import com.aptrade.desktop.calendar.formatEventDate
import com.aptrade.desktop.calendar.sessionLabel
import com.aptrade.desktop.designkit.CandleChart
import com.aptrade.desktop.designkit.ChangePill
import com.aptrade.desktop.designkit.chipLabel
import com.aptrade.desktop.designkit.kindLabel
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.LineChart
import com.aptrade.desktop.designkit.StatTile
import com.aptrade.desktop.designkit.SuperscriptPrice
import com.aptrade.desktop.designkit.TimeframeBar
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.designkit.signedMoney
import com.aptrade.desktop.infra.openUrlInBrowser
import com.aptrade.shared.l10n.L10n
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.news.ArticleRow
import com.aptrade.desktop.portfolio.HoldingRowUi
import com.aptrade.shared.domain.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.runtime.collectAsState

private val dividendExDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneOffset.UTC)

/** Asset detail. A fresh `DetailViewModel` (with its own single-thread scope) is built per
 *  `symbol` so a stale load dies with its symbol. `onBuy` (asset + live price text) opens
 *  the paper-trade dialog with Buy preselected (the dialog's toggle covers Sell).
 *  `heldPosition` is the portfolio row for this symbol (or null) — the YOUR POSITION card
 *  reads it directly; no second portfolio store read path.
 *
 *  `embedded` (M10.2 Task 6, mirrors `AssetDetailView.swift`'s `embedded: Bool`): when
 *  `true`, this is hosted beside a list column inside a Watchlist/Screener pane's own
 *  conditional split rather than shown full-window, so the internal "‹ Back" bar is
 *  skipped (the pane already renders its own ✕ close affordance over the top — see
 *  `WatchlistPane`/`ScreenerPane`'s `CircularCloseButton`) and a small top inset is added
 *  instead so that floating ✕ never overlaps the header row's BUY/SELL pill. Kotlin's
 *  `DetailScreen`, unlike Swift's `AssetDetailView`, has no window-size floor to begin
 *  with (the desktop window's own `minimumSize` already covers that) — `embedded` only
 *  ever needs to swap the back-affordance, never a size constraint. */
@Composable
fun DetailScreen(
    symbol: String,
    onBack: () -> Unit,
    heldPosition: HoldingRowUi? = null,
    onBuy: ((com.aptrade.shared.domain.Asset, String?) -> Unit)? = null,
    embedded: Boolean = false,
) {
    val graph: AppGraph = LocalAppGraph.current
    val scope = remember(symbol) { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val vm = remember(symbol) {
        DetailViewModel(
            symbol = symbol,
            fetchProfile = graph.fetchProfile,
            fetchMarketQuotes = graph.fetchMarketQuotes,
            fetchHistory = graph.fetchHistory,
            fetchChartWindow = graph.fetchChartWindow,
            fetchEarningsCalendar = graph.fetchEarningsCalendar,
            fetchDividendEvents = graph.fetchDividendEvents,
            scope = scope,
            fetchCompanyNews = graph.fetchCompanyNews,
            loadBookmarks = graph.loadBookmarks,
            toggleBookmark = graph.toggleBookmark,
        )
    }
    DisposableEffect(symbol) { onDispose { scope.cancel() } }

    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        if (embedded) {
            Spacer(Modifier.height(32.dp))
        } else {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹  " + tr(L10n.Key.Back),
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = DK.textSecondary,
                    ),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onBack() },
                )
            }
        }
        DetailContent(
            state = state,
            heldPosition = heldPosition,
            onTimeframeChange = vm::onTimeframeChange,
            onModeChange = vm::onModeChange,
            onIndicatorsActiveChange = vm::onIndicatorsActiveChange,
            onRetry = vm::retryChart,
            onBuy = onBuy,
            onToggleBookmark = vm::onToggleBookmark,
        )
    }
}

@Composable
private fun DetailContent(
    state: DetailUiState,
    heldPosition: HoldingRowUi?,
    onTimeframeChange: (com.aptrade.shared.domain.Timeframe) -> Unit,
    onModeChange: (ChartMode) -> Unit,
    onIndicatorsActiveChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onBuy: ((com.aptrade.shared.domain.Asset, String?) -> Unit)? = null,
    onToggleBookmark: (com.aptrade.shared.domain.NewsArticle) -> Unit,
) {
    // Indicator selection is view-local UI state (macOS parity: view @State), none on by default.
    var selection by remember { mutableStateOf(emptySet<Indicator>()) }
    // Report to the VM so it fetches candles in Line mode when any indicator is on.
    LaunchedEffect(selection.isNotEmpty()) { onIndicatorsActiveChange(selection.isNotEmpty()) }

    val series = remember(state.candles, selection) { computeIndicators(state.candles, selection) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                state.name ?: state.symbol,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DK.textPrimary,
                ),
            )
            state.kind?.let { KindChip(it) }
            if (onBuy != null) {
                Spacer(Modifier.weight(1f))
                TradeButton(
                    onClick = {
                        val asset = com.aptrade.shared.domain.Asset(
                            symbol = state.symbol,
                            name = state.name ?: state.symbol,
                            kind = state.kind ?: com.aptrade.shared.domain.AssetKind.Stock,
                        )
                        onBuy(asset, state.amountText)
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.amountText != null) {
                SuperscriptPrice(amountText = state.amountText, size = 34.sp)
            } else {
                Text(
                    "—",
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DK.textSecondary,
                    ),
                )
            }
            ChangePill(changePercent = state.changePercent)
        }
        Spacer(Modifier.height(18.dp))
        TimeframeBar(selection = state.timeframe, onSelect = onTimeframeChange)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModeToggle(mode = state.mode, onModeChange = onModeChange)
            Spacer(Modifier.width(16.dp))
            IndicatorChips(
                selection = selection,
                onToggle = { ind ->
                    selection = if (selection.contains(ind)) selection - ind else selection + ind
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            when {
                state.isLoadingChart -> CircularProgressIndicator(color = DK.gold)
                state.chartError != null -> ChartError(message = state.chartError, onRetry = onRetry)
                // Overlays on: honor the mode. Candles mode → REAL candlesticks with overlay
                // polylines on top (shared candle index space); Line mode → the price line drawn
                // from closes. Both share one index → x space so overlays align. Otherwise the
                // original Line/Candles rendering.
                selection.any { it.isOverlay } && state.candles.size >= 2 && state.mode == ChartMode.Candles ->
                    CandleChartWithOverlays(
                        candles = state.candles,
                        series = series,
                        selection = selection,
                        visibleStartIndex = state.visibleStartIndex,
                        modifier = Modifier.fillMaxSize(),
                    )
                selection.any { it.isOverlay } && state.candles.size >= 2 ->
                    PriceChartWithOverlays(
                        candles = state.candles,
                        series = series,
                        selection = selection,
                        lineColor = DK.changeColor(state.changePercent),
                        visibleStartIndex = state.visibleStartIndex,
                        modifier = Modifier.fillMaxSize(),
                    )
                state.mode == ChartMode.Line ->
                    LineChart(values = state.lineValues, modifier = Modifier.fillMaxSize(), color = DK.gold)
                else ->
                    // No overlays: CandleChart has no lookback/visible split, so it only ever
                    // renders the plain visible slice (matching FetchCandles' own contract).
                    CandleChart(
                        candles = state.candles.drop(state.visibleStartIndex),
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
        if (selection.contains(Indicator.Rsi) && state.candles.size >= 2) {
            Spacer(Modifier.height(16.dp))
            RsiPane(series = series, visibleStartIndex = state.visibleStartIndex)
        }
        if (selection.contains(Indicator.Macd) && state.candles.size >= 2) {
            Spacer(Modifier.height(16.dp))
            MacdPane(series = series, visibleStartIndex = state.visibleStartIndex)
        }
        Spacer(Modifier.height(24.dp))
        KeyStatsCard(state)
        state.dividendInfo?.let { info ->
            Spacer(Modifier.height(16.dp))
            DividendCard(info)
        }
        if (heldPosition != null) {
            Spacer(Modifier.height(16.dp))
            PositionCard(heldPosition)
        }
        NewsSection(state = state, onToggleBookmark = onToggleBookmark)
    }
}

/** Company-news section: a "News" section label over up to 8 article rows (hairline divider
 *  between them). Renders NOTHING — no heading — when the news key is missing or when there
 *  are no articles and we're not loading. A bare spinner shows while loading with none yet. */
@Composable
private fun NewsSection(state: DetailUiState, onToggleBookmark: (com.aptrade.shared.domain.NewsArticle) -> Unit) {
    if (state.newsKeyMissing) return
    val articles = state.newsArticles
    if (articles.isEmpty() && !state.newsLoading) return

    Spacer(Modifier.height(24.dp))
    CardHeader(tr(L10n.Key.News))
    Spacer(Modifier.height(8.dp))
    if (articles.isEmpty()) {
        // Loading with nothing yet: a bare, centered spinner (no heading duplication).
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = DK.gold)
        }
        return
    }
    val now = System.currentTimeMillis() / 1000
    Column(Modifier.fillMaxWidth()) {
        articles.forEachIndexed { index, article ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
            }
            ArticleRow(
                article = article,
                bookmarked = state.bookmarkedIds.contains(article.id),
                now = now,
                onOpen = { openUrlInBrowser(article.url) },
                onToggleBookmark = { onToggleBookmark(article) },
            )
        }
    }
}

/** Gold paper-trade entry point in the detail header. macOS ships separate Buy/Sell buttons;
 *  desktop diverges to a single "BUY / SELL" pill that opens the dialog with Buy preselected —
 *  the dialog's own toggle covers Sell. */
@Composable
private fun TradeButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.goldGradient)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            tr(L10n.Key.BuySellButton),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DK.bgBottom,
                letterSpacing = 0.8.sp,
            ),
        )
    }
}

/** Horizontally scrolling row of multi-select indicator chips, each with a leading 6dp
 *  colored dot (dimmed when off). macOS parity: none on by default. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IndicatorChips(selection: Set<Indicator>, onToggle: (Indicator) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (ind in Indicator.entries) {
            val on = selection.contains(ind)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) DK.surfaceHi else DK.surface)
                    .border(1.dp, if (on) ind.color.copy(alpha = 0.5f) else DK.hairline, RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggle(ind) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Box(Modifier.size(6.dp).background(ind.color.copy(alpha = if (on) 1f else 0.4f), CircleShape))
                Text(
                    ind.label,
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (on) DK.textPrimary else DK.textTertiary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun KindChip(kind: com.aptrade.shared.domain.AssetKind) {
    Text(
        // chipLabel is translated pre-uppercased (DE "AKTIE" ≠ "STOCK".uppercase()).
        chipLabel(kind),
        style = TextStyle(
            fontFamily = InterFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = DK.textSecondary,
            letterSpacing = 0.8.sp,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Line / Candles underline toggle — the TimeframeBar idiom for two labels. */
@Composable
private fun ModeToggle(mode: ChartMode, onModeChange: (ChartMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        for (m in ChartMode.entries) {
            val selected = m == mode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onModeChange(m) },
            ) {
                Text(
                    if (m == ChartMode.Line) tr(L10n.Key.ChartStyleLine) else tr(L10n.Key.ChartStyleCandles),
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) DK.gold else DK.textSecondary,
                    ),
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .height(2.dp)
                        .width(28.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (selected) DK.gold else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun ChartError(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            message,
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = DK.textSecondary,
            ),
        )
        Text(
            tr(L10n.Key.Retry),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = DK.gold,
            ),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onRetry() },
        )
    }
}

/** Card header with wide tracking, matching macOS KEY STATS / YOUR POSITION section labels. */
@Composable
private fun CardHeader(title: String) {
    Text(
        title,
        style = TextStyle(
            fontFamily = InterFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = DK.textSecondary,
            letterSpacing = 1.8.sp,
        ),
    )
}

/** Two-column card container (surface at 0.5 alpha, 14dp radius, hairline border, 20dp pad). */
@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DK.surface.copy(alpha = 0.5f))
            .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
            .padding(20.dp),
    ) {
        CardHeader(title)
        content()
    }
}

/** One row of the 2-column stat grid. */
@Composable
private fun StatRow(
    leftLabel: String, leftValue: String, leftColor: Color = DK.textPrimary,
    rightLabel: String, rightValue: String, rightColor: Color = DK.textPrimary,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.weight(1f)) { StatTile(label = leftLabel, value = leftValue, valueColor = leftColor) }
        Box(Modifier.weight(1f)) { StatTile(label = rightLabel, value = rightValue, valueColor = rightColor) }
    }
}

/** One-sided variant of [StatRow] for a stat with no natural pairing on its card (e.g.
 *  Next-earnings, which doesn't share a row with anything else in KEY STATS). File
 *  convention: two-value stats use the pair overload above, singles use this one. */
@Composable
private fun StatRow(label: String, value: String, color: Color = DK.textPrimary) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { StatTile(label = label, value = value, valueColor = color) }
    }
}

/** KEY STATS: Last, Previous close, Day change, Day change %, Symbol, Type. Money figures are
 *  formatted HERE via formatMoney/signedMoney from raw amountText (contract: raw money never
 *  goes through SuperscriptPrice/Money.usd from these cards). */
@Composable
private fun KeyStatsCard(state: DetailUiState) {
    val changeColor = DK.changeColor(state.changePercent)
    val last = state.amountText
    val prevClose = state.previousCloseText
    val dayChange = if (last != null && prevClose != null) {
        signedMoney((BigDecimal(last) - BigDecimal(prevClose)).toPlainString())
    } else "—"
    StatCard(title = tr(L10n.Key.KeyStats)) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            StatRow(
                leftLabel = tr(L10n.Key.StatLast), leftValue = last?.let { formatMoney(it) } ?: "—",
                rightLabel = tr(L10n.Key.StatPreviousClose), rightValue = prevClose?.let { formatMoney(it) } ?: "—",
            )
            StatRow(
                leftLabel = tr(L10n.Key.StatDayChange), leftValue = dayChange, leftColor = changeColor,
                rightLabel = tr(L10n.Key.StatDayChangePercent), rightValue = formatPercent(state.changePercent), rightColor = changeColor,
            )
            StatRow(
                leftLabel = tr(L10n.Key.StatSymbol), leftValue = state.symbol,
                rightLabel = tr(L10n.Key.StatType), rightValue = state.kind?.let { kindLabel(it) } ?: "—",
            )
            StatRow(
                label = tr(L10n.Key.NextEarnings),
                value = state.nextEarnings?.let { ev ->
                    val label = sessionLabel(ev.session)
                    val date = formatEventDate(ev.day)
                    // sessionLabel(Unknown) is "" — avoid a dangling "Jul 24 · " separator,
                    // same guard Main.kt's earnings-notification body already applies.
                    if (label.isEmpty()) date else "$date · $label"
                } ?: "—",
            )
        }
    }
}

/** YOUR POSITION (held only): Shares, Average cost, Market value, Unrealized P&L. The row's
 *  averageCost/unrealized are pre-formatted; marketValue/price are RAW → formatted here for
 *  plain display (never fed to SuperscriptPrice). */
@Composable
private fun PositionCard(row: HoldingRowUi) {
    val pnlColor = when (row.unrealizedPositive) {
        true -> DK.up
        false -> DK.down
        null -> DK.textPrimary
    }
    StatCard(title = tr(L10n.Key.YourPosition)) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            StatRow(
                leftLabel = tr(L10n.Key.StatShares), leftValue = row.quantityText,
                rightLabel = tr(L10n.Key.StatAverageCost), rightValue = row.averageCostText,
            )
            StatRow(
                leftLabel = tr(L10n.Key.StatMarketValue), leftValue = formatMoney(row.marketValueText),
                rightLabel = tr(L10n.Key.UnrealizedPnL), rightValue = row.unrealizedText, rightColor = pnlColor,
            )
        }
    }
}

// MARK: Dividends

/** DIVIDENDS card: yield/annual-rate stat pair, a next-estimated-ex-date row (with an "Est."
 *  badge, hidden when the projection is stale/absent), and a last-8 per-share mini bar chart.
 *  Only ever rendered when [DetailUiState.dividendInfo] is non-null (see [DetailContent]) —
 *  crypto, non-payers, and degraded fetches all present identically: no error state, no empty
 *  placeholder. Compose port of `AssetDetailView.dividendSection` (Swift AS-BUILT). */
@Composable
private fun DividendCard(info: DividendInfo) {
    StatCard(title = tr(L10n.Key.AssetDividendSection)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatRow(
                leftLabel = tr(L10n.Key.AssetDividendYield), leftValue = formatPercent(info.yieldFraction * 100),
                rightLabel = tr(L10n.Key.AssetDividendRate), rightValue = formatMoney(info.trailingAnnualRate.amountText),
            )
            if (info.nextEstimatedExDateEpochSeconds != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(L10n.Key.AssetNextExDate).uppercase(),
                        style = TextStyle(
                            fontFamily = InterFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DK.textTertiary,
                            letterSpacing = 1.0.sp,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        dividendExDateFormatter.format(Instant.ofEpochSecond(info.nextEstimatedExDateEpochSeconds)),
                        style = TextStyle(
                            fontFamily = InterFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DK.textPrimary,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    DividendEstBadge()
                }
            }
            if (info.recentAmounts.isNotEmpty()) {
                DividendMiniChart(info.recentAmounts)
            }
        }
    }
}

@Composable
private fun DividendEstBadge() {
    Text(
        tr(L10n.Key.IncomeEstimatedBadge).uppercase(),
        style = TextStyle(
            fontFamily = InterFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = DK.gold,
            letterSpacing = 0.4.sp,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.gold.copy(alpha = 0.12f))
            .border(1.dp, DK.gold.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** A small bar mini-chart of the last (up to) 8 per-share amounts, oldest first — no axes or
 *  labels, just the shape of the trend (bars read better than a continuous line for discrete
 *  per-payout amounts). Mirrors `AssetDetailView.dividendMiniChart`. */
@Composable
private fun DividendMiniChart(amounts: List<Money>) {
    val values = amounts.map { it.amount.doubleValue(false) }
    val maxValue = values.maxOrNull() ?: 0.0
    Row(
        Modifier.fillMaxWidth().height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (value in values) {
            val barHeight = if (maxValue > 0.0) (36.0 * value / maxValue).coerceAtLeast(3.0) else 3.0
            Box(
                Modifier
                    .weight(1f)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DK.gold.copy(alpha = 0.7f)),
            )
        }
    }
}
