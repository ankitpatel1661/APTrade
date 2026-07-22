package com.aptrade.desktop.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.calendar.sessionLabel
import com.aptrade.desktop.designkit.ChangePill
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.StatTile
import com.aptrade.desktop.designkit.SuperscriptPrice
import com.aptrade.desktop.designkit.alertSummary
import com.aptrade.desktop.designkit.crosshairIndex
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.designkit.signedMoney
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.desktop.portfolio.CrosshairTooltip
import com.aptrade.desktop.portfolio.PerfPointUi
import com.aptrade.desktop.portfolio.PortfolioSection
import com.aptrade.desktop.portfolio.PortfolioSpan
import com.aptrade.desktop.portfolio.PortfolioUiState
import com.aptrade.desktop.portfolio.SpanBar
import com.aptrade.desktop.screener.presetTitleKey
import com.aptrade.desktop.ui.InvestSection
import com.aptrade.desktop.ui.MarketsSection
import com.aptrade.desktop.ui.SidebarDestination
import com.aptrade.shared.application.HomeFeedItem
import com.aptrade.shared.application.HomeState
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.LocalDate as JavaLocalDate

/**
 * Home dashboard — the Compose port of `Sources/APTradeApp/HomeView.swift`'s `HomeViewMac`
 * AS-BUILT (post-UAT): hero (microlabel + [SuperscriptPrice] total + change pill, click →
 * Portfolio) with the RICH P&L chart expanded by default underneath it, a quick-stat trio,
 * and a two-column grid — a "Today" feed card on the left, a stats card + Alerts card on the
 * right. All hero-stat/feed data comes from [HomeViewModel] (`:shared`'s `HomeFeedAssembler`,
 * M10.2 Task 1/2) — this pane only lays it out and formats it, per house rule.
 *
 * CHART REUSE (M10.2 Task 4's central constraint — "reuse, do NOT recompute"): the hero chart
 * is fed by [portfolioState], the SAME [com.aptrade.desktop.portfolio.PortfolioViewModel]
 * instance `Main.kt` already shares with the Portfolio tab's `PerformanceSection` — not a
 * second, dedicated VM instance the way Swift's `HomeHeroPnLChart` owns its own
 * `PortfolioViewModel` copy purely for this chart. A deliberate, documented divergence: since
 * both surfaces read one shared state, the hero and the Portfolio tab's own Performance
 * section always agree (same span, same series, same day-one edge case), and switching span
 * from either surface updates the other next time it's visited. [SpanBar] and
 * [CrosshairTooltip] are hoisted (`internal`, this task) straight out of
 * `portfolio/PerformanceSection.kt` and reused verbatim; [HomeHeroChart] below is a NEW,
 * single-series sibling of that file's private `OverlayChart` — `OverlayChart` itself isn't
 * reusable as-is because it treats a null benchmark as a fetch-failure error state ("Benchmark
 * unavailable"), not as "no benchmark wanted", and Home's glance dashboard deliberately shows
 * only the portfolio curve (no benchmark picker, no risk-metric grid — those stay
 * Portfolio-tab-only "drill down" content). The line renders in the SAME fixed gold
 * `OverlayChart` uses for the portfolio series (not Swift's up/down-tinted PnL line): since
 * the hero click navigates straight into this exact chart on the Portfolio tab, one consistent
 * color reads as "the same chart, zoomed out" rather than introducing a second, incompatible
 * treatment for identical data.
 *
 * ALERTS PREVIEW: Task 5 (Desktop Alerts center) hasn't shipped its `AlertsCenterViewModel`
 * yet, so this pane loads the raw alert list directly via the [loadAlerts] closure the caller
 * (`Main.kt`) wires from `AppGraph.loadAlerts` — mirroring how Swift's `HomeViewMac` owns a
 * second, dedicated `AlertsCenterViewModel` instance purely for its 2-row preview. `onOpenAlerts`
 * is a plain closure for now (Task 5 wires the real dialog); the armed-count header always
 * comes from [HomeState.alertCount] (Global Constraint 8 — armed/non-triggered only).
 *
 * REFRESH CADENCE (Global Constraint 1): ONE sequential `LaunchedEffect` loop —
 * `while (isActive) { vm.refresh(); reload alerts; delay(15_000) }` — refresh always
 * completes before the next `delay` is scheduled, so overlapping refreshes are impossible.
 * Mirrors `HomeViewMac.body`'s `.task` exactly, folding the alerts reload into the same tick
 * the way Swift folds `alertsVM.load()` into it.
 */
