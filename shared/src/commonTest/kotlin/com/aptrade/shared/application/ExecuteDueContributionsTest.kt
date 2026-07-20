package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Transcribed from `Tests/APTradeApplicationTests/ExecuteDueContributionsTests.swift`. */
class ExecuteDueContributionsTest {

    private val sliceA = PieSlice(symbol = "A", assetKind = AssetKind.Stock, targetWeightPP = BigDecimal.parseString("50"))
    private val sliceB = PieSlice(symbol = "B", assetKind = AssetKind.Stock, targetWeightPP = BigDecimal.parseString("50"))
    private val calendar = MarketCalendar()

    private fun usd(s: String): Money = Money(BigDecimal.parseString(s), "USD")

    /** A UTC-noon instant for [day] -- always resolves, via [MarketCalendar]'s ET offset, to
     *  a market-local hour safely inside the same calendar day (see the production
     *  `dayToEpochSeconds` helper's identical rationale). Used as `now` for tests that don't
     *  need today's due day itself. */
    private fun date(day: String): Long {
        val epochDay = PieSchedule.parseDay(day) ?: 0L
        return epochDay * 86_400L + 12L * 3_600L
    }

    private fun makeRepo(closesA: Map<String, String>, closesB: Map<String, String>): FakeCatchUpRepo {
        val repo = FakeCatchUpRepo()
        repo.dailyClosesBySymbol["A"] = closesA.mapValues { usd(it.value) }
        repo.dailyClosesBySymbol["B"] = closesB.mapValues { usd(it.value) }
        return repo
    }

    // MARK: (a) Two missed monthly days execute in ascending order, each at its own close

