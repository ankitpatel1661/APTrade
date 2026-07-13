package com.aptrade.desktop

import com.aptrade.desktop.designkit.formatPercent
import com.aptrade.desktop.infra.AppSettings
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.MarketActivityPlanner
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ScheduledNotification
import com.aptrade.shared.application.SchedulerStateStore
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
                // wired in a later task (Task 6 replaces it)
                ScheduledNotification.EarningsCheckDue -> {}
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
