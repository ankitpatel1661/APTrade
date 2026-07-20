package com.aptrade.android.plans

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.FetchSearch
import com.aptrade.shared.application.PieStore
import com.aptrade.shared.application.PortfolioStore
import com.aptrade.shared.application.ReconcilePieLedgers
import com.aptrade.shared.application.SavePie
import com.aptrade.shared.application.SimulateDCA
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
import com.aptrade.shared.domain.PieSchedule
import com.aptrade.shared.domain.PieSlice
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.PricePoint
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android twin of desktopApp/src/test/kotlin/com/aptrade/desktop/plans/PieWizardViewModelTest.kt,
 *  transcribed near-verbatim. [PieWizardViewModel] is an androidx ViewModel using
 *  `viewModelScope` (Dispatchers.Main.immediate), mirroring
 *  [com.aptrade.android.portfolio.PortfolioViewModelTest]'s scheduler discipline: a
 *  [StandardTestDispatcher] installed as Main, with `runCurrent()`/`advanceTimeBy` driving
 *  [updateSearchQuery]'s debounce. */
class PieWizardViewModelTest {
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

    private val calendar = MarketCalendar()
    private val sliceA = PieSlice("A", AssetKind.Stock, BigDecimal.parseString("60"))
    private val sliceB = PieSlice("B", AssetKind.Stock, BigDecimal.parseString("40"))

    // 2025-07-18, noon ET — a Friday, an ordinary trading day (no US market holiday).
    private val fixedNowZdt: ZonedDateTime =
        ZonedDateTime.of(2025, 7, 18, 12, 0, 0, 0, ZoneId.of("America/New_York"))
    private val fixedNowEpochSeconds: Long = fixedNowZdt.toEpochSecond()

    private fun usd(amount: String): Money = Money(BigDecimal.parseString(amount), "USD")
    private fun qty(amount: String): BigDecimal = BigDecimal.parseString(amount)

    private fun vm(
        existingPie: Pie? = null,
        pieStore: PieStore,
        repo: FakeMarketDataRepository,
    ): PieWizardViewModel {
        val mutex = Mutex()
        return PieWizardViewModel(
            existingPie = existingPie,
            savePie = SavePie(pieStore, mutex),
            simulateDCA = SimulateDCA(repo, calendar),
            searchAssets = FetchSearch(repo),
            calendar = calendar,
            nowEpochSeconds = { fixedNowEpochSeconds },
        )
    }

    // MARK: - weightSumPP / canSave: live 100-check

    @Test
    fun weightSumAndCanSaveTrueOnlyWhenSumIsExactly100() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.setName("My Pie")

        vm.addSlice(Asset("A", "A", AssetKind.Stock))
        vm.addSlice(Asset("B", "B", AssetKind.Stock))
        vm.setWeight("A", BigDecimal.parseString("60"))
        vm.setWeight("B", BigDecimal.parseString("40"))

        assertEquals(BigDecimal.parseString("100"), vm.state.value.weightSumPP)
        assertTrue(vm.state.value.canSave)

        vm.addSlice(Asset("C", "C", AssetKind.Stock))
        vm.setWeight("C", BigDecimal.parseString("5"))

