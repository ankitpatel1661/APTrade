package com.aptrade.android.income

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.income.State as IncomeState
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.formatPercent
import com.aptrade.android.ui.formatShares
import com.aptrade.android.ui.money
import com.aptrade.shared.l10n.L10n
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
 *  bad bar. Mirrors desktop `IncomePane.kt`'s identical helper. */
private fun monthLabel(key: String): String = try {
    YearMonth.parse(key).format(incomeMonthLabelFormatter)
} catch (e: Exception) {
    key
}

/**
 * The Portfolio screen's Income section: dividend summary cards, a monthly received/projected
 * bar chart, upcoming payouts, a per-holding breakdown, and payment history. Android Compose
 * counterpart of desktop `IncomePane.kt`, adapted to this app's single-column phone layout and
 * Material3 idioms (rather than the desktop designkit's gold/silver `DK` tokens): the summary
 * cards stay a 2x2 grid, but Upcoming Dividends and Income by Holding always stack vertically
 * here (never the desktop's width-permitting side-by-side row) — mirrors how
 * [com.aptrade.android.plans.PlansScreen] narrows desktop `PlansPane.kt`'s wide layouts to a
 * single column for this app's narrower screens.
 *
 * Owns its own [IncomeViewModel] instance via the app's standard `viewModel { }` factory,
 * mirroring [com.aptrade.android.plans.PlansSection] — reused across re-entries into the
 * Income section exactly like `PlansViewModel`, since [IncomeViewModel.load] always reloads
 * fresh from disk/network on every (re-)appearance.
 */
@Composable
fun IncomeSection() {
    val portfolio = AppGraph.portfolio
    val viewModel: IncomeViewModel = viewModel {
        IncomeViewModel(
            portfolioStore = portfolio.portfolioStore,
            marketDataRepository = portfolio.repository,
            calendar = portfolio.marketCalendar,
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        )
    }
    LaunchedEffect(Unit) { viewModel.load() }
    val state: IncomeState by viewModel.state.collectAsState()

    when {
        state.isLoading && state.cards == null -> LoadingState()
        // No dividend has ever been received and none is projected — the whole section would
        // otherwise render as a wall of zeroed cards and empty lists. Mirrors desktop
        // IncomePane.kt's identical `isEmptyLedger` guard.
        state.history.isEmpty() && state.upcoming.isEmpty() -> EmptyIncomeState()
        else -> IncomeContent(state)
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EmptyIncomeState() {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(
            tr(L10n.Key.IncomeNoDividends),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IncomeContent(state: IncomeState) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        state.cards?.let { SummaryCardsGrid(it) }
        if (state.months.isNotEmpty()) MonthlyChart(state.months)
        // Upcoming + Income-by-Holding always STACK on this single-column phone layout — no
        // desktop-style side-by-side row (there is no width to spare here).
        if (state.upcoming.isNotEmpty()) UpcomingSection(state.upcoming)
        if (state.holdings.isNotEmpty()) HoldingsSection(state.holdings)
        if (state.history.isNotEmpty()) HistorySection(state.history)
    }
}

// MARK: - Summary cards

@Composable
private fun SummaryCardsGrid(cards: SummaryCards) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(tr(L10n.Key.IncomeProjectedAnnual), money(cards.projectedAnnual.amountText), Modifier.weight(1f))
            SummaryCard(tr(L10n.Key.IncomeReceivedYTD), money(cards.receivedYTD.amountText), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(tr(L10n.Key.IncomePortfolioYield), formatPercent(cards.portfolioYield * 100), Modifier.weight(1f))
            SummaryCard(tr(L10n.Key.IncomeYieldOnCost), formatPercent(cards.yieldOnCost * 100), Modifier.weight(1f))
        }
    }
}

/** One labeled figure in the 2x2 summary grid — Material3 echo of desktop `IncomePane.kt`'s
 *  `IncomeSummaryCard` (surface card, hairline stroke, uppercase tertiary label). */
@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// MARK: - Monthly chart

private const val MONTH_BAR_MAX_HEIGHT_DP = 120

@Composable
private fun MonthlyChart(months: List<MonthBar>) {
    val maxAmount = months.maxOfOrNull { it.amount.amount.doubleValue(false) } ?: 0.0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    Modifier
                        .width(22.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(
            monthLabel(bar.id),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Projected months render as a dashed outline with a faint fill so they read as clearly
 *  provisional next to the solid received bars — transcribed from desktop `IncomePane.kt`'s
 *  `ProjectedBar` (same Canvas dash [3, 2] / 0.12 fill / 0.6 stroke treatment). */
@Composable
private fun ProjectedBar(modifier: Modifier) {
    val gold = MaterialTheme.colorScheme.primary
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
        val gold = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(gold.copy(alpha = 0.12f))
                .border(1.dp, gold.copy(alpha = 0.6f), CircleShape),
        )
        Text(
            tr(L10n.Key.IncomeEstimatedBadge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: - Upcoming

@Composable
private fun UpcomingSection(rows: List<UpcomingRow>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Text(row.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    dateText(row.estimatedExDateEpochSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                money(row.estimatedAmount.amountText),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            EstimatedBadge()
        }
        RowDivider()
    }
}

// MARK: - Per-holding

@Composable
private fun HoldingsSection(rows: List<HoldingRow>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Text(row.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    formatShares(row.shares),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(money(row.annualIncome.amountText), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    formatPercent(row.yieldOnCost * 100),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.widthIn(min = 76.dp),
            ) {
                Text(
                    tr(L10n.Key.IncomeLastPayment).uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    row.lastPayment?.let { money(it.amountText) } ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RowDivider()
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
                    Text(entry.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (entry.wasReinvested) ReinvestedBadge()
                }
                Text(
                    dateText(entry.epochSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(money(entry.total.amountText), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatShares(entry.shares)} @ ${money(entry.amountPerShare.amountText)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        RowDivider()
    }
}

// MARK: - Shared row/badge primitives

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(Locale.US),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Bottom-inset hairline shared by every list row across Upcoming/Holdings/History — mirrors
 *  desktop `IncomePane.kt`'s `RowHairline`, backed by [HorizontalDivider] (this app's own
 *  divider idiom, e.g. [com.aptrade.android.portfolio.PortfolioScreen]'s `ActivityRow`). */
@Composable
private fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun EstimatedBadge() {
    PillBadge(tr(L10n.Key.IncomeEstimatedBadge), MaterialTheme.colorScheme.primary)
}

@Composable
private fun ReinvestedBadge() {
    PillBadge(tr(L10n.Key.IncomeReinvestedBadge), MaterialTheme.colorScheme.secondary)
}

@Composable
private fun PillBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
        Text(
            text.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