@Composable
fun HomePane(
    vm: HomeViewModel,
    portfolioState: PortfolioUiState,
    onSetPortfolioSpan: (PortfolioSpan) -> Unit,
    loadAlerts: suspend () -> List<PriceAlert>,
    onNavigate: (SidebarDestination) -> Unit,
    onOpenAlerts: () -> Unit,
) {
    val homeState by vm.state.collectAsState()
    var alerts by remember { mutableStateOf<List<PriceAlert>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            vm.refresh()
            try {
                alerts = loadAlerts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the last-good list — one degraded tick shouldn't blank the preview.
            }
            delay(15_000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroSection(
            homeState = homeState,
            portfolioState = portfolioState,
            onSetSpan = onSetPortfolioSpan,
            onNavigate = onNavigate,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TodayCard(
                feed = homeState?.feed ?: emptyList(),
                onNavigate = onNavigate,
                modifier = Modifier.weight(1f),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StatsCard(homeState = homeState, portfolioState = portfolioState)
                AlertsCard(
                    alertCount = homeState?.alertCount ?: 0,
                    alerts = alerts,
                    onOpenAlerts = onOpenAlerts,
                )
            }
        }
    }
}

// MARK: - Hero (value + change pill + rich chart)

@Composable
private fun HeroSection(
    homeState: HomeState?,
    portfolioState: PortfolioUiState,
    onSetSpan: (PortfolioSpan) -> Unit,
    onNavigate: (SidebarDestination) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    // Constraint 4: hero click -> Portfolio. Performance (not Holdings) since
                    // this chart IS the Portfolio tab's Performance section, just fed by the
                    // same state — clicking it lands on the exact same chart, in context.
                    onNavigate(SidebarDestination.Portfolio(PortfolioSection.Performance))
                }
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tr(L10n.Key.PortfolioValue).uppercase(), style = microlabelStyle())
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val totalValueText = homeState?.totalValue?.amountText
                if (totalValueText != null) {
                    SuperscriptPrice(amountText = totalValueText, size = 34.sp)
                } else {
                    Text(
                        "—",
                        style = TextStyle(fontFamily = InterFamily, fontSize = 34.sp,
                            fontWeight = FontWeight.SemiBold, color = DK.textSecondary),
                    )
                }
                if (homeState != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            signedMoney(homeState.dayChange.amountText),
                            style = TextStyle(
                                fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = DK.changeColor(homeState.dayChangePercent), fontFeatureSettings = "tnum",
                            ),
                        )
                        ChangePill(homeState.dayChangePercent)
                        Text(
                            tr(L10n.Key.TodaySection).lowercase(),
                            style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = DK.textTertiary),
                        )
                    }
                }
            }
        }
        val maxDayOne = portfolioState.span == PortfolioSpan.Max &&
            portfolioState.transactions.isNotEmpty() && portfolioState.performancePoints.size < 2
        SpanBar(selection = portfolioState.span, onSelect = onSetSpan)
        HomeHeroChart(
            values = portfolioState.performanceValues,
            points = portfolioState.performancePoints,
            maxDayOne = maxDayOne,
        )
    }
}

