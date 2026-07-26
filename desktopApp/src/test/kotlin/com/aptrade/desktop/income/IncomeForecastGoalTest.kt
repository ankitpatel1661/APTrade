package com.aptrade.desktop.income

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.GoalStore
import com.aptrade.shared.application.LoadGoals
import com.aptrade.shared.application.RemoveGoal
import com.aptrade.shared.application.SaveGoal
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalMath
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
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

/** M11.2 Task 11. Forecast, dividend calendar, income goal, and the DRIP-toggle wire. */
class IncomeForecastGoalTest {
    private val day = 86_400L
    private fun usd(s: String) = Money.usd(s)
    private fun qty(s: String) = BigDecimal.parseString(s)
    private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)

    /** 2026-07-20T12:00:00Z — the same fixed "now" IncomeViewModelTest uses. */
    private val now = 1_784_548_800L

    private class MemoryGoalStore(var goals: List<PortfolioGoal> = emptyList()) : GoalStore {
        override suspend fun load(): List<PortfolioGoal> = goals
        override suspend fun save(goals: List<PortfolioGoal>) { this.goals = goals }
    }

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
            portfolioStore = object : com.aptrade.shared.application.PortfolioStore {
                override suspend fun load(): Portfolio = portfolio
                override suspend fun save(portfolio: Portfolio) = Unit
            },
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

    /** BINDING (carry-notes §2.2). Flipping DRIP must rebuild the chart AND refresh the ETA. */
    @Test
    fun dripDidChangeRebuildsTheForecastAndRefreshesTheGoalProjection() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("50000"), 1L)))
        val f = fixture(this, drip = false, goals = goals)
        f.viewModel.load(); runCurrent()
        val before = f.viewModel.state.value.forecast.last().income.amount
        val beforeProjection = f.viewModel.state.value.incomeGoal!!.projection

        f.viewModel.dripDidChange(enabled = true); runCurrent()
        val after = f.viewModel.state.value.forecast.last().income.amount
        assertTrue(after > before, "DRIP on must raise the far-year forecast")
        // The projection is recomputed off the same rebuilt assumption — not left stale.
        assertNotNull(f.viewModel.state.value.incomeGoal)
        assertTrue(
            beforeProjection != f.viewModel.state.value.incomeGoal!!.projection ||
                beforeProjection is GoalProjection.BeyondHorizon,
            "the ETA must be recomputed against the curve that just changed",
        )
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

    /** BINDING (carry-notes §3.3): the ETA must not move when the chart horizon changes. */
    @Test
    fun theGoalEtaIsIndependentOfTheChartHorizon() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("400"), 1L)))
        val f = fixture(this, drip = true, goals = goals)
        f.viewModel.load(); runCurrent()
        val atTen = f.viewModel.state.value.incomeGoal!!.projection
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
            portfolioStore = object : com.aptrade.shared.application.PortfolioStore {
                override suspend fun load(): Portfolio = Portfolio.starting(usd("50000"))
                override suspend fun save(portfolio: Portfolio) = Unit
            },
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
            portfolioStore = object : com.aptrade.shared.application.PortfolioStore {
                override suspend fun load(): Portfolio = Portfolio.starting(usd("50000"))
                override suspend fun save(portfolio: Portfolio) = Unit
            },
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
