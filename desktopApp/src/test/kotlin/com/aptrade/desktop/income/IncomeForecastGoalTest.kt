package com.aptrade.desktop.income

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.LoadGoals
import com.aptrade.shared.application.RemoveGoal
import com.aptrade.shared.application.SaveGoal
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalMath
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioGoal
import com.aptrade.shared.domain.Quote
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M11.2 Task 11. Forecast, dividend calendar, income goal, and the DRIP-toggle wire.
 *  [FakePortfolioStore]/[MemoryGoalStore]/`usd`/`qty` are the file-level helpers shared with
 *  [IncomeViewModelTest] -- see `IncomeTestSupport.kt`'s KDoc. */
class IncomeForecastGoalTest {
    private val day = 86_400L
    private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)

    /** 2026-07-20T12:00:00Z — the same fixed "now" IncomeViewModelTest uses. */
    private val now = 1_784_548_800L

    /** Twelve quarterly $0.25 payments — three years of flat history, cadence inferable. */
    private fun events(): List<DividendEvent> =
        (0 until 12).map { i -> DividendEvent("AAPL", now - (11 - i) * 91 * day, usd("0.25")) }

    private class Fixture(
        val viewModel: IncomeViewModel,
        val goals: MemoryGoalStore,
    )

    private fun fixture(
        scope: CoroutineScope,
        drip: Boolean = false,
        goals: MemoryGoalStore = MemoryGoalStore(),
        shares: String = "100",
        quotePrice: String? = "150",
    ): Fixture {
        val portfolio = Portfolio.starting(usd("50000"))
            .buying(aapl, qty(shares), usd("50"), now - 800 * day, "txn-1")
        val market = FakeMarketDataRepository()
        market.quotesImpl = { symbols ->
            if (quotePrice == null) emptyList()
            else symbols.map { Quote(it, usd(quotePrice), usd(quotePrice), 0.0) }
        }
        market.dividendEventsImpl = { _, _ -> events() }
        val vm = IncomeViewModel(
            portfolioStore = FakePortfolioStore(portfolio),
            marketDataRepository = market,
            scope = scope,
            nowEpochSeconds = { now },
            loadGoals = LoadGoals(goals),
            saveGoal = SaveGoal(goals),
            removeGoal = RemoveGoal(goals),
            isDripEnabled = { drip },
        )
        return Fixture(vm, goals)
    }

    // MARK: forecast

    @Test
    fun loadPublishesAForecastOfTheSelectedHorizonLength() = runTest {
        val f = fixture(this)
        f.viewModel.load(); runCurrent()
        assertEquals(ForecastHorizon.Ten, f.viewModel.state.value.horizon)
        assertEquals(10, f.viewModel.state.value.forecast.size)
        assertTrue(f.viewModel.state.value.hasForecastIncome)
    }

    @Test
    fun changingTheHorizonRebuildsWithoutAReload() = runTest {
        val f = fixture(this)
        f.viewModel.load(); runCurrent()
        f.viewModel.setHorizon(ForecastHorizon.Thirty); runCurrent()
        assertEquals(30, f.viewModel.state.value.forecast.size)
    }

    /** BINDING (carry-notes §1.1). The DRIP forecast must compound at the QUOTED $150, not the
     *  $50 cost basis — pinned by comparing against a run with no quotes at all. */
    @Test
    fun dripCompoundsAtTheQuotedPriceNotCostBasis() = runTest {
        val quoted = fixture(this, drip = true)
        quoted.viewModel.load(); runCurrent()
        val costBasisOnly = fixture(this, drip = true, quotePrice = null)
        costBasisOnly.viewModel.load(); runCurrent()
        val quotedYear10 = quoted.viewModel.state.value.forecast.last().income.amount
        val costBasisYear10 = costBasisOnly.viewModel.state.value.forecast.last().income.amount
        assertTrue(quotedYear10 < costBasisYear10, "cost-basis DRIP must overstate the quoted run")
    }

    /** BINDING (review Finding 2). A total quote-fetch failure makes DRIP silently reinvest every
     *  symbol at cost basis inside `incomeForecast` -- the same overstatement mechanism
     *  [dripCompoundsAtTheQuotedPriceNotCostBasis] pins, but reached legitimately (a real fetch
     *  failure) rather than by a dropped argument. `hasForecastIncome` alone can't tell a caller
     *  this happened -- the forecast is still populated, just wrong -- so `state` must carry its
     *  own signal for Task 12 to caption the chart with. */
    @Test
    fun aTotalQuoteFetchFailureFlagsTheForecastPricesAsEstimated() = runTest {
        val f = fixture(this, drip = true, quotePrice = null)
        f.viewModel.load(); runCurrent()
        assertTrue(f.viewModel.state.value.forecastPricesAreEstimated)
    }

    /** The mirror case: a real quote for every forecasted holding means DRIP reinvests at the
     *  quoted price everywhere, so there is nothing to caption. */
    @Test
    fun aQuoteForEveryForecastedHoldingLeavesTheFlagFalse() = runTest {
        val f = fixture(this, drip = true, quotePrice = "150")
        f.viewModel.load(); runCurrent()
        assertFalse(f.viewModel.state.value.forecastPricesAreEstimated)
    }

    /** BINDING (carry-notes §2.2). Flipping DRIP must rebuild the chart AND refresh the ETA.
     *
     *  DISCRIMINATION (review Finding 1). The original fixture targeted $50000 against a curve
     *  whose year-30 value tops out at ~$159 REGARDLESS of DRIP, so `beforeProjection` was always
     *  [GoalProjection.BeyondHorizon] and the assertion's `|| beforeProjection is BeyondHorizon`
     *  disjunct made it pass unconditionally -- deleting `rebuildForecast()`'s trailing
     *  `refreshGoalProjection()` call left this test green. Retargeted to $130: with DRIP OFF this
     *  fixture's per-share rate is genuinely flat (0% measured growth -- events() is an
     *  exact-quarterly, flat-$0.25 3-year history) and no shares are reinvested, so income sits at
     *  exactly $125/yr forever; current ($125) never reaches $130, so the PRE-flip projection is
     *  [GoalProjection.NotOnTrack]. With DRIP ON the same $125/yr compounds via reinvestment at
     *  the $150 quoted price and crosses $130 at year 6 (year 5 = $129.22, year 6 = $130.30 --
     *  the exact numbers [theGoalEtaIsIndependentOfTheChartHorizon] derives and pins for this
     *  IDENTICAL fixture), so the POST-flip projection is [GoalProjection.Years]\(6.0\) -- a
     *  DIFFERENT sealed-class case, not merely a different number, so no disjunction is needed to
     *  express "changed." Verified to fail (RED) with `refreshGoalProjection()`'s call inside
     *  `rebuildForecast()` removed (reporting `NotOnTrack`, i.e. stale), then pass (GREEN) once
     *  restored (reporting `Years(6.0)`); see the task report's RED/GREEN transcript. */
    @Test
    fun dripDidChangeRebuildsTheForecastAndRefreshesTheGoalProjection() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("130"), 1L)))
        val f = fixture(this, drip = false, goals = goals)
        f.viewModel.load(); runCurrent()
        val before = f.viewModel.state.value.forecast.last().income.amount
        val beforeProjection = f.viewModel.state.value.incomeGoal!!.projection
        assertEquals(GoalProjection.NotOnTrack, beforeProjection, "sanity: DRIP off never reaches $130")

        f.viewModel.dripDidChange(enabled = true); runCurrent()
        val after = f.viewModel.state.value.forecast.last().income.amount
        assertTrue(after > before, "DRIP on must raise the far-year forecast")
        // The projection is recomputed off the same rebuilt curve -- not left stale -- and lands
        // on a DIFFERENT sealed-class case entirely, so no disjunction is needed to express
        // "changed."
        assertEquals(GoalProjection.Years(6.0), f.viewModel.state.value.incomeGoal!!.projection)
    }

    // MARK: income goal

    /** BINDING (carry-notes §3.1): the goal's "current" is forecast year 1's income exactly, so
     *  the progress % and ETA agree with the chart rendered beside them. */
    @Test
    fun theGoalsCurrentEqualsForecastYearOne() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("400"), 1L)))
        val f = fixture(this, goals = goals)
        f.viewModel.load(); runCurrent()
        val yearOne = f.viewModel.state.value.forecast.first().income
        // trailingAnnualPerShare's window is (asOf-365d, asOf]: with a 91-day quarterly cadence
        // and the most recent payment landing exactly at "now" (offset 0), FIVE payments fall
        // inside the window (offsets 0, 91, 182, 273, 364 -- all < 365 days) -- not four. 100
        // shares x (5 x $0.25) = $125 against a $400 target = 31.25%.
        assertEquals(usd("125"), yearOne)
        assertEquals(0.3125, f.viewModel.state.value.incomeGoal!!.fraction, 1e-9)
    }

    /** BINDING (carry-notes §3.3): the ETA must not move when the chart horizon changes.
     *
     *  DISCRIMINATION (review Finding 1): a $400 target here never crosses within 30 years (this
     *  fixture's flat-growth, DRIP-compounded curve tops out at ~$159 by year 30 -- see the
     *  numbers below), so EVERY horizon reports [GoalProjection.BeyondHorizon] regardless of
     *  which forecast length feeds `incomeProjection` -- the assertion would hold even if
     *  `refreshGoalProjection` leaked `_state.value.horizon.years` in place of
     *  `GoalMath.HORIZON_YEARS`. A $130 target crosses at year 6 (year 5 = 129.22, year 6 =
     *  130.30 -- verified by direct computation of this fixture's exact BigDecimal forecast, and
     *  matching the reviewer's independently re-derived numbers): year 6 -> $129.22, $130.30,
     *  $131.38, $132.48, $133.58, $134.69 (year 10), ... $159.01 (year 30). A forecast truncated
     *  to the `Five` pill's 5 entries never reaches $130 (last entry $129.22, still above
     *  `current` $125, so `incomeProjection` falls through to [GoalProjection.BeyondHorizon]) --
     *  while the correct always-30-year read crosses at year 6 and reports
     *  [GoalProjection.Years]\(6.0\) from EVERY pill. This was verified to fail (RED) when
     *  `refreshGoalProjection` was temporarily changed to pass `_state.value.horizon.years`
     *  instead of `GoalMath.HORIZON_YEARS.toInt()`, then pass (GREEN) once reverted -- see the
     *  task report's RED/GREEN transcript. */
    @Test
    fun theGoalEtaIsIndependentOfTheChartHorizon() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("130"), 1L)))
        val f = fixture(this, drip = true, goals = goals)
        f.viewModel.load(); runCurrent()
        val atTen = f.viewModel.state.value.incomeGoal!!.projection
        assertEquals(GoalProjection.Years(6.0), atTen, "sanity: the crossing must land at year 6")
        f.viewModel.setHorizon(ForecastHorizon.Five); runCurrent()
        assertEquals(atTen, f.viewModel.state.value.incomeGoal!!.projection)
        f.viewModel.setHorizon(ForecastHorizon.Thirty); runCurrent()
        assertEquals(atTen, f.viewModel.state.value.incomeGoal!!.projection)
    }

    @Test
    fun settingAGoalPersistsItAndPublishesCardState() = runTest {
        val f = fixture(this)
        f.viewModel.load(); runCurrent()
        assertNull(f.viewModel.state.value.incomeGoal)
        f.viewModel.setIncomeGoal(usd("6000")); runCurrent()
        assertEquals(usd("6000"), f.goals.goals.single().target)
        assertEquals(GoalKind.Income, f.goals.goals.single().kind)
        assertNotNull(f.viewModel.state.value.incomeGoal)
    }

    @Test
    fun removingAGoalClearsBothStoreAndState() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("6000"), 1L)))
        val f = fixture(this, goals = goals)
        f.viewModel.load(); runCurrent()
        f.viewModel.removeIncomeGoal(); runCurrent()
        assertTrue(f.goals.goals.isEmpty())
        assertNull(f.viewModel.state.value.incomeGoal)
    }

    /** Carry-notes §3.4: reset clears goals, so every appearance must re-read. */
    @Test
    fun aSecondLoadRereadsGoalStateRatherThanTrustingTheFirst() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("6000"), 1L)))
        val f = fixture(this, goals = goals)
        f.viewModel.load(); runCurrent()
        assertNotNull(f.viewModel.state.value.incomeGoal)
        goals.goals = emptyList()   // an external reset
        f.viewModel.load(); runCurrent()
        assertNull(f.viewModel.state.value.incomeGoal)
    }

    /** Carry-notes §2.4: a portfolio holding no dividend payer has an all-zero forecast — that is
     *  an absence of data, not a failing rate. */
    @Test
    fun aPortfolioWithNoDividendIncomeReportsInsufficientHistoryNotNotOnTrack() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("6000"), 1L)))
        val market = FakeMarketDataRepository()
        market.quotesImpl = { emptyList() }
        market.dividendEventsImpl = { _, _ -> emptyList() }
        val vm = IncomeViewModel(
            portfolioStore = FakePortfolioStore(Portfolio.starting(usd("50000"))),
            marketDataRepository = market,
            scope = this,
            nowEpochSeconds = { now },
            loadGoals = LoadGoals(goals),
            saveGoal = SaveGoal(goals),
            removeGoal = RemoveGoal(goals),
            isDripEnabled = { false },
        )
        vm.load(); runCurrent()
        assertFalse(vm.state.value.hasForecastIncome)
        assertEquals(GoalProjection.InsufficientHistory, vm.state.value.incomeGoal!!.projection)
        // No forecastable position at all -- nothing to caption as cost-basis-estimated either.
        assertFalse(vm.state.value.forecastPricesAreEstimated)
    }

    /** DISCRIMINATION (review Finding 3). AAPL has only THREE quarterly payments over ~8
     *  months (events at now-182d, now-91d, now) -- real trailing income ($75/yr = 100 shares x
     *  3 x $0.25), but [DividendMath.dividendGrowthRate] cannot measure a rate for it: the span
     *  (~0.5yr) is under the two-year floor, so [DividendMath.hasMeasurableGrowth] is false and
     *  the forecast is flat at $75/yr for every year (no growth to compound). That is the SAME
     *  "too little history to know" situation [GoalMath.valueProjection] already reports as
     *  insufficient history for an equivalently young position (carry-notes §2.4's sibling
     *  asymmetry, still open for this case until now). The WRONG implementation this rejects is
     *  `refreshGoalProjection` calling the pre-Finding-3 `incomeProjection` overload that ignores
     *  measurability entirely -- verified to fail (RED) reporting `NotOnTrack`, then pass (GREEN)
     *  reporting `InsufficientHistory` once the `hasMeasurableGrowth` wiring was added; see the
     *  task report's RED/GREEN transcript. */
    @Test
    fun aPayerWithUnmeasurableGrowthReportsInsufficientHistoryNotNotOnTrack() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("6000"), 1L)))
        val youngEvents = (0 until 3).map { i -> DividendEvent("AAPL", now - (2 - i) * 91 * day, usd("0.25")) }
        val portfolio = Portfolio.starting(usd("50000"))
            .buying(aapl, qty("100"), usd("50"), now - 800 * day, "txn-1")
        val market = FakeMarketDataRepository()
        market.quotesImpl = { symbols -> symbols.map { Quote(it, usd("150"), usd("150"), 0.0) } }
        market.dividendEventsImpl = { _, _ -> youngEvents }
        val vm = IncomeViewModel(
            portfolioStore = FakePortfolioStore(portfolio),
            marketDataRepository = market,
            scope = this,
            nowEpochSeconds = { now },
            loadGoals = LoadGoals(goals),
            saveGoal = SaveGoal(goals),
            removeGoal = RemoveGoal(goals),
            isDripEnabled = { false },
        )
        vm.load(); runCurrent()
        // Sanity: there IS real trailing income -- this is NOT the already-covered all-zero case.
        assertEquals(usd("75"), vm.state.value.forecast.first().income)
        assertEquals(GoalProjection.InsufficientHistory, vm.state.value.incomeGoal!!.projection)
    }

    // MARK: dividend calendar

    @Test
    fun theCalendarGroupsTwelveMonthsOfEstimatedPayoutsAscendingWithMonthTotals() = runTest {
        val f = fixture(this)
        f.viewModel.load(); runCurrent()
        val months = f.viewModel.state.value.calendarMonths
        assertTrue(months.isNotEmpty())
        assertEquals(months.map { it.id }.sorted(), months.map { it.id })
        for (month in months) {
            assertTrue(month.rows.isNotEmpty())
            val expected = month.rows.fold(BigDecimal.ZERO) { acc, row -> acc + row.estimatedAmount.amount }
            assertEquals(expected, month.total.amount)
            assertTrue(month.rows.all { it.exDateEpochSeconds > now && it.exDateEpochSeconds <= now + 365 * day })
        }
    }

    @Test
    fun aPortfolioWithNoProjectablePayoutsHasAnEmptyCalendar() = runTest {
        val market = FakeMarketDataRepository()
        market.quotesImpl = { emptyList() }
        market.dividendEventsImpl = { _, _ -> emptyList() }
        val vm = IncomeViewModel(
            portfolioStore = FakePortfolioStore(Portfolio.starting(usd("50000"))),
            marketDataRepository = market,
            scope = this,
            nowEpochSeconds = { now },
            loadGoals = LoadGoals(MemoryGoalStore()),
            saveGoal = SaveGoal(MemoryGoalStore()),
            removeGoal = RemoveGoal(MemoryGoalStore()),
            isDripEnabled = { false },
        )
        vm.load(); runCurrent()
        assertTrue(vm.state.value.calendarMonths.isEmpty())
    }

    @Test
    fun theGoalProjectionAlwaysReadsAFullHorizonForecast() {
        // Documents the contract the ETA test above exercises: 30 entries, not the pill's length.
        assertEquals(30, GoalMath.HORIZON_YEARS.toInt())
    }
}
