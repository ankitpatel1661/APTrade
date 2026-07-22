package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.MarketStatus
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PresetScreen
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.ScreenSelection
import com.aptrade.shared.domain.ScreenerSnapshot
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate

/**
 * One upcoming dividend payout, reduced to the two facts Home's feed needs. Deliberately its
 * own shared shape (not the desktop `IncomeViewModel.UpcomingRow`, which is epochSeconds-based
 * and lives in `:desktopApp`, unreachable from `:shared`) — [estimatedExDate] is a calendar day
 * ([LocalDate]), matching this file's day-only convention (see the header doc below).
 */
data class HomeUpcomingDividend(
    val symbol: String,
    val estimatedAmount: Money,
    val estimatedExDate: LocalDate,
)

/**
 * Everything the income engine has to say about dividends, reduced to the two facts Home
 * cares about: this year's cash received, and the single next upcoming payout (if any).
 *
 * WRAPS the existing income pipeline (Global Constraint 2 of the M10.2 plan) — the
 * assembler's [HomeFeedAssembler.loadIncomeSummary] source is expected to be built by
 * wrapping the SAME computation the platform's IncomeViewModel already runs
 * (`receivedYTD`/`buildUpcoming` on desktop — see `IncomeViewModel.kt`), never by
 * recomputing dividend math a second time here.
 */
data class HomeIncomeSummary(
    val receivedYTD: Money,
    val nextDividend: HomeUpcomingDividend?,
)

/**
 * One row in Home's "Today" feed. Carries data only — copy/formatting is the view's job (per
 * house rule: ViewModels/assemblers expose data, Views format it through `tr()`/`trf()`).
 *
 * Day-only fields ([Earnings.day], [Dividend.day]) are [LocalDate] end-to-end, never
 * round-tripped through an Instant — see the header doc on [HomeFeedAssembler]. Market-HOURS
 * transition instants (an exact moment, not a calendar day) stay on the existing
 * epochSeconds/[MarketCalendar] convention instead — [MarketStatus.nextTransitionEpochSeconds].
 *
 * [ScreenerFresh] carries the [PresetScreen] value itself rather than a display-name string:
 * unlike Swift (whose `PresetScreen` is a raw-String enum, so the row stored `.rawValue`),
 * Kotlin's `PresetScreen` is a plain domain enum with no serialization boundary between this
 * file and the desktop/Android UI layers, so there is nothing to gain from encoding it as a
 * string up front. This also satisfies Global Constraint 5 (single-source preset display
 * titles): the one `presetDisplayName(PresetScreen): String` mapping the UI hoists (Task 3/4)
 * can take this same enum value directly — no second string-keyed lookup to keep in sync.
 */
sealed class HomeFeedItem {
    data class MarketStatus(val isOpen: Boolean, val nextTransitionEpochSeconds: Long) : HomeFeedItem()
    data class TopGainer(val symbol: String, val changePercent: Double) : HomeFeedItem()
    data class TopLoser(val symbol: String, val changePercent: Double) : HomeFeedItem()
    data class Earnings(val symbol: String, val session: EarningsSession, val day: LocalDate) : HomeFeedItem()
    data class Dividend(val symbol: String, val amount: Money, val day: LocalDate) : HomeFeedItem()
    data class ScreenerFresh(val preset: PresetScreen, val matches: Int) : HomeFeedItem()
}

/**
 * Home dashboard's aggregate state: hero stats plus the fixed-order "Today" feed. A plain,
 * immutable snapshot — the assembler returns a fresh one from every [HomeFeedAssembler.refresh]
 * call rather than mutating published fields in place (see that class's header doc for why).
 */
data class HomeState(
    val totalValue: Money,
    val dayChange: Money,
    /** Already ×100-scaled (10.0 means +10%), matching [Quote.changePercent]'s convention.
     *  Zero-guarded: 0.0 whenever the previous-day total was zero (never a division by zero). */
    val dayChangePercent: Double,
    val cash: Money,
    val incomeYTD: Money,
    /** Count of non-triggered ("armed") alerts only — see [HomeFeedAssembler.refreshAlertCount]. */
    val alertCount: Int,
    val feed: List<HomeFeedItem>,
)

