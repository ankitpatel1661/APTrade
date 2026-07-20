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
            savePie: SavePie(store: pieStore),
            simulateDCA: SimulateDCA(market: repo, calendar: calendar),
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

        vm.addSlice(asset: Asset(symbol: "C", name: "C", kind: .stock))
        vm.setWeight(symbol: "C", pp: 5)

        XCTAssertEqual(vm.weightSumPP, 105)
        XCTAssertFalse(vm.canSave, "sum must be EXACTLY 100 for canSave")
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
        vm.cadence = .weekly // a new rhythm legitimately restarts the schedule

        let saved = await vm.save()

        XCTAssertTrue(saved)
        let today = calendar.tradingDay(of: fixedNow)
        let expectedFirstDue = PieSchedule.nextDueDay(
            anchorDay: today, cadence: .weekly, afterDay: "1900-01-01", calendar: calendar
        )
        let updated = pieStore.pies.first?.schedule
        XCTAssertEqual(updated?.anchorDay, expectedFirstDue)
        XCTAssertEqual(updated?.nextDueDay, expectedFirstDue)
        XCTAssertNotEqual(updated?.anchorDay, "2025-01-03", "a cadence change must re-anchor, not keep the old anchor")
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
}