/** The Home hero's single-series crosshair chart — see this file's header doc ("CHART
 *  REUSE") for why this is a small new sibling of `PerformanceSection.OverlayChart` rather
 *  than that composable reused as-is. Same visual family: gold stroke, dashed (3,3) hairline
 *  crosshair, gold dot, [CrosshairTooltip] pill — just one series and a soft gradient fill
 *  (the `HomeHeroSpark`-style touch Swift's fallback sparkline used, kept here since this is
 *  the ONLY chart the Home hero shows, never a bare-sparkline fallback). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeHeroChart(values: List<Double>, points: List<PerfPointUi>, maxDayOne: Boolean) {
    var hoverIndex by remember { mutableStateOf<Int?>(null) }
    var chartWidthPx by remember { mutableStateOf(0f) }
    val lastIndex = (values.size - 1).coerceAtLeast(0)
    val activeIndex = (hoverIndex ?: lastIndex).coerceIn(0, lastIndex)

    Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) {
        when {
            maxDayOne -> HeroChartMessage(tr(L10n.Key.TrackingStartsTodayMessage))
            values.size < 2 -> HeroChartMessage(tr(L10n.Key.NoPerformanceDataYet))
            else -> {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
                            hoverIndex = crosshairIndex(x, chartWidthPx, values.size)
                        }
                        .onPointerEvent(PointerEventType.Exit) { hoverIndex = null },
                ) {
                    chartWidthPx = size.width
                    val min = values.min(); val max = values.max()
                    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
                    val stepX = size.width / (values.size - 1)
                    fun y(v: Double) = size.height - ((v - min) / span * size.height).toFloat()

                    val line = Path()
                    values.forEachIndexed { i, v ->
                        val x = i * stepX
                        if (i == 0) line.moveTo(x, y(v)) else line.lineTo(x, y(v))
                    }
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(
                        fill,
                        Brush.verticalGradient(listOf(DK.gold.copy(alpha = 0.18f), DK.gold.copy(alpha = 0f))),
                    )
                    drawPath(
                        line, DK.gold,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )

                    val idx = activeIndex.coerceIn(0, values.size - 1)
                    val cx = idx * stepX
                    val cy = y(values[idx])
                    drawLine(
                        DK.hairline, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f),
                    )
                    drawCircle(DK.gold, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
                }

                val activePoint = points.getOrNull(activeIndex)
                if (activePoint != null && values.size > 1) {
                    val fraction = activeIndex.toFloat() / (values.size - 1)
                    CrosshairTooltip(
                        valueText = activePoint.valueText,
                        dateText = activePoint.tooltipDateText,
                        fraction = fraction,
                        emphasized = hoverIndex != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroChartMessage(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
    )
}

// MARK: - Quick stats + Alerts (right column)

/** The trio of quick stats, in its own card — matches `HomeViewMac.statsCard` exactly (Swift
 *  has NO standalone stats row on desktop/macOS; that's an iPhone-only layout element). Total
 *  Return is read from [PortfolioUiState.metrics] (the SAME performance report the hero chart
 *  and Portfolio tab's risk-metric grid already share) rather than `HomeState`, which carries
 *  no total-return field — `HomeFeedAssembler` (Task 1, shared, out of this task's scope)
 *  aggregates hero-stat/feed data only, not the performance report's own metrics. This mirrors
 *  Swift's own `HomeViewModel.totalReturnPercent`, which is likewise sourced from a
 *  `PerformanceReport.metrics.totalReturn` fetch — just the SAME shared fetch here, rather
 *  than a second, Home-only one. */
@Composable
private fun StatsCard(homeState: HomeState?, portfolioState: PortfolioUiState) {
    val totalReturnText = portfolioState.metrics?.totalReturn ?: "—"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        StatTile(
            label = tr(L10n.Key.TotalReturn),
            value = totalReturnText,
            valueColor = signColorFromFormattedText(totalReturnText),
        )
        StatTile(
            label = tr(L10n.Key.CashLabel),
            value = homeState?.cash?.let { formatMoney(it.amountText) } ?: "—",
        )
        StatTile(
            label = tr(L10n.Key.IncomeYtdLabel),
            value = homeState?.incomeYTD?.let { formatMoney(it.amountText) } ?: "—",
        )
    }
}

