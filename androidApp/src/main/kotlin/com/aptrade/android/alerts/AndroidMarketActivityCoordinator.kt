package com.aptrade.android.alerts

import com.aptrade.android.ui.formatPercent
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.MarketActivityPlanner
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ScheduledNotification
import com.aptrade.shared.application.SchedulerStateStore
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drives time-based notifications (market open/close, the daily digest, and the
 * once-per-trading-day earnings check) on a fixed 60s cadence — the Android port of
 * `desktopApp/src/main/kotlin/com/aptrade/desktop/DesktopMarketActivityCoordinator.kt`
 * (Task 8), which itself transcribes Sources/APTradeApp/MarketActivityCoordinator.swift.
 * A light loop polls the pure [MarketActivityPlanner] every tick, persists whatever state
 * it returns, and dispatches whatever events are due. All policy lives in the planner;
 * this type only supplies the clock, persistence, and digest content.
 *
 * `scope` MUST be single-thread-confined (Dispatchers.Main on Android, matching desktop) —
 * started in MainActivity alongside the alert-evaluation loop, and cancelled the same way
 * on teardown. Ticks immediately on [start], then every [intervalMillis] until the scope is
 * cancelled.
 */
class AndroidMarketActivityCoordinator(
    private val planner: MarketActivityPlanner,
    private val stateStore: SchedulerStateStore,
    private val loadSettings: suspend () -> AppSettings,
    private val notifyMarketStatus: suspend (opened: Boolean) -> Unit,
    private val notifyDigest: suspend (summary: String) -> Unit,
    // Kept a raw `EarningsEvent` -> Unit closure rather than pre-formatted (title, body)
    // strings (contrast notifyDigest above, which DOES receive a pre-built summary this
    // class assembles itself in digestSummary()). This class has no L10n access — digestSummary
    // builds plain hardcoded English, it never calls into com.aptrade.android.l10n — so there
    // is no localization precedent here worth mirroring. Earnings notifications DO need
    // localized L10n.Key.EarningsTodayTitle/EarningsTodayBodyFmt strings, and the only place
    // that already imports `tr`/`trf` is AppGraph's `marketActivityCoordinator` factory (the
    // wiring site); routing the typed event there keeps this coordinator entirely ignorant of
    // L10n, matching desktop's shape exactly.
    private val notifyEarnings: suspend (event: EarningsEvent) -> Unit,
    private val fetchTodaysOwnEarnings: suspend () -> List<EarningsEvent>,
    private val fetchWatchlist: FetchWatchlist,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long,
    private val intervalMillis: Long = 60_000,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                tick()
                delay(intervalMillis)
            }
        }
    }

    private suspend fun tick() {
        val settings = loadSettings()
        val (events, newState) = planner.plan(
            nowEpochSeconds = nowEpochSeconds(),
            state = stateStore.load(),
            marketOpenCloseEnabled = settings.marketOpenClose,
            newsDigestEnabled = settings.newsDigest,
            earningsReportsEnabled = settings.earningsReports,
        )
        stateStore.save(newState)
        for (event in events) {
            when (event) {
                ScheduledNotification.MarketOpened -> notifyMarketStatus(true)
                ScheduledNotification.MarketClosed -> notifyMarketStatus(false)
                ScheduledNotification.DigestDue -> notifyDigest(digestSummary())
                ScheduledNotification.EarningsCheckDue -> {
                    // Same "never drop the whole tick" reasoning as digestSummary() below: an
                    // uncaught failure here would kill the tick coroutine and silently disable
                    // ALL scheduled notifications (open/close, digest, earnings) until relaunch.
                    // FetchEarningsCalendar.ownedToday already swallows repository failures, but
                    // its ownSymbols() closure (file-backed watchlist/portfolio reads) can still
                    // throw — and this constructor param accepts ANY closure — so guard broadly
                    // here: failure -> no earnings notifications this tick, everything else
                    // proceeds. Exception (not just QuoteError, the digest's narrower quotes-only
                    // catch) matches fetchOrEmpty's own breadth in EarningsUseCases.kt.
                    val todaysOwn = try {
                        fetchTodaysOwnEarnings()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    for (earningsEvent in todaysOwn) {
                        notifyEarnings(earningsEvent)
                    }
                }
                ScheduledNotification.ContributionCheckDue -> {
                    // No-op for now: M7.2 Task 9 only adds the planner event (mirroring
                    // EarningsCheckDue's shape) so the sealed `when` above stays exhaustive.
                    // Real due-contribution catch-up + notification wiring is Android's
                    // M7.3 pie-contributions increment (this app is untouched by M7.2
                    // otherwise), gated by settings.pieContributions per Global Constraints
                    // correction 6 (desktop's Task 12 precedent).
                }
            }
        }
    }

    /** Builds the digest body from the watchlist's biggest movers today — the top 3 by
     *  `abs(changePercent)`, "Today's movers — SYM +x.xx%, ...". A quote-fetch failure
     *  (or an empty watchlist) falls back to a plain no-data message rather than
     *  propagating and dropping the whole tick. */
    private suspend fun digestSummary(): String {
        val symbols = fetchWatchlist.execute().map { it.symbol }
        if (symbols.isEmpty()) return "No watchlist symbols to report yet."
        val quotes = try {
            fetchMarketQuotes.execute(symbols)
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuoteError) {
            emptyList()
        }
        if (quotes.isEmpty()) return "Market data is still updating."

        val movers = quotes
            .sortedByDescending { abs(it.changePercent) }
            .take(3)
            .joinToString(", ") { "${it.symbol} ${formatPercent(it.changePercent)}" }
        return "Today's movers — $movers"
    }
}
