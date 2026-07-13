package com.aptrade.android.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.android.ui.money
import com.aptrade.shared.application.normalized
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.USMarketHoliday
import com.aptrade.shared.l10n.L10n
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** USMarketHoliday -> localized name. UI-layer mapping (domain stays L10n-free). Transcribed
 *  from desktop's `CalendarPane.kt` (Task 7) — a deliberate per-app twin, not a shared function,
 *  same rationale as [sessionLabel] below. */
fun holidayName(holiday: USMarketHoliday): String = when (holiday) {
    USMarketHoliday.NewYearsDay -> tr(L10n.Key.HolidayNewYears)
    USMarketHoliday.MartinLutherKingDay -> tr(L10n.Key.HolidayMLK)
    USMarketHoliday.WashingtonsBirthday -> tr(L10n.Key.HolidayWashington)
    USMarketHoliday.GoodFriday -> tr(L10n.Key.HolidayGoodFriday)
    USMarketHoliday.MemorialDay -> tr(L10n.Key.HolidayMemorial)
    USMarketHoliday.Juneteenth -> tr(L10n.Key.HolidayJuneteenth)
    USMarketHoliday.IndependenceDay -> tr(L10n.Key.HolidayIndependence)
    USMarketHoliday.LaborDay -> tr(L10n.Key.HolidayLabor)
    USMarketHoliday.Thanksgiving -> tr(L10n.Key.HolidayThanksgiving)
    USMarketHoliday.Christmas -> tr(L10n.Key.HolidayChristmas)
}

/** [EarningsSession] -> localized label. Transcribed from desktop's `CalendarPane.kt` (Task 7).
 *  PUBLIC (not private) so [com.aptrade.android.AppGraph]'s `marketActivityCoordinator`
 *  `notifyEarnings` closure can import this ONE definition instead of keeping its own copy —
 *  the move that file's KDoc called for once the Calendar tab landed on Android, mirroring how
 *  desktop consolidated its own copy into `CalendarPane.kt`. */
fun sessionLabel(session: EarningsSession): String = when (session) {
    EarningsSession.BeforeOpen -> tr(L10n.Key.SessionBeforeOpen)
    EarningsSession.AfterClose -> tr(L10n.Key.SessionAfterClose)
    EarningsSession.DuringMarket -> tr(L10n.Key.SessionDuringMarket)
    EarningsSession.Unknown -> ""
}

private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val dayHeaderFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)

/** `yyyy-MM-dd` -> "Jul 24". Transcribed from desktop's `CalendarPane.kt` (Task 7); used here by
 *  the half-day banner only ("Closes 1:00 PM — Jul 24"). */
fun formatEventDate(day: String): String = shortDateFormatter.format(LocalDate.parse(day))

/** `yyyy-MM-dd` -> "MONDAY, NOV 23" — the day-row header. */
private fun formatDayHeader(day: String): String =
    dayHeaderFormatter.format(LocalDate.parse(day)).uppercase(Locale.US)

/** Calendar tab root: fourteen days of NYSE holiday/half-day banners plus S&P-500 + owned-symbol
 *  earnings, grouped by day. Wired against [AppGraph]'s earnings use case (never null —
 *  [AppGraph.fetchEarningsCalendar] falls back to an empty repository when no Finnhub key is
 *  configured) and [AppGraph.earningsKeyMissing] for the needs-key gate — the same Finnhub key
 *  check [com.aptrade.android.news.NewsScreen] derives its own `needsKey` from, just exposed as
 *  a named AppGraph property rather than `newsRepository == null`.
 *
 *  [padding] is [com.aptrade.android.AppShell]'s Scaffold content padding, consumed the same way
 *  `NewsScreen`/`WatchlistScreen` do. */
@Composable
fun CalendarScreen(padding: PaddingValues) {
    val viewModel: CalendarViewModel = viewModel {
        CalendarViewModel(
            fetchProvider = { AppGraph.fetchEarningsCalendar },
            needsKeyProvider = { AppGraph.earningsKeyMissing },
            ownSymbols = AppGraph.ownSymbols,
        )
    }

    LifecycleStartEffect(viewModel) {
        viewModel.load()
        onStopOrDispose { }
    }

    val state by viewModel.state.collectAsState()
    CalendarContent(state = state, padding = padding)
}