        assertEquals(BigDecimal.parseString("105"), vm.state.value.weightSumPP)
        assertFalse(vm.state.value.canSave, "sum must be EXACTLY 100 for canSave")
    }

    @Test
    fun canSaveFalseWhenNameEmpty() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.addSlice(Asset("A", "A", AssetKind.Stock))
        vm.setWeight("A", BigDecimal.parseString("100"))

        assertEquals(BigDecimal.parseString("100"), vm.state.value.weightSumPP)
        assertFalse(vm.state.value.canSave, "an empty name must block save even at a perfect weight sum")
    }

    @Test
    fun canSaveFalseWhenSlicesHaveDuplicateSymbols() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.setName("Dup Pie")
        // Bypass addSlice's own dedupe guard by assigning slices directly, exercising the
        // recompute on the raw setter.
        vm.setSlices(listOf(sliceA, PieSlice("A", AssetKind.Stock, BigDecimal.parseString("40"))))

        assertEquals(BigDecimal.parseString("100"), vm.state.value.weightSumPP)
        assertFalse(vm.state.value.canSave, "duplicate symbols must block save even at a perfect weight sum")
    }

    @Test
    fun canSaveFalseWhenScheduleEnabledWithInvalidAmount() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.setName("Scheduled Pie")
        vm.setSlices(listOf(sliceA, sliceB))
        vm.setScheduleEnabled(true)
        vm.setScheduleAmountText("not a number")

        assertFalse(vm.state.value.canSave)

        vm.setScheduleAmountText("50")
        assertTrue(vm.state.value.canSave)
    }

    // MARK: - addSlice / removeSlice

    @Test
    fun addSliceIgnoresDuplicateSymbol() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.addSlice(Asset("A", "A", AssetKind.Stock))
        vm.addSlice(Asset("A", "Apple", AssetKind.Stock))
        assertEquals(1, vm.state.value.slices.size)
    }

    @Test
    fun removeSliceDropsBySymbol() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.setSlices(listOf(sliceA, sliceB))
        vm.removeSlice("A")
        assertEquals(listOf("B"), vm.state.value.slices.map { it.symbol })
    }

    // MARK: - equalSplit: largest-remainder method

    @Test
    fun equalSplitThreeSlicesUsesLargestRemainder() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.addSlice(Asset("A", "A", AssetKind.Stock))
        vm.addSlice(Asset("B", "B", AssetKind.Stock))
        vm.addSlice(Asset("C", "C", AssetKind.Stock))

        vm.equalSplit()

        assertEquals(
            listOf(BigDecimal.parseString("33.33"), BigDecimal.parseString("33.33"), BigDecimal.parseString("33.34")),
            vm.state.value.slices.map { it.targetWeightPP },
        )
        assertEquals(BigDecimal.parseString("100"), vm.state.value.weightSumPP, "largest-remainder split must sum to EXACTLY 100")
    }

    @Test
    fun equalSplitTwoSlicesEvenSplit() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.setSlices(listOf(sliceA, sliceB))
        vm.equalSplit()
        assertEquals(listOf(BigDecimal.parseString("50"), BigDecimal.parseString("50")), vm.state.value.slices.map { it.targetWeightPP })
    }

    // MARK: - save(): schedule only when enabled

    @Test
    fun saveWithoutScheduleCreatesValidPieAndPersistsIt() = runTest(dispatcher.scheduler) {
        val pieStore = FakePieStore()
        val vm = vm(pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.setName("No Schedule Pie")
        vm.setSlices(listOf(sliceA, sliceB))

        val saved = vm.save()

        assertTrue(saved)
        assertEquals(1, pieStore.pies.size)
        assertEquals("No Schedule Pie", pieStore.pies.first().name)
        assertNull(pieStore.pies.first().schedule)
    }

    @Test
    fun saveWithScheduleEnabledAnchorDayEqualsInitialNextDueDay() = runTest(dispatcher.scheduler) {
        val pieStore = FakePieStore()
        val vm = vm(pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.setName("Scheduled Pie")
        vm.setSlices(listOf(sliceA, sliceB))
        vm.setScheduleEnabled(true)
        vm.setCadence(PieCadence.Weekly)
        vm.setScheduleAmountText("50")

        val saved = vm.save()
        assertTrue(saved)

        val today = calendar.tradingDay(fixedNowEpochSeconds)
        // Any sentinel day strictly before `today` produces the same result as the production
        // `dayBefore(today)` call here — `nextDueDay`'s step-0 branch only checks
        // `unrolledDay > afterDay`, which is true either way since the anchor itself (`today`)
        // is being evaluated.
        val expectedFirstDue = PieSchedule.nextDueDay(today, PieCadence.Weekly, "1900-01-01", calendar)

        val schedule = pieStore.pies.first().schedule
        assertEquals(expectedFirstDue, schedule?.anchorDay)
        assertEquals(expectedFirstDue, schedule?.nextDueDay)
        assertEquals(usd("50"), schedule?.amount)
        assertEquals(PieCadence.Weekly, schedule?.cadence)
    }

    // MARK: - Editing an existing SCHEDULED pie must not silently re-anchor the cadence

    @Test
    fun saveScheduledPieNameOnlyEditPreservesScheduleByteIdentical() = runTest(dispatcher.scheduler) {
        val schedule = ContributionSchedule(usd("50"), PieCadence.Monthly, "2025-01-03", "2025-05-02")
        val existing = Pie.create(id = "p1", name = "Old Name", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(existing))
        val vm = vm(existingPie = existing, pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.setName("New Name") // the only change

        val saved = vm.save()

        assertTrue(saved)
        assertEquals("New Name", pieStore.pies.first().name)
        assertEquals(schedule, pieStore.pies.first().schedule, "schedule must be byte-identical after a name-only edit")
    }

    @Test
    fun saveScheduledPieAmountOnlyEditPreservesAnchorAndCursorUpdatesAmount() = runTest(dispatcher.scheduler) {
        val schedule = ContributionSchedule(usd("50"), PieCadence.Monthly, "2025-01-03", "2025-05-02")
        val existing = Pie.create(id = "p1", name = "Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(existing))
        val vm = vm(existingPie = existing, pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.setScheduleAmountText("75") // the only change

        val saved = vm.save()

        assertTrue(saved)
        val updated = pieStore.pies.first().schedule
        assertEquals("2025-01-03", updated?.anchorDay, "anchor must survive an amount-only edit")
        assertEquals("2025-05-02", updated?.nextDueDay, "the schedule cursor must survive an amount-only edit")
        assertEquals(usd("75"), updated?.amount)
        assertEquals(PieCadence.Monthly, updated?.cadence)
    }

    @Test
    fun saveScheduledPieCadenceChangeStartsFreshAnchorFromToday() = runTest(dispatcher.scheduler) {
        val schedule = ContributionSchedule(usd("50"), PieCadence.Monthly, "2025-01-03", "2025-05-02")
        val existing = Pie.create(id = "p1", name = "Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(existing))
        val vm = vm(existingPie = existing, pieStore = pieStore, repo = FakeMarketDataRepository())
        val today = calendar.tradingDay(fixedNowEpochSeconds)
        vm.setCadence(PieCadence.Weekly) // a new rhythm legitimately restarts the schedule
        // F2: a cadence change re-anchors on `scheduleStartDay`, not unconditionally on today —
        // the field pre-filled from the OLD anchor ("2025-01-03", now in the past), so the
        // user must (re)choose a day; here that choice is today.
        vm.setScheduleStartDay(today)

        val saved = vm.save()

        assertTrue(saved)
        val expectedFirstDue = PieSchedule.rollToTradingDay(today, calendar)
        val updated = pieStore.pies.first().schedule
        assertEquals(expectedFirstDue, updated?.anchorDay)
        assertEquals(expectedFirstDue, updated?.nextDueDay)
        assertNotEquals("2025-01-03", updated?.anchorDay, "a cadence change must re-anchor, not keep the old anchor")
        assertEquals(PieCadence.Weekly, updated?.cadence)
    }

    // MARK: - F2: scheduleStartDay

    @Test
    fun scheduleStartDayDefaultsToTodaysTradingDay() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        assertEquals(calendar.tradingDay(fixedNowEpochSeconds), vm.state.value.scheduleStartDay)
    }

    @Test
    fun saveNewScheduleFutureSaturdayStartRollsToMondayAnchor() = runTest(dispatcher.scheduler) {
        val pieStore = FakePieStore()
        val vm = vm(pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.setName("Scheduled Pie")
        vm.setSlices(listOf(sliceA, sliceB))
        vm.setScheduleEnabled(true)
        vm.setScheduleAmountText("50")
        // 2025-07-19 is a Saturday; the next trading day is Monday 2025-07-21.
        vm.setScheduleStartDay("2025-07-19")
        assertTrue(vm.state.value.canSave, "a future Saturday is still >= today, so it must remain valid")

        val saved = vm.save()

        assertTrue(saved)
        assertEquals("2025-07-21", pieStore.pies.first().schedule?.anchorDay)
        assertEquals("2025-07-21", pieStore.pies.first().schedule?.nextDueDay)
    }

    @Test
    fun scheduleStartDayPastDayBlocksCanSave() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.setName("Scheduled Pie")
        vm.setSlices(listOf(sliceA, sliceB))
        vm.setScheduleEnabled(true)
        vm.setScheduleAmountText("50")
        assertTrue(vm.state.value.canSave, "precondition: valid before touching the start day")

        vm.setScheduleStartDay("2020-01-01") // long past
        assertFalse(vm.state.value.canSave, "a past start day must block save")

        vm.setScheduleStartDay(calendar.tradingDay(fixedNowEpochSeconds))
        assertTrue(vm.state.value.canSave, "restoring a valid (today) start day unblocks save again")
    }

    @Test
    fun scheduleStartDayMalformedBlocksCanSave() = runTest(dispatcher.scheduler) {
        val vm = vm(pieStore = FakePieStore(), repo = FakeMarketDataRepository())
        vm.setName("Scheduled Pie")
        vm.setSlices(listOf(sliceA, sliceB))
        vm.setScheduleEnabled(true)
        vm.setScheduleAmountText("50")

        vm.setScheduleStartDay("not-a-date")
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun saveScheduledPieCadenceUnchangedEditIgnoresScheduleStartDay() = runTest(dispatcher.scheduler) {
        val schedule = ContributionSchedule(usd("50"), PieCadence.Monthly, "2025-01-03", "2025-05-02")
        val existing = Pie.create(id = "p1", name = "Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(existing))
        val vm = vm(existingPie = existing, pieStore = pieStore, repo = FakeMarketDataRepository())

        // The field pre-fills from the existing (now long-past) anchor, which would be an
        // INVALID start day on its own — but cadence is unchanged, so it must never be
        // consulted, and canSave/save must both succeed regardless.
        assertEquals("2025-01-03", vm.state.value.scheduleStartDay, "precondition: pre-filled from the existing anchor")
        assertTrue(vm.state.value.canSave, "a cadence-unchanged edit must not be blocked by the pre-filled (past) start day")
        vm.setScheduleAmountText("80") // an ordinary amount-only edit

        val saved = vm.save()

        assertTrue(saved)
        val updated = pieStore.pies.first().schedule
        assertEquals("2025-01-03", updated?.anchorDay, "cadence-unchanged edit preserves the existing anchor untouched")
        assertEquals("2025-05-02", updated?.nextDueDay)
        assertEquals(usd("80"), updated?.amount)
    }

    @Test
    fun saveScheduledPieCadenceChangeReAnchorsOnChosenDay() = runTest(dispatcher.scheduler) {
        val schedule = ContributionSchedule(usd("50"), PieCadence.Monthly, "2025-01-03", "2025-05-02")
        val existing = Pie.create(id = "p1", name = "Pie", slices = listOf(sliceA, sliceB), schedule = schedule, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(existing))
        val vm = vm(existingPie = existing, pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.setCadence(PieCadence.Weekly)
        // The user explicitly picks a future start day distinct from both the old anchor and
        // today.
        vm.setScheduleStartDay("2025-07-19") // a Saturday -> rolls to Monday 2025-07-21

        val saved = vm.save()

        assertTrue(saved)
        val updated = pieStore.pies.first().schedule
        assertEquals("2025-07-21", updated?.anchorDay)
        assertEquals("2025-07-21", updated?.nextDueDay)
        assertEquals(PieCadence.Weekly, updated?.cadence)
    }

    @Test
    fun saveExistingPieWithoutScheduleEnablingScheduleStartsFreshFromToday() = runTest(dispatcher.scheduler) {
        val existing = Pie.create(id = "p1", name = "Pie", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01")
        val pieStore = FakePieStore(listOf(existing))
        val vm = vm(existingPie = existing, pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.setScheduleEnabled(true)
        vm.setCadence(PieCadence.Weekly)
        vm.setScheduleAmountText("20")

        val saved = vm.save()

        assertTrue(saved)
        val today = calendar.tradingDay(fixedNowEpochSeconds)
        val expectedFirstDue = PieSchedule.nextDueDay(today, PieCadence.Weekly, "1900-01-01", calendar)
        assertEquals(expectedFirstDue, pieStore.pies.first().schedule?.anchorDay)
        assertEquals(expectedFirstDue, pieStore.pies.first().schedule?.nextDueDay)
    }

    @Test
    fun saveUpdatesExistingPiePreservesIdLedgerAndCreatedDay() = runTest(dispatcher.scheduler) {
        val ledger = listOf(PieLedgerEntry("A", qty("3")))
        val existing = Pie.create(id = "existing-1", name = "Old Name", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2020-01-01", ledger = ledger)
        val pieStore = FakePieStore(listOf(existing))
        val vm = vm(existingPie = existing, pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.setName("New Name")

        val saved = vm.save()

        assertTrue(saved)
        assertEquals(1, pieStore.pies.size, "update replaces, doesn't duplicate")
        val updated = pieStore.pies.first()
        assertEquals("existing-1", updated.id)
        assertEquals("New Name", updated.name)
        assertEquals("2020-01-01", updated.createdDay)
        assertEquals(qty("3"), updated.quantityOf("A"))
    }

    @Test
    fun saveReturnsFalseWhenCanSaveIsFalse() = runTest(dispatcher.scheduler) {
        val pieStore = FakePieStore()
        val vm = vm(pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.setName("") // invalid: empty name
        vm.setSlices(listOf(sliceA, sliceB))

        val saved = vm.save()

        assertFalse(saved)
        assertTrue(pieStore.pies.isEmpty())
    }

    // MARK: - runBacktest wires SimulateDCA

    @Test
    fun runBacktestWiresSimulateDCAProducesReport() = runTest(dispatcher.scheduler) {
        val startEpochSeconds = fixedNowZdt.minusYears(1).toEpochSecond()
        val repo = FakeMarketDataRepository()
        repo.historyImpl = { symbol, _ -> if (symbol == "A") listOf(PricePoint(startEpochSeconds, usd("10"))) else emptyList() }

        val vm = vm(pieStore = FakePieStore(), repo = repo)
        vm.setName("Backtest Pie")
        vm.setSlices(listOf(PieSlice("A", AssetKind.Stock, BigDecimal.parseString("100"))))
        vm.setScheduleEnabled(true)
        vm.setCadence(PieCadence.Weekly)
        vm.setScheduleAmountText("10")

        assertNull(vm.state.value.backtest)
        vm.runBacktest(1)

        assertNotNull(vm.state.value.backtest, "a single valid close on the computed start day should produce a report")
        assertEquals(usd("10"), vm.state.value.backtest?.totalInvested)
    }

    @Test
    fun runBacktestNoHistoryLeavesBacktestNil() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository() // no histories configured
        val vm = vm(pieStore = FakePieStore(), repo = repo)
        vm.setName("No History Pie")
        vm.setSlices(listOf(PieSlice("A", AssetKind.Stock, BigDecimal.parseString("100"))))
        vm.setScheduleEnabled(true)
        vm.setScheduleAmountText("10")

        vm.runBacktest(1)

        assertNull(vm.state.value.backtest)
    }

    @Test
    fun runBacktestInvalidAmountDoesNotRunAndLeavesBacktestNil() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.historyImpl = { symbol, _ -> if (symbol == "A") listOf(PricePoint(fixedNowEpochSeconds, usd("10"))) else emptyList() }
        val vm = vm(pieStore = FakePieStore(), repo = repo)
        vm.setSlices(listOf(PieSlice("A", AssetKind.Stock, BigDecimal.parseString("100"))))
        vm.setScheduleAmountText("") // no valid amount to simulate with

        vm.runBacktest(1)

        assertNull(vm.state.value.backtest)
    }

    // MARK: - updateSearchQuery: debounced search, excluding already-added slices

    @Test
    fun updateSearchQueryExcludesAlreadyAddedSlices() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { listOf(Asset("A", "A Corp", AssetKind.Stock), Asset("C", "C Corp", AssetKind.Stock)) }
        val vm = vm(pieStore = FakePieStore(), repo = repo)
        vm.addSlice(Asset("A", "A Corp", AssetKind.Stock))

        vm.updateSearchQuery("corp")
        advanceTimeBy(400); runCurrent() // past the 250ms debounce

        assertEquals(listOf("C"), vm.state.value.searchResults.map { it.symbol }, "an already-added symbol (A) must be excluded")
    }

    @Test
    fun updateSearchQueryEmptyQueryClearsResultsSynchronously() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { listOf(Asset("A", "A Corp", AssetKind.Stock)) }
        val vm = vm(pieStore = FakePieStore(), repo = repo)

        vm.updateSearchQuery("a")
        advanceTimeBy(400); runCurrent()
        assertFalse(vm.state.value.searchResults.isEmpty(), "precondition: the debounced search must have populated results")

        vm.updateSearchQuery("") // no debounce wait needed — an empty query clears immediately
        assertTrue(vm.state.value.searchResults.isEmpty())
    }

    @Test
    fun addSliceClearsSearchResults() = runTest(dispatcher.scheduler) {
        val repo = FakeMarketDataRepository()
        repo.searchImpl = { listOf(Asset("A", "A Corp", AssetKind.Stock)) }
        val vm = vm(pieStore = FakePieStore(), repo = repo)

        vm.updateSearchQuery("a")
        advanceTimeBy(400); runCurrent()
        assertFalse(vm.state.value.searchResults.isEmpty(), "precondition: the debounced search must have populated results")

        vm.addSlice(Asset("A", "A Corp", AssetKind.Stock))
        assertTrue(vm.state.value.searchResults.isEmpty(), "adding a slice must clear the now-stale search results")
    }

    // MARK: - F3: removing a slice drops its now-orphaned ledger entry at save-time

    @Test
    fun saveRemovingSliceDropsOrphanedLedgerEntryActivityAndSurvivingEntryUntouched() = runTest(dispatcher.scheduler) {
        val ledger = listOf(PieLedgerEntry("A", qty("5")), PieLedgerEntry("B", qty("3")))
        val activity = listOf(PieActivityEntry(kind = PieActivityKind.Contribution, day = "2025-01-01", amount = usd("100")))
        val existing = Pie.create(id = "p1", name = "Pie", slices = listOf(sliceA, sliceB), schedule = null, createdDay = "2025-01-01", ledger = ledger, activity = activity)
        val pieStore = FakePieStore(listOf(existing))
        val vm = vm(existingPie = existing, pieStore = pieStore, repo = FakeMarketDataRepository())

        vm.removeSlice("B")
        vm.equalSplit() // re-normalize the sole remaining slice to 100%

        val saved = vm.save()

        assertTrue(saved)
        val updated = pieStore.pies.first()
        assertEquals(listOf("A"), updated.ledger.map { it.symbol }, "the orphaned B ledger entry must be dropped")
        assertEquals(qty("5"), updated.quantityOf("A"), "the surviving slice's ledger entry is untouched")
        assertEquals(activity, updated.activity, "activity history (the audit log) must be left untouched")
    }

    // MARK: - F3 (reviewer scenario): a dead ledger claim, once filtered at save-time, must
    // no longer wrongly clamp a different pie's legitimate claim to the same symbol.

    @Test
    fun saveRemovingSliceThenReconcileOtherPieNoLongerWronglyClamped() = runTest(dispatcher.scheduler) {
        // Pie A: has an AAPL slice with a 3-share ledger claim, plus an unrelated MSFT slice.
        // This edit REMOVES the AAPL slice entirely, leaving its ledger entry "dead" once
        // F3's filter applies.
        val sliceAAPL = PieSlice("AAPL", AssetKind.Stock, BigDecimal.parseString("50"))
        val sliceMSFT = PieSlice("MSFT", AssetKind.Stock, BigDecimal.parseString("50"))
        val pieALedger = listOf(PieLedgerEntry("AAPL", qty("3")), PieLedgerEntry("MSFT", qty("3")))
        val pieA = Pie.create(id = "pieA", name = "Pie A", slices = listOf(sliceAAPL, sliceMSFT), schedule = null, createdDay = "2025-01-01", ledger = pieALedger)

        // Pie B: legitimately claims 12 AAPL shares, untouched by this edit.
        val pieBSlice = PieSlice("AAPL", AssetKind.Stock, BigDecimal.parseString("100"))
        val pieB = Pie.create(id = "pieB", name = "Pie B", slices = listOf(pieBSlice), schedule = null, createdDay = "2025-01-01", ledger = listOf(PieLedgerEntry("AAPL", qty("12"))))

        val pieStore = FakePieStore(listOf(pieA, pieB))

        // Edit pie A: drop the AAPL slice (keep MSFT). Per F3, saving must drop the
        // now-orphaned 3-AAPL ledger claim rather than carry it forward as a dead claim.
        val vm = vm(existingPie = pieA, pieStore = pieStore, repo = FakeMarketDataRepository())
        vm.removeSlice("AAPL")
        vm.equalSplit()
        val saved = vm.save()
        assertTrue(saved)
        assertNull(
            pieStore.pies.first { it.id == "pieA" }.ledger.firstOrNull { it.symbol == "AAPL" },
            "pie A's dead AAPL claim must be dropped at save-time",
        )

        // Portfolio: originally held 15 AAPL (3 + 12, matching both pies' original claims); a
        // manual sell (outside any pie) drops the actual holding to 10.
        val portfolio = Portfolio(
            cash = usd("0"),
            positions = listOf(
                Position(Asset("AAPL", "AAPL", AssetKind.Stock), qty("10"), usd("10"), usd("0")),
            ),
        )
        val portfolioStore = FakePortfolioStore(portfolio)

        val reconcile = ReconcilePieLedgers(pieStore, portfolioStore, Mutex())
        val result = reconcile.execute(fixedNowEpochSeconds)

        // Without F3's fix, pie A's still-present 3-share dead claim would make the
        // largest-clamps-first walk wrongly reduce pie B's legitimate 12-share claim to 7
        // (preserving A's 3 in full first). With the dead claim filtered at save-time, pie B
        // is the only remaining claimant and correctly receives the FULL 10 held.
        val resultB = result.first { it.id == "pieB" }
        assertEquals(qty("10"), resultB.quantityOf("AAPL"), "pie B must receive the full 10 held shares, not be wrongly clamped to 7 by A's dead claim")
    }
}
