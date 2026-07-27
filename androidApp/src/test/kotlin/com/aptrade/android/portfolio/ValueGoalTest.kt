package com.aptrade.android.portfolio

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M11.3 Task 4 — the whole-portfolio value goal in Android's `PortfolioViewModel`. Android twin
 * of desktop `ValueGoalTest`.
 *
 * TWO CONVENTIONS THIS FILE HOLDS TO, BOTH LOAD-BEARING:
 *
 * 1. STOP BEFORE ASSERTING. Every case calls `viewModel.stop()` immediately after its last
 *    `runCurrent()` and before its first assertion. The poll loop is an infinite
 *    `while (isActive) { … delay(tickMillis) }` running on the SAME `TestDispatcher` `runTest`
 *    drains, so a test that asserts first and stops last does not merely leak a coroutine when it
 *    fails — the failure skips `stop()`, `runTest` then advances virtual time into a loop that
 *    never goes idle, and the build HANGS instead of reporting a red test. Stopping first is what
 *    makes a broken implementation here visible rather than wedging.
 *
 * 2. DISCRIMINATING FIXTURES. `GoalProjection.InsufficientHistory` is reachable by several
 *    independent paths (no account history, no curve, a degenerate curve), so a fixture that
 *    lands on it by accident would let a broken projection pass. Every case below that asserts a
 *    projection is paired with one proving the SAME fixture reaches a DIFFERENT case when only
 *    the input under test changes — see [aSeasonedAccountWithAMeasurableCurveReportsAConcreteEta]
 *    and [aFlatCurveReportsNotOnTrackRatherThanNoHistory].
 */
class ValueGoalTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun settingAValueGoalPersistsItAndPublishesCardState() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture()
        f.viewModel.start(); runCurrent()
        val beforeAnyGoal = f.viewModel.state.value.valueGoal

        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal
        val stored = f.goalStore.goals
        f.viewModel.stop()

        assertNull(beforeAnyGoal)
        // Persisted...
        assertEquals(1, stored.size)
        assertEquals(GoalKind.Value, stored.single().kind)
        assertEquals("500000", stored.single().target.amountText)
        // ...and published, against the seeded $100,000 cash + $1,000 cost basis.
        assertNotNull(card)
        assertEquals("$101,000.00", card.currentText)
        assertEquals("$500,000.00", card.targetText)
        assertEquals("500000", card.targetAmountText)   // RAW — refills the edit field
        assertEquals(0.202, card.fraction, 1e-9)
    }

    @Test
    fun removingAValueGoalClearsBothStoreAndState() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture()
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        val afterSet = f.viewModel.state.value.valueGoal

        f.viewModel.removeValueGoal(); runCurrent()
        val afterRemove = f.viewModel.state.value.valueGoal
        val stored = f.goalStore.goals
        f.viewModel.stop()

        assertNotNull(afterSet)
        assertTrue(stored.isEmpty(), "the goal must be gone from the store, not just the screen")
        assertNull(afterRemove)
    }

    /** A goal set on another surface (or in a previous session) must appear from the FIRST load —
     *  the card cannot depend on this session having called [PortfolioViewModel.setValueGoal]. */
    @Test
    fun aGoalAlreadyOnDiskIsPublishedByTheFirstLoad() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture()
        f.goalStore.goals = listOf(PortfolioGoal(GoalKind.Value, Money.usd("250000"), 1_700_000_000L))

        f.viewModel.start(); runCurrent()
        val card = f.viewModel.state.value.valueGoal
        f.viewModel.stop()

        assertNotNull(card)
        assertEquals("$250,000.00", card.targetText)
    }

    /** BINDING (carry-notes §2.3): an EMPTY equity curve — which any offline or rate-limited
     *  session produces, not just an all-cash portfolio — must never render a fabricated $0
     *  against a real target. It is cash + cost basis of every position.
     *
     *  Quotes are deliberately moved to $500 AFTER the fixture builds so a quote-valued
     *  implementation ($100,000 + 10 x $500 = $105,000) is distinguishable from the cost-basis
     *  floor ($100,000 + 10 x $100 = $101,000) — with both at $100 the two are identical and this
     *  case would pass against either. */
    @Test
    fun anEmptyCurveFallsBackToCashPlusCostBasisNotZero() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        val f = portfolioViewModelFixture(repo = repo, failHistory = true)
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "500.00") } }

        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal
        f.viewModel.stop()

        assertNotNull(card)
        assertTrue(card.fraction > 0.0, "a portfolio holding real assets must not read 0%")
        assertEquals("$101,000.00", f.expectedCostBasisFloorText)
        assertEquals(f.expectedCostBasisFloorText, card.currentText)
    }

    /** The all-cash reading is preserved exactly by the same expression. */
    @Test
    fun anAllCashPortfolioReadsItsCashAsCurrentValue() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture(holdings = emptyList())
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal
        f.viewModel.stop()

        assertNotNull(card)
        assertEquals("$100,000.00", card.currentText)
    }

    /** THE §4a.2 DIVERGENCE, at the view-model seam: a brand-new account holding a seasoned symbol
     *  has a full 250-day priced curve with a measurable growth rate, but only 20 days of ACCOUNT
     *  history — so the card says "needs more history" rather than projecting off a 20-day-old
     *  account. Its discriminating twin is the next test: the SAME curve at the default account
     *  age produces a concrete ETA, so this result can only have come from the age gate. */
    @Test
    fun aNewAccountHoldingASeasonedSymbolReportsInsufficientHistory() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture(risingCurve = true, accountAgeDays = 20)
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal
        f.viewModel.stop()

        assertNotNull(card)
        assertEquals(GoalProjection.InsufficientHistory, card.projection)
    }

    @Test
    fun aSeasonedAccountWithAMeasurableCurveReportsAConcreteEta() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture(risingCurve = true)   // default account age: 400 days
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal
        f.viewModel.stop()

        assertNotNull(card)
        val years = assertNotNull(
            card.projection as? GoalProjection.Years,
            "the age gate, not the fixture, must be what produces InsufficientHistory; " +
                "got ${card.projection}",
        )
        assertTrue(years.value > 0.0 && years.value <= 30.0)
    }

    /** A seasoned account whose value has not moved is NOT "no history" — it is genuinely not on
     *  track. Pins a third distinct outcome from the same fixture, so none of the cases above is
     *  passing merely because every path collapses to one value. */
    @Test
    fun aFlatCurveReportsNotOnTrackRatherThanNoHistory() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture()   // flat 250-day curve, 400-day-old account
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal
        f.viewModel.stop()

        assertNotNull(card)
        assertEquals(GoalProjection.NotOnTrack, card.projection)
    }

    /** A target already met reads as reached — the branch that must never be reported as an ETA of
     *  zero years, and the one that catches a current/target argument swap. */
    @Test
    fun aTargetAlreadyMetReportsReached() = runTest(dispatcher.scheduler) {
        val f = portfolioViewModelFixture()
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("50000")); runCurrent()   // below the $101,000 current
        val card = f.viewModel.state.value.valueGoal
        f.viewModel.stop()

        assertNotNull(card)
        assertEquals(GoalProjection.Reached, card.projection)
        assertTrue(card.fraction > 1.0)
    }
}
