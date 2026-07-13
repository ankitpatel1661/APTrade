package com.aptrade.android.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.shared.application.CalendarDay
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.buildCalendarDays
import com.aptrade.shared.application.normalized
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MarketCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** [ownSymbols] holds the dot/dash-[normalized] form (see [FetchEarningsCalendar]'s helper of the
 *  same name) so the owned-dot check at render time (`normalized(event.symbol) in
 *  state.ownSymbols`) matches a watched "BRK-B" against Finnhub's "BRK.B" event. */
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
            // fetchProvider().execute's OWN internals already swallow repository failures to
            // emptyList() (FetchEarningsCalendar contract) — but ownSymbols() is a raw
            // file-backed read (watchlist + portfolio) that sits OUTSIDE that guard and can throw
            // IOException. Uncaught here it would reach the default handler and CRASH the
            // process (androidx viewModelScope has no built-in catch), so guard broadly: degrade
            // to empty events/ownSymbols TOGETHER on any non-cancellation failure (the Pair keeps
            // the two assignments atomic — a fetch that already succeeded before a failing
            // ownSymbols() must not leave a half-updated state); buildCalendarDays with an empty
            // event list still yields the holiday/half-day rows for the window, which are local
            // and must render regardless.
            val (events, own) = try {
                fetchProvider().execute(calendar.dayString(start), calendar.dayString(start + 13)) to ownSymbols()
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
