package com.aptrade.desktop.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.shared.application.CalendarDay
import com.aptrade.shared.application.normalized
import com.aptrade.shared.infrastructure.SP500Names
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.USMarketHoliday
import com.aptrade.shared.l10n.L10n
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** USMarketHoliday -> localized name. UI-layer mapping (domain stays L10n-free).
 *  Deliberately NOT `@Composable` — like NewsPane's `categoryLabel`, [tr] itself needs no
 *  composable scope, and this stays callable from non-composition call sites too. */
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

/** [EarningsSession] -> localized label. MOVED here (Task 7) from Main.kt's former private
 *  copy so the Calendar tab's earnings rows and Main.kt's earnings-notification wiring share
 *  ONE definition — Main.kt now imports this instead of declaring its own. Kept non-
 *  `@Composable` (like [holidayName]) because Main.kt's `notifyEarnings` callback runs from a
 *  plain coroutine, not a composition — [tr] doesn't require one either way. */
fun sessionLabel(session: EarningsSession): String = when (session) {
    EarningsSession.BeforeOpen -> tr(L10n.Key.SessionBeforeOpen)
    EarningsSession.AfterClose -> tr(L10n.Key.SessionAfterClose)
    EarningsSession.DuringMarket -> tr(L10n.Key.SessionDuringMarket)
    EarningsSession.Unknown -> ""
}

private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val dayHeaderFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)

/** `yyyy-MM-dd` -> "Jul 24", the same absolute-date shape RelativeTime.kt falls back to.
 *  Public: the Next-earnings detail stat (DetailPane.kt) reuses this so the two earnings-date
 *  surfaces (Calendar tab, Detail KEY STATS) render identically. */
fun formatEventDate(day: String): String = shortDateFormatter.format(LocalDate.parse(day))

/** `yyyy-MM-dd` -> "MONDAY, NOV 23" — the day-row header, matching DetailPane's CardHeader
 *  wide-tracking bold-caps section-label idiom ("KEY STATS", "YOUR POSITION"). */
private fun formatDayHeader(day: String): String =
    dayHeaderFormatter.format(LocalDate.parse(day)).uppercase(Locale.US)

/** Calendar tab: fourteen days of NYSE holiday/half-day banners plus S&P-500 + owned-symbol
 *  earnings, grouped by day. All state comes from [CalendarViewModel]; the pane owns no fetch
 *  logic. The list root is a [LazyColumn] (this pane is a tab root, not nested in a
 *  verticalScroll) — mirrors NewsPane's structural idiom. */
@Composable
fun CalendarPane(viewModel: CalendarViewModel) {
    val state by viewModel.state.collectAsState()

    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        when {
            state.isLoading && state.days.isEmpty() ->
                CircularProgressIndicator(color = DK.gold, modifier = Modifier.align(Alignment.Center))
            state.days.isEmpty() && !state.needsKey ->
                Text(
                    tr(L10n.Key.NoUpcomingEarnings),
                    style = TextStyle(
                        fontFamily = InterFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = DK.textSecondary,
                    ),
                    modifier = Modifier.align(Alignment.Center),
                )
            state.days.isEmpty() && state.needsKey ->
                NoKeyState(modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(Modifier.fillMaxSize()) {
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
                    // but a duplicate LazyColumn key kills the whole app (observed live with
                    // Finnhub's revision rows before the mapper dedupe) — never let list
                    // identity be the thing that crashes on unexpected data.
                    itemsIndexed(day.events, key = { index, ev -> "ev-${day.localEpochDay}-${ev.symbol}-$index" }) { _, event ->
                        // state.ownSymbols is already normalized (CalendarViewModel) — normalize
                        // the event's symbol here too so a watched "BRK-B" still lights up the
                        // owned dot on Finnhub's "BRK.B" event.
                        EarningsRow(event = event, owned = normalized(event.symbol) in state.ownSymbols)
                    }
                    item(key = "div-${day.localEpochDay}") {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
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

/** Day-row section label. */
@Composable
private fun DayHeader(day: String) {
    Text(
        formatDayHeader(day),
        style = TextStyle(
            fontFamily = InterFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = DK.textSecondary,
            letterSpacing = 1.8.sp,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Full-width quiet row for a holiday/half-day notice: `DK.surface` background, a thin
 *  gold-alpha border (brand accent as a quiet signal, never a price-direction color). */
@Composable
private fun Banner(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DK.surface)
            .border(1.dp, DK.gold.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = DK.textSecondary,
            ),
        )
    }
}

/** One earnings row, laid out as four fixed columns so every row lines up like a table:
 *  `dot · SYMBOL (fixed) · Company Name (fills middle) · chip + est (right rail)`.
 *  The symbol column is fixed-width so all company names start on the same vertical line;
 *  the estimate column is fixed-width and end-aligned (and always reserved, even when
 *  empty) so the session chips' right edges align on the same line too. */
@Composable
private fun EarningsRow(event: EarningsEvent, owned: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(6.dp)) {
            if (owned) Box(Modifier.size(6.dp).clip(CircleShape).background(DK.gold))
        }
        Spacer(Modifier.width(10.dp))
        // Symbol column — 76dp fits the longest ticker form ("BRK.B"-style, 5 chars + dot).
        Text(
            event.symbol,
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = DK.textPrimary,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            modifier = Modifier.width(76.dp),
        )
        // Company-name column — desktop-only by product decision (the mobile apps show the
        // symbol alone). Finnhub's calendar payload carries no names, so the lookup is the
        // bundled S&P 500 snapshot; non-index (user-owned) symbols render an empty column.
        // Sole weighted child: it owns ALL the middle space, pinning the chip/est right rail
        // flush to the row's end.
        Text(
            event.companyName.ifBlank { null } ?: SP500Names[event.symbol].orEmpty(),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = DK.textSecondary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        SessionChip(event.session)
        Spacer(Modifier.width(12.dp))
        // Estimate column — fixed width, end-aligned, ALWAYS reserved so chip right edges
        // stay on one line whether or not an estimate exists.
        Text(
            event.epsEstimate?.let { "est " + formatMoney(BigDecimal.valueOf(it).toPlainString()) } ?: "",
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = DK.textSecondary,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(92.dp),
        )
    }
}

/** Session pill — same visual as DetailPane's private `KindChip` (rounded 6dp, `DK.surface`,
 *  hairline border, bold 10sp wide-tracking caps). Renders nothing for [EarningsSession.Unknown]
 *  (no label to show). */
@Composable
private fun SessionChip(session: EarningsSession) {
    val label = sessionLabel(session)
    if (label.isEmpty()) return
    Text(
        label,
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

/** No-key state. Transcribed from NewsPane's private `NoKeyState` (not reusable across
 *  packages — that composable is private to NewsPane.kt) since the Calendar tab shares the
 *  SAME Finnhub key gate as News (`AppGraph.earningsKeyMissing` reads the identical
 *  `finnhubApiKey`) and therefore the same "connect a source" copy applies verbatim. Unlike
 *  News (which replaces the whole tab), here it renders BELOW any holiday/half-day banners
 *  when the window has some, or centered alone when it doesn't — see [CalendarPane]. */
@Composable
private fun NoKeyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr(L10n.Key.ConnectNewsSource),
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DK.textPrimary,
                ),
            )
            Text(
                tr(L10n.Key.FinnhubKeyInstructions),
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textSecondary,
                ),
            )
        }
    }
}
