package com.aptrade.desktop.calendar

import com.aptrade.shared.application.CalendarDay
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.buildCalendarDays
import com.aptrade.shared.application.normalized
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MarketCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** [ownSymbols] holds the dot/dash-[normalized] form (shared/EarningsUseCases.kt's helper of
 *  the same name) so the owned-dot check at render time (`normalized(event.symbol) in
 *  state.ownSymbols`) matches a watched "BRK-B" against Finnhub's "BRK.B" event. */
data class CalendarUiState(
    val days: List<CalendarDay> = emptyList(),
    val ownSymbols: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val needsKey: Boolean = false,
)

/** Fourteen days of holidays + S&P-500 earnings. Holiday banners never depend on the
 *  fetch: a failed/keyless fetch still renders the local calendar rows.
 *  `scope` MUST be single-thread-confined (Dispatchers.Main on desktop): the state update
 *  relies on that confinement instead of locks — same contract as every other desktop VM. */
class CalendarViewModel(
    private val fetch: FetchEarningsCalendar,
    private val calendar: MarketCalendar,
    private val ownSymbols: suspend () -> Set<String>,
    private val needsKey: Boolean,
    private val nowEpochSeconds: () -> Long,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CalendarUiState(needsKey = needsKey))
    val state: StateFlow<CalendarUiState> = _state

    fun load() {
        val start = calendar.localEpochDay(nowEpochSeconds())
        _state.value = _state.value.copy(isLoading = true)
        scope.launch {
            // fetch.execute's OWN internals already swallow repository failures to emptyList()
            // (FetchEarningsCalendar contract) — but ownSymbols() is a raw file-backed read
            // (watchlist + portfolio) that sits OUTSIDE that guard and can throw IOException.
            // Same "never leave the caller stuck" reasoning as
            // DesktopMarketActivityCoordinator's EarningsCheckDue guard: an uncaught failure
            // here would leave isLoading spinning forever (desktop loads this tab once per
            // session). Degrade to empty events/ownSymbols TOGETHER on any non-cancellation
            // failure (the Pair keeps the two assignments atomic — a fetch that already
            // succeeded before a failing ownSymbols() must not leave a half-updated state);
            // buildCalendarDays with an empty event list still yields the holiday/half-day rows
            // for the window, which are local and must render regardless.
            val (events, own) = try {
                fetch.execute(calendar.dayString(start), calendar.dayString(start + 13)) to ownSymbols()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<EarningsEvent>() to emptySet()
            }
            _state.value = CalendarUiState(
                days = buildCalendarDays(start, 14, calendar, events),
                ownSymbols = own.mapTo(HashSet(), ::normalized),
                isLoading = false,
                needsKey = needsKey,
            )
        }
    }
}
