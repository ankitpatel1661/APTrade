package com.aptrade.desktop.income

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.AppGraph
import com.aptrade.desktop.LocalAppGraph
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.DKSwitch
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.designkit.formatShares
import com.aptrade.desktop.goals.GoalCard
import com.aptrade.desktop.income.State as IncomeState
import com.aptrade.desktop.infra.AppSettings
import com.aptrade.desktop.l10n.tr
import com.aptrade.shared.domain.ForecastYear
import com.aptrade.shared.domain.GoalKind
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
 *  Swift `#else` branch) whenever both lists are non-empty.
 *
 *  M10.2 Task 7 (the settings-honesty pass, Swift M10.1 Task 8's desktop twin): the DRIP
 *  toggle re-homes here from the account "⋯" panel — [notificationSettings]/
 *  [onUpdateNotificationSettings] are the SAME hoisted `AppSettings` + load-merge-save seam
 *  `AccountPanel`'s Notifications page already uses (threaded down from `Main.kt`/`AppRoot`),
 *  so there is still only ONE persisted `dripEnabled` field, never a second copy. Per
 *  `IncomeSection.swift`'s doc comment, the DRIP card is this pane's own reachability floor:
 *  it must render even before the user has ever received or projected a dividend (turning
 *  DRIP on ahead of a first payout is the common case, not an edge case) — so it renders
 *  ABOVE the loading/empty/ledger split below, not conditioned on it. */
@Composable
fun IncomePane(
    notificationSettings: AppSettings,
    onUpdateNotificationSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    val graph: AppGraph = LocalAppGraph.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val viewModel = remember { graph.makeIncomeViewModel(scope) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    LaunchedEffect(Unit) { viewModel.load() }

    val state: IncomeState by viewModel.state.collectAsState()

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        DripCard(
            checked = notificationSettings.dripEnabled,
            onCheckedChange = { checked ->
                onUpdateNotificationSettings { it.copy(dripEnabled = checked) }
                // BINDING (carry-notes §2.2): the persist above is fire-and-forget and nothing
                // observes the setting, so without this call the forecast chart directly below —
                // whose caption promises DRIP compounding — would keep the old assumption until
                // the user happened to tap a horizon pill, understating every displayed year. It
                // refreshes the income-goal projection too, which reads the same curve.
                viewModel.dripDidChange(enabled = checked)
            },
        )
        // The DRIP card and the income-goal card are this pane's reachability floor: BOTH render
        // above the state switch, unconditionally (carry-notes §1.3). Turning DRIP on, or setting
        // an income goal, BEFORE a first payout is the common case, not an edge case — the Swift
        // wave shipped the goal card inside the ledger branch and a user holding no dividend payer
        // could never set an income goal at all.
        GoalCard(
            title = tr(L10n.Key.IncomeGoal),
            kind = GoalKind.Income,
            ui = state.incomeGoal,
            onSet = { amount -> viewModel.setIncomeGoal(amount) },
            onRemove = { viewModel.removeIncomeGoal() },
        )
        when {
            state.isLoading && state.cards == null -> LoadingState()
            // No dividend has ever been received and none is projected — the ledger portion
            // would otherwise render as a wall of zeroed cards and empty lists. Mirrors
            // IncomeSection.swift's `isEmptyLedger`.
            state.history.isEmpty() && state.upcoming.isEmpty() -> EmptyIncomeState()
            else -> IncomeContent(state, onSetHorizon = { viewModel.setHorizon(it) })
        }
    }
}

/** Bold title + subtitle + gold [DKSwitch], bound to the same [AppSettings.dripEnabled] field
 *  the account panel used to host — mirrors `IncomeSection.swift`'s `dripCard` (surface fill,
 *  16dp radius, hairline stroke) exactly, including its title/subtitle type weights. */
@Composable
private fun DripCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                tr(L10n.Key.DripCardTitle),
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.textPrimary),
            )
            Text(
                tr(L10n.Key.DripCardSubtitle),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Normal, color = DK.textTertiary),
            )
        }
        DKSwitch(checked = checked, onCheckedChange = onCheckedChange)
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

/** M10.2 Task 7: no longer applies its own page padding — [IncomePane] wraps the DRIP card
 *  AND this content in one outer `Column` now (so the DRIP card sits flush with the ledger
 *  below it, same 20dp rhythm), so this inner `Column` only needs its OWN inter-section
 *  spacing, not a second copy of the page margins. */