/** Derives a sign color from an already-formatted (`formatPercent`) string rather than a raw
 *  Double — `PortfolioUiState.metrics.totalReturn` is pre-formatted text (Task 7/8's per-field
 *  contract: render verbatim, never re-parse), so this only reads the leading sign glyph the
 *  formatter itself always writes ("+"/"-"), never re-parses the number. */
private fun signColorFromFormattedText(text: String): Color = when {
    text.startsWith("-") -> DK.down
    text.startsWith("+") -> DK.up
    else -> DK.textPrimary
}

/** Armed (non-triggered) alert count in the header (Global Constraint 8) plus a 2-row preview
 *  of the oldest-store-order armed alerts — mirrors `HomeViewMac.alertsCard`/`alertsPreview`
 *  exactly. Clicking the whole card opens the Alerts center ([onOpenAlerts] — Task 5 wires the
 *  real dialog; today it's a no-op the caller supplies). No SF-Symbol-style bell glyph exists
 *  in this desktop design kit (checked: no icon-font/Canvas-glyph precedent for "bell" the way
 *  `MagnifierIcon`/`MoonIcon`/`SunIcon` cover search/appearance) — a small gold dot stands in,
 *  the same "colored dot as status indicator" idiom the Today card's market-status row and the
 *  mockup's own alert bullet already use. */
@Composable
private fun AlertsCard(alertCount: Int, alerts: List<PriceAlert>, onOpenAlerts: () -> Unit) {
    val armed = remember(alerts) { alerts.filter { !it.isTriggered }.take(2) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpenAlerts)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${tr(L10n.Key.AlertsCenterTitle).uppercase()} · ${trf(L10n.Key.AlertsActiveFmt, alertCount)}",
            style = microlabelStyle(),
        )
        if (armed.isEmpty()) {
            Text(
                tr(L10n.Key.AlertsEmpty),
                style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = DK.textSecondary),
            )
        } else {
            for (alert in armed) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(DK.gold))
                    Text(
                        alert.symbol,
                        style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DK.textPrimary),
                    )
                    Text(
                        alertSummary(alert.condition),
                        style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, color = DK.textSecondary),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        tr(L10n.Key.AlertArmed).uppercase(),
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            color = DK.gold, letterSpacing = 0.6.sp,
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(DK.gold.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

// MARK: - Today card (feed)

@Composable
private fun TodayCard(feed: List<HomeFeedItem>, onNavigate: (SidebarDestination) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            tr(L10n.Key.TodaySection).uppercase(),
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = DK.textTertiary, letterSpacing = 1.4.sp,
            ),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        // Feed order is fixed by the assembler (Global Constraint 1); rendered verbatim,
        // never re-sorted here. Index-keyed (not item-keyed): `HomeFeedItem` isn't a stable
        // identity type across refreshes, mirroring `homeTodayCard`'s own offset-composited
        // `ForEach` identity on the Swift side.
        feed.forEachIndexed { index, item ->
            FeedRow(item, onNavigate)
            if (index < feed.size - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
            }
        }
    }
}

