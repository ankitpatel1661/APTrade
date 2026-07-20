package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.BacktestReport
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityEntry
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieBacktest
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieError
import com.aptrade.shared.domain.PieLedgerEntry
import com.aptrade.shared.domain.PieMath
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.RebalanceOrder
import com.aptrade.shared.domain.RebalanceSide
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Investment Plans (Pies): turning scheduled/manual cash contributions into self-balancing
 * buys across a pie's slices, plus manual rebalancing, cross-pie ledger reconciliation, and
 * historical DCA simulation. Transcribed from
 * `Sources/APTradeApplication/PieUseCases.swift` (the shipped M7.1 Swift/macOS reference)
 * AS-BUILT — [ContributeToPie], [ExecuteDueContributions], [RebalancePie],
 * [ReconcilePieLedgers], and [SimulateDCA] below, plus their shared helpers.
 *
 * KOTLIN MUTEX ADAPTATION (Global Constraint #8): Swift's `ContributeToPie`/
 * `ExecuteDueContributions`/`RebalancePie`/`ReconcilePieLedgers` run their ENTIRE
 * load-modify-save sequence — including the quote fetch — inside `TradeSerializer.run`.
 * Kotlin's shared core instead follows [BuyAsset]'s established contract: network I/O
 * (quote/history fetches) stays OUTSIDE [Mutex.withLock]; only the state that depends on a
 * *loaded* snapshot runs inside the lock. The one documented exception is per-day catch-up
 * PRICING inside [ExecuteDueContributions] — the distribute computation itself (not the
 * network fetch feeding it) runs inside that day's lock, mirroring Swift's per-day
 * critical-section shape so a concurrent save can interleave BETWEEN days but never WITHIN
 * one. [SimulateDCA] holds no lock at all — like [LoadPies], it never mutates either store.
 */

/**
 * Fetches and indexes daily historical closes by symbol and day, for [symbols] over
 * [MarketDataRepository]'s one-year history window ([Timeframe.OneYear]). Shared by
 * [ExecuteDueContributions]'s historical catch-up pricing today; a future `SimulateDCA`
 * (Task 8) will reuse this identical helper. Transcribed from Swift `PieUseCases.swift`'s
 * private top-level `fetchClosesByDay`.
 */
private suspend fun fetchClosesByDay(
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
 * Live quotes for [symbols], indexed by symbol, fetched in ONE batch call — the network I/O
 * this file's use cases keep OUTSIDE [Mutex.withLock] (see file doc). A symbol
 * [MarketDataRepository.quotes] doesn't return a quote for (unknown/delisted) is simply
 * absent from the result; callers check for a missing entry and throw [QuoteError.NotFound]
 * themselves, mirroring [BuyAsset]'s `.firstOrNull() ?: throw QuoteError.NotFound` contract.
 */
private suspend fun fetchQuotes(market: MarketDataRepository, symbols: List<String>): Map<String, Quote> =
    market.quotes(symbols).associateBy { it.symbol }

/**
 * Outcome of a pie contribution: either buys were executed across the portfolio and pie, or
 * the whole contribution was skipped due to insufficient cash (never partial). Transcribed
 * from Swift `ContributionOutcome`.
 */
sealed class ContributionOutcome {
    data class Executed(val portfolio: Portfolio, val pie: Pie) : ContributionOutcome()

    /** [pie] already carries the `missedInsufficientCash` activity entry; the portfolio is
     *  left completely untouched (not even saved). */
    data class SkippedInsufficientCash(val pie: Pie) : ContributionOutcome()
}

/** The [Pie] left behind by a [ContributionOutcome], whichever case it is. */
internal val ContributionOutcome.updatedPie: Pie
    get() = when (this) {
        is ContributionOutcome.Executed -> pie
        is ContributionOutcome.SkippedInsufficientCash -> pie
    }

/**
 * Turns a cash contribution into self-balancing buys across a pie's slices.
 *
 * Fetches a live quote for every slice symbol first (see [fetchQuotes]) — a missing quote for
 * any slice symbol propagates as a thrown [QuoteError.NotFound] (this use case does not
 * degrade to a partial contribution; the caller decides how to surface the failure). Each
 * slice's current value is computed from the pie's ledger (`quantity × quote price`), and
 * [PieMath.distribute] decides how to split `amount` across slices, preferring underweight
 * ones.
 *
 * If `amount` exceeds the portfolio's cash, the whole contribution is skipped: a
 * `missedInsufficientCash` activity entry is recorded on the pie, the pie is saved, and
 * [ContributionOutcome.SkippedInsufficientCash] is returned with the portfolio left
 * completely untouched. Otherwise, each slice's allocated share is converted to a fractional
 * share quantity (`share ÷ price`, unrounded via [MONEY_MATH] — fractional shares are
 * allowed) and bought into the portfolio tagged with `pieId`. Slices that received a zero
 * share are skipped entirely (no zero-quantity buys). The pie's ledger is incremented for
 * each symbol bought, a `contribution` activity entry is appended, and both stores are saved.
 */
class ContributeToPie(
    private val pieStore: PieStore,
    private val portfolioStore: PortfolioStore,
    private val market: MarketDataRepository,
    private val portfolioMutex: Mutex,
) {
    /**
     * Self-locking public entry point: loads the pie (to know which slice symbols need a
     * quote), fetches those quotes OUTSIDE [portfolioMutex] (network I/O never guards on a
     * mutex — see file doc), then runs [core] under the lock.
     *
     * @param day Stamps the pie's activity entry (`contribution` or `missedInsufficientCash`).
     * @param nowEpochSeconds Stamps the epoch-seconds of any resulting transactions.
     */
    suspend fun execute(pieId: String, amount: Money, day: String, nowEpochSeconds: Long): ContributionOutcome {
        val pies = pieStore.load()
        val pie = pies.firstOrNull { it.id == pieId } ?: throw PieError.NotFound
        val quotes = fetchQuotes(market, pie.slices.map { it.symbol })
        return portfolioMutex.withLock {
            core(pieId, amount, day, nowEpochSeconds, quotes)
        }
    }

    /**
     * The core load-modify-save sequence, WITHOUT acquiring [portfolioMutex] itself — for
     * callers (namely [ExecuteDueContributions]) that already hold the lock for a broader
     * per-day critical section and must not re-acquire it (kotlinx's [Mutex] is NOT
     * reentrant; acquiring it twice from within the same held critical section deadlocks
     * forever). [execute] above is the only public, self-locking entry point for ordinary
     * callers.
     *
     * [quotes] must already be fetched (see [fetchQuotes]) — a symbol among the
     * freshly-reloaded pie's slices missing from [quotes] throws [QuoteError.NotFound].
     *
     * [scheduleOverride], when non-null, replaces the persisted pie's `schedule` in the SAME
     * write as the ledger/activity mutation. [ExecuteDueContributions] uses this to bundle
     * today's due-day cursor advance atomically with its contribution — one [Pie.create]
     * rebuild, one [PieStore.save] — rather than a separate follow-up write. `null` (the
     * default, used by [execute]) keeps the freshly-reloaded pie's own `schedule` unchanged,
     * which is what an ordinary (non-catch-up) contribution always wants.
     */
    internal suspend fun core(
        pieId: String,
        amount: Money,
        day: String,
        nowEpochSeconds: Long,
        quotes: Map<String, Quote>,
        scheduleOverride: ContributionSchedule? = null,
    ): ContributionOutcome {
        val pies = pieStore.load()
        val pie = pies.firstOrNull { it.id == pieId } ?: throw PieError.NotFound

        val currentValues = LinkedHashMap<String, Money>()
        for (slice in pie.slices) {
            val quote = quotes[slice.symbol] ?: throw QuoteError.NotFound
            val quantity = pie.quantityOf(slice.symbol)
            currentValues[slice.symbol] = Money(quantity * quote.price.amount, quote.price.currencyCode)
        }

        val shares = PieMath.distribute(amount, currentValues, pie.slices)
        val schedule = scheduleOverride ?: pie.schedule

        val portfolio = portfolioStore.load() ?: Portfolio.starting()
        if (amount.amount > portfolio.cash.amount) {
            val missed = PieActivityEntry(kind = PieActivityKind.MissedInsufficientCash, day = day, amount = amount)
            val updatedPie = rebuild(pie, pies, schedule, pie.ledger, pie.activity + missed)
            return ContributionOutcome.SkippedInsufficientCash(updatedPie)
        }

        var updatedPortfolio = portfolio
        val newLedger = pie.ledger.toMutableList()
        for (slice in pie.slices) {
            val share = shares[slice.symbol] ?: continue
            if (share.amount <= BigDecimal.ZERO) continue
            val quote = quotes[slice.symbol] ?: throw QuoteError.NotFound

            val quantity = share.amount.divide(quote.price.amount, MONEY_MATH)
            val asset = Asset(symbol = slice.symbol, name = slice.symbol, kind = slice.assetKind)
            updatedPortfolio = updatedPortfolio.buying(asset, quantity, quote.price, nowEpochSeconds, pieId = pieId)

            val newQuantity = pie.quantityOf(slice.symbol) + quantity
            val index = newLedger.indexOfFirst { it.symbol == slice.symbol }
            if (index >= 0) {
                newLedger[index] = PieLedgerEntry(slice.symbol, newQuantity)
            } else {
                newLedger += PieLedgerEntry(slice.symbol, newQuantity)
            }
        }

        val contributed = PieActivityEntry(kind = PieActivityKind.Contribution, day = day, amount = amount)
        val updatedPie = rebuild(pie, pies, schedule, newLedger, pie.activity + contributed)

        portfolioStore.save(updatedPortfolio)
        return ContributionOutcome.Executed(updatedPortfolio, updatedPie)
    }

    /** Rebuilds [pie] with the given overrides via [Pie.create] (rethrows — slices pass
     *  through unchanged so validation always succeeds here), replaces it within [pies], and
     *  persists the full list. */
    private suspend fun rebuild(
        pie: Pie,
        pies: List<Pie>,
        schedule: ContributionSchedule?,
        ledger: List<PieLedgerEntry>,
        activity: List<PieActivityEntry>,
    ): Pie {
        val updated = Pie.create(
            id = pie.id, name = pie.name, slices = pie.slices, schedule = schedule,
            createdDay = pie.createdDay, ledger = ledger, activity = activity,
        )
        val all = pies.toMutableList()
        val index = all.indexOfFirst { it.id == pie.id }
        if (index >= 0) all[index] = updated else all += updated
        pieStore.save(all)
        return updated
    }
}

/** One catch-up run's result for a single [Pie]: every [ContributionOutcome] executed (or
 *  skipped-for-cash) during this run, in due-day order. Transcribed from Swift's
 *  `(pie: Pie, outcomes: [ContributionOutcome])` tuple return. */
data class PieRunResult(val pie: Pie, val outcomes: List<ContributionOutcome>)

/**
 * Catch-up engine: executes scheduled Pie contributions that were missed because the app
 * wasn't running on their due day(s). For each due day strictly after the Pie's schedule
 * cursor (`schedule.nextDueDay`) through today, contributes at that day's historical closing
 * price — except for today's own due day (if due), which uses a live quote via
 * [ContributeToPie]'s lock-free [ContributeToPie.core] (delegating keeps the one live-pricing
 * path defined in a single place rather than duplicated here).
 *
 * Historical closes come from [MarketDataRepository.history] (same as [fetchClosesByDay]),
 * fetched once per slice symbol (not once per due day), indexed by its market-local day
 * string and looked up with an exact match against the due day being executed.
 * [Timeframe.OneYear] is requested; a Pie neglected longer than that simply has its oldest
 * due days silently skipped for lack of a close — the same fallback as a day with a
 * genuinely missing close (see below) rather than a hard failure.
 *
 * A past due day missing a close for ANY slice symbol is skipped silently: no
 * [ContributionOutcome], no activity entry, no ledger change — it is simply consumed (the
 * schedule cursor still advances past it). Insufficient cash, in contrast, IS recorded
 * ([ContributionOutcome.SkippedInsufficientCash]) and does not stop later due days in the
 * same run from being attempted.
 *
 * **Crash/failure resumability:** the schedule cursor (`nextDueDay`) advances incrementally,
 * immediately after each due day is *consumed* — executed, recorded as missed (insufficient
 * cash), or silently skipped (missing close) — not once at the end of the due-day loop.
 * Every consumed day's cursor advance is persisted ATOMICALLY with that same day's
 * ledger/activity write (one [Pie.create] rebuild, one [PieStore.save] — including today's
 * live-quote day, via [ContributeToPie.core]'s `scheduleOverride` parameter). This makes a
 * mid-run throw (e.g. today's live quote failing after several historical days already
 * executed) safe to retry: the cursor sits exactly at the first not-yet-consumed day, so the
 * next run resumes there instead of replaying already-executed days.
 *
 * Each Pie is processed independently and defensively: any thrown error while processing a
 * Pie (a live-quote failure on today's due day, or a `history` fetch failure while building
 * the historical closes table) degrades that one Pie's result to an empty outcomes list
 * rather than failing the whole run ([CancellationException] excepted — rethrown, matching
 * [FetchEarningsCalendar]'s degrade convention) — other Pies still get processed, and any due
 * days already consumed for that Pie before the failure remain persisted, cursor included.
 *
 * PER-DAY critical sections: each due day gets its OWN `portfolioMutex.withLock` block, not
 * one for the whole multi-day catch-up — this is what lets a wizard-style `SavePie` (or any
 * other mutation) interleave BETWEEN days — never within one — and is why every field read
 * inside a day's block comes from a FRESH reload, not from `schedule`/`pie` captured at the
 * top of [catchUp].
 */
class ExecuteDueContributions(
    private val pieStore: PieStore,
    private val portfolioStore: PortfolioStore,
    private val market: MarketDataRepository,
    private val calendar: MarketCalendar,
    private val portfolioMutex: Mutex,
) {
    /** One due day's outcome, returned from inside a single `portfolioMutex.withLock` block
     *  (see [catchUp]) so the surrounding loop can react to it once the lock has released. */
    private sealed class DueDayStep {
        /** The pie (or its schedule) no longer existed on reload — an interleaved save
         *  deleted or unscheduled it. This pie's catch-up stops here. */
        object Stopped : DueDayStep()

        data class Stepped(val outcome: ContributionOutcome, val pie: Pie) : DueDayStep()

        data class AdvancedOnly(val pie: Pie) : DueDayStep()
    }

    /** Runs catch-up for every scheduled Pie. Pies without a `schedule`, or whose schedule
     *  has nothing due yet, are left completely untouched and omitted from the result.
     *  Non-throwing (see class doc for the per-Pie degrade contract). */
    suspend fun execute(nowEpochSeconds: Long): List<PieRunResult> {
        val today = calendar.tradingDay(nowEpochSeconds)
        val results = mutableListOf<PieRunResult>()

        for (pie in pieStore.load()) {
            val schedule = pie.schedule ?: continue

            try {
                val result = catchUp(pie, schedule, today, nowEpochSeconds)
                if (result != null) results += result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Degrade this Pie's result to empty outcomes rather than aborting the whole
                // run; any due days already consumed before the failure — buys, missed-cash
                // entries, silently-skipped days — are still reflected in the reloaded Pie
                // below, cursor included, since each one persists immediately as it's
                // processed (see catchUp's per-day loop).
                val latest = pieStore.load().firstOrNull { it.id == pie.id } ?: pie
                results += PieRunResult(latest, emptyList())
            }
        }
        return results
    }

    /** @return `null` when nothing is due yet (the schedule cursor is still in the future). */
    private suspend fun catchUp(
        pie: Pie,
        schedule: ContributionSchedule,
        today: String,
        nowEpochSeconds: Long,
    ): PieRunResult? {
        val dayBeforeCursor = dayBefore(schedule.nextDueDay) ?: return null

        // The cursor itself is the first eligible due day — PieSchedule.dueDays never treats
        // its own anchor as a candidate (stepping starts at anchor + 1×cadence), so
        // nextDueDay's step-0 case (which DOES treat the anchor as eligible) finds this first
        // one, and dueDays finds every later one from there.
        //
        // Every step below uses schedule.anchorDay (the schedule's fixed, ORIGINAL first due
        // day) — never schedule.nextDueDay (the moving cursor) — as the cadence anchor. See
        // ContributionSchedule's doc comment for why: re-anchoring on a clamped cursor
        // permanently loses the original day-of-month for a monthly cadence.
        val firstDue = PieSchedule.nextDueDay(schedule.anchorDay, schedule.cadence, dayBeforeCursor, calendar)
        if (firstDue > today) return null

        val laterDueDays = PieSchedule.dueDays(schedule.anchorDay, schedule.cadence, firstDue, today, calendar)
        val dueDays = (listOf(firstDue) + laterDueDays).distinct().sorted()

        val closesBySymbol = historicalCloses(pie.slices.map { it.symbol }, today, dueDays)
        // Reused across every day below rather than constructed per-day — ContributeToPie is
        // stateless aside from its injected dependencies, so one instance is equivalent to
        // many, and this avoids reallocating it per iteration.
        val contributeToPie = ContributeToPie(pieStore, portfolioStore, market, portfolioMutex)

        val outcomes = mutableListOf<ContributionOutcome>()
        var latestPie = pie

        for (day in dueDays) {
            // Live quotes for today's due day are fetched OUTSIDE this day's lock (network
            // I/O — see file doc); historical days need no live quote at all (their pricing
            // comes from closesBySymbol, already fetched above, outside every day's lock).
            //
            // DOCUMENTED DIVERGENCE: this fetch reads `latestPie.slices` — the pre-lock
            // snapshot from the END of the PREVIOUS iteration (or the outer `catchUp` snapshot
            // on the first iteration) — not a fresh in-lock reload. If an interleaved `SavePie`
            // edits this pie's slices (adds/removes a symbol) in the narrow window between
            // this fetch and the in-lock reload just below, `liveQuotes` and the freshly
            // reloaded pie's slice list can briefly disagree. That does not corrupt state: the
            // in-lock `ContributeToPie.core` call looks up each of the FRESH pie's slice
            // symbols in `liveQuotes` and throws `QuoteError.NotFound` for any symbol missing
            // from it (a newly-added slice this stale fetch never priced) — which this Pie's
            // whole run then degrades to (caught by `execute`'s per-Pie try/catch, see class
            // doc), rather than silently pricing against a slice list that no longer matches
            // what was just saved. This mirrors [BuyAsset]'s network-outside-lock contract
            // (a quote fetch is never re-validated against a fresher load) and is the same
            // shape of race carried by the historical `closesBySymbol` fetch above — both are
            // deliberate: graceful degradation on a rare interleaving beats holding the mutex
            // across a network call.
            val liveQuotes: Map<String, Quote>? =
                if (day == today) fetchQuotes(market, latestPie.slices.map { it.symbol }) else null

            val step: DueDayStep = portfolioMutex.withLock {
                val pies = pieStore.load()
                val freshPie = pies.firstOrNull { it.id == pie.id }
                val freshSchedule = freshPie?.schedule

                if (freshPie == null || freshSchedule == null) {
                    // The pie was deleted, or unscheduled, by an interleaved save — stop this
                    // pie's catch-up cleanly rather than acting on a schedule that no longer
                    // exists.
                    DueDayStep.Stopped
                } else {
                    val cursorAfterDay = PieSchedule.nextDueDay(freshSchedule.anchorDay, freshSchedule.cadence, day, calendar)

                    if (day == today) {
                        val scheduleOverride = ContributionSchedule(
                            amount = freshSchedule.amount,
                            cadence = freshSchedule.cadence,
                            anchorDay = freshSchedule.anchorDay,
                            nextDueDay = cursorAfterDay,
                        )
                        val outcome = contributeToPie.core(
                            pieId = pie.id,
                            amount = freshSchedule.amount,
                            day = day,
                            nowEpochSeconds = nowEpochSeconds,
                            quotes = liveQuotes ?: emptyMap(),
                            scheduleOverride = scheduleOverride,
                        )
                        DueDayStep.Stepped(outcome, outcome.updatedPie)
                    } else {
                        val outcome = executeAtClose(
                            pies = pies,
                            pie = freshPie,
                            amount = freshSchedule.amount,
                            day = day,
                            closesBySymbol = closesBySymbol,
                            newNextDueDay = cursorAfterDay,
                            anchorDay = freshSchedule.anchorDay,
                            cadence = freshSchedule.cadence,
                        )
                        if (outcome != null) {
                            DueDayStep.Stepped(outcome, outcome.updatedPie)
                        } else {
                            // At least one slice symbol is missing a close on `day`: no buy,
                            // no activity entry — but the day is still consumed, so the
                            // cursor still advances past it (its only persisted trace) so it
                            // is never reconsidered on a later run.
                            val updated = advanceScheduleOnly(
                                pies = pies,
                                pie = freshPie,
                                nextDueDay = cursorAfterDay,
                                amount = freshSchedule.amount,
                                anchorDay = freshSchedule.anchorDay,
                                cadence = freshSchedule.cadence,
                            )
                            DueDayStep.AdvancedOnly(updated)
                        }
                    }
                }
            }

            when (val s = step) {
                is DueDayStep.Stopped -> return PieRunResult(latestPie, outcomes)
                is DueDayStep.Stepped -> {
                    outcomes += s.outcome
                    latestPie = s.pie
                }
                is DueDayStep.AdvancedOnly -> latestPie = s.pie
            }
        }

        return PieRunResult(latestPie, outcomes)
    }

    /** `closes[symbol][day]`, fetched once per symbol and reused across every historical due
     *  day in this run. Skipped entirely when the only due day is `today` — no historical
     *  closes are needed in that case. */
    private suspend fun historicalCloses(
        symbols: List<String>,
        today: String,
        dueDays: List<String>,
    ): Map<String, Map<String, Money>> {
        if (dueDays.none { it != today }) return emptyMap()
        return fetchClosesByDay(market, symbols, calendar)
    }

    /**
     * Executes one historical due day at its close, mirroring [ContributeToPie.core]'s
     * semantics (distribute -> unrounded qty = share/close -> buy -> ledger/activity) but
     * priced from [closesBySymbol] instead of a live quote. [pie]/[pies] are the FRESH
     * snapshot already reloaded by [catchUp]'s per-day lock (no extra load here — this
     * function is only ever called from inside that lock). The schedule cursor is advanced
     * to [newNextDueDay] in the SAME [replace] write as the ledger/activity change, so a
     * throw on a later day can never leave this day partially persisted.
     *
     * @return `null` (no outcome, no mutation at all — cursor included) if any slice symbol
     *   is missing a positive close on [day]. The caller is responsible for still advancing
     *   the cursor past a `null` result, since the day is consumed either way.
     */
    private suspend fun executeAtClose(
        pies: List<Pie>,
        pie: Pie,
        amount: Money,
        day: String,
        closesBySymbol: Map<String, Map<String, Money>>,
        newNextDueDay: String,
        anchorDay: String,
        cadence: PieCadence,
    ): ContributionOutcome? {
        val closes = LinkedHashMap<String, Money>()
        for (slice in pie.slices) {
            val close = closesBySymbol[slice.symbol]?.get(day) ?: return null
            if (close.amount <= BigDecimal.ZERO) return null
            closes[slice.symbol] = close
        }

        val currentValues = LinkedHashMap<String, Money>()
        for (slice in pie.slices) {
            val close = closes[slice.symbol] ?: continue
            val quantity = pie.quantityOf(slice.symbol)
            currentValues[slice.symbol] = Money(quantity * close.amount, close.currencyCode)
        }

        val shares = PieMath.distribute(amount, currentValues, pie.slices)
        val advancedSchedule = ContributionSchedule(amount = amount, cadence = cadence, anchorDay = anchorDay, nextDueDay = newNextDueDay)

        val portfolio = portfolioStore.load() ?: Portfolio.starting()
        if (amount.amount > portfolio.cash.amount) {
            val missed = PieActivityEntry(kind = PieActivityKind.MissedInsufficientCash, day = day, amount = amount)
            val updatedPie = replace(pie, pies, advancedSchedule, pie.ledger, pie.activity + missed)
            return ContributionOutcome.SkippedInsufficientCash(updatedPie)
        }

        var updatedPortfolio = portfolio
        val newLedger = pie.ledger.toMutableList()
        val transactionEpochSeconds = dayToEpochSeconds(day)
        for (slice in pie.slices) {
            val share = shares[slice.symbol] ?: continue
            if (share.amount <= BigDecimal.ZERO) continue
            val close = closes[slice.symbol] ?: continue

            val quantity = share.amount.divide(close.amount, MONEY_MATH)
            val asset = Asset(symbol = slice.symbol, name = slice.symbol, kind = slice.assetKind)
            updatedPortfolio = updatedPortfolio.buying(asset, quantity, close, transactionEpochSeconds, pieId = pie.id)

            val newQuantity = pie.quantityOf(slice.symbol) + quantity
            val index = newLedger.indexOfFirst { it.symbol == slice.symbol }
            if (index >= 0) {
                newLedger[index] = PieLedgerEntry(slice.symbol, newQuantity)
            } else {
                newLedger += PieLedgerEntry(slice.symbol, newQuantity)
            }
        }

        val contributed = PieActivityEntry(kind = PieActivityKind.Contribution, day = day, amount = amount)
        val updatedPie = replace(pie, pies, advancedSchedule, newLedger, pie.activity + contributed)

        portfolioStore.save(updatedPortfolio)
        return ContributionOutcome.Executed(updatedPortfolio, updatedPie)
    }

    /** Advances the schedule cursor past a day that was consumed without a Pie mutation of
     *  its own to piggy-back the cursor write onto — a historical day silently skipped for a
     *  missing close. Persisting the cursor immediately here, rather than deferring it to the
     *  end of the due-day loop, is what makes a mid-run throw resumable without replaying
     *  already-consumed days. */
    private suspend fun advanceScheduleOnly(
        pies: List<Pie>,
        pie: Pie,
        nextDueDay: String,
        amount: Money,
        anchorDay: String,
        cadence: PieCadence,
    ): Pie {
        val schedule = ContributionSchedule(amount = amount, cadence = cadence, anchorDay = anchorDay, nextDueDay = nextDueDay)
        return replace(pie, pies, schedule, pie.ledger, pie.activity)
    }

    /** Rebuilds [pie] with the given overrides via [Pie.create] (rethrows — slices pass
     *  through unchanged so validation always succeeds here), replaces it within [pies], and
     *  persists the full list in one write. `schedule` is always passed explicitly (never
     *  defaulted to `pie.schedule`) so every call site is forced to state, atomically with
     *  whatever else it's changing, exactly where the cursor lands. Otherwise mirrors
     *  [ContributeToPie]'s identical private helper (duplicated rather than shared — a small
     *  pure rebuild-and-save step isn't worth introducing a shared dependency between the two
     *  use cases). */
    private suspend fun replace(
        pie: Pie,
        pies: List<Pie>,
        schedule: ContributionSchedule?,
        ledger: List<PieLedgerEntry>,
        activity: List<PieActivityEntry>,
    ): Pie {
        val updated = Pie.create(
            id = pie.id, name = pie.name, slices = pie.slices, schedule = schedule,
            createdDay = pie.createdDay, ledger = ledger, activity = activity,
        )
        val all = pies.toMutableList()
        val index = all.indexOfFirst { it.id == pie.id }
        if (index >= 0) all[index] = updated else all += updated
        pieStore.save(all)
        return updated
    }

    /** The calendar day immediately before [day], in the same `yyyy-MM-dd` shape (used as
     *  [PieSchedule.dueDays]'/[PieSchedule.nextDueDay]'s lower-bound `afterDay` so the
     *  schedule cursor itself is treated as eligible to be the first due day). `null` on
     *  malformed input. Mirrors [com.aptrade.shared.domain.PieBacktest]'s identical private
     *  `dayBefore` helper (epoch-day arithmetic, no `Calendar` round trip needed). */
    private fun dayBefore(day: String): String? {
        val epochDay = PieSchedule.parseDay(day) ?: return null
        return calendar.dayString(epochDay - 1)
    }

    /** A deterministic UTC-noon instant for [day] ("yyyy-MM-dd"), used only to stamp
     *  historical due-day transactions. UTC noon converts to a market-local (ET, UTC-4/-5)
     *  hour of 7-8 AM — safely inside the same calendar day per [MarketCalendar]'s offset —
     *  so no DST-aware conversion is needed for what is only ever a day-precision timestamp
     *  (nothing reads the intraday portion of a historical catch-up transaction's instant). */
    private fun dayToEpochSeconds(day: String): Long {
        val epochDay = PieSchedule.parseDay(day) ?: return 0L
        return epochDay * 86_400L + 12L * 3_600L
    }
}

/**
 * Manual rebalance: prices a Pie's current ledger against live quotes and derives the trades
 * needed to restore its target allocation ([PieMath.rebalancePlan]), then optionally executes
 * them. Transcribed from Swift `RebalancePie` (`Sources/APTradeApplication/PieUseCases.swift`)
 * AS-BUILT.
 */
class RebalancePie(
    private val pieStore: PieStore,
    private val portfolioStore: PortfolioStore,
    private val market: MarketDataRepository,
    private val portfolioMutex: Mutex,
) {
    /**
     * The orders [execute] would place, priced at live quotes. Read-only — fetches quotes but
     * never touches either store, and takes no lock at all: a preview mutates nothing, so
     * there is nothing for [portfolioMutex] to protect here, and gating it behind the same
     * lock would only make the UI's live preview wait on unrelated mutations (mirrors Swift's
     * identical rationale for leaving `preview` un-serialized).
     */
    suspend fun preview(pieId: String): List<RebalanceOrder> {
        val (_, pie) = loadPieOrThrow(pieId)
        val quotes = fetchQuotes(market, pie.slices.map { it.symbol })
        return PieMath.rebalancePlan(currentValuesFor(pie, quotes), pie.slices)
    }

    /**
     * Executes the previewed orders: all sells first (freeing cash), then all buys, each
     * tagged `pieId`. Sell quantities are clamped to the pie's own ledger quantity for that
     * symbol (a pie only rebalances what it owns) and, defensively, to the portfolio's
     * actually-held quantity (in case the ledger has drifted ahead of a manual sell that
     * [ReconcilePieLedgers] hasn't yet clamped — see that class's doc). The pie's ledger is
     * updated for every filled order and a `rebalance` activity entry — its amount the total
     * value bought (equivalently sold, since orders net to zero) — is appended.
     *
     * Quotes are fetched OUTSIDE [portfolioMutex] (network I/O — see file doc), against the
     * pie's slices as loaded just before this call. The ONE [withLock] block below then
     * RELOADS the pie fresh and RECOMPUTES the rebalance plan against that fresh ledger before
     * trading — never against the pre-lock snapshot — so a concurrent contribution or
     * catch-up landing between the pre-lock load and lock acquisition is never traded against
     * a stale ledger. If a concurrent `SavePie` changed the pie's SLICES (not just its ledger)
     * in that same narrow window, the pre-fetched `quotes` can disagree with the
     * freshly-reloaded slice list; the in-lock recomputation then throws [QuoteError.NotFound]
     * for whichever symbol it can't price — the same documented divergence
     * [ExecuteDueContributions]'s today-quote fetch carries for the identical reason (see its
     * per-day loop's doc comment).
     */
    suspend fun execute(pieId: String, day: String, nowEpochSeconds: Long): Pair<Portfolio, Pie> {
        val (_, pie) = loadPieOrThrow(pieId)
        val quotes = fetchQuotes(market, pie.slices.map { it.symbol })
        return portfolioMutex.withLock {
            executeLocked(pieId, day, nowEpochSeconds, quotes)
        }
    }

    /** The core reload-price-trade-save sequence, run inside [portfolioMutex]'s lock by
     *  [execute] above. [quotes] must already be fetched (see [execute]'s doc comment) — a
     *  symbol among the freshly-reloaded pie's slices missing from [quotes] throws
     *  [QuoteError.NotFound]. */
    private suspend fun executeLocked(
        pieId: String,
        day: String,
        nowEpochSeconds: Long,
        quotes: Map<String, Quote>,
    ): Pair<Portfolio, Pie> {
        val pies = pieStore.load()
        val pie = pies.firstOrNull { it.id == pieId } ?: throw PieError.NotFound
        val orders = PieMath.rebalancePlan(currentValuesFor(pie, quotes), pie.slices)

        var portfolio = portfolioStore.load() ?: Portfolio.starting()
        val ledger = pie.ledger.toMutableList()

        for (order in orders.filter { it.side == RebalanceSide.Sell }) {
            val quote = quotes[order.symbol] ?: throw QuoteError.NotFound
            val rawQuantity = order.amount.amount.divide(quote.price.amount, MONEY_MATH)
            val ledgerQuantity = ledger.firstOrNull { it.symbol == order.symbol }?.quantity ?: BigDecimal.ZERO
            // `heldQuantity` is the portfolio-WIDE position for this symbol, per spec — not
            // this pie's slice of it. If another pie also claims the symbol, this clamp alone
            // can't see that cross-pie contention (only the portfolio total vs. THIS pie's
            // ledger), so a narrow drift window exists between a manual sell and the next
            // `ReconcilePieLedgers` run where two pies could both still believe they own
            // shares the portfolio no longer has. That's the clamp `ReconcilePieLedgers`
            // exists to rebound; this one only guards against this single pie outrunning the
            // portfolio's total holding.
            val heldQuantity = portfolio.positionFor(order.symbol)?.quantity ?: BigDecimal.ZERO
            val quantity = minOf(rawQuantity, ledgerQuantity, heldQuantity)
            if (quantity <= BigDecimal.ZERO) continue

            portfolio = portfolio.selling(order.symbol, quantity, quote.price, nowEpochSeconds, pieId = pieId)
            applyLedgerDelta(-quantity, order.symbol, ledger)
        }

        for (order in orders.filter { it.side == RebalanceSide.Buy }) {
            val quote = quotes[order.symbol] ?: throw QuoteError.NotFound
            val quantity = order.amount.amount.divide(quote.price.amount, MONEY_MATH)
            if (quantity <= BigDecimal.ZERO) continue

            val kind = pie.slices.firstOrNull { it.symbol == order.symbol }?.assetKind ?: AssetKind.Stock
            val asset = Asset(symbol = order.symbol, name = order.symbol, kind = kind)
            portfolio = portfolio.buying(asset, quantity, quote.price, nowEpochSeconds, pieId = pieId)
            applyLedgerDelta(quantity, order.symbol, ledger)
        }

        val tradedAmount = orders.filter { it.side == RebalanceSide.Buy }
            .fold(BigDecimal.ZERO) { acc, order -> acc + order.amount.amount }
        val currencyCode = orders.firstOrNull()?.amount?.currencyCode ?: portfolio.cash.currencyCode
        val rebalanced = PieActivityEntry(
            kind = PieActivityKind.Rebalance, day = day, amount = Money(tradedAmount, currencyCode),
        )
        val updatedPie = replace(pie, pies, pie.schedule, ledger, pie.activity + rebalanced)

        portfolioStore.save(portfolio)
        return portfolio to updatedPie
    }

    /** Loads all pies and the one matching [pieId], throwing [PieError.NotFound] if absent —
     *  shared by [preview] and the pre-lock half of [execute]. */
    private suspend fun loadPieOrThrow(pieId: String): Pair<List<Pie>, Pie> {
        val pies = pieStore.load()
        val pie = pies.firstOrNull { it.id == pieId } ?: throw PieError.NotFound
        return pies to pie
    }

    /** Prices [pie]'s current ledger against [quotes]: `quantity × quote price` per slice.
     *  Throws [QuoteError.NotFound] for any slice symbol missing from [quotes]. */
    private fun currentValuesFor(pie: Pie, quotes: Map<String, Quote>): Map<String, Money> {
        val result = LinkedHashMap<String, Money>()
        for (slice in pie.slices) {
            val quote = quotes[slice.symbol] ?: throw QuoteError.NotFound
            val quantity = pie.quantityOf(slice.symbol)
            result[slice.symbol] = Money(quantity * quote.price.amount, quote.price.currencyCode)
        }
        return result
    }

    /** Increments or decrements [symbol]'s ledger quantity by [delta] in [ledger] (creating
     *  the entry if it doesn't already exist), clamped at zero. Kotlin's [PieLedgerEntry]
     *  carries a raw [BigDecimal] quantity (no `Quantity` value object — see
     *  [PieSlice.targetWeightPP]'s doc comment for the same divergence), so this clamp
     *  reproduces by hand what Swift's `Quantity` init does automatically: an over-large sell
     *  can never drive the ledger negative. */
    private fun applyLedgerDelta(delta: BigDecimal, symbol: String, ledger: MutableList<PieLedgerEntry>) {
        val current = ledger.firstOrNull { it.symbol == symbol }?.quantity ?: BigDecimal.ZERO
        val updated = maxOf(BigDecimal.ZERO, current + delta)
        val index = ledger.indexOfFirst { it.symbol == symbol }
        if (index >= 0) ledger[index] = PieLedgerEntry(symbol, updated) else ledger += PieLedgerEntry(symbol, updated)
    }

    /** Rebuilds [pie] with the given overrides via [Pie.create] (rethrows — slices pass
     *  through unchanged so validation always succeeds here), replaces it within [pies], and
     *  persists the full list. Mirrors [ContributeToPie]'s identical private helper
     *  (duplicated rather than shared — a small pure rebuild-and-save step isn't worth
     *  introducing a shared dependency between the use cases in this file). */
    private suspend fun replace(
        pie: Pie,
        pies: List<Pie>,
        schedule: ContributionSchedule?,
        ledger: List<PieLedgerEntry>,
        activity: List<PieActivityEntry>,
    ): Pie {
        val updated = Pie.create(
            id = pie.id, name = pie.name, slices = pie.slices, schedule = schedule,
            createdDay = pie.createdDay, ledger = ledger, activity = activity,
        )
        val all = pies.toMutableList()
        val index = all.indexOfFirst { it.id == pie.id }
        if (index >= 0) all[index] = updated else all += updated
        pieStore.save(all)
        return updated
    }
}

/**
 * Manual-sell clamp: keeps every Pie's ledger honest against what the portfolio actually
 * holds. A symbol sold manually outside a Pie (e.g. from the portfolio screen) can leave one
 * or more Pies' ledgers claiming more shares than the portfolio still owns; this reconciles
 * every over-claimed symbol back down to the held quantity, recording who lost how much.
 * Transcribed from Swift `ReconcilePieLedgers` AS-BUILT.
 *
 * Store-only — no market data is touched, since reconciliation only compares recorded
 * quantities, never prices. The whole read-clamp-save sequence still runs inside
 * [portfolioMutex]'s lock, since it reads and writes the same [pieStore]/[portfolioStore] a
 * concurrent contribution, rebalance, or catch-up write could otherwise interleave with.
 *
 * "Clock injectable": unlike Swift's `now: @escaping @Sendable () -> Date = { Date() }`
 * constructor default, this Kotlin port follows the established house convention (see
 * [MarketActivityPlanner.plan] / [ExecuteDueContributions.execute]) of taking `nowEpochSeconds`
 * as a plain [Long] parameter on [execute] rather than a wall-clock closure on the
 * constructor — the caller (an `EpochClock.epochSecondsNow()` reader in production, a fixed
 * literal in tests) IS the injected clock. [calendar] stays constructor-injected (like every
 * other pie use case here) since it is stateless and shared across calls.
 */
class ReconcilePieLedgers(
    private val pieStore: PieStore,
    private val portfolioStore: PortfolioStore,
    private val portfolioMutex: Mutex,
    private val calendar: MarketCalendar = MarketCalendar(),
) {
    /**
     * Clamps every pie ledger entry to the actually-held portfolio quantity for that symbol.
     * When multiple Pies claim the same over-subscribed symbol, the clamp is applied
     * largest-ledger-first: smaller claims are preserved in full and the largest claimant
     * absorbs the shortfall (walking to the next-largest if even a fully-zeroed largest
     * claimant isn't enough). Ties break lexicographically by pie id — the lexicographically
     * first id clamps first. Only pies whose ledger actually changed gain a `manualAdjustment`
     * activity entry; saves the full pie list once (skipped entirely if nothing clamped).
     */
    suspend fun execute(nowEpochSeconds: Long): List<Pie> = portfolioMutex.withLock {
        reconcile(nowEpochSeconds)
    }

    private suspend fun reconcile(nowEpochSeconds: Long): List<Pie> {
        val pies = pieStore.load()
        val portfolio = portfolioStore.load() ?: Portfolio.starting()
        val today = calendar.tradingDay(nowEpochSeconds)

        // symbol -> pieId -> current ledger quantity (mutated in place as clamps apply).
        val quantities = LinkedHashMap<String, MutableMap<String, BigDecimal>>()
        val symbols = LinkedHashSet<String>()
        for (pie in pies) {
            for (entry in pie.ledger) {
                if (entry.quantity <= BigDecimal.ZERO) continue
                quantities.getOrPut(entry.symbol) { LinkedHashMap() }[pie.id] = entry.quantity
                symbols += entry.symbol
            }
        }

        val clampedPieIds = mutableSetOf<String>()

        for (symbol in symbols) {
            val claims = quantities[symbol] ?: continue
            val totalClaimed = claims.values.fold(BigDecimal.ZERO) { acc, q -> acc + q }
            val heldQuantity = portfolio.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
            if (totalClaimed <= heldQuantity) continue

            // Smallest claim first (full allocation preserved); ties give priority to the
            // lexicographically LATER pie id here, so the lexicographically FIRST id lands
            // last in this walk — exactly where the remaining budget runs out first, making
            // it the one clamped.
            val ordered = claims.keys.sortedWith(
                compareBy<String> { claims[it] ?: BigDecimal.ZERO }.thenByDescending { it },
            )

            var remaining = heldQuantity
            for (pieId in ordered) {
                val claim = claims[pieId] ?: BigDecimal.ZERO
                if (remaining >= claim) {
                    remaining -= claim
                } else {
                    val clampedQuantity = maxOf(BigDecimal.ZERO, remaining)
                    if (clampedQuantity.compareTo(claim) != 0) {
                        claims[pieId] = clampedQuantity
                        clampedPieIds += pieId
                    }
                    remaining = BigDecimal.ZERO
                }
            }
        }

        if (clampedPieIds.isEmpty()) return pies

        val updatedPies = pies.map { pie ->
            if (pie.id !in clampedPieIds) return@map pie

            val newLedger = pie.ledger.map { entry ->
                val clamped = quantities[entry.symbol]?.get(pie.id)
                if (clamped != null) PieLedgerEntry(entry.symbol, clamped) else entry
            }

            val adjustment = PieActivityEntry(kind = PieActivityKind.ManualAdjustment, day = today, amount = null)
            // `Pie.create` only rejects malformed slices (empty, duplicate, mis-summed
            // weights) — none of which this ledger-only rebuild can trigger, since `slices`
            // passes through unchanged from an already-valid Pie.
            runCatching {
                Pie.create(
                    id = pie.id, name = pie.name, slices = pie.slices, schedule = pie.schedule,
                    createdDay = pie.createdDay, ledger = newLedger, activity = pie.activity + adjustment,
                )
            }.getOrDefault(pie)
        }

        pieStore.save(updatedPies)
        return updatedPies
    }
}

/** [nowEpochSeconds]'s market-local day minus [years] calendar years, day-of-month clamped on
 *  overflow (e.g. Feb 29 -> Feb 28 in a non-leap target year) — mirrors Swift's
 *  `Calendar.current.date(byAdding: DateComponents(year: -years), to: now)`. Kotlin
 *  `commonMain` has no Foundation `Calendar`; duplicates the minimal Hinnant civil-date math
 *  already used privately by [MarketCalendar]/[PieSchedule]/`USMarketHolidays` (each of those
 *  keeps its own private copy of this same algorithm rather than exposing it more broadly —
 *  see [PieSchedule]'s file doc — this follows the same precedent). */
private fun yearsAgoDay(nowEpochSeconds: Long, years: Int, calendar: MarketCalendar): String {
    val localEpochDay = calendar.localEpochDay(nowEpochSeconds)
    val (year, month, day) = civilFromDaysForYearsAgo(localEpochDay)
    val newYear = year - years
    val clampedDay = minOf(day, daysInMonthForYearsAgo(newYear, month))
    return calendar.dayString(daysFromCivilForYearsAgo(newYear, month, clampedDay))
}

private fun daysInMonthForYearsAgo(year: Long, month: Int): Int {
    val thisMonthFirst = daysFromCivilForYearsAgo(year, month, 1)
    val nextMonthFirst = if (month == 12) {
        daysFromCivilForYearsAgo(year + 1, 1, 1)
    } else {
        daysFromCivilForYearsAgo(year, month + 1, 1)
    }
    return (nextMonthFirst - thisMonthFirst).toInt()
}

private fun daysFromCivilForYearsAgo(y0: Long, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = floorDivForYearsAgo(y, 400L)
    val yoe = y - era * 400L
    val mp = if (m > 2) m - 3 else m + 9
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

private fun civilFromDaysForYearsAgo(z0: Long): Triple<Long, Int, Int> {
    val z = z0 + 719_468L
    val era = floorDivForYearsAgo(z, 146_097L)
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = if (m <= 2) y + 1 else y
    return Triple(year, m, d)
}

private fun floorDivForYearsAgo(x: Long, y: Long): Long {
    val q = x / y
    return if ((x xor y) < 0 && q * y != x) q - 1 else q
}

/**
 * Dollar-cost-averaging (DCA) simulation: fetches daily historical closes for a Pie's slices
 * over a given timeframe and runs a backtest ([PieBacktest.dcaBacktest]) to show projected
 * portfolio growth under a scheduled contribution plan. Transcribed from Swift `SimulateDCA`
 * (`Sources/APTradeApplication/PieUseCases.swift`) AS-BUILT.
 *
 * Fetches one year of daily history per slice symbol using
 * [MarketDataRepository.history]/[Timeframe.OneYear] — the longest window
 * [MarketDataRepository]'s port offers (see [Timeframe]; there is no longer value to request).
 * The `years` parameter (1, 3, or 5) determines the backtest window: `startDay = now minus
 * years calendar years`, `endDay = today's trading day`. For `years > 1`, the fetched history
 * may not cover the full span; [PieBacktest.dcaBacktest]'s missing-close semantics handle the
 * gap gracefully — early due days simply skip if their close is absent, and the report shows
 * only what's coverable. A future extension to the history port may lift this limitation; this
 * class does NOT itself widen [MarketDataRepository]'s contract.
 *
 * Lock-free: like [LoadPies], this class never touches [PieStore] or [PortfolioStore] at all
 * — it is a pure read against [MarketDataRepository] plus a pure domain computation, so there
 * is nothing for [portfolioMutex] to protect.
 *
 * **Cancellation divergence from Swift (deliberate):** Swift's `catch { return nil }` catches
 * `CancellationError` exactly like any other failure — `Task` cancellation in Swift is
 * cooperative and checked, not an exception that unwinds a call stack the way Kotlin's
 * [CancellationException] does, so degrading it to `nil` there is harmless. Kotlin's
 * structured concurrency, by contrast, RELIES on [CancellationException] propagating out of
 * every suspend function in a cancelled scope — silently swallowing it here (returning `null`
 * instead of rethrowing) would let this coroutine keep running inside a scope its caller
 * already tore down, exactly the "non-cooperative cancellation" bug
 * [ExecuteDueContributions.execute] already guards against (see its `catch (e:
 * CancellationException) { throw e }` and doc comment) — [FetchEarningsCalendar] establishes
 * the same house convention. So [execute] here rethrows [CancellationException] and degrades
 * every OTHER failure (network error, per-symbol failure, insufficient history) to `null`.
 */
class SimulateDCA(
    private val market: MarketDataRepository,
    private val calendar: MarketCalendar,
) {
    /**
     * Fetches daily history for each slice symbol over [years] and runs
     * [PieBacktest.dcaBacktest]. Returns `null` if insufficient history prevents any
     * execution (every due day missing a close) or on any network failure.
     *
     * @param slices Target allocation for the Pie being simulated.
     * @param amount Contribution amount on each due day.
     * @param cadence Contribution frequency.
     * @param years Backtest window in calendar years (1, 3, or 5). `years <= 0` returns `null`.
     * @param nowEpochSeconds Reference instant for computing the window and today's trading day.
     * @return A backtest report, or `null` if no due day is executable or any network failure
     *   occurs. [CancellationException] is NEVER degraded to `null` — see the class doc.
     */
    suspend fun execute(
        slices: List<PieSlice>,
        amount: Money,
        cadence: PieCadence,
        years: Int,
        nowEpochSeconds: Long,
    ): BacktestReport? {
        if (years <= 0) return null
        return try {
            val startDay = yearsAgoDay(nowEpochSeconds, years, calendar)
            val endDay = calendar.tradingDay(nowEpochSeconds)

            val dailyCloses = fetchClosesByDay(market, slices.map { it.symbol }, calendar)

            PieBacktest.dcaBacktest(
                slices = slices,
                amount = amount,
                cadence = cadence,
                startDay = startDay,
                endDay = endDay,
                dailyCloses = dailyCloses,
                calendar = calendar,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