@Composable
private fun IncomeContent(state: IncomeState, onSetHorizon: (ForecastHorizon) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        state.cards?.let { SummaryCardsGrid(it) }
        if (state.months.isNotEmpty()) MonthlyChart(state.months)
        DividendCalendarCard(state.calendarMonths)
        ForecastCard(state.forecast, state.hasForecastIncome, state.forecastPricesAreEstimated, state.horizon, onSetHorizon)
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

// MARK: - Dividend calendar

private val calendarMonthTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

/** Full "August 2026" title for a `"yyyy-MM"` bucket key. The YEAR is always rendered: the
 *  underlying window is a fixed 365 days, so across a year boundary two partial buckets can share
 *  a month name, and the year is what disambiguates them. Falls back to the raw key on a malformed
 *  bucket rather than crashing the whole card over one bad group — same idiom as [monthLabel]. */
private fun calendarMonthTitle(key: String): String = try {
    YearMonth.parse(key).format(calendarMonthTitleFormatter)
} catch (e: Exception) {
    key
}

/** The (up to 13-month) projected payout calendar.
 *
 *  TITLED "Dividend Calendar" (carry-notes §1.4, BINDING) — deliberately NOT
 *  `L10n.Key.IncomeUpcomingTitle` ("Upcoming Dividends"), which belongs to the pre-existing
 *  next-payout list further down this same scroll view. Two identically-titled cards shipped
 *  briefly on Swift before this was caught.
 *
 *  EVERY row is an estimate (carry-notes §3.7): the upstream feed exposes no forward-declared
 *  ex-dates, so the card carries the disclaimer in its header and again per row. */
@Composable
private fun DividendCalendarCard(months: List<CalendarMonth>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(tr(L10n.Key.DividendCalendarTitle))
            Spacer(Modifier.weight(1f))
            Text(
                tr(L10n.Key.IncomeEstimatedBadge).lowercase(),
                style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = DK.textTertiary),
            )
        }
        if (months.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    tr(L10n.Key.NoDividendPayersHeld),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Up to 13 buckets (carry-notes §3.6, [CalendarMonth]'s own KDoc): the window is
                // a fixed 365 days, so with "now" mid-month the first and last buckets are both
                // partial and can share a month NAME across a year boundary. Rendered in the
                // model's ascending order, one group per `id` — never collapsed or assumed to be
                // exactly 12 — with `calendarMonthTitle` carrying the year to disambiguate.
                for (month in months) CalendarMonthGroup(month)
            }
        }
    }
}

@Composable
private fun CalendarMonthGroup(month: CalendarMonth) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                calendarMonthTitle(month.id),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 0.4.sp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatMoney(month.total.amountText),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DK.textSecondary, fontFeatureSettings = "tnum"),
            )
        }
        // Two holdings can legitimately project onto the exact same ex-date within a month, so
        // rows are rendered in list order — never keyed by date, which could collide.
        for (row in month.rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.symbol,
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
                )
                Text(
                    // NOTE: ScheduledDividend's field is `exDateEpochSeconds`, not
                    // `estimatedExDateEpochSeconds` like the pre-existing UpcomingRow (carry-notes
                    // §3.7) — the naming is inconsistent in the shared core; bound deliberately.
                    dateText(row.exDateEpochSeconds),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatMoney(row.estimatedAmount.amountText),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary, fontFeatureSettings = "tnum"),
                )
                Text(
                    tr(L10n.Key.IncomeEstimatedBadge).lowercase(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = DK.textTertiary),
                )
            }
        }
    }
}

// MARK: - Forecast

private const val FORECAST_CHART_HEIGHT_DP = 160

/** Multi-year income forecast with 5/10/20/30 horizon pills.
 *
 *  The header WRAPS rather than sharing one fixed-width row with the pills: carry-notes §4 records
 *  the Swift picker's narrow-width behaviour as unverified at 375pt, and the instruction for
 *  Kotlin is to design for narrow width from the start rather than inherit an unconfirmed
 *  fallback. Title on its own line, pills below, scrollable if the window is genuinely tiny.
 *
 *  [hasIncome] — NOT `forecast.isNotEmpty()` (carry-notes §1.1): `incomeForecast` always returns
 *  `horizon` entries, all zero for a portfolio holding no dividend payer, so an emptiness check can
 *  never distinguish "nothing to chart" from "a flat zero curve" and would render the latter as if
 *  it were real data.
 *
 *  [pricesAreEstimated] captions the chart when a total quote-fetch failure forced the forecast's
 *  DRIP compounding to fall back to cost-basis pricing for at least one contributing holding
 *  (carry-notes §1.2, `State.forecastPricesAreEstimated`'s KDoc) — the same ~66% overstatement this
 *  milestone exists to prevent, reachable here through an ordinary offline/rate-limited quote
 *  fetch rather than a missing argument. */
@Composable
private fun ForecastCard(
    forecast: List<ForecastYear>,
    hasIncome: Boolean,
    pricesAreEstimated: Boolean,
    horizon: ForecastHorizon,
    onSetHorizon: (ForecastHorizon) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(tr(L10n.Key.IncomeForecastTitle))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (option in ForecastHorizon.entries) {
                HorizonPill(option.label, option == horizon) { onSetHorizon(option) }
            }
        }
        if (!hasIncome) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    tr(L10n.Key.NoDividendPayersHeld),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
                )
            }
        } else {
            ForecastChart(forecast)
            Text(
                tr(L10n.Key.ForecastCaption),
                style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
            )
            if (pricesAreEstimated) {
                Text(
                    tr(L10n.Key.ForecastPricesEstimatedCaption),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
                )
            }
        }
    }
}

