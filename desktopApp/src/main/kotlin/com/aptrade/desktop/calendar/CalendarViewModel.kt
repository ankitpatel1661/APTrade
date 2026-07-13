package com.aptrade.desktop.calendar

import com.aptrade.shared.application.CalendarDay
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.buildCalendarDays
import com.aptrade.shared.domain.MarketCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
            // fetch.execute already swallows failures to emptyList() (FetchEarningsCalendar
            // contract) — no try/catch needed here; buildCalendarDays with empty events still
            // yields the holiday/half-day rows for the window.
            val events = fetch.execute(calendar.dayString(start), calendar.dayString(start + 13))
            _state.value = CalendarUiState(
                days = buildCalendarDays(start, 14, calendar, events),
                ownSymbols = ownSymbols(),
                isLoading = false,
                needsKey = needsKey,
            )
        }
    }
}
