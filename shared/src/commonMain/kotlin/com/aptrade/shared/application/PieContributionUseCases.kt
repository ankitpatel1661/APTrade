package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityEntry
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieError
import com.aptrade.shared.domain.PieLedgerEntry
import com.aptrade.shared.domain.PieMath
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Investment Plans (Pies): turning scheduled/manual cash contributions into self-balancing
 * buys across a pie's slices. Transcribed from `Sources/APTradeApplication/PieUseCases.swift`
 * (the shipped M7.1 Swift/macOS reference) AS-BUILT — [ContributeToPie] and
 * [ExecuteDueContributions] below, plus their shared helpers.
 *
 * KOTLIN MUTEX ADAPTATION (Global Constraint #8): Swift's `ContributeToPie`/
 * `ExecuteDueContributions` run their ENTIRE load-modify-save sequence — including the quote
 * fetch — inside `TradeSerializer.run`. Kotlin's shared core instead follows [BuyAsset]'s
 * established contract: network I/O (quote/history fetches) stays OUTSIDE [Mutex.withLock];
 * only the state that depends on a *loaded* snapshot runs inside the lock. The one documented
 * exception is per-day catch-up PRICING inside [ExecuteDueContributions] — the distribute
 * computation itself (not the network fetch feeding it) runs inside that day's lock, mirroring
 * Swift's per-day critical-section shape so a concurrent save can interleave BETWEEN days but
 * never WITHIN one.
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
