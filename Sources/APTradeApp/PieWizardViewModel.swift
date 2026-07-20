import Foundation
import APTradeApplication
import APTradeDomain

/// Drives the pie creation/edit wizard: name, slice allocation (with a live 100%
/// weight-sum check), an optional recurring contribution schedule, and an on-demand DCA
/// backtest preview before saving.
@MainActor
@Observable
public final class PieWizardViewModel {

    public var name: String {
        didSet { recomputeValidation() }
    }
    public var slices: [PieSlice] {
        didSet { recomputeValidation() }
    }
    public var scheduleAmountText: String {
        didSet { recomputeValidation() }
    }
    public var cadence: PieCadence
    public var scheduleEnabled: Bool {
        didSet { recomputeValidation() }
    }

    /// Live sum of every slice's target weight, in percentage points. `canSave` requires
    /// this to be exactly 100.
    public private(set) var weightSumPP: Decimal = 0
    public private(set) var canSave: Bool = false
    /// `nil` until `runBacktest` completes, and again if the run finds insufficient
    /// history or fails outright (mirrors `SimulateDCA`'s own non-throwing degrade-to-nil).
    public private(set) var backtest: BacktestReport?

    /// Debounced asset-search results for the slice-editor step, already filtered to
    /// exclude symbols that are already slices. Populated by `updateSearchQuery`.
    public private(set) var searchResults: [Asset] = []

    /// The Pie being edited, or `nil` when creating a new one. Supplies the id, ledger,
    /// activity log, and creation day that `save()` must carry through unchanged.
    private let existingPie: Pie?
    private let savePie: SavePie
    private let simulateDCA: SimulateDCA
    private let searchAssets: SearchAssetsUseCase
    private let calendar: MarketCalendar
    private let now: () -> Date
    private var searchTask: Task<Void, Never>?

    public init(
        existingPie: Pie? = nil,
        savePie: SavePie,
        simulateDCA: SimulateDCA,
        searchAssets: SearchAssetsUseCase,
        calendar: MarketCalendar = MarketCalendar(),
        now: @escaping () -> Date = Date.init
    ) {
        self.existingPie = existingPie
        self.savePie = savePie
        self.simulateDCA = simulateDCA
        self.searchAssets = searchAssets
        self.calendar = calendar
        self.now = now
        self.name = existingPie?.name ?? ""
        self.slices = existingPie?.slices ?? []
        if let schedule = existingPie?.schedule {
            self.scheduleEnabled = true
            self.scheduleAmountText = Self.text(for: schedule.amount)
            self.cadence = schedule.cadence
        } else {
            self.scheduleEnabled = false
            self.scheduleAmountText = ""
            self.cadence = .monthly
        }
        // Property observers don't fire for a type's own assignments inside its own
        // initializer, so the initial validation state has to be computed explicitly.
        recomputeValidation()
    }

    public func addSlice(asset: Asset) {
        guard !slices.contains(where: { $0.symbol == asset.symbol }) else { return }
        slices.append(PieSlice(symbol: asset.symbol, assetKind: asset.kind, targetWeight: Percentage(value: 0)))
        clearSearch()
    }

    public func removeSlice(symbol: String) {
        slices.removeAll { $0.symbol == symbol }
    }

