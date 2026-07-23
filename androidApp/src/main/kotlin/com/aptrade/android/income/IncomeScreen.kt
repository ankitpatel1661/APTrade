package com.aptrade.android.income

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
 *
 * M10.3 Task 5 (the settings-honesty pass, desktop `IncomePane.kt`'s Task 7 twin): the DRIP
 * toggle re-homes here from Settings' Account Settings page. [dripEnabled]/[onDripChanged] are
 * threaded down from [com.aptrade.android.MainActivity]'s single Activity-scoped
 * `SettingsViewModel` (through [com.aptrade.android.invest.InvestScreen]) — the SAME
 * load-merge-save seam Settings used, so there is still only ONE persisted `dripEnabled`
 * field, never a second copy. Per desktop's doc comment, the DRIP card is this screen's own
 * reachability floor: it must render even before the user has ever received or projected a
 * dividend (turning DRIP on ahead of a first payout is the common case, not an edge case) — so
 * it renders ABOVE the loading/empty/ledger split below, not conditioned on it.
 */
@Composable
fun IncomeSection(dripEnabled: Boolean, onDripChanged: (Boolean) -> Unit) {
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

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        DripCard(checked = dripEnabled, onCheckedChange = onDripChanged)
        when {
            state.isLoading && state.cards == null -> LoadingState()
            // No dividend has ever been received and none is projected — the whole section
            // would otherwise render as a wall of zeroed cards and empty lists. Mirrors
            // desktop IncomePane.kt's identical `isEmptyLedger` guard.
            state.history.isEmpty() && state.upcoming.isEmpty() -> EmptyIncomeState()
            else -> IncomeContent(state)
        }
    }
}

/** Bold title + subtitle + [Switch], bound to the same [com.aptrade.shared.settings.AppSettings
 *  .dripEnabled] field Settings used to host — mirrors desktop `IncomePane.kt`'s `DripCard`
 *  (surface fill, 16dp radius, hairline stroke) exactly, including its title/subtitle type
 *  weights, adapted to this screen's Material3 [Surface] idiom rather than desktop's `DK`
 *  design tokens. */
@Composable
private fun DripCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    tr(L10n.Key.DripCardTitle),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    tr(L10n.Key.DripCardSubtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
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
    // M10.3 Task 5: no longer applies its own page padding — [IncomeSection] wraps the DRIP
    // card AND this content in one outer `Column` now (so the DRIP card sits flush with the
    // ledger below it, same 20dp rhythm), mirroring desktop `IncomePane.kt`'s identical Task 7
    // restructuring.
    Column(
        Modifier.fillMaxWidth(),
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
    // Which bar's tooltip is showing (M10.3 Task 5 — desktop `IncomePane.kt`'s UAT U6 hover
    // twin). Desktop sets/clears this on pointer hover; a phone has no hover, so this is a TAP
    // toggle instead: tapping the active bar again (or tapping a different bar) clears/moves
    // it, matching [com.aptrade.android.portfolio.PortfolioScreen]'s crosshair-tooltip idiom of
    // "one thing showing at a time" adapted for touch rather than pointer/drag.
    var activeMonthId by remember { mutableStateOf<String?>(null) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionHeader(tr(L10n.Key.IncomeMonthlyTitle))
            Row(
                // Headroom for a tooltip floating above whichever bar is active, without
                // clipping against the chart's own bounds — mirrors desktop `IncomePane.kt`'s
                // `.padding(top = 32.dp)` on this same row.
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (bar in months) {
                    MonthBarColumn(
                        bar = bar,
                        maxAmount = maxAmount,
                        isActive = activeMonthId == bar.id,
                        onTap = {
                            activeMonthId = if (activeMonthId == bar.id) null else bar.id
                        },
                    )
                }
            }
            LegendRow()
        }
    }
}

@Composable
private fun MonthBarColumn(bar: MonthBar, maxAmount: Double, isActive: Boolean, onTap: () -> Unit) {
    val value = bar.amount.amount.doubleValue(false)
    val fraction = if (maxAmount > 0.0) (value / maxAmount).toFloat().coerceIn(0f, 1f) else 0f
    val barHeight = (MONTH_BAR_MAX_HEIGHT_DP.dp * fraction).coerceAtLeast(2.dp)
    Box {
        Column(
            modifier = Modifier
                .width(22.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onTap() },
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
        // Month + exact amount on tap (M10.3 Task 5 — desktop UAT U6's tap twin): no amount is
        // shown anywhere else on this chart, only relative bar height.
        if (isActive) {
            // -30dp matches desktop `IncomePane.kt`'s `.offset(y: -30)` exactly, same headroom
            // the enclosing Row's `padding(top = 32.dp)` reserves for it.
            MonthTooltip(bar, modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp))
        }
    }
}

@Composable
private fun MonthTooltip(bar: MonthBar, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.wrapContentSize(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                money(bar.amount.amountText),
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                monthLabel(bar.id),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