/**
 * Home dashboard: PURE aggregation over the same engines Portfolio/Watchlist/Income/
 * Calendar/Screener/Alerts already expose — no calculation here is reimplemented from those
 * surfaces, only combined. Transcribes the SEMANTICS of the shipped macOS/iPhone
 * `Sources/APTradeApp/HomeViewModel.swift` (M10.1) into a Kotlin-first, platform-agnostic
 * shape — not its `@Observable`/class-shape — so both the desktop (M10.2) and Android (M10.3)
 * ViewModels can share this ONE assembler.
 *
 * DAY-ONLY VALUES: every calendar-day field in the produced feed
 * ([HomeFeedItem.Earnings.day], [HomeFeedItem.Dividend.day]) is a `kotlinx.datetime.LocalDate`,
 * parsed straight from the upstream day string/day value and never round-tripped through an
 * Instant. The one exception is [HomeFeedItem.MarketStatus.nextTransitionEpochSeconds], which
 * is a genuine INSTANT (an exact moment the session opens/closes) resolved in ET terms by
 * [MarketCalendar] — that stays on the existing epochSeconds convention every other call site
 * of [MarketCalendar] already uses, rather than being forced into a day type it isn't.
 *
 * FAILURE ISOLATION: every source below is fetched independently. [CancellationException] is
 * always rethrown FIRST in every per-source `catch` — never swallowed into a degraded state,
 * mirroring `CalendarViewModel.load()`'s handling elsewhere in this codebase. Any OTHER error
 * degrades ONLY that source's own contribution (a zeroed hero stat, or an absent feed row);
 * every other source is unaffected — e.g. a portfolio-load failure still leaves the
 * `marketStatus` row (and every other row) in the feed untouched.
 *
 * STATELESS PER CALL: unlike the Swift `@Observable` VM (which mutates published `var`
 * properties across refreshes, so a degraded source's old value could in principle leak into
 * view between the write and the next refresh), this assembler holds no mutable scratch state
 * between calls — each [refresh] computes every source fresh and returns one complete
 * [HomeState] snapshot. This is a deliberate Kotlin-first simplification: nothing here is
 * "published" incrementally the way `@Observable` fields are, so there is no reason to keep
 * instance-level scratch fields around only to overwrite them on the very next line.
 *
 * SEQUENTIAL: sources are awaited one after another (matching the Swift reference), not
 * fanned out with `async`/`awaitAll` — this keeps the per-source isolation semantics identical
 * to the reference and leaves the outer polling cadence (Global Constraint 1's single
 * sequential 15s loop) entirely to the caller (the desktop/Android ViewModel, Tasks 2/M10.3).
 */
