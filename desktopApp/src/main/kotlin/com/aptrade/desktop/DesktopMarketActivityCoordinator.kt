package com.aptrade.desktop

import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.infra.AppSettings
import com.aptrade.shared.application.ContributionOutcome
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.MarketActivityPlanner
import com.aptrade.shared.application.PieRunResult
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ScheduledNotification
import com.aptrade.shared.application.SchedulerStateStore
import com.aptrade.shared.domain.EarningsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drives time-based notifications (market open/close, the daily digest, and the
 * once-per-trading-day earnings check) on a fixed
 * 60s cadence. Transcribed from Sources/APTradeApp/MarketActivityCoordinator.swift: a
 * light loop polls the pure [MarketActivityPlanner] every tick, persists whatever state
 * it returns, and dispatches whatever events are due. All policy lives in the planner;
 * this type only supplies the clock, persistence, and digest content.
 *
 * `scope` MUST be single-thread-confined (Dispatchers.Main on desktop) — started in
 * Main.kt alongside the watchlist/portfolio polling scope, and cancelled the same way on
 * dispose. Ticks immediately on [start], then every [intervalMillis] until the scope is
 * cancelled.
 */
class DesktopMarketActivityCoordinator(
    private val planner: MarketActivityPlanner,
    private val stateStore: SchedulerStateStore,
    private val loadSettings: suspend () -> AppSettings,
    private val notifyMarketStatus: suspend (opened: Boolean) -> Unit,
    private val notifyDigest: suspend (summary: String) -> Unit,
    // Kept a raw `EarningsEvent` -> Unit closure rather than pre-formatted (title, body)
    // strings (contrast notifyDigest above, which DOES receive a pre-built summary this
    // class assembles itself in digestSummary()). This class has no L10n access — digestSummary
    // builds plain hardcoded English, it never calls into com.aptrade.desktop.l10n — so there
    // is no localization precedent here worth mirroring. Earnings notifications DO need
    // localized L10n.Key.EarningsTodayTitle/EarningsTodayBodyFmt strings (Task 5), and the
    // only place that already imports `tr`/`trf` is Main.kt (UI land); routing the typed event
    // there keeps this coordinator entirely ignorant of L10n, matching its existing
    // JVM/Compose-light, string-formatting-free shape everywhere except the (unlocalized) digest.
    private val notifyEarnings: suspend (event: EarningsEvent) -> Unit,
    private val fetchTodaysOwnEarnings: suspend () -> List<EarningsEvent>,
    // Pie contributions (M7.2 Task 12). [executeDueContributions] is the non-throwing,
    // per-pie-degrading catch-up engine (`ExecuteDueContributions.execute`, wrapped as a plain
    // suspend closure — same "typed use case behind a closure" shape [fetchTodaysOwnEarnings]
    // already establishes). [notifyPieContribution] mirrors [notifyEarnings]'s "raw typed
    // event -> Unit closure, no L10n access here" shape exactly: Main.kt resolves the
    // executed/skipped title+body via `tr`/`trf` and calls into `TrayNotifier.notifyPieContribution`.
    private val executeDueContributions: suspend (nowEpochSeconds: Long) -> List<PieRunResult>,
    private val notifyPieContribution: suspend (outcome: ContributionOutcome) -> Unit,
    private val fetchWatchlist: FetchWatchlist,
    private val fetchMarketQuotes: FetchMarketQuotes,
    private val scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long,
    private val intervalMillis: Long = 60_000,
) {
    private var job: Job? = null

    /**
     * Starts the 60s tick loop AND, once, a launch-time due-contribution catch-up — GATED on
     * `settings.pieContributions` on BOTH paths (Global Constraints correction 6: the toggle
     * gates EXECUTION, not just notification delivery). The launch catch-up runs once, before
     * the first tick, so contributions missed while the app wasn't running (possibly several
     * days' worth) execute immediately at startup rather than waiting for the market to next
     * open. The tick loop's own `ContributionCheckDue` handler (fired once per trading day,
     * mirroring `EarningsCheckDue`) then covers same-day due contributions for an app that's
     * already running when the market opens.
     */
    fun start() {
        if (job != null) return
        job = scope.launch {
            runContributionCatchUpIfEnabled()
            while (isActive) {
                tick()
                delay(intervalMillis)
            }
        }
    }

    /** Runs [executeDueContributions] and notifies per outcome, but only when the user's
     *  `pieContributions` setting is on — shared by [start]'s launch catch-up and [tick]'s
     *  `ContributionCheckDue` handler so both execution paths honor the SAME gate (see [start]'s
     *  doc comment). Non-throwing: [executeDueContributions] itself already degrades per-pie
     *  failures internally, but this wraps the call defensively too (mirrors the
     *  `EarningsCheckDue` handler's "never drop the whole tick" guard below) since any other
     *  closure passed in here could still throw. */
    private suspend fun runContributionCatchUpIfEnabled() {
        if (!loadSettings().pieContributions) return
        val results = try {
            executeDueContributions(nowEpochSeconds())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        for (result in results) {
            for (outcome in result.outcomes) {
                notifyPieContribution(outcome)
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
            pieContributionsEnabled = settings.pieContributions,
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
                    // The planner only fires this event when `pieContributionsEnabled` was
                    // already true for THIS tick's `plan()` call, but `settings` here is the
                    // same snapshot read at the top of this tick — re-checking would be
                    // redundant. Both execution paths (this handler and `start()`'s launch
                    // catch-up) route through the SAME gated helper so the toggle's semantics
                    // never drift between them (Global Constraints correction 6).
                    runContributionCatchUpIfEnabled()
                }
                // No-op for now: the planner already fires this once per trading day
                // (M8.2 Task 3), but wiring it to the dividend-processing engine and the
                // tray notifier is M8.2 Task 10's job. Listed here only so the exhaustive
                // `when` compiles.
                ScheduledNotification.DividendCheckDue -> Unit
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