@Composable
private fun HorizonPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) DK.gold else DK.textTertiary, fontFeatureSettings = "tnum",
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) DK.gold.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) DK.gold.copy(alpha = 0.4f) else DK.hairline, RoundedCornerShape(50))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Gold area+line over the forecast years, scaled to the largest year. Same Canvas idiom the
 *  monthly chart already uses — no new chart dependency. */
@Composable
private fun ForecastChart(forecast: List<ForecastYear>) {
    val values = forecast.map { it.income.amount.doubleValue(false) }
    val maxValue = values.maxOrNull() ?: 0.0
    if (values.size < 2 || maxValue <= 0.0) return
    Canvas(Modifier.fillMaxWidth().height(FORECAST_CHART_HEIGHT_DP.dp)) {
        val stepX = size.width / (values.size - 1).toFloat()
        fun y(v: Double) = (size.height - (v / maxValue * size.height)).toFloat()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, y(values.first()))
            values.forEachIndexed { i, v -> if (i > 0) lineTo(i * stepX, y(v)) }
        }
        val filled = androidx.compose.ui.graphics.Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(filled, DK.gold.copy(alpha = 0.14f))
        drawPath(path, DK.gold, style = Stroke(width = 2.dp.toPx()))
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
    // Which bar's tooltip is showing (M10.1 UAT U6 desktop twin) — macOS sets/clears this on
    // hover (`IncomeSection.swift`'s `activeMonthID`, itself mirroring `WatchlistView
    // .hoveredSymbol`'s idiom); desktop has the same pointer-hover affordance the watchlist
    // row already uses, so the same onPointerEvent Enter/Exit pair applies here.
    var activeMonthId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            SectionHeader(tr(L10n.Key.IncomeMonthlyTitle))
            Spacer(Modifier.weight(1f))
            // Subtle max-value axis label (M10.1 UAT U6) — no new copy, just the tallest
            // bar's own already-formatted amount, read straight off the data the bars
            // themselves are already scaled against.
            months.maxByOrNull { it.amount.amount.doubleValue(false) }?.let { maxMonth ->
                Text(
                    formatMoney(maxMonth.amount.amountText),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = DK.textTertiary, fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
        Row(
            // Headroom for a tooltip floating above whichever bar is active, without
            // clipping against the chart's own bounds — mirrors IncomeSection.swift's
            // `.padding(.top, 32)` on this same row.
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (bar in months) {
                MonthBarColumn(
                    bar = bar,
                    maxAmount = maxAmount,
                    isActive = activeMonthId == bar.id,
                    onHoverChange = { hovering ->
                        activeMonthId = when {
                            hovering -> bar.id
                            activeMonthId == bar.id -> null
                            else -> activeMonthId
                        }
                    },
                )
            }
        }
        LegendRow()
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MonthBarColumn(bar: MonthBar, maxAmount: Double, isActive: Boolean, onHoverChange: (Boolean) -> Unit) {
    val value = bar.amount.amount.doubleValue(false)
    val fraction = if (maxAmount > 0.0) (value / maxAmount).toFloat().coerceIn(0f, 1f) else 0f
    val barHeight = (MONTH_BAR_MAX_HEIGHT_DP.dp * fraction).coerceAtLeast(2.dp)
    Box {
        Column(
            modifier = Modifier
                .width(22.dp)
                .onPointerEvent(PointerEventType.Enter) { onHoverChange(true) }
                .onPointerEvent(PointerEventType.Exit) { onHoverChange(false) },
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
                            // Active bar reads lighter gold — matches IncomeSection.swift's
                            // `isActive ? Theme.goldLight : Theme.gold` exactly (DK.goldLight
                            // is the same accent-family highlight stop).
                            .background(if (isActive) DK.goldLight else DK.gold),
                    )
                }
            }
            Text(
                monthLabel(bar.id),
                style = TextStyle(fontFamily = InterFamily, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = DK.textTertiary),
            )
        }
        // Month + exact amount on hover (M10.1 UAT U6) — no amount is shown anywhere else on
        // this chart, only relative bar height. Same floating-readout idiom as
        // `ExpandedValueCard`'s hover tooltip (rounded surface-hi card, hairline stroke).
        if (isActive) {
            // -30dp matches IncomeSection.swift's `monthTooltip(bar).fixedSize().offset(y: -30)`
            // exactly, same headroom the enclosing Row's `padding(top = 32.dp)` reserves for it.
            MonthTooltip(bar, modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp))
        }
    }
}

@Composable
private fun MonthTooltip(bar: MonthBar, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .wrapContentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(DK.surfaceHi)
            .border(1.dp, DK.hairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            formatMoney(bar.amount.amountText),
            maxLines = 1,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = DK.textPrimary, fontFeatureSettings = "tnum",
            ),
        )
        Text(
            monthLabel(bar.id),
            style = TextStyle(fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
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
                    formatShares(row.shares),
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
                    "${formatShares(entry.shares)} @ ${formatMoney(entry.amountPerShare.amountText)}",
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
