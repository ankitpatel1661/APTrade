import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class MemoryPieStore: PieStore, @unchecked Sendable {
    var pies: [Pie] = []
    var saveCallCount = 0
    func load() -> [Pie] { pies }
    func save(_ pies: [Pie]) {
        self.pies = pies
        saveCallCount += 1
    }
}

private final class MemoryPortfolioStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

@MainActor
final class PieWizardViewModelTests: XCTestCase {
    private let calendar = MarketCalendar()
    private let sliceA = PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 60))
    private let sliceB = PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 40))

    private func usd(_ amount: Decimal) -> Money { Money(amount: amount) }

    /// 2025-07-18, noon ET — a Friday, an ordinary trading day (no US market holiday).
    private var fixedNow: Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York")!
        let comps = DateComponents(year: 2025, month: 7, day: 18, hour: 12)
        return cal.date(from: comps)!
    }

    private func makeVM(existingPie: Pie? = nil, pieStore: MemoryPieStore, repo: VMFakeRepo) -> PieWizardViewModel {
        let now = fixedNow // captured as a value below — avoids capturing non-Sendable `self`
        return PieWizardViewModel(
            existingPie: existingPie,
            savePie: SavePie(store: pieStore, serializer: TradeSerializer()),
            simulateDCA: SimulateDCA(market: repo, calendar: calendar),
            searchAssets: SearchAssetsUseCase(repository: repo),
            calendar: calendar,
            now: { now }
        )
    }

    // MARK: - weightSumPP / canSave: live 100-check

    func test_weightSum_and_canSave_trueOnlyWhenSumIsExactly100() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.name = "My Pie"

        vm.addSlice(asset: Asset(symbol: "A", name: "A", kind: .stock))
        vm.addSlice(asset: Asset(symbol: "B", name: "B", kind: .stock))
        vm.setWeight(symbol: "A", pp: 60)
        vm.setWeight(symbol: "B", pp: 40)

        XCTAssertEqual(vm.weightSumPP, 100)
        XCTAssertTrue(vm.canSave)

        // With A+B already at 100, a new slice's weight clamps to the zero remainder —
        // the sum can no longer overshoot (see test_setWeight_clampsToRemainingBudget…).
        vm.addSlice(asset: Asset(symbol: "C", name: "C", kind: .stock))
        vm.setWeight(symbol: "C", pp: 5)
        XCTAssertEqual(vm.weightSumPP, 100)

        // An under-100 sum still blocks saving: canSave demands EXACTLY 100.
        vm.setWeight(symbol: "B", pp: 30)
        XCTAssertEqual(vm.weightSumPP, 90)
        XCTAssertFalse(vm.canSave, "sum must be EXACTLY 100 for canSave")
    }

    // MARK: - setWeight clamping: total can never exceed 100

    func test_setWeight_clampsToRemainingBudget_totalNeverExceeds100() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        for symbol in ["AAPL", "NVDA", "MSFT", "AMZN"] {
            vm.addSlice(asset: Asset(symbol: symbol, name: symbol, kind: .stock))
        }
        vm.setWeight(symbol: "AAPL", pp: 10)
        vm.setWeight(symbol: "NVDA", pp: 20)
        vm.setWeight(symbol: "MSFT", pp: 35)

        // Others hold 65pp, so AMZN's ceiling is 35 — a request for 40 clamps.
        vm.setWeight(symbol: "AMZN", pp: 40)
        XCTAssertEqual(vm.slices.first { $0.symbol == "AMZN" }?.targetWeight.value, 35)
        XCTAssertEqual(vm.weightSumPP, 100)

        // Repeated overshoot stays pinned at the ceiling.
        vm.setWeight(symbol: "AMZN", pp: 50)
        XCTAssertEqual(vm.slices.first { $0.symbol == "AMZN" }?.targetWeight.value, 35)
        XCTAssertEqual(vm.weightSumPP, 100)

        // Raising an EXISTING slice is clamped by the same budget: with the other
        // three at 90pp, AAPL cannot go past its current 10.
        vm.setWeight(symbol: "AAPL", pp: 50)
        XCTAssertEqual(vm.slices.first { $0.symbol == "AAPL" }?.targetWeight.value, 10)
        XCTAssertEqual(vm.weightSumPP, 100)

        // Lowering one slice frees budget for another.
        vm.setWeight(symbol: "MSFT", pp: 15)
        vm.setWeight(symbol: "AMZN", pp: 55)
        XCTAssertEqual(vm.slices.first { $0.symbol == "AMZN" }?.targetWeight.value, 55)
        XCTAssertEqual(vm.weightSumPP, 100)
    }

    func test_setWeight_negativeClampsToZero() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.addSlice(asset: Asset(symbol: "AAPL", name: "AAPL", kind: .stock))
        vm.setWeight(symbol: "AAPL", pp: -5)
        XCTAssertEqual(vm.slices.first { $0.symbol == "AAPL" }?.targetWeight.value, 0)
    }

    func test_canSave_false_whenNameEmpty() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.addSlice(asset: Asset(symbol: "A", name: "A", kind: .stock))
        vm.setWeight(symbol: "A", pp: 100)

        XCTAssertEqual(vm.weightSumPP, 100)
        XCTAssertFalse(vm.canSave, "an empty name must block save even at a perfect weight sum")
    }

    func test_canSave_false_whenSlicesHaveDuplicateSymbols() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.name = "Dup Pie"
        // Bypass addSlice's own dedupe guard by assigning `slices` directly, exercising
        // the `didSet`-driven recompute on the raw public property.
        vm.slices = [sliceA, PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 40))]

        XCTAssertEqual(vm.weightSumPP, 100)
        XCTAssertFalse(vm.canSave, "duplicate symbols must block save even at a perfect weight sum")
    }

    func test_canSave_false_whenScheduleEnabledWithInvalidAmount() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.name = "Scheduled Pie"
        vm.slices = [sliceA, sliceB]
        vm.scheduleEnabled = true
        vm.scheduleAmountText = "not a number"

        XCTAssertFalse(vm.canSave)

        vm.scheduleAmountText = "50"
        XCTAssertTrue(vm.canSave)
    }

    // MARK: - addSlice / removeSlice

    func test_addSlice_ignoresDuplicateSymbol() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.addSlice(asset: Asset(symbol: "A", name: "A", kind: .stock))
        vm.addSlice(asset: Asset(symbol: "A", name: "Apple", kind: .stock))
        XCTAssertEqual(vm.slices.count, 1)
    }

    func test_removeSlice_dropsBySymbol() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.slices = [sliceA, sliceB]
        vm.removeSlice(symbol: "A")
        XCTAssertEqual(vm.slices.map(\.symbol), ["B"])
    }

    // MARK: - equalSplit: largest-remainder method

    func test_equalSplit_threeSlices_usesLargestRemainder() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.addSlice(asset: Asset(symbol: "A", name: "A", kind: .stock))
        vm.addSlice(asset: Asset(symbol: "B", name: "B", kind: .stock))
        vm.addSlice(asset: Asset(symbol: "C", name: "C", kind: .stock))

        vm.equalSplit()

        XCTAssertEqual(vm.slices.map { $0.targetWeight.value }, [
            Decimal(string: "33.33"), Decimal(string: "33.33"), Decimal(string: "33.34"),
        ])
        XCTAssertEqual(vm.weightSumPP, 100, "largest-remainder split must sum to EXACTLY 100")
    }

    func test_equalSplit_twoSlices_evenSplit() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.slices = [sliceA, sliceB]
        vm.equalSplit()
        XCTAssertEqual(vm.slices.map { $0.targetWeight.value }, [50, 50])
    }

    // MARK: - save(): schedule only when enabled

    func test_save_withoutSchedule_createsValidPieAndPersistsIt() async {
        let pieStore = MemoryPieStore()
        let vm = makeVM(pieStore: pieStore, repo: VMFakeRepo())
        vm.name = "No Schedule Pie"
        vm.slices = [sliceA, sliceB]

        let saved = await vm.save()

        XCTAssertTrue(saved)
        XCTAssertEqual(pieStore.pies.count, 1)
        XCTAssertEqual(pieStore.pies.first?.name, "No Schedule Pie")
        XCTAssertNil(pieStore.pies.first?.schedule)
    }

    func test_save_withScheduleEnabled_anchorDayEqualsInitialNextDueDay() async {
        let pieStore = MemoryPieStore()
        let vm = makeVM(pieStore: pieStore, repo: VMFakeRepo())
        vm.name = "Scheduled Pie"
        vm.slices = [sliceA, sliceB]
        vm.scheduleEnabled = true
        vm.cadence = .weekly
        vm.scheduleAmountText = "50"

        let saved = await vm.save()
        XCTAssertTrue(saved)

        let today = calendar.tradingDay(of: fixedNow)
        // Any sentinel day strictly before `today` produces the same result as the
        // production `dayBefore(today)` call here — `nextDueDay`'s step-0 branch only
        // checks `unrolledDay > afterDay`, which is true either way since the anchor
        // itself (`today`) is being evaluated.
        let expectedFirstDue = PieSchedule.nextDueDay(
            anchorDay: today, cadence: .weekly, afterDay: "1900-01-01", calendar: calendar
        )

        let schedule = pieStore.pies.first?.schedule
        XCTAssertEqual(schedule?.anchorDay, expectedFirstDue)
        XCTAssertEqual(schedule?.nextDueDay, expectedFirstDue)
        XCTAssertEqual(schedule?.amount, usd(50))
        XCTAssertEqual(schedule?.cadence, .weekly)
    }

    // MARK: - Editing an existing SCHEDULED pie must not silently re-anchor the cadence

    func test_save_scheduledPie_nameOnlyEdit_preservesScheduleByteIdentical() async {
        let schedule = ContributionSchedule(amount: usd(50), cadence: .monthly, anchorDay: "2025-01-03", nextDueDay: "2025-05-02")
        let existing = try! Pie(id: "p1", name: "Old Name", slices: [sliceA, sliceB], schedule: schedule, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [existing]
        let vm = makeVM(existingPie: existing, pieStore: pieStore, repo: VMFakeRepo())
        vm.name = "New Name" // the only change

        let saved = await vm.save()

        XCTAssertTrue(saved)
        XCTAssertEqual(pieStore.pies.first?.name, "New Name")
        XCTAssertEqual(pieStore.pies.first?.schedule, schedule, "schedule must be byte-identical after a name-only edit")
    }

    func test_save_scheduledPie_amountOnlyEdit_preservesAnchorAndCursor_updatesAmount() async {
        let schedule = ContributionSchedule(amount: usd(50), cadence: .monthly, anchorDay: "2025-01-03", nextDueDay: "2025-05-02")
        let existing = try! Pie(id: "p1", name: "Pie", slices: [sliceA, sliceB], schedule: schedule, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [existing]
        let vm = makeVM(existingPie: existing, pieStore: pieStore, repo: VMFakeRepo())
        vm.scheduleAmountText = "75" // the only change

        let saved = await vm.save()

        XCTAssertTrue(saved)
        let updated = pieStore.pies.first?.schedule
        XCTAssertEqual(updated?.anchorDay, "2025-01-03", "anchor must survive an amount-only edit")
        XCTAssertEqual(updated?.nextDueDay, "2025-05-02", "the schedule cursor must survive an amount-only edit")
        XCTAssertEqual(updated?.amount, usd(75))
        XCTAssertEqual(updated?.cadence, .monthly)
    }

    func test_save_scheduledPie_cadenceChange_startsFreshAnchorFromToday() async {
        let schedule = ContributionSchedule(amount: usd(50), cadence: .monthly, anchorDay: "2025-01-03", nextDueDay: "2025-05-02")
        let existing = try! Pie(id: "p1", name: "Pie", slices: [sliceA, sliceB], schedule: schedule, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [existing]
        let vm = makeVM(existingPie: existing, pieStore: pieStore, repo: VMFakeRepo())
        let today = calendar.tradingDay(of: fixedNow)
        vm.cadence = .weekly // a new rhythm legitimately restarts the schedule
        // F2: a cadence change re-anchors on `scheduleStartDay`, not unconditionally on
        // today — the field pre-filled from the OLD anchor ("2025-01-03", now in the
        // past), so the user must (re)choose a day; here that choice is today, matching
        // this test's original (pre-F2) expectation.
        vm.scheduleStartDay = today

        let saved = await vm.save()

        XCTAssertTrue(saved)
        let expectedFirstDue = PieSchedule.rollToTradingDay(today, calendar: calendar)
        let updated = pieStore.pies.first?.schedule
        XCTAssertEqual(updated?.anchorDay, expectedFirstDue)
        XCTAssertEqual(updated?.nextDueDay, expectedFirstDue)
        XCTAssertNotEqual(updated?.anchorDay, "2025-01-03", "a cadence change must re-anchor, not keep the old anchor")
        XCTAssertEqual(updated?.cadence, .weekly)
    }

    // MARK: - F2: scheduleStartDay

    func test_scheduleStartDay_defaultsToTodaysTradingDay() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        XCTAssertEqual(vm.scheduleStartDay, calendar.tradingDay(of: fixedNow))
    }

    func test_save_newSchedule_futureSaturdayStart_rollsToMondayAnchor() async {
        let pieStore = MemoryPieStore()
        let vm = makeVM(pieStore: pieStore, repo: VMFakeRepo())
        vm.name = "Scheduled Pie"
        vm.slices = [sliceA, sliceB]
        vm.scheduleEnabled = true
        vm.scheduleAmountText = "50"
        // 2025-07-19 is a Saturday; the next trading day is Monday 2025-07-21.
        vm.scheduleStartDay = "2025-07-19"
        XCTAssertTrue(vm.canSave, "a future Saturday is still >= today, so it must remain valid")

        let saved = await vm.save()

        XCTAssertTrue(saved)
        XCTAssertEqual(pieStore.pies.first?.schedule?.anchorDay, "2025-07-21")
        XCTAssertEqual(pieStore.pies.first?.schedule?.nextDueDay, "2025-07-21")
    }

    func test_scheduleStartDay_pastDay_blocksCanSave() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.name = "Scheduled Pie"
        vm.slices = [sliceA, sliceB]
        vm.scheduleEnabled = true
        vm.scheduleAmountText = "50"
        XCTAssertTrue(vm.canSave, "precondition: valid before touching the start day")

        vm.scheduleStartDay = "2020-01-01" // long past
        XCTAssertFalse(vm.canSave, "a past start day must block save")

        vm.scheduleStartDay = calendar.tradingDay(of: fixedNow)
        XCTAssertTrue(vm.canSave, "restoring a valid (today) start day unblocks save again")
    }

    func test_scheduleStartDay_malformed_blocksCanSave() {
        let vm = makeVM(pieStore: MemoryPieStore(), repo: VMFakeRepo())
        vm.name = "Scheduled Pie"
        vm.slices = [sliceA, sliceB]
        vm.scheduleEnabled = true
        vm.scheduleAmountText = "50"

        vm.scheduleStartDay = "not-a-date"
        XCTAssertFalse(vm.canSave)
    }

    func test_save_scheduledPie_cadenceUnchangedEdit_ignoresScheduleStartDay() async {
        let schedule = ContributionSchedule(amount: usd(50), cadence: .monthly, anchorDay: "2025-01-03", nextDueDay: "2025-05-02")
        let existing = try! Pie(id: "p1", name: "Pie", slices: [sliceA, sliceB], schedule: schedule, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [existing]
        let vm = makeVM(existingPie: existing, pieStore: pieStore, repo: VMFakeRepo())

        // The field pre-fills from the existing (now long-past) anchor, which would be
        // an INVALID start day on its own — but cadence is unchanged, so it must never
        // be consulted, and canSave/save must both succeed regardless.
        XCTAssertEqual(vm.scheduleStartDay, "2025-01-03", "precondition: pre-filled from the existing anchor")
        XCTAssertTrue(vm.canSave, "a cadence-unchanged edit must not be blocked by the pre-filled (past) start day")
        vm.scheduleAmountText = "80" // an ordinary amount-only edit

        let saved = await vm.save()

        XCTAssertTrue(saved)
        let updated = pieStore.pies.first?.schedule
        XCTAssertEqual(updated?.anchorDay, "2025-01-03", "cadence-unchanged edit preserves the existing anchor untouched")
        XCTAssertEqual(updated?.nextDueDay, "2025-05-02")
        XCTAssertEqual(updated?.amount, usd(80))
    }

    func test_save_scheduledPie_cadenceChange_reAnchorsOnChosenDay() async {
        let schedule = ContributionSchedule(amount: usd(50), cadence: .monthly, anchorDay: "2025-01-03", nextDueDay: "2025-05-02")
        let existing = try! Pie(id: "p1", name: "Pie", slices: [sliceA, sliceB], schedule: schedule, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [existing]
        let vm = makeVM(existingPie: existing, pieStore: pieStore, repo: VMFakeRepo())
        vm.cadence = .weekly
        // The user explicitly picks a future start day distinct from both the old
        // anchor and today.
        vm.scheduleStartDay = "2025-07-19" // a Saturday -> rolls to Monday 2025-07-21

        let saved = await vm.save()

        XCTAssertTrue(saved)
        let updated = pieStore.pies.first?.schedule
        XCTAssertEqual(updated?.anchorDay, "2025-07-21")
        XCTAssertEqual(updated?.nextDueDay, "2025-07-21")
        XCTAssertEqual(updated?.cadence, .weekly)
    }

    func test_save_existingPieWithoutSchedule_enablingSchedule_startsFreshFromToday() async {
        let existing = try! Pie(id: "p1", name: "Pie", slices: [sliceA, sliceB], schedule: nil, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [existing]
        let vm = makeVM(existingPie: existing, pieStore: pieStore, repo: VMFakeRepo())
        vm.scheduleEnabled = true
        vm.cadence = .weekly
        vm.scheduleAmountText = "20"

        let saved = await vm.save()

        XCTAssertTrue(saved)
        let today = calendar.tradingDay(of: fixedNow)
        let expectedFirstDue = PieSchedule.nextDueDay(
            anchorDay: today, cadence: .weekly, afterDay: "1900-01-01", calendar: calendar
        )
        XCTAssertEqual(pieStore.pies.first?.schedule?.anchorDay, expectedFirstDue)
        XCTAssertEqual(pieStore.pies.first?.schedule?.nextDueDay, expectedFirstDue)
    }

    func test_save_updatesExistingPie_preservesIdLedgerAndCreatedDay() async {
        let ledger = [PieLedgerEntry(symbol: "A", quantity: Quantity(3))]
        let existing = try! Pie(id: "existing-1", name: "Old Name", slices: [sliceA, sliceB], schedule: nil,
                                createdDay: "2020-01-01", ledger: ledger)
        let pieStore = MemoryPieStore()
        pieStore.pies = [existing]
        let vm = makeVM(existingPie: existing, pieStore: pieStore, repo: VMFakeRepo())
        vm.name = "New Name"

        let saved = await vm.save()

        XCTAssertTrue(saved)
        XCTAssertEqual(pieStore.pies.count, 1, "update replaces, doesn't duplicate")
        let updated = pieStore.pies.first
        XCTAssertEqual(updated?.id, "existing-1")
        XCTAssertEqual(updated?.name, "New Name")
        XCTAssertEqual(updated?.createdDay, "2020-01-01")
        XCTAssertEqual(updated?.quantity(of: "A"), Quantity(3))
    }

    func test_save_returnsFalse_whenCanSaveIsFalse() async {
        let pieStore = MemoryPieStore()
        let vm = makeVM(pieStore: pieStore, repo: VMFakeRepo())
        vm.name = "" // invalid: empty name
        vm.slices = [sliceA, sliceB]

        let saved = await vm.save()

        XCTAssertFalse(saved)
        XCTAssertTrue(pieStore.pies.isEmpty)
    }

    // MARK: - runBacktest wires SimulateDCA

    func test_runBacktest_wiresSimulateDCA_producesReport() async {
        let calendar = self.calendar
        let now = fixedNow
        let startDate = Calendar.current.date(byAdding: DateComponents(year: -1), to: now) ?? now
        let startDay = calendar.tradingDay(of: startDate)

        let repo = VMFakeRepo()
        repo.histories["A"] = [PricePoint(date: startDate, close: usd(10))]

        let vm = makeVM(pieStore: MemoryPieStore(), repo: repo)
        vm.name = "Backtest Pie"
        vm.slices = [PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 100))]
        vm.scheduleEnabled = true
        vm.cadence = .weekly
        vm.scheduleAmountText = "10"

        XCTAssertNil(vm.backtest)
        await vm.runBacktest(years: 1)

        XCTAssertNotNil(vm.backtest, "a single valid close on the computed start day (\(startDay)) should produce a report")
        XCTAssertEqual(vm.backtest?.totalInvested, usd(10))
    }

    func test_runBacktest_noHistory_leavesBacktestNil() async {
        let repo = VMFakeRepo() // no histories configured
        let vm = makeVM(pieStore: MemoryPieStore(), repo: repo)
        vm.name = "No History Pie"
        vm.slices = [PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 100))]
        vm.scheduleEnabled = true
        vm.scheduleAmountText = "10"

        await vm.runBacktest(years: 1)

        XCTAssertNil(vm.backtest)
    }

    func test_runBacktest_invalidAmount_doesNotRunAndLeavesBacktestNil() async {
        let repo = VMFakeRepo()
        repo.histories["A"] = [PricePoint(date: fixedNow, close: usd(10))]
        let vm = makeVM(pieStore: MemoryPieStore(), repo: repo)
        vm.slices = [PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 100))]
        vm.scheduleAmountText = "" // no valid amount to simulate with

        await vm.runBacktest(years: 1)

        XCTAssertNil(vm.backtest)
    }

    // MARK: - updateSearchQuery: debounced search, excluding already-added slices

    func test_updateSearchQuery_excludesAlreadyAddedSlices() async throws {
        let repo = VMFakeRepo()
        repo.searchResults = [
            Asset(symbol: "A", name: "A Corp", kind: .stock),
            Asset(symbol: "C", name: "C Corp", kind: .stock),
        ]
        let vm = makeVM(pieStore: MemoryPieStore(), repo: repo)
        vm.addSlice(asset: Asset(symbol: "A", name: "A Corp", kind: .stock))

        vm.updateSearchQuery("corp")
        try await Task.sleep(for: .milliseconds(400)) // past the 250ms debounce

        XCTAssertEqual(vm.searchResults.map(\.symbol), ["C"], "an already-added symbol (A) must be excluded")
    }

    func test_updateSearchQuery_emptyQuery_clearsResultsSynchronously() async throws {
        let repo = VMFakeRepo()
        repo.searchResults = [Asset(symbol: "A", name: "A Corp", kind: .stock)]
        let vm = makeVM(pieStore: MemoryPieStore(), repo: repo)

        vm.updateSearchQuery("a")
        try await Task.sleep(for: .milliseconds(400))
        XCTAssertFalse(vm.searchResults.isEmpty, "precondition: the debounced search must have populated results")

        vm.updateSearchQuery("") // no debounce wait needed — an empty query clears immediately
        XCTAssertTrue(vm.searchResults.isEmpty)
    }

    func test_addSlice_clearsSearchResults() async throws {
        let repo = VMFakeRepo()
        repo.searchResults = [Asset(symbol: "A", name: "A Corp", kind: .stock)]
        let vm = makeVM(pieStore: MemoryPieStore(), repo: repo)

        vm.updateSearchQuery("a")
        try await Task.sleep(for: .milliseconds(400))
        XCTAssertFalse(vm.searchResults.isEmpty, "precondition: the debounced search must have populated results")

        vm.addSlice(asset: Asset(symbol: "A", name: "A Corp", kind: .stock))
        XCTAssertTrue(vm.searchResults.isEmpty, "adding a slice must clear the now-stale search results")
    }

    // MARK: - F3: removing a slice drops its now-orphaned ledger entry at save-time

    func test_save_removingSlice_dropsOrphanedLedgerEntry_activityAndSurvivingEntryUntouched() async {
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(5)),
            PieLedgerEntry(symbol: "B", quantity: Quantity(3)),
        ]
        let activity = [PieActivityEntry(kind: .contribution, day: "2025-01-01", amount: usd(100))]
        let existing = try! Pie(id: "p1", name: "Pie", slices: [sliceA, sliceB], schedule: nil,
                                createdDay: "2025-01-01", ledger: ledger, activity: activity)
        let pieStore = MemoryPieStore()
        pieStore.pies = [existing]
        let vm = makeVM(existingPie: existing, pieStore: pieStore, repo: VMFakeRepo())

        vm.removeSlice(symbol: "B")
        vm.equalSplit() // re-normalize the sole remaining slice to 100%

        let saved = await vm.save()

        XCTAssertTrue(saved)
        let updated = pieStore.pies.first
        XCTAssertEqual(updated?.ledger.map(\.symbol), ["A"], "the orphaned B ledger entry must be dropped")
        XCTAssertEqual(updated?.quantity(of: "A"), Quantity(5), "the surviving slice's ledger entry is untouched")
        XCTAssertEqual(updated?.activity, activity, "activity history (the audit log) must be left untouched")
    }

    // MARK: - F3 (reviewer scenario): a dead ledger claim, once filtered at save-time,
    // must no longer wrongly clamp a different pie's legitimate claim to the same symbol.

    func test_save_removingSlice_thenReconcile_otherPieNoLongerWronglyClamped() async throws {
        // Pie A: has an AAPL slice with a 3-share ledger claim, plus an unrelated MSFT
        // slice. This edit REMOVES the AAPL slice entirely, leaving its ledger entry
        // "dead" once F3's filter applies.
        let sliceAAPL = PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 50))
        let sliceMSFT = PieSlice(symbol: "MSFT", assetKind: .stock, targetWeight: Percentage(value: 50))
        let pieALedger = [
            PieLedgerEntry(symbol: "AAPL", quantity: Quantity(3)),
            PieLedgerEntry(symbol: "MSFT", quantity: Quantity(3)),
        ]
        let pieA = try Pie(id: "pieA", name: "Pie A", slices: [sliceAAPL, sliceMSFT], schedule: nil,
                           createdDay: "2025-01-01", ledger: pieALedger)

        // Pie B: legitimately claims 12 AAPL shares, untouched by this edit.
        let pieBSlice = PieSlice(symbol: "AAPL", assetKind: .stock, targetWeight: Percentage(value: 100))
        let pieB = try Pie(id: "pieB", name: "Pie B", slices: [pieBSlice], schedule: nil,
                           createdDay: "2025-01-01",
                           ledger: [PieLedgerEntry(symbol: "AAPL", quantity: Quantity(12))])

        let pieStore = MemoryPieStore()
        pieStore.pies = [pieA, pieB]

        // Edit pie A: drop the AAPL slice (keep MSFT). Per F3, saving must drop the
        // now-orphaned 3-AAPL ledger claim rather than carry it forward as a dead claim.
        let vm = makeVM(existingPie: pieA, pieStore: pieStore, repo: VMFakeRepo())
        vm.removeSlice(symbol: "AAPL")
        vm.equalSplit()
        let saved = await vm.save()
        XCTAssertTrue(saved)
        XCTAssertNil(pieStore.pies.first(where: { $0.id == "pieA" })?.ledger.first(where: { $0.symbol == "AAPL" }),
                     "pie A's dead AAPL claim must be dropped at save-time")

        // Portfolio: originally held 15 AAPL (3 + 12, matching both pies' original
        // claims); a manual sell (outside any pie) drops the actual holding to 10.
        let portfolio = Portfolio(cash: usd(0), positions: [
            Position(asset: Asset(symbol: "AAPL", name: "AAPL", kind: .stock), quantity: Quantity(10),
                    averageCost: usd(10), realizedPnL: usd(0))
        ], transactions: [])
        let portfolioStore = MemoryPortfolioStore(portfolio)

        let reconcile = ReconcilePieLedgers(pieStore: pieStore, portfolioStore: portfolioStore, serializer: TradeSerializer())
        let result = await reconcile()

        // Without F3's fix, pie A's still-present 3-share dead claim would make the
        // largest-clamps-first walk wrongly reduce pie B's legitimate 12-share claim to
        // 7 (preserving A's 3 in full first). With the dead claim filtered at save-time,
        // pie B is the only remaining claimant and correctly receives the FULL 10 held.
        let resultB = result.first { $0.id == "pieB" }
        XCTAssertEqual(resultB?.quantity(of: "AAPL"), Quantity(10),
                       "pie B must receive the full 10 held shares, not be wrongly clamped to 7 by A's dead claim")
    }
}