@Composable
private fun CalendarContent(state: CalendarUiState, padding: PaddingValues) {
    Box(Modifier.padding(padding).fillMaxSize()) {
        when {
            state.isLoading && state.days.isEmpty() ->
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.days.isEmpty() && !state.needsKey ->
                Text(
                    tr(L10n.Key.NoUpcomingEarnings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
                )
            state.days.isEmpty() && state.needsKey ->
                NoKeyState(Modifier.align(Alignment.Center))
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item { Spacer(Modifier.height(6.dp)) }
                for (day in state.days) {
                    item(key = "hdr-${day.localEpochDay}") { DayHeader(day.day) }
                    val holiday = day.holiday
                    when {
                        holiday != null -> item(key = "banner-${day.localEpochDay}") {
                            Banner(trf(L10n.Key.MarketClosedBannerFmt, holidayName(holiday)))
                        }
                        day.isHalfDay -> item(key = "banner-${day.localEpochDay}") {
                            Banner(trf(L10n.Key.ClosesEarlyBannerFmt, formatEventDate(day.day)))
                        }
                    }
                    // Index-suffixed key: the mapper guarantees one event per (symbol, day),
                    // but a duplicate LazyColumn key crashes the process (observed live on
                    // desktop with Finnhub's revision rows before the mapper dedupe) — never
                    // let list identity be the thing that crashes on unexpected data.
                    itemsIndexed(day.events, key = { index, ev -> "ev-${day.localEpochDay}-${ev.symbol}-$index" }) { _, event ->
                        // state.ownSymbols is already normalized (CalendarViewModel) — normalize
                        // the event's symbol here too so a watched "BRK-B" still lights up the
                        // owned dot on Finnhub's "BRK.B" event.
                        EarningsRow(event = event, owned = normalized(event.symbol) in state.ownSymbols)
                    }
                    item(key = "div-${day.localEpochDay}") {
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (state.needsKey) {
                    item(key = "needs-key") {
                        Spacer(Modifier.height(16.dp))
                        NoKeyState()
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

/** Day-row section label — uppercase wide-tracking caps, the Android counterpart to desktop
 *  `CalendarPane.kt`'s `DayHeader` (same idiom `SettingsScreen`'s private `SectionLabel` uses). */
@Composable
private fun DayHeader(day: String) {
    Text(
        formatDayHeader(day),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.6.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    )
}

/** Full-width quiet row for a holiday/half-day notice: a thin gold-alpha border (brand accent
 *  as a quiet signal, never a price-direction color) — the Android counterpart to desktop
 *  `CalendarPane.kt`'s `Banner`. */
@Composable
private fun Banner(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** One earnings row: a gold dot when [owned] (watchlist/portfolio symbol), the ticker, a
 *  session chip, and the EPS estimate when present. The Android counterpart to desktop
 *  `CalendarPane.kt`'s `EarningsRow`. */
@Composable
private fun EarningsRow(event: EarningsEvent, owned: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Box(Modifier.size(6.dp)) {
            if (owned) Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            event.symbol,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        SessionChip(event.session)
        event.epsEstimate?.let { eps ->
            Spacer(Modifier.width(10.dp))
            Text(
                "est " + money(BigDecimal.valueOf(eps).toPlainString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Session pill. Renders nothing for [EarningsSession.Unknown] (no label to show). */
@Composable
private fun SessionChip(session: EarningsSession) {
    val label = sessionLabel(session)
    if (label.isEmpty()) return
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** No-key state. Transcribed from [com.aptrade.android.news.NewsScreen]'s private `NoKeyState`
 *  (not reusable across packages — that composable is private to `NewsScreen.kt`) since the
 *  Calendar tab shares the SAME Finnhub key gate as News. Unlike News (which replaces the whole
 *  tab), here it renders BELOW any holiday/half-day banners when the window has some, or
 *  centered alone when it doesn't — see [CalendarContent]. */
@Composable
private fun NoKeyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            tr(L10n.Key.ConnectNewsSource),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            // The in-app variant, not desktop's file-drop instructions: same key NewsScreen
            // uses today (Android's sandboxed config.json isn't user-reachable — the key is
            // entered in Settings instead).
            tr(L10n.Key.FinnhubKeyInstructionsInApp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