    /// Debounced (250ms) autocomplete for the slice-editor step's search field, reusing
    /// the same `SearchAssetsUseCase` the watchlist/command palette use. Cancels any
    /// in-flight search on each keystroke (mirrors `WatchlistViewModel.updateQuery`'s
    /// identical debounce/cancel pattern) and excludes symbols already added as slices,
    /// so a result never invites a duplicate `addSlice` no-op.
    public func updateSearchQuery(_ text: String) {
        searchTask?.cancel()
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { searchResults = []; return }
        searchTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self else { return }
            let results = (try? await self.searchAssets(query: query)) ?? []
            guard !Task.isCancelled else { return }
            self.searchResults = results.filter { asset in !self.slices.contains { $0.symbol == asset.symbol } }
        }
    }

    /// Cancels any in-flight search and clears the results — called after a slice is
    /// added (the query text becomes stale) or when the view wants to dismiss the list.
    public func clearSearch() {
        searchTask?.cancel()
        searchResults = []
    }

    public func setWeight(symbol: String, pp: Decimal) {
        guard let index = slices.firstIndex(where: { $0.symbol == symbol }) else { return }
        let slice = slices[index]
        slices[index] = PieSlice(symbol: slice.symbol, assetKind: slice.assetKind, targetWeight: Percentage(value: pp))
    }

    /// Splits 100pp evenly across every current slice via `PieMath.equalWeights`
    /// (largest-remainder method, so the weights always sum to exactly 100).
    public func equalSplit() {
        let weights = PieMath.equalWeights(count: slices.count)
        slices = zip(slices, weights).map { slice, weight in
            PieSlice(symbol: slice.symbol, assetKind: slice.assetKind, targetWeight: weight)
        }
    }

    /// Runs a DCA backtest over the wizard's current slices/amount/cadence. Requires at
    /// least one slice and a valid (positive) schedule amount; otherwise clears
    /// `backtest` without calling `simulateDCA`.
    public func runBacktest(years: Int) async {
        guard !slices.isEmpty, let amount = parsedScheduleAmount else {
            backtest = nil
            return
        }
        backtest = await simulateDCA(slices: slices, amount: amount, cadence: cadence, years: years, now: now())
    }

    /// Constructs (or updates) the Pie and persists it via `SavePie`. Returns `false`
    /// without saving when `canSave` is false, or if `Pie`'s own validation rejects the
    /// result — unreachable in practice since `canSave` mirrors `Pie`'s validation rules
    /// exactly, but handled defensively rather than force-tried.
    public func save() async -> Bool {
        guard canSave else { return false }

        var schedule: ContributionSchedule?
        if scheduleEnabled, let amount = parsedScheduleAmount {
            schedule = buildSchedule(amount: amount)
        }

        do {
            let pie = try Pie(
                id: existingPie?.id ?? UUID().uuidString,
                name: name,
                slices: slices,
                schedule: schedule,
                createdDay: existingPie?.createdDay ?? calendar.tradingDay(of: now()),
                ledger: existingPie?.ledger ?? [],
                activity: existingPie?.activity ?? []
            )
            _ = await savePie(pie)
            return true
        } catch {
            return false
        }
    }

    /// Decides the `ContributionSchedule` to save, per this rule (fixes a bug where any
    /// edit — even a name-only one — silently re-anchored a pie's cadence, corrupting
    /// `ContributionSchedule.anchorDay`'s "fixed for the schedule's entire lifetime"
    /// invariant, since `PieSchedule`'s monthly stepping must always derive from that
    /// one original anchor to avoid the Jan 31 -> Feb 28 -> Mar 28 (should be Mar 31)
    /// drift described there):
    ///
    /// - New pie, or an existing pie that previously had NO schedule: start a fresh
    ///   schedule, `anchorDay` = the initial `nextDueDay` computed from today.
    /// - Existing pie that already has a schedule, cadence UNCHANGED: preserve
    ///   `anchorDay` AND the `nextDueDay` cursor exactly — only `amount` may differ.
    /// - Existing pie that already has a schedule, cadence CHANGED: start a fresh
    ///   schedule anchored on today — a new rhythm legitimately restarts the cycle.
    private func buildSchedule(amount: Money) -> ContributionSchedule {
        if let previous = existingPie?.schedule, previous.cadence == cadence {
            return ContributionSchedule(amount: amount, cadence: cadence,
                                        anchorDay: previous.anchorDay, nextDueDay: previous.nextDueDay)
        }

        let today = calendar.tradingDay(of: now())
        // The schedule's `anchorDay` is fixed to the INITIAL `nextDueDay` computed from
        // today. `PieSchedule.nextDueDay`'s step-0 case treats the anchor itself
        // (`today`) as eligible when it's strictly after `afterDay`, so passing
        // yesterday lets today itself become the very first due day.
        let firstDue = PieSchedule.nextDueDay(
            anchorDay: today, cadence: cadence, afterDay: dayBefore(today), calendar: calendar
        )
        return ContributionSchedule(amount: amount, cadence: cadence, anchorDay: firstDue, nextDueDay: firstDue)
    }

    // MARK: - Validation

    private func recomputeValidation() {
        weightSumPP = slices.reduce(Decimal(0)) { $0 + $1.targetWeight.value }
        let uniqueSymbols = Set(slices.map(\.symbol)).count == slices.count
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let amountValid = !scheduleEnabled || parsedScheduleAmount != nil
        canSave = !slices.isEmpty && uniqueSymbols && !trimmedName.isEmpty && weightSumPP == 100 && amountValid
    }

    private var parsedScheduleAmount: Money? {
        guard let value = Decimal(string: scheduleAmountText), value > 0 else { return nil }
        return Money(amount: value)
    }

    private static func text(for money: Money) -> String {
        NSDecimalNumber(decimal: money.amount).stringValue
    }

    /// The calendar day immediately before `day`. Mirrors `PieMathBacktest`'s and
    /// `ExecuteDueContributions`'s identical private helper (duplicated rather than
    /// shared — a small pure day-math step isn't worth a cross-layer dependency here).
    private func dayBefore(_ day: String) -> String {
        guard let date = PieSchedule.date(fromDay: day, calendar: calendar) else { return day }
        guard let previous = Self.parsingCalendar.date(byAdding: .day, value: -1, to: date) else { return day }
        return calendar.tradingDay(of: previous)
    }

    private static var parsingCalendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        return cal
    }
}
