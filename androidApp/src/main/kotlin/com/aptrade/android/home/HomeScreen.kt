package com.aptrade.android.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.aptrade.android.calendar.sessionLabel
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.android.portfolio.PortfolioSpan
import com.aptrade.android.portfolio.PortfolioSpanSelector
import com.aptrade.android.portfolio.PortfolioUiState
import com.aptrade.android.portfolio.PortfolioViewModel
import com.aptrade.android.screener.presetTitleKey
import com.aptrade.android.ui.chart.CrosshairTooltip
import com.aptrade.android.ui.chart.DualLineChart
import com.aptrade.android.ui.chart.DualSeriesCrosshairOverlay
import com.aptrade.android.ui.chart.crosshairReadout
import com.aptrade.android.ui.chart.nearestIndex
import com.aptrade.android.ui.formatPercent
import com.aptrade.android.ui.money
import com.aptrade.android.ui.signedMoney
import com.aptrade.android.ui.theme.GainGreen
import com.aptrade.android.ui.theme.LossRed
import com.aptrade.shared.application.HomeFeedItem
import com.aptrade.shared.application.HomeState
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.LocalDate as JavaLocalDate

/**
 * Where a Home row/card/hero sends the user — mirrors Swift `HomeDestination`
 * (`Sources/APTradeApp/HomeView.swift`) and desktop HomePane's `SidebarDestination` writes:
 * [com.aptrade.android.MainActivity]'s `AppNavHost` owns the actual tab/section switch (it maps
 * each case onto [com.aptrade.android.ShellTab] + the destination tab's hoisted section state,
 * Global Constraint 3); this only describes the destination, never navigates directly.
 */
sealed class HomeDestination {
    data object PortfolioPerformance : HomeDestination()
    data object MarketsWatchlist : HomeDestination()
    data object MarketsScreener : HomeDestination()
    data object MarketsCalendar : HomeDestination()
    data object MarketsNews : HomeDestination()
    data object InvestIncome : HomeDestination()
}

