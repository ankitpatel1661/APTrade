package com.aptrade.desktop.income

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.AppGraph
import com.aptrade.desktop.LocalAppGraph
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.income.State as IncomeState
import com.aptrade.desktop.l10n.tr
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val incomeDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneOffset.UTC)
private val incomeMonthLabelFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)

private fun dateText(epochSeconds: Long): String = incomeDateFormatter.format(Instant.ofEpochSecond(epochSeconds))

/** Parses a [MonthBar.id] `"yyyy-MM"` bucket key into its short month label ("Jul"). Falls
 *  back to the raw key on a malformed bucket rather than crashing the whole chart over one
 *  bad bar. */
private fun monthLabel(key: String): String = try {
    YearMonth.parse(key).format(incomeMonthLabelFormatter)
} catch (e: Exception) {
    key
}

/** The Portfolio tab's Income section: dividend summary cards, a monthly received/projected
 *  bar chart, upcoming payouts, a per-holding breakdown, and payment history. Compose port of
 *  `Sources/APTradeApp/IncomeSection.swift`, including its UAT polish. Owns its own
 *  [IncomeViewModel] instance and single-thread-confined scope (mirrors `PlansPane`'s
 *  per-composable VM pattern) — reads [AppGraph] via [LocalAppGraph] rather than threading VM
 *  state through `Main.kt`/`AppRoot`, since Income is a SECTION nested inside the Portfolio
 *  tab, not a top-level tab of its own.
 *
 *  Desktop has no wide/narrow split the way the Swift `#if os(iOS)` branch does — the desktop
 *  window is always "wide", so Upcoming and Income-by-Holding always render side by side (the
 *  Swift `#else` branch) whenever both lists are non-empty. */
@Composable
fun IncomePane() {
    val graph: AppGraph = LocalAppGraph.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val viewModel = remember { graph.makeIncomeViewModel(scope) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    LaunchedEffect(Unit) { viewModel.load() }

    val state: IncomeState by viewModel.state.collectAsState()

    when {
        state.isLoading && state.cards == null -> LoadingState()
        // No dividend has ever been received and none is projected — the whole section would
        // otherwise render as a wall of zeroed cards and empty lists. Mirrors
        // IncomeSection.swift's `isEmptyLedger`.
        state.history.isEmpty() && state.upcoming.isEmpty() -> EmptyIncomeState()
        else -> IncomeContent(state)
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = DK.gold)
    }
}

@Composable
private fun EmptyIncomeState() {
    Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BanknoteGlyph()
            Text(
                tr(L10n.Key.IncomeNoDividends),
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A simple banknote glyph drawn on [Canvas] — Compose Desktop has no SF Symbols equivalent
 *  to Swift's `Image(systemName: "banknote")`, so the empty state's icon is redrawn as a
 *  minimal rounded-rect-with-a-circle bill shape rather than pulling in an icon font
 *  dependency for one glyph. Same "decorative Canvas shape" idiom [com.aptrade.desktop.designkit.DonutChart]
 *  already establishes for other empty states (e.g. `PlansPane`'s `EmptyPlansState`). */
@Composable
private fun BanknoteGlyph() {
    val color = DK.textTertiary
    Canvas(Modifier.size(width = 40.dp, height = 28.dp)) {
        val strokeWidth = 1.6.dp.toPx()
        val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        drawRoundRect(color = color, cornerRadius = corner, style = Stroke(width = strokeWidth))
        drawCircle(color = color, radius = size.minDimension / 5f, style = Stroke(width = strokeWidth))
    }
}

@Composable
private fun IncomeContent(state: IncomeState) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        state.cards?.let { SummaryCardsGrid(it) }
        if (state.months.isNotEmpty()) MonthlyChart(state.months)
        // Upcoming + Income-by-Holding share one row when both are non-empty (UAT polish):
        // both tables stay visible without scrolling and neither stretches symbol-to-price
        // across the whole pane width.
        when {
            state.upcoming.isNotEmpty() && state.holdings.isNotEmpty() -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    UpcomingSection(state.upcoming, Modifier.weight(1f))
                    HoldingsSection(state.holdings, Modifier.weight(1f))
                }
            }
            state.upcoming.isNotEmpty() -> UpcomingSection(state.upcoming)
            state.holdings.isNotEmpty() -> HoldingsSection(state.holdings)
        }
        if (state.history.isNotEmpty()) HistorySection(state.history)
    }
}

// MARK: - Summary cards

@Composable
private fun SummaryCardsGrid(cards: SummaryCards) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(tr(L10n.Key.IncomeProjectedAnnual), formatMoney(cards.projectedAnnual.amountText), Modifier.weight(1f))
            SummaryCard(tr(L10n.Key.IncomeReceivedYTD), formatMoney(cards.receivedYTD.amountText), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(tr(L10n.Key.IncomePortfolioYield), formatPercent(cards.portfolioYield * 100), Modifier.weight(1f))
            SummaryCard(tr(L10n.Key.IncomeYieldOnCost), formatPercent(cards.yieldOnCost * 100), Modifier.weight(1f))
        }
    }
}

/** One labeled figure in the 2×2 summary grid — mirrors `IncomeSection.swift`'s
 *  `IncomeSummaryCard` (surface card, hairline stroke, uppercase tertiary label). */
@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title.uppercase(),
            style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
        )
        Text(
            value,
            maxLines = 1,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = DK.textPrimary, fontFeatureSettings = "tnum",
            ),
        )
    }
}

// MARK: - Monthly chart

private const val MONTH_BAR_MAX_HEIGHT_DP = 120

