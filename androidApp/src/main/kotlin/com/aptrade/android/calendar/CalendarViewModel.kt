package com.aptrade.android.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.shared.application.CalendarDay
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.buildCalendarDays
import com.aptrade.shared.domain.MarketCalendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CalendarUiState(
    val days: List<CalendarDay> = emptyList(),
    val ownSymbols: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val needsKey: Boolean = false,
)

/** 14 days of holidays + S&P-500 earnings. [fetchProvider] and [needsKeyProvider] are
 *  providers (NewsViewModel convention) so a Finnhub key saved in Settings mid-session
 *  applies on the next tab entry; holiday banners render regardless of key state. */
class CalendarViewModel(
    private val fetchProvider: () -> FetchEarningsCalendar,
    private val needsKeyProvider: () -> Boolean,
    private val ownSymbols: suspend () -> Set<String>,
    private val calendar: MarketCalendar = MarketCalendar(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state

    fun load() {
        val start = calendar.localEpochDay(nowEpochSeconds())
        val needsKey = needsKeyProvider()
        _state.value = _state.value.copy(isLoading = true, needsKey = needsKey)
        viewModelScope.launch {
            val events = fetchProvider().execute(calendar.dayString(start), calendar.dayString(start + 13))
            _state.value = CalendarUiState(
                days = buildCalendarDays(start, 14, calendar, events),
                ownSymbols = ownSymbols(),
                isLoading = false,
                needsKey = needsKey,
            )
        }
    }
}