/**
 * Home dashboard — phone layout per the M10 mockup's Phone frame Home screen and
 * `Sources/APTradeApp/HomeView.swift`'s iPhone `HomeView.body` as-built: a hero (microlabel +
 * total + change pill, tap → Portfolio·Performance) with THE rich P&L chart underneath it, a
 * quick-stat trio, a "Today" feed card, and a 2×2 quick-card grid. All hero-stat/feed data comes
 * from [vm] (`:shared`'s `HomeFeedAssembler` via Android's [HomeViewModel], M10.3 Task 1) — this
 * screen only lays it out and formats it, per house rule.
 *
 * CHART REUSE (M10.3 Task 3's central constraint, Global Constraint 2 divergence #3 — "reuse,
 * do NOT recompute"): the hero chart is fed by [portfolioViewModel], THE SAME
 * [PortfolioViewModel] instance `AppNavHost` already shares with the Portfolio tab's own
 * Performance section (hoisted there — see that file's KDoc for the full ownership trace; it
 * used to be constructed locally inside `PortfolioScreen` through Task 2). Since both surfaces
 * read one shared state, the hero and the Portfolio tab's own Performance section always agree
 * (same span, same series, same day-one edge case), and switching span from either surface
 * updates the other next time it's visited — this is the SAME span-coupling desktop's `HomePane`
 * documents on its own `HeroSection`. [PortfolioSpanSelector] is reused verbatim (hoisted
 * `internal` out of `PortfolioScreen.kt`'s `PerformanceSection`, mirroring desktop's own `SpanBar`
 * hoist). The chart itself needs NO new single-series sibling component the way desktop's
 * `HomeHeroChart` did: Android's existing [DualLineChart]/[DualSeriesCrosshairOverlay] already
 * treat a `null` secondary series as "no benchmark wanted" (see `Charts.kt`'s own KDoc), so this
 * screen reuses them directly with `secondary = null` — the divergence desktop had to work around
 * (its `OverlayChart` treats a null benchmark as a fetch-failure error state) doesn't exist here.
 *
 * REFRESH CADENCE (M10.3 Global Constraint 1): ONE sequential `LaunchedEffect` +
 * `repeatOnLifecycle(STARTED) { while (isActive) { vm.refresh(); delay(15_000) } }` — refresh
 * always completes before the next `delay` is scheduled, so overlapping refreshes are
 * impossible. [portfolioViewModel]'s own poll runs on its own lifecycle, gated at `AppNavHost`
 * (not here) — see that file's KDoc for why it's no longer gated to the Portfolio tab's own
 * visibility.
 *
 * [padding] is [com.aptrade.android.AppShell]'s Scaffold content padding — same
 * start/end/top-on-the-container, bottom-as-`contentPadding` split [com.aptrade.android.invest
 * .InvestScreen]/[com.aptrade.android.markets.MarketsScreen] already use.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    portfolioViewModel: PortfolioViewModel,
    padding: PaddingValues,
    onNavigate: (HomeDestination) -> Unit,
    onOpenAlerts: () -> Unit,
) {
    val homeState by vm.state.collectAsState()
    val portfolioState by portfolioViewModel.state.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                vm.refresh()
                delay(15_000)
            }
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = padding.calculateStartPadding(layoutDirection),
                end = padding.calculateEndPadding(layoutDirection),
                top = padding.calculateTopPadding(),
            ),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HomeHeader(alertCount = homeState?.alertCount ?: 0, onOpenAlerts = onOpenAlerts) }
        item {
            HeroSection(
                homeState = homeState,
                portfolioState = portfolioState,
                onSetSpan = portfolioViewModel::setSpan,
                onNavigate = onNavigate,
            )
        }
        item { StatsTrio(homeState = homeState, portfolioState = portfolioState) }
        item { TodayCard(feed = homeState?.feed ?: emptyList(), onNavigate = onNavigate) }
        item { QuickCardsGrid(homeState = homeState, onNavigate = onNavigate, onOpenAlerts = onOpenAlerts) }
    }
}

// MARK: - Header (bell)

@Composable
private fun HomeHeader(alertCount: Int, onOpenAlerts: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        HomeBellButton(alertCount = alertCount, onClick = onOpenAlerts)
    }
}

/**
 * The bell, badged when [alertCount] > 0 — placed in Home's OWN screen header rather than
 * [com.aptrade.android.AppShell]'s persistent TopAppBar (the M10.3 Task 3 brief allows either;
 * the TopAppBar is shared by all four tabs, and threading `alertCount` there would mean hoisting
 * [HomeViewModel] itself out of the Home-only branch just to badge an icon only Home currently
 * needs — this keeps the change contained to this screen). Opens the real Alerts center once
 * that screen exists — Android has none yet (that lands in a later task); until then this is an
 * inert placeholder, same as the Alerts quick card below. Mirrors
 * [com.aptrade.android.watchlist.WatchlistScreen]'s own private `AlertBell` exactly (same plain
 * "🔔" glyph — no bell glyph ships in `material-icons-core`, see that composable's own KDoc —
 * same [BadgedBox]/[Badge] idiom), just reading Home's armed-only [HomeState.alertCount]
 * (Global Constraint 8) instead of a per-symbol alert count.
 */
