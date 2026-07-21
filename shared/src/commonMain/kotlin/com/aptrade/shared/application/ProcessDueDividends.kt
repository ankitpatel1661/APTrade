package com.aptrade.shared.application

import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.DividendMath
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.Transaction
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches and indexes daily historical closes by symbol and day, over
 * [MarketDataRepository]'s one-year history window ([Timeframe.OneYear]). A LOCAL replica of
 * [com.aptrade.shared.application.fetchClosesByDay] in `PieContributionUseCases.kt` (itself a
 * transcription of Swift `PieUseCases.swift`'s private top-level helper) — that one is
 * `private` to its file, so it cannot be shared across files; duplicating the ~10 lines keeps
 * `ProcessDueDividends` self-contained rather than widening either file's API surface just to
 * share it. Mirrors Swift `DividendUseCases.swift`'s identically-motivated
 * `fetchDividendClosesByDay`.
 */
private suspend fun fetchDividendClosesByDay(
    market: MarketDataRepository,
    symbols: List<String>,
    calendar: MarketCalendar,
): Map<String, Map<String, Money>> {
    val result = mutableMapOf<String, Map<String, Money>>()
    for (symbol in symbols) {
        val points = market.history(symbol, Timeframe.OneYear)
        val byDay = mutableMapOf<String, Money>()
        for (point in points) {
            byDay[calendar.tradingDay(point.epochSeconds)] = point.close
        }
        result[symbol] = byDay
    }
    return result
}

/**
 * The result of crediting one dividend event to the portfolio. Transcribed from Swift
 * `DividendUseCases.swift`'s `DividendOutcome` enum.
 */
sealed class DividendOutcome {
    /**
     * Cash was credited: a plain dividend, or a DRIP that fell back to cash because the
     * ex-date trading day's close was missing/non-positive, DRIP was off, or the position had
     * vanished (carry-note 3). [isBackfill] is `true` when the event's ex-date trading day
     * predates this install's `dividendsFirstRunDay` — historical events credited in bulk on
     * first run, as opposed to a live event processed as it becomes due. Callers use this to
     * avoid a notification burst on first launch.
     */
    data class Credited(val symbol: String, val cash: Money, val isBackfill: Boolean) : DividendOutcome()

    /**
     * Cash was credited and immediately reinvested into [shares] of the position, bought at
     * the ex-date trading day's close (DRIP). [isBackfill] has the same meaning as on
     * [Credited] — in practice always `false` here, since backfill events always credit as
     * cash (see [ProcessDueDividends]'s doc), but the case still carries it for symmetry and
     * so callers can pattern-match uniformly.
     */
    data class Reinvested(
        val symbol: String,
        val cash: Money,
        val shares: BigDecimal,
        val isBackfill: Boolean,
    ) : DividendOutcome()
}

/**
 * Dividend-crediting engine: for every non-crypto position, fetches its historical dividend
 * events and credits any the ledger hasn't already recorded — as cash, or, when DRIP is
 * enabled and the event post-dates this install's first run, reinvested at the ex-date close.
 * Transcribed from Swift `Sources/APTradeApplication/DividendUseCases.swift`'s
 * `ProcessDueDividends` AS-BUILT.
 *
 * **Backfill.** The very first run records `dividendsFirstRunDay` (this install's first
 * dividend-processing trading day) BEFORE crediting anything. Events whose ex-date trading day
 * predates it are historical backfill and always credit as cash, regardless of the DRIP toggle
 * — reinvesting a payout that landed before the user ever enabled the feature would fabricate
 * share lots they never chose. Events on or after that day honor [isDripEnabled], read fresh at
 * processing time.
 *
 * **Idempotency & concurrency.** Every event is credited inside its own [portfolioMutex]
 * critical section, mirroring [ExecuteDueContributions]' per-step discipline: the portfolio is
 * reloaded fresh, the ledger dedup is re-checked against that fresh ledger, and only then is
 * the mutation applied and saved. Two concurrent [execute] runs therefore cannot double-credit
 * — whichever acquires the lock first writes the `Dividend` transaction; the second reloads,
 * sees it, and skips. Dividend transaction ids are random ([com.aptrade.shared.domain.generateTradeId]);
 * dedup is a ledger scan on symbol + `tradingDay(epochSeconds)`, never a deterministic id.
 * Network (dividend events and daily closes) is fetched OUTSIDE the lock, never within it —
 * [BuyAsset]'s established network-outside-lock contract; [portfolioMutex] is THE app-graph
 * portfolio mutex, the SAME instance [BuyAsset]/[SellAsset]/`ContributeToPie`/
 * `ExecuteDueContributions`/`RebalancePie`/`ReconcilePieLedgers`/`SavePie`/`DeletePie`/
 * `ResetPortfolio` hold (see [BuyAsset]'s co-holder doc; this is the 10th co-holder).
 *
 * **Degradation.** [execute] never throws (except [CancellationException], which structured
 * concurrency requires to propagate — the house convention [ExecuteDueContributions]/
 * [SimulateDCA] already follow). Any other error while processing one symbol (a
 * dividend-events fetch, a history fetch) abandons that symbol's remaining events silently;
 * other symbols still process, and events already saved stay saved.
 *
 * **Kotlin carry-note divergences from the Swift as-built** (each named at its site below):
 * 1. An out-of-lock dedup PRE-FILTER against the snapshot ledger skips already-credited events
 *    before ever acquiring the lock; the in-lock re-check stays authoritative for events a
 *    concurrent run credits in the window between snapshot and lock.
 * 2. A DRIP cost clamp: the reinvestment buy's cost must never exceed the credited cash, so a
 *    rounding ulp can never overspend the dividend (or trip [com.aptrade.shared.domain.TradeError.InsufficientFunds]).
 * 3. A position that vanished between the snapshot and the in-lock reload credits as CASH,
 *    never a fabricated `Asset(kind = Stock)` DRIP buy (Swift's `?? Asset(...)` fallback).
 * 4. [isDripEnabled] is `suspend`, unlike Swift's synchronous equivalent (its `SettingsStore`
 *    is `UserDefaults`-backed, so reading a toggle never needs to suspend). Kotlin's real
 *    settings source (`FileSettingsStore.load()`) is `suspend` (file I/O), and this engine is
 *    already suspend end-to-end, so a suspend closure is the idiomatic equivalent here —
 *    semantics are unchanged either way: the toggle is read fresh at processing time (once per
 *    [process] call to decide whether to fetch closes at all, then again per event inside
 *    [credit]), never captured once at construction time.
 */
class ProcessDueDividends(
    private val portfolioStore: PortfolioStore,
    private val market: MarketDataRepository,
    private val stateStore: SchedulerStateStore,
    private val calendar: MarketCalendar,
    private val portfolioMutex: Mutex,
    private val isDripEnabled: suspend () -> Boolean,
) {
    /** Floor division mode used only by carry-note 2's clamp — truncates the quotient toward
     *  zero so `quantity × close` can never exceed the credited cash. Same 38-digit precision
     *  as [MONEY_MATH], only the rounding mode differs. */
    private val floorMode = DecimalMode(38, RoundingMode.FLOOR)

    /** Never throws (except [CancellationException]): per-symbol failures degrade silently;
     *  saved events stay saved. */
    suspend fun execute(nowEpochSeconds: Long): List<DividendOutcome> {
        // Rule 1: establish (and persist, before any crediting) this install's first-run
        // marker. Backfill events (ex-date trading day < this) always credit as cash below.
        val firstRunDay = ensureFirstRunDay(nowEpochSeconds)

        // Rule 2: candidate symbols are the portfolio's non-crypto positions. This snapshot only
        // selects symbols and the earliest-ledger `since` bound; every mutation reloads fresh
        // inside the lock.
        val snapshot = portfolioStore.load() ?: Portfolio.starting()
        val candidates = snapshot.positions.filter { it.asset.kind != AssetKind.Crypto }

        val outcomes = mutableListOf<DividendOutcome>()
        for (position in candidates) {
            // Rule 7: a throw while processing this symbol abandons its remaining events
            // silently; other symbols still process, already-saved events stay saved.
            // CancellationException propagates (structured concurrency — see class doc).
            try {
                outcomes += process(position.asset.symbol, firstRunDay, snapshot.transactions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
        }
        return outcomes
    }

    /** Loads state; if `dividendsFirstRunDay` is unset, sets it to today's trading day and
     *  saves BEFORE any crediting, then returns whichever value is now in effect. */
    private suspend fun ensureFirstRunDay(nowEpochSeconds: Long): String {
        val state = stateStore.load()
        state.dividendsFirstRunDay?.let { return it }
        val today = calendar.tradingDay(nowEpochSeconds)
        stateStore.save(state.copy(dividendsFirstRunDay = today))
        return today
    }

    /**
     * Fetches, filters, and credits one symbol's dividend events. Both network calls (events
     * and — only when a DRIP is actually possible — daily closes) happen here, outside the
     * lock; each surviving event is then credited in its own locked critical section. A throw
     * propagates to [execute], degrading this symbol.
     */
    private suspend fun process(
        symbol: String,
        firstRunDay: String,
        snapshotTransactions: List<Transaction>,
    ): List<DividendOutcome> {
        // `since` = this symbol's earliest ledger instant. A held position always has at least
        // one buy, so the 0L fallback is never the real bound here.
        val since = snapshotTransactions.filter { it.symbol == symbol }.minOfOrNull { it.epochSeconds } ?: 0L
        val events = market.dividendEvents(symbol, since)

        // CARRY-NOTE 1: out-of-lock dedup pre-filter. Events already recorded in the snapshot
        // ledger are dropped BEFORE any lock is acquired — the common no-op replay path never
        // touches the mutex. The in-lock re-check in `credit` stays authoritative for the
        // narrow window in which a concurrent run credits an event between this snapshot and
        // the lock.
        val survivors = events.filter { !alreadyCredited(it, snapshotTransactions) }

        // Whether any surviving event could reinvest — the only reason to fetch closes. Read
        // isDripEnabled() once here for the fetch decision; each event re-reads it at
        // processing time below.
        val mayReinvest = isDripEnabled() && survivors.any {
            calendar.tradingDay(it.exDateEpochSeconds) >= firstRunDay
        }
        val closesByDay: Map<String, Money> =
            if (mayReinvest) fetchDividendClosesByDay(market, listOf(symbol), calendar)[symbol] ?: emptyMap()
            else emptyMap()

        val outcomes = mutableListOf<DividendOutcome>()
        for (event in survivors) {
            credit(event, firstRunDay, closesByDay)?.let { outcomes += it }
        }
        return outcomes
    }

    /**
     * Credits one event inside its own [portfolioMutex] critical section: reload fresh,
     * re-dedup against the fresh ledger, recompute eligibility, mutate, save. Returns `null`
     * when the event is skipped (already credited, or no shares held strictly-before the
     * ex-date).
     */
    private suspend fun credit(
        event: DividendEvent,
        firstRunDay: String,
        closesByDay: Map<String, Money>,
    ): DividendOutcome? {
        val eventDay = calendar.tradingDay(event.exDateEpochSeconds)

        return portfolioMutex.withLock {
            val portfolio = portfolioStore.load() ?: Portfolio.starting()

            // Rule 4 (re-checked on the fresh ledger): a Dividend transaction for this symbol on
            // this trading day means it is already credited — skip. This is the in-lock
            // guarantee against a concurrent run double-crediting (carry-note 1's pre-filter is
            // only an optimization; THIS is the authority).
            if (alreadyCredited(event, portfolio.transactions)) return@withLock null

            // Rule 3: eligibility is shares held STRICTLY BEFORE the ex-date, recomputed from the
            // fresh ledger. Zero (or a non-positive dividend) -> skip silently.
            val shares = DividendMath.sharesHeld(event.symbol, event.exDateEpochSeconds, portfolio.transactions)
            if (shares <= BigDecimal.ZERO || event.amountPerShare.amount <= BigDecimal.ZERO) return@withLock null

            val credit = Money(event.amountPerShare.amount * shares, event.amountPerShare.currencyCode)

            // Rule 1 & 5: backfill (ex-date day < firstRunDay) always credits as cash. Otherwise,
            // when DRIP is enabled and a positive close exists for the ex-date day AND the
            // position still exists (carry-note 3), reinvest; anything else falls back to cash.
            val isBackfill = eventDay < firstRunDay
            val close = closesByDay[eventDay]
            val position = portfolio.positionFor(event.symbol)

            if (!isBackfill && isDripEnabled() && close != null && close.amount > BigDecimal.ZERO && position != null) {
                val credited = portfolio.receivingDividend(
                    symbol = event.symbol,
                    amountPerShare = event.amountPerShare,
                    shares = shares,
                    exDateEpochSeconds = event.exDateEpochSeconds,
                )
                // CARRY-NOTE 2: DRIP cost clamp. Unrounded fractional shares = credit / close,
                // but half-away-from-zero rounding can round the quotient UP, making
                // `quantity × close` a rounding ulp above the credited cash. Clamp by
                // recomputing with a floor (toward-zero) division so the buy's cost can never
                // exceed the credit.
                val rawQuantity = credit.amount.divide(close.amount, MONEY_MATH)
                val quantity =
                    if (rawQuantity.multiply(close.amount) > credit.amount) {
                        credit.amount.divide(close.amount, floorMode)
                    } else {
                        rawQuantity
                    }
                // CARRY-NOTE 3: `position` is guaranteed non-null here, so the DRIP buy always
                // uses the real held asset — never a fabricated Asset(kind = Stock). A vanished
                // position falls through to the cash branch below instead.
                val reinvested = credited.buying(
                    position.asset, quantity, close, event.exDateEpochSeconds, isDrip = true,
                )
                portfolioStore.save(reinvested)
                DividendOutcome.Reinvested(event.symbol, credit, quantity, isBackfill)
            } else {
                val credited = portfolio.receivingDividend(
                    symbol = event.symbol,
                    amountPerShare = event.amountPerShare,
                    shares = shares,
                    exDateEpochSeconds = event.exDateEpochSeconds,
                )
                portfolioStore.save(credited)
                DividendOutcome.Credited(event.symbol, credit, isBackfill)
            }
        }
    }

    /** True when [transactions] already contains a Dividend credit for [event]'s symbol on its
     *  ex-date trading day — the symbol + trading-day dedup key (ids are random, so never a
     *  key). Shared by the out-of-lock pre-filter (carry-note 1) and the authoritative in-lock
     *  re-check. */
    private fun alreadyCredited(event: DividendEvent, transactions: List<Transaction>): Boolean {
        val eventDay = calendar.tradingDay(event.exDateEpochSeconds)
        return transactions.any {
            it.side == TradeSide.Dividend && it.symbol == event.symbol &&
                calendar.tradingDay(it.epochSeconds) == eventDay
        }
    }
}