class HomeFeedAssembler(
    /** The same source `PortfolioViewModel`-equivalents read: positions + cash. */
    private val loadPortfolio: suspend () -> Portfolio,
    /** Live quotes for whatever symbol set is asked for, keyed by symbol. A symbol this
     *  couldn't fetch is simply ABSENT from the returned map (never a partial-failure
     *  exception) — the same shape [Portfolio.valuation] already expects and degrades
     *  gracefully (cost-basis fallback) when a quote is missing. */
    private val fetchQuotes: suspend (symbols: List<String>) -> Map<String, Quote>,
    /** Holdings ∪ watchlist symbols, deduped — the SAME provider the earnings-calendar's
     *  `ownSymbols` closure precedent already builds (see `AppGraph.kt`'s `ownSymbols`).
     *  Independent of [loadPortfolio], so a portfolio-load failure never blocks the movers row. */
    private val ownSymbols: suspend () -> Set<String>,
    /** Wraps the SAME pipeline the platform's IncomeViewModel runs (Global Constraint 2) —
     *  never recompute dividend math here. */
    private val loadIncomeSummary: suspend () -> HomeIncomeSummary,
    /** The earnings-calendar fetch, restricted to owned+watched symbols, soonest first —
     *  reuses [FetchEarningsCalendar]'s existing owned-first day-ascending sort rather than
     *  re-deriving "next" here. */
    private val fetchNextEarnings: suspend () -> EarningsEvent?,
    /** The persisted screener snapshot — same store `ScreenerViewModel` reads. */
    private val loadScreenerSnapshot: suspend () -> ScreenerSnapshot?,
    /** All persisted alerts — same store the Watchlist/Alerts-center VMs load from.
     *  [refreshAlertCount] filters to armed-only; see that function's doc. */
    private val loadAlerts: suspend () -> List<PriceAlert>,
    /** Shared with the screener's freshness check and the market-hours status below, so
     *  "today" means the same trading day everywhere. */
    private val calendar: MarketCalendar = MarketCalendar(),
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {

    /** Fixed order: status, then movers (gainer, loser), then earnings, dividend, screener. */
    suspend fun refresh(): HomeState {
        val statusItem = marketStatusItem()
        val hero = refreshHero()
        val movers = refreshMovers()
        val income = refreshIncome()
        val earningsItem = refreshEarningsItem()
        val screenerItem = refreshScreenerItem()
        val alertCount = refreshAlertCount()

        val feed = listOfNotNull(
            statusItem, movers.gainer, movers.loser, earningsItem, income.dividendItem, screenerItem,
        )

        return HomeState(
            totalValue = hero.totalValue,
            dayChange = hero.dayChange,
            dayChangePercent = hero.dayChangePercent,
            cash = hero.cash,
            incomeYTD = income.receivedYTD,
            alertCount = alertCount,
            feed = feed,
        )
    }

    // MARK: - Market status (pure/sync — can't fail)

    private fun marketStatusItem(): HomeFeedItem.MarketStatus {
        val instant = nowEpochSeconds()
        val status = calendar.status(instant)
        val transition = nextTransition(instant, status)
        return HomeFeedItem.MarketStatus(isOpen = status == MarketStatus.OPEN, nextTransitionEpochSeconds = transition)
    }

    /** Finds the next minute at which [MarketCalendar.status] disagrees with
     *  [currentStatus] by stepping forward minute-by-minute. This queries the calendar's own
     *  hours table as an oracle rather than re-encoding 9:30/16:00/holiday knowledge here —
     *  "reuse, don't duplicate," the same divergence-avoidance the Swift reference documents.
     *  Capped at one week of minutes, comfortably covering any holiday+weekend stack; the cap
     *  is defensive only and should never be hit in practice. */
    private fun nextTransition(fromEpochSeconds: Long, currentStatus: MarketStatus): Long {
        var candidate = fromEpochSeconds
        repeat(7 * 24 * 60) {
            candidate += 60
            if (calendar.status(candidate) != currentStatus) return candidate
        }
        return candidate
    }

    // MARK: - Hero stats (totalValue / dayChange / dayChangePercent / cash)

    private data class HeroResult(
        val totalValue: Money,
        val dayChange: Money,
        val dayChangePercent: Double,
        val cash: Money,
    )

    private suspend fun refreshHero(): HeroResult = try {
        val portfolio = loadPortfolio()
        val symbols = portfolio.positions.map { it.asset.symbol }
        val quotes = if (symbols.isEmpty()) emptyMap() else fetchQuotes(symbols)
        val valuation = portfolio.valuation(quotes)
        val previousTotal = valuation.totalValue.amount - valuation.dayChange.amount
        val dayChangePercent = if (previousTotal.compareTo(BigDecimal.ZERO) == 0) {
            0.0
        } else {
            valuation.dayChange.amount.divide(previousTotal, MONEY_MATH).doubleValue(false) * 100.0
        }
        HeroResult(
            totalValue = valuation.totalValue,
            dayChange = valuation.dayChange,
            dayChangePercent = dayChangePercent,
            cash = valuation.cash,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        HeroResult(
            totalValue = Money(BigDecimal.ZERO),
            dayChange = Money(BigDecimal.ZERO),
            dayChangePercent = 0.0,
            cash = Money(BigDecimal.ZERO),
        )
    }

    // MARK: - Movers (top gainer / top loser across holdings ∪ watchlist)

    private data class MoversResult(val gainer: HomeFeedItem.TopGainer?, val loser: HomeFeedItem.TopLoser?)

    private suspend fun refreshMovers(): MoversResult = try {
        val symbols = ownSymbols()
        if (symbols.isEmpty()) {
            MoversResult(gainer = null, loser = null)
        } else {
            val quotes = fetchQuotes(symbols.toList())
            if (quotes.isEmpty()) {
                MoversResult(gainer = null, loser = null)
            } else {
                val sorted = quotes.entries.sortedByDescending { it.value.changePercent }
                val gainer = sorted.first().let { HomeFeedItem.TopGainer(it.key, it.value.changePercent) }
                // Loser suppressed when only one symbol was actually quoted — a single quote
                // can't be both the day's best AND worst mover.
                val loser = if (sorted.size > 1) {
                    sorted.last().let { HomeFeedItem.TopLoser(it.key, it.value.changePercent) }
                } else {
                    null
                }
                MoversResult(gainer = gainer, loser = loser)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MoversResult(gainer = null, loser = null)
    }

    // MARK: - Income (YTD + next upcoming dividend)

    private data class IncomeResult(val receivedYTD: Money, val dividendItem: HomeFeedItem.Dividend?)

    private suspend fun refreshIncome(): IncomeResult = try {
        val summary = loadIncomeSummary()
        val dividendItem = summary.nextDividend?.let {
            HomeFeedItem.Dividend(symbol = it.symbol, amount = it.estimatedAmount, day = it.estimatedExDate)
        }
        IncomeResult(receivedYTD = summary.receivedYTD, dividendItem = dividendItem)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        IncomeResult(receivedYTD = Money(BigDecimal.ZERO), dividendItem = null)
    }

    // MARK: - Next earnings among owned+watched

    private suspend fun refreshEarningsItem(): HomeFeedItem.Earnings? = try {
        fetchNextEarnings()?.let { event ->
            HomeFeedItem.Earnings(symbol = event.symbol, session = event.session, day = LocalDate.parse(event.day))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // MARK: - Screener freshness (same store + calendar `ScreenerViewModel` reads)

    private suspend fun refreshScreenerItem(): HomeFeedItem.ScreenerFresh? = try {
        val snapshot = loadScreenerSnapshot()
        if (snapshot == null || snapshot.tradingDay != calendar.tradingDay(nowEpochSeconds())) {
            null
        } else {
            // No "last selected screen" is persisted anywhere (`ScreenerViewModel`'s active
            // selection lives only in that VM's in-memory state) — Home falls back to the
            // first preset, matching `ScreenerViewModel`'s own default selection.
            val preset = PresetScreen.entries.firstOrNull()
            if (preset == null) {
                null
            } else {
                val matches = ScreenSelection.Preset(preset).evaluate(snapshot.rows).size
                HomeFeedItem.ScreenerFresh(preset = preset, matches = matches)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // MARK: - Alerts

    /** Counts only non-triggered ("armed") alerts — the settled cross-platform decision
     *  (Global Constraint 8) for what the Home alerts entry counts. This is DELIBERATELY NOT
     *  "all alerts": the shipped Swift `alertsActiveFmt` counted every alert regardless of
     *  trigger state, a recorded mismatch with what "active" should mean that Swift is
     *  expected to backport later. Kotlin starts from the corrected semantics instead of
     *  transcribing the mismatch forward. */
    private suspend fun refreshAlertCount(): Int = try {
        loadAlerts().count { !it.isTriggered }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        0
    }
}