    @Test
    fun twoMissedMonthlyDaysExecuteInAscendingOrderAtEachDaysClose() = runTest {
        val schedule = ContributionSchedule(usd("100"), PieCadence.Monthly, "2025-04-01", "2025-04-01")
        val pie = Pie.create(id = "pie-a", name = "DCA Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = CatchUpFakePieStore(listOf(pie))
        val portfolioStore = CatchUpFakePortfolioStore(Portfolio.starting())
        val repo = makeRepo(
            closesA = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
            closesB = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
        )

        val sut = ExecuteDueContributions(pieStore, portfolioStore, repo, calendar, Mutex())
        val results = sut.execute(date("2025-05-15"))

        assertEquals(1, results.size)
        val (resultPie, outcomes) = results[0].pie to results[0].outcomes
        assertEquals(2, outcomes.size)

        val day1 = outcomes[0] as? ContributionOutcome.Executed ?: fail("day 1 expected Executed")
        val day2 = outcomes[1] as? ContributionOutcome.Executed ?: fail("day 2 expected Executed")

        // Day 1 (April 1) at close $10: $50 -> A (5 sh), $50 -> B (5 sh).
        assertEquals(BigDecimal.parseString("5"), day1.portfolio.positionFor("A")?.quantity)
        assertEquals(BigDecimal.parseString("5"), day1.portfolio.positionFor("B")?.quantity)
        assertEquals(usd("99900"), day1.portfolio.cash)

        // Day 2 (May 1) at close $20: $50 -> A (2.5 sh more), $50 -> B (2.5 sh more).
        assertEquals(BigDecimal.parseString("7.5"), day2.portfolio.positionFor("A")?.quantity)
        assertEquals(BigDecimal.parseString("7.5"), day2.portfolio.positionFor("B")?.quantity)
        assertEquals(usd("99800"), day2.portfolio.cash)

        // Final persisted state reflects both contributions.
        assertEquals(usd("99800"), portfolioStore.portfolio.cash)
        assertEquals(BigDecimal.parseString("7.5"), resultPie.quantityOf("A"))
        assertEquals(BigDecimal.parseString("7.5"), resultPie.quantityOf("B"))
        assertEquals(
            listOf("2025-04-01", "2025-05-01"),
            resultPie.activity.filter { it.kind == PieActivityKind.Contribution }.map { it.day },
        )
    }

    // MARK: (b) nextDueDay advanced to the first due day after now

    @Test
    fun nextDueDayAdvancedToFirstDueDayAfterNow() = runTest {
        val schedule = ContributionSchedule(usd("100"), PieCadence.Monthly, "2025-04-01", "2025-04-01")
        val pie = Pie.create(id = "pie-b", name = "DCA Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = CatchUpFakePieStore(listOf(pie))
        val portfolioStore = CatchUpFakePortfolioStore(Portfolio.starting())
        val repo = makeRepo(
            closesA = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
            closesB = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
        )

        val sut = ExecuteDueContributions(pieStore, portfolioStore, repo, calendar, Mutex())
        val results = sut.execute(date("2025-05-15"))

        // June 1 2025 is a Sunday -> rolls to Monday June 2.
        assertEquals("2025-06-02", results[0].pie.schedule?.nextDueDay)
        assertEquals("2025-06-02", pieStore.pies.first().schedule?.nextDueDay)
    }

    // MARK: (c) Insufficient cash on the second day -> first executes, second recorded missed

    @Test
    fun insufficientCashOnSecondDayFirstExecutesSecondRecordedMissed() = runTest {
        val schedule = ContributionSchedule(usd("100"), PieCadence.Monthly, "2025-04-01", "2025-04-01")
        val pie = Pie.create(id = "pie-c", name = "DCA Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = CatchUpFakePieStore(listOf(pie))
        // Exactly enough cash for one $100 contribution, not two.
        val portfolioStore = CatchUpFakePortfolioStore(Portfolio(usd("150")))
        val repo = makeRepo(
            closesA = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
            closesB = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
        )

        val sut = ExecuteDueContributions(pieStore, portfolioStore, repo, calendar, Mutex())
        val results = sut.execute(date("2025-05-15"))

        assertEquals(1, results.size)
        val (resultPie, outcomes) = results[0].pie to results[0].outcomes
        assertEquals(2, outcomes.size)

        assertTrue(outcomes[0] is ContributionOutcome.Executed, "day 1 expected Executed")
        val missed = outcomes[1] as? ContributionOutcome.SkippedInsufficientCash ?: fail("day 2 expected SkippedInsufficientCash")

        assertEquals(usd("50"), portfolioStore.portfolio.cash)
        assertEquals(BigDecimal.parseString("5"), resultPie.quantityOf("A")) // only day 1's buy
        assertEquals(BigDecimal.parseString("5"), resultPie.quantityOf("B"))
        assertEquals(
            listOf(PieActivityKind.Contribution, PieActivityKind.MissedInsufficientCash),
            resultPie.activity.map { it.kind },
        )
        assertEquals("2025-05-01", missed.pie.activity.last().day)
    }

    // MARK: (d) Missing close for one symbol on day 1 -> day 1 skipped silently, day 2 executes

    @Test
    fun missingCloseForOneSymbolOnDay1SkipsDay1SilentlyExecutesDay2() = runTest {
        val schedule = ContributionSchedule(usd("100"), PieCadence.Monthly, "2025-04-01", "2025-04-01")
        val pie = Pie.create(id = "pie-d", name = "DCA Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = CatchUpFakePieStore(listOf(pie))
        val portfolioStore = CatchUpFakePortfolioStore(Portfolio.starting())
        // B has no close on April 1 -> day 1 must be skipped entirely.
        val repo = makeRepo(
            closesA = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
            closesB = mapOf("2025-05-01" to "20"),
        )

        val sut = ExecuteDueContributions(pieStore, portfolioStore, repo, calendar, Mutex())
        val results = sut.execute(date("2025-05-15"))

        assertEquals(1, results.size)
        val (resultPie, outcomes) = results[0].pie to results[0].outcomes

        // Only day 2's outcome is recorded -- day 1 leaves no trace at all.
        assertEquals(1, outcomes.size)
        assertTrue(outcomes[0] is ContributionOutcome.Executed, "day 2 expected Executed")

        assertEquals(1, resultPie.activity.size)
        assertEquals("2025-05-01", resultPie.activity.first().day)
        // Day 2 ($100 @ $20 close, 50/50, no prior holdings): 2.5 shares each.
        assertEquals(BigDecimal.parseString("2.5"), resultPie.quantityOf("A"))
        assertEquals(BigDecimal.parseString("2.5"), resultPie.quantityOf("B"))
        assertEquals(usd("99900"), portfolioStore.portfolio.cash)
    }

    // MARK: (e) Pies without schedules are untouched

    @Test
    fun pieWithoutScheduleUntouched() = runTest {
        val pie = Pie.create(id = "pie-e", name = "No Schedule Pie", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = CatchUpFakePieStore(listOf(pie))
        val portfolioStore = CatchUpFakePortfolioStore(Portfolio.starting())
        val repo = makeRepo(closesA = emptyMap(), closesB = emptyMap())

        val sut = ExecuteDueContributions(pieStore, portfolioStore, repo, calendar, Mutex())
        val results = sut.execute(date("2025-05-15"))

        assertTrue(results.isEmpty())
        assertEquals(listOf(pie), pieStore.pies)
        assertEquals(Portfolio.starting(), portfolioStore.portfolio)
        assertEquals(0, portfolioStore.saveCallCount)
    }

    // MARK: (regression) A throw on today's live quote, after historical days already
    // executed, must not leave the cursor behind them -- a retry (with the same failure)
    // must not replay the already-executed historical days.

    @Test
    fun todayLiveQuoteThrowsAfterHistoricalDaysCursorAdvancesPastThemNoReplayOnRetry() = runTest {
        val schedule = ContributionSchedule(usd("100"), PieCadence.Monthly, "2025-04-01", "2025-04-01")
        val pie = Pie.create(id = "pie-f", name = "DCA Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = CatchUpFakePieStore(listOf(pie))
        val portfolioStore = CatchUpFakePortfolioStore(Portfolio.starting())
        val repo = makeRepo(
            closesA = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
            closesB = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
        )
        // June 1 2025 is a Sunday -> rolls to Monday June 2, which is due given the April 1
        // cursor and monthly cadence. repo.quotesBySymbol is left empty, so the live quote
        // for today throws (QuoteError.NotFound) once the two historical days ahead of it
        // have already executed.
        val today = "2025-06-02"

        val sut = ExecuteDueContributions(pieStore, portfolioStore, repo, calendar, Mutex())

        // Run 1: April 1 and May 1 execute at their historical closes; today's live quote
        // throws, degrading this Pie's outcomes to empty -- but the two historical
        // executions and their cursor advances must already be persisted.
        val results1 = sut.execute(date(today))
        assertEquals(1, results1.size)
        assertEquals(0, results1[0].outcomes.size, "today's throw degrades outcomes to empty")
        assertEquals(usd("99800"), portfolioStore.portfolio.cash, "both historical days spent cash")
        assertEquals(
            listOf("2025-04-01", "2025-05-01"),
            pieStore.pies.first().activity.filter { it.kind == PieActivityKind.Contribution }.map { it.day },
        )
        assertEquals(today, pieStore.pies.first().schedule?.nextDueDay, "cursor sits at today -- NOT past it -- since today itself never consumed")
        val cashAfterRun1 = portfolioStore.portfolio.cash
        val activityAfterRun1 = pieStore.pies.first().activity.size

        // Run 2: identical failure. The regression: if the cursor had stayed at
        // "2025-04-01" (the bug), this run would recompute April 1 and May 1 as due again
        // and re-execute them (double-buy/double-spend). With the incremental cursor, only
        // today is due -- and it fails the same way, with zero replay.
        val results2 = sut.execute(date(today))
        assertEquals(1, results2.size)
        assertEquals(0, results2[0].outcomes.size)
        assertEquals(cashAfterRun1, portfolioStore.portfolio.cash, "no re-execution of already-consumed days")
        assertEquals(activityAfterRun1, pieStore.pies.first().activity.size)
        assertEquals(today, pieStore.pies.first().schedule?.nextDueDay)

        // Run 3: the live quote now succeeds -- only today's due day executes.
        repo.quotesBySymbol["A"] = Quote("A", usd("30"), usd("30"), 0.0)
        repo.quotesBySymbol["B"] = Quote("B", usd("30"), usd("30"), 0.0)
        val results3 = sut.execute(date(today))
        assertEquals(1, results3.size)
        assertEquals(1, results3[0].outcomes.size)
        assertTrue(results3[0].outcomes[0] is ContributionOutcome.Executed, "today expected Executed")
        assertEquals(
            listOf("2025-04-01", "2025-05-01", today),
            pieStore.pies.first().activity.filter { it.kind == PieActivityKind.Contribution }.map { it.day },
            "only today's day newly executed",
        )
        assertTrue((pieStore.pies.first().schedule?.nextDueDay ?: "") > today, "cursor now advances past today")
    }

    // MARK: (regression) Monthly cadence must not drift once a clamped step lands the cursor
    // on a shorter day-of-month -- stepping must always be relative to the schedule's fixed
    // original anchor, never the moving cursor.

    @Test
    fun monthlyCadenceStepsFromFixedAnchorNotMovingCursorNoDrift() = runTest {
        // Anchored on the 31st. Jan 31 (Saturday) rolls to Feb 2; that due day has already
        // executed by the time this test starts, and its cadence sibling -- anchor + 1
        // month = Feb 28 (clamped; also a Saturday) -- rolled to Monday Mar 2, is the
        // current, not-yet-consumed cursor.
        val schedule = ContributionSchedule(usd("100"), PieCadence.Monthly, "2026-01-31", "2026-03-02")
        val pie = Pie.create(id = "pie-g", name = "DCA Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2026-01-01")
        val pieStore = CatchUpFakePieStore(listOf(pie))
        val portfolioStore = CatchUpFakePortfolioStore(Portfolio.starting())
        // The bug under test: re-anchoring stepping on the moving cursor (Feb 28, clamped
        // from the 31st) would step by whole months from THAT day, losing the original
        // 31st entirely (Mar 28, Apr 28, ... forever) instead of re-deriving each step
        // fresh from the fixed Jan 31 anchor (-> Mar 31, Apr 30, ...).
        val repo = makeRepo(
            closesA = mapOf("2026-03-02" to "10", "2026-03-31" to "20"),
            closesB = mapOf("2026-03-02" to "10", "2026-03-31" to "20"),
        )

        val sut = ExecuteDueContributions(pieStore, portfolioStore, repo, calendar, Mutex())
        val results = sut.execute(date("2026-04-01"))

        assertEquals(1, results.size)
        val (resultPie, outcomes) = results[0].pie to results[0].outcomes
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it is ContributionOutcome.Executed })

        val contributionDays = resultPie.activity.filter { it.kind == PieActivityKind.Contribution }.map { it.day }
        assertEquals(
            listOf("2026-03-02", "2026-03-31"), contributionDays,
            "the second execution must be anchor-derived Mar 31, not cursor-derived Mar 28",
        )
        assertTrue("2026-03-28" !in contributionDays)

        assertEquals(usd("99800"), portfolioStore.portfolio.cash)
        // Correctness continues to hold going forward too: Jan 31 + 3 months = Apr 30 (no
        // clamp needed), re-derived fresh from the fixed anchor.
        assertEquals("2026-01-31", resultPie.schedule?.anchorDay, "anchor itself never changes")
        assertEquals("2026-04-30", resultPie.schedule?.nextDueDay)
    }

    // MARK: (F1/F4 regression) A wizard-style SavePie "racing" a multi-day catch-up must not
    // clobber the day actively in flight, and every SUBSEQUENT day in the same catch-up run
    // must pick up the saved schedule. Modeled deterministically by having the fake PieStore
    // apply the "concurrent save" the moment it observes day 1's contribution already
    // persisted -- exactly the reload-per-day semantics `catchUp` is required to have.

    @Test
    fun savePieRacesMultiDayCatchUpInFlightDayUnaffectedSubsequentDaysUseSavedSchedule() = runTest {
        val schedule = ContributionSchedule(usd("50"), PieCadence.Monthly, "2025-04-01", "2025-04-01")
        val pie = Pie.create(id = "pie-race", name = "Race Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = InterleavedSavePieStore(listOf(pie), "pie-race", "2025-04-01") { racedPie ->
            val existing = racedPie.schedule
            if (existing == null) {
                racedPie
            } else {
                // Models a wizard-style amount-only edit (cadence unchanged -> anchor/cursor
                // preserved) landing the instant day 1 (April 1) has been consumed.
                val updatedSchedule = ContributionSchedule(usd("999"), existing.cadence, existing.anchorDay, existing.nextDueDay)
                runCatching {
                    Pie.create(
                        id = racedPie.id, name = racedPie.name, slices = racedPie.slices, schedule = updatedSchedule,
                        createdDay = racedPie.createdDay, ledger = racedPie.ledger, activity = racedPie.activity,
                    )
                }.getOrDefault(racedPie)
            }
        }
        val portfolioStore = CatchUpFakePortfolioStore(Portfolio.starting())
        val repo = makeRepo(
            closesA = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
            closesB = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
        )

        val sut = ExecuteDueContributions(pieStore, portfolioStore, repo, calendar, Mutex())
        val results = sut.execute(date("2025-05-15"))

        assertEquals(1, results.size)
        val (resultPie, outcomes) = results[0].pie to results[0].outcomes
        assertEquals(2, outcomes.size)

        // Day 1 (April 1): the day actively in flight when the "save" lands must not be
        // clobbered -- it executes at the ORIGINAL $50 amount.
        assertTrue(outcomes[0] is ContributionOutcome.Executed, "day 1 expected Executed")
        assertEquals(PieActivityKind.Contribution, resultPie.activity.first().kind)
        assertEquals(usd("50"), resultPie.activity.first().amount, "the in-flight day must use its own fresh snapshot, not the racing save")

        // Day 2 (May 1): a SUBSEQUENT day in the same run must pick up the saved schedule.
        assertTrue(outcomes[1] is ContributionOutcome.Executed, "day 2 expected Executed")
        assertEquals(PieActivityKind.Contribution, resultPie.activity.last().kind)
        assertEquals(usd("999"), resultPie.activity.last().amount, "subsequent days must reload and use the SAVED schedule")

        assertEquals(listOf(usd("50"), usd("999")), resultPie.activity.map { it.amount })
    }

    // MARK: (Task 8 carry-over A) A wizard-style SavePie racing a multi-day catch-up through
    // ONE shared portfolioMutex must never land INSIDE a day's critical section -- only
    // BETWEEN days. Unlike the deterministic simulated-save test above (which pins that a
    // SUBSEQUENT day picks up an already-landed save), this test proves the mutex itself
    // genuinely serializes the two callers, in the house controllable-suspension style (a
    // store whose FIRST save() call parks on a gate) -- mirrors
    // `PortfolioUseCasesTest.racingBuyAndSellSharingOneMutexBothLandNoLostUpdate` and
    // `ContributeToPieTest.contributionRacingManualBuyThroughSharedMutexBothLandNoLostUpdate`.

    @Test
    fun savePieRacesMultiDayCatchUpThroughSharedMutexCannotInterleaveInsideADaysCriticalSection() = runTest {
        val schedule = ContributionSchedule(usd("50"), PieCadence.Monthly, "2025-04-01", "2025-04-01")
        val pie = Pie.create(id = "pie-race", name = "Race Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val otherPie = Pie.create(id = "pie-other", name = "Other Pie", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = GatedPieStore(listOf(pie))
        val portfolioStore = CatchUpFakePortfolioStore(Portfolio.starting())
        val repo = makeRepo(
            closesA = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
            closesB = mapOf("2025-04-01" to "10", "2025-05-01" to "20"),
        )
        val sharedMutex = Mutex()

        val sut = ExecuteDueContributions(pieStore, portfolioStore, repo, calendar, sharedMutex)
        val savePie = SavePie(pieStore, sharedMutex)

        // Day 1 (April 1, NOT today) is priced from `closesBySymbol` -- no live-quote fetch,
        // no other real suspension point before the day's `portfolioMutex.withLock` block
        // calls `pieStore.save()` and hangs on the gate, still holding the lock.
        val catchUpJob = launch { sut.execute(date("2025-05-15")) }
        runCurrent() // day 1's critical section: loaded, computed, now hangs inside pieStore.save()

        // SavePie attempts to enter the SAME mutex the catch-up's day-1 critical section still
        // holds. It cannot proceed to its own load/save until that section's save() completes.
        val saveJob = launch { savePie.execute(otherPie) }
        runCurrent() // SavePie is parked waiting on the shared mutex -- it cannot have run yet

        assertEquals(0, pieStore.saveCount, "neither day 1's in-flight save nor SavePie's own save has completed")
        assertEquals(1, pieStore.pies.size, "SavePie must not have inserted otherPie yet -- still blocked behind day 1's critical section")

        pieStore.firstSaveGate.complete(Unit) // release day 1's save
        runCurrent()

        catchUpJob.join()
        saveJob.join()

        // Both landed, with no lost update: day 1 AND day 2's contributions persisted (proving
        // catch-up itself completed normally after the mutex was released), and the racing
        // SavePie's insert also landed -- proving the shared mutex genuinely serialized
        // SavePie against the in-flight catch-up rather than letting it race past mid-day.
        assertTrue(pieStore.pies.any { it.id == "pie-other" }, "the racing SavePie's insert must have landed")
        val racedPie = pieStore.pies.first { it.id == "pie-race" }
        assertEquals(2, racedPie.activity.count { it.kind == PieActivityKind.Contribution }, "both due days must still have executed")
    }
}

// -- Shared fakes for this file --

private class CatchUpFakePieStore(initial: List<Pie> = emptyList()) : PieStore {
    var pies: List<Pie> = initial
    override suspend fun load(): List<Pie> = pies
    override suspend fun save(pies: List<Pie>) {
        this.pies = pies
    }
}

/** A [PieStore] whose FIRST [save] call suspends on [firstSaveGate] until the test releases
 *  it, so a test can force two coroutines to interleave -- mirrors `GatedPortfolioStore` in
 *  `PortfolioUseCasesTest` and `ContribGatedPortfolioStore` in `ContributeToPieTest` (see
 *  either's doc comment for the full rationale). Gating is keyed by CALL ORDER
 *  (`callIndex`), not "count of saves that have completed". */
private class GatedPieStore(initial: List<Pie>) : PieStore {
    var pies: List<Pie> = initial
        private set
    var saveCount = 0
        private set
    val firstSaveGate = CompletableDeferred<Unit>()
    private var callIndex = 0

    override suspend fun load(): List<Pie> = pies
    override suspend fun save(pies: List<Pie>) {
        val myIndex = callIndex++
        if (myIndex == 0) firstSaveGate.await()
        this.pies = pies
        saveCount++
    }
}

private class CatchUpFakePortfolioStore(initial: Portfolio) : PortfolioStore {
    var portfolio: Portfolio = initial
    var saveCallCount = 0
        private set

    override suspend fun load(): Portfolio = portfolio
    override suspend fun save(portfolio: Portfolio) {
        this.portfolio = portfolio
        saveCallCount += 1
    }
}

/** Fake market data source supporting both live quotes (for today's due day) and a canned
 *  daily-close table (for historical due days), indexed by `yyyy-MM-dd` day string exactly
 *  as [ExecuteDueContributions] indexes [MarketDataRepository.history]. */
private class FakeCatchUpRepo : MarketDataRepository {
    val quotesBySymbol: MutableMap<String, Quote> = mutableMapOf()

    /** dailyClosesBySymbol[symbol][day] = close */
    val dailyClosesBySymbol: MutableMap<String, Map<String, Money>> = mutableMapOf()

    override suspend fun quotes(symbols: List<String>): List<Quote> = symbols.mapNotNull { quotesBySymbol[it] }

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
        val closes = dailyClosesBySymbol[symbol] ?: emptyMap()
        return closes.mapNotNull { (day, close) ->
            val epochDay = PieSchedule.parseDay(day) ?: return@mapNotNull null
            PricePoint(epochSeconds = epochDay * 86_400L + 12L * 3_600L, close = close)
        }
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
    override suspend fun search(query: String): List<Asset> = emptyList()
}

/** Simulates a wizard-style `SavePie` landing exactly once the day it "races" against has
 *  already been persisted -- deterministic (no real thread race needed) while still
 *  exercising the same reload-per-day contract a genuine concurrent save would rely on.
 *  [load] inspects whatever the store currently holds (post any REAL [save] calls from the
 *  catch-up under test) and, the first time it sees [triggerDay]'s contribution already
 *  recorded for [pieId], applies [simulateSave] and keeps that pie "saved" from then on. */
private class InterleavedSavePieStore(
    initialPies: List<Pie>,
    private val pieId: String,
    private val triggerDay: String,
    private val simulateSave: (Pie) -> Pie,
) : PieStore {
    private var pies: List<Pie> = initialPies
    private var applied = false

    override suspend fun load(): List<Pie> {
        if (!applied && pies.firstOrNull { it.id == pieId }?.activity?.any { it.day == triggerDay } == true) {
            applied = true
            pies = pies.map { if (it.id == pieId) simulateSave(it) else it }
        }
        return pies
    }

    override suspend fun save(pies: List<Pie>) {
        this.pies = pies
    }
}