@Composable
private fun HomeBellButton(alertCount: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            if (alertCount > 0) Badge { Text(alertCount.toString()) }
        }) {
            Text(
                "🔔",
                style = MaterialTheme.typography.titleMedium,
                color = if (alertCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: - Hero (value + change pill + span selector + rich chart)

@Composable
private fun HeroSection(
    homeState: HomeState?,
    portfolioState: PortfolioUiState,
    onSetSpan: (PortfolioSpan) -> Unit,
    onNavigate: (HomeDestination) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    // Global Constraint 2 divergence #2: hero tap -> Portfolio·Performance (not
                    // Holdings) — this chart literally IS the Portfolio tab's Performance
                    // section, just fed by the same shared VM (see this file's header KDoc);
                    // tapping it lands on the exact same chart, in context. Mirrors desktop
                    // HomePane's identical click-site comment/destination choice.
                    onNavigate(HomeDestination.PortfolioPerformance)
                }
                .padding(vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                tr(L10n.Key.PortfolioValue).uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
            )
            Text(
                homeState?.totalValue?.amountText?.let { money(it) } ?: "—",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            if (homeState != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeChangePill(homeState.dayChangePercent)
                    Text(
                        signedMoney(homeState.dayChange.amountText),
                        style = MaterialTheme.typography.bodySmall,
                        color = homeChangeColor(homeState.dayChangePercent),
                    )
                    Text(
                        tr(L10n.Key.TodaySection).lowercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        PortfolioSpanSelector(selected = portfolioState.span, onSelect = onSetSpan)
        HomeHeroChart(portfolioState)
    }
}

@Composable
private fun HomeChangePill(percent: Double) {
    val color = homeChangeColor(percent)
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
        Text(
            formatPercent(percent),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun homeChangeColor(percent: Double): Color = when {
    percent > 0.0 -> GainGreen
    percent < 0.0 -> LossRed
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * The Home hero's single-series drag-crosshair chart, fed by [portfolioState] (see this file's
 * header KDoc — "CHART REUSE"). Structurally a slimmed [PortfolioUiState]-only twin of
 * `PortfolioScreen.kt`'s own `PerformanceSection` chart Box — same drag-gesture/crosshair
 * mechanic, same [DualLineChart]/[DualSeriesCrosshairOverlay]/[CrosshairTooltip]/[nearestIndex]/
 * [crosshairReadout] primitives — just fed `secondary = null` throughout (no benchmark picker,
 * no risk-metric grid: those stay Portfolio-tab-only "drill down" content, matching desktop
 * HomePane's own scope decision for its hero chart).
 */
@Composable
private fun HomeHeroChart(portfolioState: PortfolioUiState) {
    // On MAX, a portfolio that has traded but has fewer than two performance points is day-one:
    // the tracking curve fills in from the first market close, not instantly. Mirrors
    // PerformanceSection/desktop.
    val maxDayOne = portfolioState.span == PortfolioSpan.Max &&
        portfolioState.transactions.isNotEmpty() && portfolioState.performanceValues.size < 2
    val chartRenders = !maxDayOne && portfolioState.performanceValues.size >= 2

    var dragX by remember { mutableStateOf<Float?>(null) }
    var chartWidthPx by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .onSizeChanged { chartWidthPx = it.width.toFloat() }
            .let { base ->
                if (chartRenders) {
                    base.pointerInput(portfolioState.span, portfolioState.performanceValues.size) {
                        detectDragGestures(
                            onDragStart = { offset -> dragX = offset.x },
                            onDrag = { change, _ -> dragX = change.position.x },
                            onDragEnd = { dragX = null },
                            onDragCancel = { dragX = null },
                        )
                    }
                } else {
                    base
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            maxDayOne -> HomeEmptyChartText(tr(L10n.Key.TrackingStartsTodayMessage))
            portfolioState.performanceValues.size < 2 -> HomeEmptyChartText(tr(L10n.Key.NoPerformanceDataYet))
            else -> {
                DualLineChart(
                    primary = portfolioState.performanceValues,
                    secondary = null,
                    modifier = Modifier.fillMaxSize(),
                )
                dragX?.let { x ->
                    val index = nearestIndex(x, chartWidthPx, portfolioState.performanceValues.size)
                    DualSeriesCrosshairOverlay(
                        primary = portfolioState.performanceValues,
                        secondary = null,
                        index = index,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val readout = crosshairReadout(index, portfolioState.performanceValueTexts, portfolioState.performanceDates)
                    readout?.let {
                        CrosshairTooltip(
                            priceText = it.priceText,
                            dateText = it.dateText,
                            rawX = x,
                            chartWidthPx = chartWidthPx,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeEmptyChartText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// MARK: - Quick stats trio

/** Total Return / Cash / Income YTD, matching the mockup's phone Home quick-stat row exactly.
 *  Total Return is read from [PortfolioUiState.metrics] (the SAME performance report the hero
 *  chart shares) rather than [HomeState], which carries no total-return field — mirrors desktop
 *  `HomePane.StatsCard`'s identical sourcing rationale. */
@Composable
private fun StatsTrio(homeState: HomeState?, portfolioState: PortfolioUiState) {
    val totalReturnText = portfolioState.metrics?.totalReturn ?: "—"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        HomeStatTile(
            label = tr(L10n.Key.TotalReturn),
            value = totalReturnText,
            valueColor = signColorFromFormattedText(totalReturnText),
        )
        HomeStatTile(
            label = tr(L10n.Key.CashLabel),
            value = homeState?.cash?.amountText?.let { money(it) } ?: "—",
        )
        HomeStatTile(
            label = tr(L10n.Key.IncomeYtdLabel),
            value = homeState?.incomeYTD?.amountText?.let { money(it) } ?: "—",
        )
    }
}

@Composable
private fun HomeStatTile(label: String, value: String, valueColor: Color? = null) {
    Column {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Derives a sign color from an already-formatted (`formatPercent`) string rather than a raw
 *  Double — [PortfolioUiState.metrics]' `totalReturn` is pre-formatted text (render verbatim,
 *  never re-parse), so this only reads the leading sign glyph the formatter itself always writes
 *  ("+"/"-"). Mirrors desktop HomePane's `signColorFromFormattedText` exactly. */
@Composable
private fun signColorFromFormattedText(text: String): Color = when {
    text.startsWith("-") -> LossRed
    text.startsWith("+") -> GainGreen
    else -> MaterialTheme.colorScheme.onSurface
}

// MARK: - Today card (feed)

@Composable
private fun TodayCard(feed: List<HomeFeedItem>, onNavigate: (HomeDestination) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                tr(L10n.Key.TodaySection).uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            // Feed order is fixed by the assembler (Global Constraint 1); rendered verbatim,
            // never re-sorted here. Index-keyed (not item-keyed): HomeFeedItem isn't a stable
            // identity type across refreshes — mirrors desktop HomePane's own `forEachIndexed`
            // (Global Constraint 5's index-keyed-rows trap).
            feed.forEachIndexed { index, item ->
                FeedRow(item, onNavigate)
                if (index < feed.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FeedRow(item: HomeFeedItem, onNavigate: (HomeDestination) -> Unit) {
    when (item) {
        is HomeFeedItem.MarketStatus -> MarketStatusRow(item)

        is HomeFeedItem.TopGainer -> ClickableFeedRow(onClick = { onNavigate(HomeDestination.MarketsWatchlist) }) {
            HomeChangePill(item.changePercent)
            val (prefix, suffix) = splitOnToken(tr(L10n.Key.LeadsHoldingsFmt), "%s")
            BoldSegmentRow(prefix to false, item.symbol to true, suffix to false, modifier = Modifier.weight(1f))
        }

        is HomeFeedItem.TopLoser -> ClickableFeedRow(onClick = { onNavigate(HomeDestination.MarketsWatchlist) }) {
            HomeChangePill(item.changePercent)
            val (prefix, suffix) = splitOnToken(tr(L10n.Key.BiggestFallerFmt), "%s")
            BoldSegmentRow(prefix to false, item.symbol to true, suffix to false, modifier = Modifier.weight(1f))
        }

        is HomeFeedItem.Earnings -> ClickableFeedRow(onClick = { onNavigate(HomeDestination.MarketsCalendar) }) {
            FeedGlyph("⏱")
            val (prefix, suffix) = splitOnToken(tr(L10n.Key.ReportsEarningsFmt), "%s")
            BoldSegmentRow(prefix to false, item.symbol to true, suffix to false, modifier = Modifier.weight(1f))
            val label = sessionLabel(item.session)
            Text(
                label.ifEmpty { homeDayAbbrev(item.day) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is HomeFeedItem.Dividend -> ClickableFeedRow(onClick = { onNavigate(HomeDestination.InvestIncome) }) {
            FeedGlyph("$")
            val raw = tr(L10n.Key.DividendEstFmt)
            val (prefix, rest) = splitOnToken(raw, "%1\$s")
            val (middle, suffix) = splitOnToken(rest, "%2\$s")
            BoldSegmentRow(
                prefix to false, item.symbol to true, middle to false, money(item.amount.amountText) to false, suffix to false,
                modifier = Modifier.weight(1f),
            )
            Text(
                homeDayAbbrev(item.day),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is HomeFeedItem.ScreenerFresh -> ClickableFeedRow(onClick = { onNavigate(HomeDestination.MarketsScreener) }) {
            FeedGlyph("◎")
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
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (item.isOpen) GainGreen else LossRed))
        Text(
            if (item.isOpen) tr(L10n.Key.MarketOpenStatus) else tr(L10n.Key.MarketClosedStatus),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        val whenText = if (item.isOpen) {
            trf(L10n.Key.ClosesAtFmt, formatMarketTransition(item.nextTransitionEpochSeconds, homeCloseTimeFormatter))
        } else {
            trf(L10n.Key.OpensAtFmt, formatMarketTransition(item.nextTransitionEpochSeconds, homeOpenTimeFormatter))
        }
        Text(whenText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A clickable Today row: every `HomeFeedItem` case except `.marketStatus` navigates on tap —
 *  mirrors desktop HomePane's `ClickableFeedRow` (only `.marketStatus` is a plain `Row`). */
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

/** Fixed-width (16dp) leading glyph — plain Unicode glyphs (⏱ / $ / ◎), not emoji-icon-font:
 *  no Canvas-drawn icon exists for these three in this app's material-icons-core-only icon set
 *  (mirrors [com.aptrade.android.ShellTab]'s own icon-substitution rationale and desktop
 *  HomePane's `FeedGlyph`), and the mockup's own glyphs for these exact rows are simple enough
 *  that a plain character reads cleanly without adding new icon components for a UI-waiver task. */
@Composable
private fun FeedGlyph(text: String) {
    Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Splits a catalog format string on a literal placeholder token (e.g. "%s", "%1$s", "%2$d") —
 *  mirrors desktop HomePane's `splitOnToken`, which splits the LOCALIZED string on the literal
 *  placeholder rather than the English source, so this works unchanged in every language. Falls
 *  back to the whole string as the prefix (empty suffix) if the token isn't found (defensive
 *  only — every format this is called with has the expected tokens). */
private fun splitOnToken(source: String, token: String): Pair<String, String> {
    val idx = source.indexOf(token)
    return if (idx < 0) source to "" else source.substring(0, idx) to source.substring(idx + token.length)
}

/** Renders a sequence of (text, bold) segments as adjacent `Text` elements in a `Row` — mirrors
 *  desktop HomePane's `BoldSegmentRow`. Empty segments (an argument landing at the very start/
 *  end of a format string) are skipped so no stray empty `Text` widens the row. */
@Composable
private fun BoldSegmentRow(vararg segments: Pair<String, Boolean>, modifier: Modifier = Modifier) {
    Row(modifier) {
        // Trap (M10.3 Global Constraint 5 / transcribed verbatim from desktop HomePane's own
        // comment here): `continue`/`break` inside a composable `for` loop body is deliberately
        // avoided — a known Compose-compiler group-insertion hazard where an early-exit branch
        // mid-loop can desync the slot table from what the applier expects, surfacing as "Cannot
        // end node insertion...". An `if` guard around the single `Text` call keeps every
        // iteration's control flow structurally uniform instead.
        for ((text, bold) in segments) {
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

// MARK: - Quick cards (2×2 grid)

@Composable
private fun QuickCardsGrid(
    homeState: HomeState?,
    onNavigate: (HomeDestination) -> Unit,
    onOpenAlerts: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeQuickCard(
                title = tr(L10n.Key.ScreenerTab),
                subtitle = screenerQuickSubtitle(homeState),
                onClick = { onNavigate(HomeDestination.MarketsScreener) },
                modifier = Modifier.weight(1f),
            )
            HomeQuickCard(
                title = tr(L10n.Key.AlertsCenterTitle),
                subtitle = trf(L10n.Key.AlertsActiveFmt, homeState?.alertCount ?: 0),
                // Task 4: the Android Alerts center doesn't exist yet — inert placeholder,
                // same as the header bell above.
                onClick = onOpenAlerts,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeQuickCard(
                title = tr(L10n.Key.CalendarTab),
                subtitle = calendarQuickSubtitle(homeState),
                onClick = { onNavigate(HomeDestination.MarketsCalendar) },
                modifier = Modifier.weight(1f),
            )
            // HomeFeedAssembler carries no news data at all (it aggregates Portfolio/Watchlist/
            // Performance/Income/Calendar/Screener/Alerts, never News), so — mirroring Swift
            // HomeView's identical `nil`-subtitle rationale for this exact card — this degrades
            // to a plain title/nav affordance rather than fabricating a live one-liner the data
            // doesn't support.
            HomeQuickCard(
                title = tr(L10n.Key.News),
                subtitle = null,
                onClick = { onNavigate(HomeDestination.MarketsNews) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Reuses the SAME `.screenerFresh` row Today shows (same freshness check, same match count,
 *  same [presetTitleKey] single-source mapping, Global Constraint 5) rather than re-deriving it —
 *  mirrors Swift `HomeView.screenerQuickSubtitle`'s "reuse, don't re-derive" rationale exactly. */
private fun screenerQuickSubtitle(homeState: HomeState?): String {
    val fresh = homeState?.feed?.filterIsInstance<HomeFeedItem.ScreenerFresh>()?.firstOrNull()
        ?: return tr(L10n.Key.ScreenerNotScanned)
    return "${tr(presetTitleKey(fresh.preset))} · ${trf(L10n.Key.ScreenerMatchCountFmt, fresh.matches)}"
}

/**
 * [L10n.Key.QuickEarningsWeekFmt]'s FIRST consumer (M10.3 Task 3). `HomeFeedAssembler` only ever
 * surfaces the SINGLE next earnings event among owned+watched symbols (not a true weekly tally
 * across the whole market — see [HomeFeedItem.Earnings]'s own KDoc in `:shared`'s `HomeFeed.kt`),
 * and no earnings-this-week COUNT is exposed anywhere in the assembler/[HomeState] to read
 * instead — so this derives the count minimally from whether the Today feed's own earnings row
 * is present, rather than re-fetching a real weekly tally the assembler doesn't have. "1" can
 * undercount a week with more than one event, but never overstates: mirrors Swift
 * `HomeView.calendarQuickSubtitle` exactly (same feed-presence check, same hardcoded "1", same
 * honesty rationale documented there).
 */
private fun calendarQuickSubtitle(homeState: HomeState?): String? {
    val hasEarnings = homeState?.feed?.any { it is HomeFeedItem.Earnings } ?: false
    return if (hasEarnings) trf(L10n.Key.QuickEarningsWeekFmt, 1) else null
}

@Composable
private fun HomeQuickCard(title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            // A blank placeholder line (rather than omitting the row) keeps every card in the
            // 2×2 grid the same height whether or not it has a live one-liner right now —
            // mirrors Swift HomeQuickCard's `subtitle ?? " "` exactly.
            Text(
                subtitle ?: " ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

// MARK: - Date/time formatting (M10.3 Global Constraint 1: LocalDate end-to-end, never via Instant)

private val homeDayAbbrevFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.US)

/** `kotlinx.datetime.LocalDate` -> "Thu" — day-of-week abbreviation. Built the SAME way
 *  [com.aptrade.android.calendar.CalendarScreen]'s own `formatEventDate`/`formatDayHeader`
 *  format a day string: a `java.time.DateTimeFormatter` over `Locale.US`, fed from the day's own
 *  year/monthNumber/dayOfMonth fields — `java.time.LocalDate.of(...)` is used here PURELY as a
 *  formatting shim (no Instant/epoch round-trip), so this stays LocalDate end-to-end per Global
 *  Constraint 1, exactly mirroring desktop HomePane's own `homeDayAbbrev`. */
private fun homeDayAbbrev(date: LocalDate): String =
    homeDayAbbrevFormatter.format(JavaLocalDate.of(date.year, date.monthNumber, date.dayOfMonth))

/** Market-hours transition instants ARE real instants (an exact moment the session opens/
 *  closes), unlike the day-only fields above — displayed in the exchange's own timezone,
 *  matching [com.aptrade.android.calendar.CalendarScreen]'s/desktop's precedent
 *  (America/New_York, en_US). */
private val marketZone: ZoneId = ZoneId.of("America/New_York")
private val homeCloseTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val homeOpenTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, h:mm a", Locale.US)

private fun formatMarketTransition(epochSeconds: Long, formatter: DateTimeFormatter): String =
    formatter.format(Instant.ofEpochSecond(epochSeconds).atZone(marketZone))
