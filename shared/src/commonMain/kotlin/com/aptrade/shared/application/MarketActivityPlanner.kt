package com.aptrade.shared.application

import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.MarketStatus

/** A scheduled notification the app should deliver this tick. [DigestDue] and
 *  [EarningsCheckDue] carry no content — the caller builds the body from live data.
 *  Mirrors macOS's `ScheduledNotification` (Sources/APTradeApplication/MarketActivityPlanner.swift). */
sealed class ScheduledNotification {
    object MarketOpened : ScheduledNotification()
    object MarketClosed : ScheduledNotification()
    object DigestDue : ScheduledNotification()
    object EarningsCheckDue : ScheduledNotification()
}

/** Persisted markers so scheduled notifications fire once per event rather than every
 *  poll, and survive relaunches (no duplicate digest/earnings-check after restarting
 *  mid-day). Mirrors macOS's `SchedulerState`. */
data class SchedulerState(
    val lastStatus: MarketStatus? = null,
    val lastDigestDay: String? = null,
    val lastEarningsDay: String? = null,
)

/** Persists the scheduler's last-fired markers across launches. */
interface SchedulerStateStore {
    suspend fun load(): SchedulerState
    suspend fun save(state: SchedulerState)
}

/**
 * Pure decision policy for time-based notifications. Given the current instant, the
 * last persisted state, and the user's preferences, returns which notifications are
 * due and the state to persist. No clocks, timers, or I/O — fully testable. Caller
 * supplies `nowEpochSeconds` (see EpochClock.epochSecondsNow's clock-injection
 * precedent); this function never reads the wall clock itself.
 *
 * Transcribed from macOS's `MarketActivityPlanner.plan(now:state:settings:)`. The three
 * settings flags ([marketOpenCloseEnabled], [newsDigestEnabled], [earningsReportsEnabled])
 * replace the Swift `AppSettings` struct parameter one-for-one — this module has no
 * `AppSettings` port yet, so the booleans it actually reads are passed directly.
 */
class MarketActivityPlanner(private val calendar: MarketCalendar = MarketCalendar()) {

    fun plan(
        nowEpochSeconds: Long,
        state: SchedulerState,
        marketOpenCloseEnabled: Boolean,
        newsDigestEnabled: Boolean,
        // Defaults false (unlike AppSettings.earningsReports' true) purely so existing
        // call sites that predate this flag keep their exact-event-list assertions;
        // real callers (DesktopMarketActivityCoordinator) always pass it explicitly.
        earningsReportsEnabled: Boolean = false,
    ): Pair<List<ScheduledNotification>, SchedulerState> {
        val events = mutableListOf<ScheduledNotification>()
        val status = calendar.status(nowEpochSeconds)

        // Open/close transition. The first observation only seeds the baseline (no
        // event), so launching mid-session never fires a spurious "market opened".
        val lastStatus = state.lastStatus
        if (lastStatus != null && lastStatus != status && marketOpenCloseEnabled) {
            events += if (status == MarketStatus.OPEN) {
                ScheduledNotification.MarketOpened
            } else {
                ScheduledNotification.MarketClosed
            }
        }

        var lastDigestDay = state.lastDigestDay

        // One digest per trading day, the first tick we observe the market open. The
        // day marker only advances when we actually fire, so enabling the toggle later
        // in the day still delivers that day's digest.
        if (status == MarketStatus.OPEN) {
            val day = calendar.tradingDay(nowEpochSeconds)
            if (lastDigestDay != day && newsDigestEnabled) {
                events += ScheduledNotification.DigestDue
                lastDigestDay = day
            }
        }

        var lastEarningsDay = state.lastEarningsDay

        // One earnings check per trading day, the first tick we observe the market
        // open. Mirrors the digest block exactly: the day marker only advances when we
        // actually fire, so enabling the toggle later in the day still delivers that
        // day's check.
        if (status == MarketStatus.OPEN) {
            val day = calendar.tradingDay(nowEpochSeconds)
            if (lastEarningsDay != day && earningsReportsEnabled) {
                events += ScheduledNotification.EarningsCheckDue
                lastEarningsDay = day
            }
        }

        val newState = SchedulerState(
            lastStatus = status,
            lastDigestDay = lastDigestDay,
            lastEarningsDay = lastEarningsDay,
        )
        return events to newState
    }
}