@Composable
private fun FeedRow(item: HomeFeedItem, onNavigate: (SidebarDestination) -> Unit) {
    when (item) {
        is HomeFeedItem.MarketStatus -> MarketStatusRow(item)

        is HomeFeedItem.TopGainer -> ClickableFeedRow(onClick = { onNavigate(SidebarDestination.Markets(MarketsSection.Watchlist)) }) {
            ChangePill(item.changePercent)
            val (prefix, suffix) = splitOnToken(tr(L10n.Key.LeadsHoldingsFmt), "%s")
            BoldSegmentRow(prefix to false, item.symbol to true, suffix to false, modifier = Modifier.weight(1f))
        }

        is HomeFeedItem.TopLoser -> ClickableFeedRow(onClick = { onNavigate(SidebarDestination.Markets(MarketsSection.Watchlist)) }) {
            ChangePill(item.changePercent)
            val (prefix, suffix) = splitOnToken(tr(L10n.Key.BiggestFallerFmt), "%s")
            BoldSegmentRow(prefix to false, item.symbol to true, suffix to false, modifier = Modifier.weight(1f))
        }

        is HomeFeedItem.Earnings -> ClickableFeedRow(onClick = { onNavigate(SidebarDestination.Markets(MarketsSection.Calendar)) }) {
            FeedGlyph("⏱", DK.textSecondary)
            val (prefix, suffix) = splitOnToken(tr(L10n.Key.ReportsEarningsFmt), "%s")
            BoldSegmentRow(prefix to false, item.symbol to true, suffix to false, modifier = Modifier.weight(1f))
            val label = sessionLabel(item.session)
            Text(
                label.ifEmpty { homeDayAbbrev(item.day) },
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = DK.textTertiary),
            )
        }

        is HomeFeedItem.Dividend -> ClickableFeedRow(onClick = { onNavigate(SidebarDestination.Invest(InvestSection.Income)) }) {
            FeedGlyph("$", DK.gold)
            val raw = tr(L10n.Key.DividendEstFmt)
            val (prefix, rest) = splitOnToken(raw, "%1\$s")
            val (middle, suffix) = splitOnToken(rest, "%2\$s")
            BoldSegmentRow(
                prefix to false, item.symbol to true, middle to false, formatMoney(item.amount.amountText) to false, suffix to false,
                modifier = Modifier.weight(1f),
            )
            Text(
                homeDayAbbrev(item.day),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = DK.textTertiary),
            )
        }

        is HomeFeedItem.ScreenerFresh -> ClickableFeedRow(onClick = { onNavigate(SidebarDestination.Markets(MarketsSection.Screener)) }) {
            FeedGlyph("◎", DK.gold)
            val raw = tr(L10n.Key.ScreenerFreshFmt)
            val (prefix, rest) = splitOnToken(raw, "%1\$s")
            val (middle, suffix) = splitOnToken(rest, "%2\$d")
            BoldSegmentRow(
                prefix to false, tr(presetTitleKey(item.preset)) to true, middle to false, item.matches.toString() to false, suffix to false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MarketStatusRow(item: HomeFeedItem.MarketStatus) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (item.isOpen) DK.up else DK.down))
        Text(
            if (item.isOpen) tr(L10n.Key.MarketOpenStatus) else tr(L10n.Key.MarketClosedStatus),
            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = DK.textPrimary),
            modifier = Modifier.weight(1f),
        )
        val whenText = if (item.isOpen) {
            trf(L10n.Key.ClosesAtFmt, formatMarketTransition(item.nextTransitionEpochSeconds, homeCloseTimeFormatter))
        } else {
            trf(L10n.Key.OpensAtFmt, formatMarketTransition(item.nextTransitionEpochSeconds, homeOpenTimeFormatter))
        }
        Text(whenText, style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = DK.textTertiary))
    }
}

/** A clickable Today row: every `HomeFeedItem` case except `.marketStatus` navigates on tap
 *  (mirrors Swift's `homeFeedRow`, where only `.marketStatus` is a plain `HStack`, not a
 *  `Button`). */
@Composable
private fun ClickableFeedRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Fixed-width (16dp) leading glyph — mirrors Swift's `.frame(width: 16)` SF-Symbol column
 *  for the earnings/dividend/screener rows. Plain Unicode glyphs (⏱ / $ / ◎), not emoji: no
 *  Canvas-drawn icon exists for these three in this design kit, and the mockup's own glyphs
 *  for these exact rows (⏱ 💰 ◎) are simple enough that a plain character (colored, sized like
 *  the rest of the row) reads cleanly without adding new icon components for a UI-waiver task. */
