package com.aptrade.android.plans

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.ContributeToPie
import com.aptrade.shared.application.DeletePie
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.LoadPies
import com.aptrade.shared.application.PieStore
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.ReconcilePieLedgers
import com.aptrade.shared.application.RebalancePie
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.ContributionSchedule
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Pie
import com.aptrade.shared.domain.PieActivityEntry
import com.aptrade.shared.domain.PieActivityKind
import com.aptrade.shared.domain.PieCadence
import com.aptrade.shared.domain.PieLedgerEntry
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Quote
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android twin of desktopApp/src/test/kotlin/com/aptrade/desktop/plans/PlansViewModelTest.kt,
 *  transcribed near-verbatim. [PlansViewModel] is an androidx ViewModel using
 *  `viewModelScope` (Dispatchers.Main.immediate), mirroring
 *  [com.aptrade.android.portfolio.PortfolioViewModelTest]'s scheduler discipline: a
 *  [StandardTestDispatcher] installed as Main, with `runCurrent()` after each VM call. */
class PlansViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakePieStore(initial: List<Pie> = emptyList()) : PieStore {
        var pies: List<Pie> = initial
        override suspend fun load(): List<Pie> = pies
        override suspend fun save(pies: List<Pie>) {
            this.pies = pies
        }
    }

    private class FakePortfolioStore(initial: Portfolio) : PortfolioStore {
        var portfolio: Portfolio = initial
        override suspend fun load(): Portfolio = portfolio
        override suspend fun save(portfolio: Portfolio) {
            this.portfolio = portfolio
        }
    }

    // 2025-07-20, a Sunday — matches the desktop fixture's `fixedNow`.
    private val fixedNow = 1_753_000_000L

    private val sliceA = PieSlice("A", AssetKind.Stock, BigDecimal.parseString("50"))
    private val sliceB = PieSlice("B", AssetKind.Stock, BigDecimal.parseString("50"))

    private fun usd(amount: String): Money = Money(BigDecimal.parseString(amount), "USD")
    private fun qty(amount: String): BigDecimal = BigDecimal.parseString(amount)
    private fun quote(symbol: String, price: String) = Quote(symbol, usd(price), usd(price), 0.0)

    private fun repoWithQuotes(vararg quotes: Pair<String, Quote>): FakeMarketDataRepository {
        val map = quotes.toMap()
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.mapNotNull { map[it] } }
        return repo
    }

    private fun vm(
        pieStore: PieStore,
        portfolioStore: PortfolioStore,
        repo: FakeMarketDataRepository,
        now: Long = fixedNow,
    ): PlansViewModel {
        val mutex = Mutex()
        val calendar = MarketCalendar()
        return PlansViewModel(
            loadPies = LoadPies(pieStore),
            deletePieUseCase = DeletePie(pieStore, mutex),
            contributeToPie = ContributeToPie(pieStore, portfolioStore, repo, mutex),
            rebalancePie = RebalancePie(pieStore, portfolioStore, repo, mutex),
            reconcileLedgers = ReconcilePieLedgers(pieStore, portfolioStore, mutex, calendar),
            fetchMarketQuotes = FetchMarketQuotes(repo),
            calendar = calendar,
            nowEpochSeconds = { now },
        )
    }

    /** A portfolio that actually holds [quantityA]/[quantityB] shares of A/B, so
     *  [ReconcilePieLedgers] never clamps a pie's ledger down in tests that aren't
     *  specifically exercising reconciliation. */
    private fun backingPortfolio(quantityA: String, quantityB: String, priceA: String = "10", priceB: String = "10"): Portfolio {
        var portfolio = Portfolio.starting()
        if (qty(quantityA) > BigDecimal.ZERO) {
            portfolio = portfolio.buying(Asset("A", "A", AssetKind.Stock), qty(quantityA), usd(priceA), 0L)
        }
        if (qty(quantityB) > BigDecimal.ZERO) {
            portfolio = portfolio.buying(Asset("B", "B", AssetKind.Stock), qty(quantityB), usd(priceB), 0L)
        }
        return portfolio
    }

    // MARK: - onAppear builds rows from fake quotes

    @Test
    fun onAppearBuildsRowsFromFakeQuotes() = runTest(dispatcher.scheduler) {
        val ledger = listOf(PieLedgerEntry("A", qty("8")), PieLedgerEntry("B", qty("2")))
        val pie = Pie.create(id = "pie-1", name = "Growth", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01", ledger = ledger)
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(backingPortfolio("8", "2"))
        val repo = repoWithQuotes("A" to quote("A", "10"), "B" to quote("B", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()

        val rows = vm.state.value.rows
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("pie-1", row.id)
        assertEquals("Growth", row.name)
        assertEquals(usd("100"), row.currentValue)
        assertNull(row.nextContributionLabel)
        assertEquals(listOf("A", "B"), row.sliceWeights.map { it.first })
        assertEquals(listOf(BigDecimal.parseString("50"), BigDecimal.parseString("50")), row.sliceWeights.map { it.second })
    }

    // MARK: - Drift badge math (>5pp fixture)

    @Test
    fun onAppearMaxDriftPPReflectsLargestSliceDrift() = runTest(dispatcher.scheduler) {
        // A: 8 shares @ $10 = $80 (target 50%, actual 80% -> drift +30pp)
        // B: 2 shares @ $10 = $20 (target 50%, actual 20% -> drift -30pp)
        val ledger = listOf(PieLedgerEntry("A", qty("8")), PieLedgerEntry("B", qty("2")))
        val pie = Pie.create(id = "pie-drift", name = "Drifted", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01", ledger = ledger)
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(backingPortfolio("8", "2"))
        val repo = repoWithQuotes("A" to quote("A", "10"), "B" to quote("B", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()

        val row = vm.state.value.rows.first()
        assertEquals(BigDecimal.parseString("30"), row.maxDriftPP)
        assertTrue(row.maxDriftPP > BigDecimal.parseString("5"), "drift badge should show above the 5pp threshold")
    }

    @Test
    fun onAppearAtTargetMaxDriftPPIsZeroNoBadge() = runTest(dispatcher.scheduler) {
        val ledger = listOf(PieLedgerEntry("A", qty("5")), PieLedgerEntry("B", qty("5")))
        val pie = Pie.create(id = "pie-balanced", name = "Balanced", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01", ledger = ledger)
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(backingPortfolio("5", "5"))
        val repo = repoWithQuotes("A" to quote("A", "10"), "B" to quote("B", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()

        val row = vm.state.value.rows.first()
        assertEquals(BigDecimal.ZERO, row.maxDriftPP)
    }

    // MARK: - Reconcile-before-display

    @Test
    fun onAppearReconcilesLedgersBeforeBuildingRows() = runTest(dispatcher.scheduler) {
        val sliceC = PieSlice("C", AssetKind.Stock, BigDecimal.parseString("100"))
        val pie1 = Pie.create(id = "pie1", name = "P1", slices = listOf(sliceC), schedule = null, createdDay = "2025-01-01", ledger = listOf(PieLedgerEntry("C", qty("3"))))
        val pie2 = Pie.create(id = "pie2", name = "P2", slices = listOf(sliceC), schedule = null, createdDay = "2025-01-01", ledger = listOf(PieLedgerEntry("C", qty("9"))))
        val pieStore = FakePieStore(listOf(pie1, pie2))

        // Portfolio only actually holds 10 of C; pies together over-claim 12 (3 + 9).
        val portfolio = Portfolio.starting().buying(Asset("C", "C", AssetKind.Stock), qty("10"), usd("10"), 0L)
        val portfolioStore = FakePortfolioStore(portfolio)
        val repo = repoWithQuotes("C" to quote("C", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()

        // pie1's smaller claim (3) is preserved in full; pie2's larger claim (9) absorbs the
        // shortfall and is clamped to 7 (10 - 3).
        assertEquals(qty("3"), pieStore.pies.first { it.id == "pie1" }.quantityOf("C"))
        assertEquals(qty("7"), pieStore.pies.first { it.id == "pie2" }.quantityOf("C"))

        // Rows must reflect the RECONCILED ledger, not the pre-reconcile claim.
        val row2 = vm.state.value.rows.first { it.id == "pie2" }
        assertEquals(usd("70"), row2.currentValue, "row should be built from the reconciled (clamped) ledger")
    }

    // MARK: - contributeNow refreshes rows

    @Test
    fun contributeNowSufficientCashRefreshesRows() = runTest(dispatcher.scheduler) {
        val pie = Pie.create(id = "pie-1", name = "Growth", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(Portfolio.starting())
        val repo = repoWithQuotes("A" to quote("A", "10"), "B" to quote("B", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()
        assertEquals(usd("0"), vm.state.value.rows.first().currentValue)

        vm.contributeNow("pie-1", usd("100")); runCurrent()

        assertNull(vm.state.value.errorMessage)
        assertEquals(usd("100"), vm.state.value.rows.first().currentValue)
    }

    // MARK: - contributeNow insufficient cash surfaces localized error

    @Test
    fun contributeNowInsufficientCashSetsLocalizedErrorMessage() = runTest(dispatcher.scheduler) {
        val pie = Pie.create(id = "pie-1", name = "Growth", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(Portfolio(cash = usd("10")))
        val repo = repoWithQuotes("A" to quote("A", "10"), "B" to quote("B", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()
        vm.contributeNow("pie-1", usd("100")); runCurrent()

        assertEquals("Not enough cash for this contribution.", vm.state.value.errorMessage)
        assertEquals(usd("10"), portfolioStore.portfolio.cash, "portfolio must be untouched on a skipped contribution")
    }

    // MARK: - Rebalance request -> confirm flow mutates stores

    @Test
    fun requestRebalanceThenConfirmMutatesStoresAndClearsPreview() = runTest(dispatcher.scheduler) {
        // A: 7 shares @ $10 = $70 (target 50%), B: 3 shares @ $10 = $30 (target 50%) -> drifted.
        val ledger = listOf(PieLedgerEntry("A", qty("7")), PieLedgerEntry("B", qty("3")))
        val pie = Pie.create(id = "pie-1", name = "Drifted", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01", ledger = ledger)
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(backingPortfolio("7", "3"))
        val repo = repoWithQuotes("A" to quote("A", "10"), "B" to quote("B", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()

        vm.requestRebalance("pie-1"); runCurrent()
        val preview = vm.state.value.rebalancePreview
        assertNotNull(preview)
        assertFalse(preview.isEmpty())

        vm.confirmRebalance("pie-1"); runCurrent()

        assertNull(vm.state.value.rebalancePreview)
        // After rebalancing to 50/50 on $100 total, both slices should hold $50 worth (5 shares @ $10).
        assertEquals(qty("5"), pieStore.pies.first().quantityOf("A"))
        assertEquals(qty("5"), pieStore.pies.first().quantityOf("B"))
        assertTrue(pieStore.pies.first().activity.any { it.kind == PieActivityKind.Rebalance })
    }

    // MARK: - deletePie

    @Test
    fun deletePieRemovesFromStoreAndRows() = runTest(dispatcher.scheduler) {
        val pie = Pie.create(id = "pie-1", name = "Growth", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(Portfolio.starting())
        val repo = FakeMarketDataRepository()

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()
        assertEquals(1, vm.state.value.rows.size)

        vm.deletePie("pie-1"); runCurrent()

        assertTrue(vm.state.value.rows.isEmpty())
        assertTrue(pieStore.pies.isEmpty())
    }

    // MARK: - openDetail builds target/actual/drift, activity, schedule

    @Test
    fun openDetailBuildsSlicesWithTargetActualDriftAndActivityAndSchedule() = runTest(dispatcher.scheduler) {
        val schedule = ContributionSchedule(usd("50"), PieCadence.Monthly, "2025-07-25", "2025-07-25")
        val ledger = listOf(PieLedgerEntry("A", qty("8")), PieLedgerEntry("B", qty("2")))
        val activity = listOf(PieActivityEntry(kind = PieActivityKind.Contribution, day = "2025-06-01", amount = usd("100")))
        val pie = Pie.create(id = "pie-1", name = "Growth", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01", ledger = ledger, activity = activity)
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(backingPortfolio("8", "2"))
        val repo = repoWithQuotes("A" to quote("A", "10"), "B" to quote("B", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()
        vm.openDetail("pie-1"); runCurrent()

        val detail = vm.state.value.detail
        assertNotNull(detail)
        assertEquals("pie-1", detail.pieId)
        assertEquals(2, detail.slices.size)
        val sliceADetail = detail.slices.first { it.symbol == "A" }
        assertEquals(BigDecimal.parseString("50"), sliceADetail.targetWeight)
        assertEquals(BigDecimal.parseString("80"), sliceADetail.actualWeight)
        assertEquals(BigDecimal.parseString("30"), sliceADetail.drift)
        assertEquals(usd("80"), sliceADetail.currentValue)
        assertEquals(activity, detail.activity)
        assertEquals(schedule, detail.schedule)
    }

    /** Review carry-over from M7.3 Task 2: activity ordering (newest-first) belongs in the
     *  VM's `buildDetail`, not the view's render loop — PlansScreen just iterates
     *  `detail.activity` in whatever order the VM hands back. Proves the VM itself sorts by
     *  descending `day`, regardless of the store's insertion order. */
    @Test
    fun openDetailSortsActivityNewestFirst() = runTest(dispatcher.scheduler) {
        val activity = listOf(
            PieActivityEntry(kind = PieActivityKind.Contribution, day = "2025-06-01", amount = usd("100")),
            PieActivityEntry(kind = PieActivityKind.Contribution, day = "2025-07-01", amount = usd("50")),
            PieActivityEntry(kind = PieActivityKind.Rebalance, day = "2025-06-15", amount = usd("0")),
        )
        val pie = Pie.create(id = "pie-1", name = "Growth", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01", activity = activity)
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(backingPortfolio("8", "2"))
        val repo = repoWithQuotes("A" to quote("A", "10"), "B" to quote("B", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()
        vm.openDetail("pie-1"); runCurrent()

        val detail = vm.state.value.detail
        assertNotNull(detail)
        assertEquals(listOf("2025-07-01", "2025-06-15", "2025-06-01"), detail.activity.map { it.day })
    }

    // MARK: - nextContributionLabel is formatted via L10n

    @Test
    fun onAppearScheduledPieSetsFormattedNextContributionLabel() = runTest(dispatcher.scheduler) {
        val schedule = ContributionSchedule(usd("50"), PieCadence.Monthly, "2025-07-25", "2025-07-25")
        val pie = Pie.create(id = "pie-1", name = "Growth", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(Portfolio.starting())
        val repo = FakeMarketDataRepository()

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()

        assertEquals("Next: Jul 25", vm.state.value.rows.first().nextContributionLabel)
    }

    // MARK: - contributeNow guards non-positive amounts before calling the use case

    @Test
    fun contributeNowZeroOrNegativeAmountSetsErrorAndNeverCallsUseCase() = runTest(dispatcher.scheduler) {
        val pie = Pie.create(id = "pie-1", name = "Growth", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(pie))
        val portfolioStore = FakePortfolioStore(Portfolio.starting())
        var quoteCallCount = 0
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols ->
            quoteCallCount++
            symbols.mapNotNull { mapOf("A" to quote("A", "10"), "B" to quote("B", "10"))[it] }
        }

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()
        quoteCallCount = 0 // reset the count run up by onAppear's own row-building fetch

        vm.contributeNow("pie-1", usd("0")); runCurrent()
        assertEquals("Enter an amount greater than zero.", vm.state.value.errorMessage)
        assertEquals(0, quoteCallCount, "ContributeToPie must never run for a non-positive amount")
        assertTrue(pieStore.pies.first().activity.isEmpty(), "no activity entry for a rejected contribution")

        vm.contributeNow("pie-1", usd("-5")); runCurrent()
        assertEquals("Enter an amount greater than zero.", vm.state.value.errorMessage)
        assertEquals(0, quoteCallCount)
        assertTrue(pieStore.pies.first().activity.isEmpty())
    }

    // MARK: - Stale rebalance preview is cleared on navigation away

    @Test
    fun openDetailClearsStaleRebalancePreview() = runTest(dispatcher.scheduler) {
        // A: 7 shares @ $10 = $70 (target 50%), B: 3 shares @ $10 = $30 (target 50%) -> drifted, so
        // requestRebalance produces a non-empty preview.
        val ledger = listOf(PieLedgerEntry("A", qty("7")), PieLedgerEntry("B", qty("3")))
        val pie1 = Pie.create(id = "pie-1", name = "Drifted", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01", ledger = ledger)
        val sliceC = PieSlice("C", AssetKind.Stock, BigDecimal.parseString("100"))
        val pie2 = Pie.create(id = "pie-2", name = "Other", slices = listOf(sliceC), schedule = null, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(pie1, pie2))
        val portfolioStore = FakePortfolioStore(backingPortfolio("7", "3"))
        val repo = repoWithQuotes("A" to quote("A", "10"), "B" to quote("B", "10"), "C" to quote("C", "10"))

        val vm = vm(pieStore, portfolioStore, repo)
        vm.onAppear(); runCurrent()
        vm.requestRebalance("pie-1"); runCurrent()
        assertNotNull(vm.state.value.rebalancePreview)

        vm.openDetail("pie-2"); runCurrent()

        assertNull(vm.state.value.rebalancePreview, "a stale preview from a different pie must not linger after navigating away")
    }
}