@Composable
private fun MonthlyChart(months: List<MonthBar>) {
    val maxAmount = months.maxOfOrNull { it.amount.amount.doubleValue(false) } ?: 0.0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader(tr(L10n.Key.IncomeMonthlyTitle))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (bar in months) MonthBarColumn(bar, maxAmount)
        }
        LegendRow()
    }
}

@Composable
private fun MonthBarColumn(bar: MonthBar, maxAmount: Double) {
    val value = bar.amount.amount.doubleValue(false)
    val fraction = if (maxAmount > 0.0) (value / maxAmount).toFloat().coerceIn(0f, 1f) else 0f
    val barHeight = (MONTH_BAR_MAX_HEIGHT_DP.dp * fraction).coerceAtLeast(2.dp)
    Column(
        modifier = Modifier.width(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.width(22.dp).height(MONTH_BAR_MAX_HEIGHT_DP.dp), contentAlignment = Alignment.BottomCenter) {
            if (bar.isProjected) {
                ProjectedBar(Modifier.width(22.dp).height(barHeight))
            } else {
                Box(
                    Modifier.width(22.dp).height(barHeight).clip(RoundedCornerShape(3.dp)).background(DK.gold),
                )
            }
        }
        Text(
            monthLabel(bar.id),
            style = TextStyle(fontFamily = InterFamily, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = DK.textTertiary),
        )
    }
}

/** Projected months render as a dashed outline with a faint fill so they read as clearly
 *  provisional next to the solid received bars — the UAT-mandated treatment, transcribed from
 *  `IncomeSection.swift`'s `monthBarColumn` (`strokeBorder(..., style: StrokeStyle(dash: [3, 2]))`). */
@Composable
private fun ProjectedBar(modifier: Modifier) {
    val gold = DK.gold
    Canvas(modifier) {
        val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        drawRoundRect(color = gold.copy(alpha = 0.12f), cornerRadius = corner)
        drawRoundRect(
            color = gold.copy(alpha = 0.6f),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2f))),
        )
    }
}

@Composable
private fun LegendRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(DK.gold.copy(alpha = 0.12f))
                .border(1.dp, DK.gold.copy(alpha = 0.6f), CircleShape),
        )
        Text(
            tr(L10n.Key.IncomeEstimatedBadge),
            style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
        )
    }
}

// MARK: - Upcoming

@Composable
private fun UpcomingSection(rows: List<UpcomingRow>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(tr(L10n.Key.IncomeUpcomingTitle))
        Column(Modifier.fillMaxWidth()) {
            for (row in rows) UpcomingRowItem(row)
        }
    }
}

@Composable
private fun UpcomingRowItem(row: UpcomingRow) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.symbol, style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary))
                Text(
                    dateText(row.estimatedExDateEpochSeconds),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
                )
            }
            Text(
                formatMoney(row.estimatedAmount.amountText),
                style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary, fontFeatureSettings = "tnum"),
            )
            EstimatedBadge()
        }
        RowHairline()
    }
}

// MARK: - Per-holding

@Composable
private fun HoldingsSection(rows: List<HoldingRow>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(tr(L10n.Key.IncomePerHoldingTitle))
        Column(Modifier.fillMaxWidth()) {
            for (row in rows) HoldingRowItem(row)
        }
    }
}

@Composable
private fun HoldingRowItem(row: HoldingRow) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.symbol, style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary))
                Text(
                    row.shares.toStringExpanded(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary, fontFeatureSettings = "tnum"),
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    formatMoney(row.annualIncome.amountText),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary, fontFeatureSettings = "tnum"),
                )
                Text(
                    formatPercent(row.yieldOnCost * 100),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary, fontFeatureSettings = "tnum"),
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.widthIn(min = 76.dp),
            ) {
                Text(
                    tr(L10n.Key.IncomeLastPayment).uppercase(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 0.6.sp),
                )
                Text(
                    row.lastPayment?.let { formatMoney(it.amountText) } ?: "—",
                    style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary, fontFeatureSettings = "tnum"),
                )
            }
        }
        RowHairline()
    }
}

// MARK: - History

@Composable
private fun HistorySection(rows: List<HistoryEntry>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(tr(L10n.Key.IncomeHistoryTitle))
        Column(Modifier.fillMaxWidth()) {
            for (entry in rows) HistoryRowItem(entry)
        }
    }
}

@Composable
private fun HistoryRowItem(entry: HistoryEntry) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.symbol, style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary))
                    if (entry.wasReinvested) ReinvestedBadge()
                }
                Text(
                    dateText(entry.epochSeconds),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    formatMoney(entry.total.amountText),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary, fontFeatureSettings = "tnum"),
                )
                Text(
                    "${entry.shares.toStringExpanded()} @ ${formatMoney(entry.amountPerShare.amountText)}",
                    style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary, fontFeatureSettings = "tnum"),
                )
            }
        }
        RowHairline()
    }
}

// MARK: - Shared row/badge primitives

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.4.sp),
    )
}

/** Bottom-inset hairline shared by every list row across Upcoming/Holdings/History —
 *  mirrors `IncomeSection.swift`'s `.overlay(alignment: .bottom) { Rectangle()... }`. */
@Composable
private fun RowHairline() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(1.dp).background(DK.hairline))
}

@Composable
private fun EstimatedBadge() {
    PillBadge(tr(L10n.Key.IncomeEstimatedBadge), DK.gold)
}

@Composable
private fun ReinvestedBadge() {
    PillBadge(tr(L10n.Key.IncomeReinvestedBadge), DK.silver)
}

@Composable
private fun PillBadge(text: String, color: Color) {
    Text(
        text.uppercase(),
        style = TextStyle(fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.4.sp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