@Composable
private fun FeedGlyph(text: String, color: Color) {
    Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = color))
    }
}

// MARK: - Bold-substring row rendering

/** Splits a catalog format string on a literal placeholder token (e.g. "%s", "%1$s", "%2$d")
 *  — mirrors `Sources/APTradeApp/HomeView.swift`'s `boldedRow`/`dividendRowText`/
 *  `screenerFreshRowText`, which split the LOCALIZED string on the literal placeholder rather
 *  than the English source, so this works unchanged in every language (every translation in
 *  the catalog keeps the same `%s`/`%1$s`/`%2$d` tokens verbatim — only the surrounding prose
 *  is translated). Falls back to the whole string as the prefix (empty suffix) if the token
 *  isn't found (defensive only — every format this is called with has the expected tokens). */
private fun splitOnToken(source: String, token: String): Pair<String, String> {
    val idx = source.indexOf(token)
    return if (idx < 0) source to "" else source.substring(0, idx) to source.substring(idx + token.length)
}

/** Renders a sequence of (text, bold) segments as adjacent `Text` elements in a `Row` — this
 *  codebase's established multi-segment-line idiom (see `CalendarPane.EarningsRow`'s
 *  symbol+name+chip layout) rather than an `AnnotatedString`. Empty segments (an argument
 *  landing at the very start/end of a format string) are skipped so no stray empty `Text`
 *  widens the row. */
@Composable
private fun BoldSegmentRow(vararg segments: Pair<String, Boolean>, modifier: Modifier = Modifier) {
    Row(modifier) {
        // `continue` inside a composable `for` loop body is deliberately avoided here (a
        // known Compose-compiler group-insertion hazard — an early-exit branch mid-loop can
        // desync the slot table from what the applier expects, surfacing as "Cannot end node
        // insertion..."). An `if` guard around the single `Text` call keeps every iteration's
        // control flow structurally uniform instead.
        for ((text, bold) in segments) {
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp,
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        color = DK.textPrimary,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun microlabelStyle(): TextStyle = TextStyle(
    fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
    color = DK.textTertiary, letterSpacing = 1.4.sp,
)

// MARK: - Date/time formatting (Global Constraint 1: LocalDate end-to-end, never via Instant)

/** `kotlinx.datetime.LocalDate` -> "Thu" — day-of-week abbreviation, matching
 *  `Sources/APTradeApp/HomeView.swift`'s `homeUTCDayFormatter` ("EEE") output shape exactly.
 *  Built the SAME way `CalendarPane.formatEventDate`/`formatDayHeader` already format a day
 *  string: a `java.time.DateTimeFormatter` over `Locale.US`, fed from the day's own
 *  year/monthNumber/dayOfMonth fields — `java.time.LocalDate.of(...)` is used here PURELY as a
 *  formatting shim (no Instant/epoch round-trip), so this stays LocalDate end-to-end per
 *  Global Constraint 1, while reusing the exact date-format idiom already established for
 *  day-only values on this desktop app rather than hand-rolling a Mon/Tue/... string table. */
private val homeDayAbbrevFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.US)

private fun homeDayAbbrev(date: LocalDate): String =
    homeDayAbbrevFormatter.format(JavaLocalDate.of(date.year, date.monthNumber, date.dayOfMonth))

/** Market-hours transition instants ARE real instants (an exact moment the session opens/
 *  closes), unlike the day-only fields above — displayed in the exchange's own timezone,
 *  matching `CalendarView`'s/Swift's `homeCloseTimeFormatter`/`homeOpenTimeFormatter`
 *  precedent (America/New_York, en_US). */
private val marketZone: ZoneId = ZoneId.of("America/New_York")
private val homeCloseTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val homeOpenTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, h:mm a", Locale.US)

private fun formatMarketTransition(epochSeconds: Long, formatter: DateTimeFormatter): String =
    formatter.format(Instant.ofEpochSecond(epochSeconds).atZone(marketZone))
