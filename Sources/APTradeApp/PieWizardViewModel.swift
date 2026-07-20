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
    public var cadence: PieCadence {
        // A cadence change flips `willCreateNewSchedule` (see `buildSchedule`), which
        // changes whether `scheduleStartDay` is even consulted for `canSave` — so a
        // cadence change must re-run validation exactly like the other schedule fields.
        didSet { recomputeValidation() }
    }
    public var scheduleEnabled: Bool {
        didSet { recomputeValidation() }
    }
    /// The day (`yyyy-MM-dd`) a NEW or re-anchored schedule's first contribution should
    /// land on. Defaults to today's trading day at init; when editing an already-scheduled
    /// pie, pre-fills from the existing `anchorDay` for display — but per `buildSchedule`'s
    /// rule, that pre-filled value is only actually HONORED when a new schedule is being
    /// (re)created (new pie, previously-unscheduled, or a cadence change), never on a
    /// cadence-unchanged edit, which preserves the existing anchor/cursor untouched.
    public var scheduleStartDay: String {
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
            self.scheduleStartDay = schedule.anchorDay
        } else {
            self.scheduleEnabled = false
            self.scheduleAmountText = ""
            self.cadence = .monthly
            self.scheduleStartDay = calendar.tradingDay(of: now())
        }
        // Property observers don't fire for a type's own assignments inside its own
        // initializer, so the initial validation state has to be computed explicitly.
        recomputeValidation()
    }

    /// `scheduleStartDay` as a `Date`, for the schedule step's `DatePicker`. Conversion
    /// lives here (not the view) so `PieWizardView` stays purely declarative — get parses
    /// via `PieSchedule.date(fromDay:calendar:)` (falling back to `now()` on a momentarily
    /// unparseable value, which a `DatePicker`-driven field should never actually produce);
    /// set re-derives the trading-day string via `calendar.tradingDay(of:)`, which also
    /// re-triggers `scheduleStartDay`'s own validation via its `didSet`.
    public var scheduleStartDate: Date {
        get { PieSchedule.date(fromDay: scheduleStartDay, calendar: calendar) ?? now() }
        set { scheduleStartDay = calendar.tradingDay(of: newValue) }
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
    ///   schedule, `anchorDay` = the user-chosen `scheduleStartDay` (F2), rolled to a
    ///   trading day.
    /// - Existing pie that already has a schedule, cadence UNCHANGED: preserve
    ///   `anchorDay` AND the `nextDueDay` cursor exactly — only `amount` may differ.
    ///   `scheduleStartDay` is NOT consulted on this path (see its doc comment).
    /// - Existing pie that already has a schedule, cadence CHANGED: start a fresh
    ///   schedule anchored on `scheduleStartDay` — a new rhythm legitimately restarts
    ///   the cycle.
    private func buildSchedule(amount: Money) -> ContributionSchedule {
        if let previous = existingPie?.schedule, previous.cadence == cadence {
            return ContributionSchedule(amount: amount, cadence: cadence,
                                        anchorDay: previous.anchorDay, nextDueDay: previous.nextDueDay)
        }

        // A NEW schedule anchors on the user-chosen start day (defaults to today, but the
        // wizard lets it be moved into the future), rolled forward to the next trading day
        // if it lands on a weekend/holiday — `canSave`'s `isValidScheduleStartDay` check
        // already guarantees this parses and is not in the past.
        let anchor = PieSchedule.rollToTradingDay(scheduleStartDay, calendar: calendar)
        return ContributionSchedule(amount: amount, cadence: cadence, anchorDay: anchor, nextDueDay: anchor)
    }

    // MARK: - Validation

    private func recomputeValidation() {
        weightSumPP = slices.reduce(Decimal(0)) { $0 + $1.targetWeight.value }
        let uniqueSymbols = Set(slices.map(\.symbol)).count == slices.count
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let amountValid = !scheduleEnabled || parsedScheduleAmount != nil
        // `scheduleStartDay` only needs to be valid when it will actually be USED to
        // build a schedule (see `buildSchedule`'s doc comment): a cadence-unchanged edit
        // of an already-scheduled pie preserves the existing anchor/cursor untouched and
        // never reads `scheduleStartDay` at all, so an unrelated (e.g. long-past)
        // pre-filled value there must never block saving that edit.
        let startDayValid = !willCreateNewSchedule || isValidScheduleStartDay
        canSave = !slices.isEmpty && uniqueSymbols && !trimmedName.isEmpty && weightSumPP == 100
            && amountValid && startDayValid
    }

    private var parsedScheduleAmount: Money? {
        guard let value = Decimal(string: scheduleAmountText), value > 0 else { return nil }
        return Money(amount: value)
    }

    /// `true` when saving would build a NEW schedule anchor from `scheduleStartDay`
    /// (see `buildSchedule`): a new pie, an existing pie that had no schedule, or a
    /// cadence change on an already-scheduled pie. `false` for a cadence-unchanged edit,
    /// which preserves the existing anchor/cursor and ignores `scheduleStartDay` entirely.
    private var willCreateNewSchedule: Bool {
        guard scheduleEnabled else { return false }
        guard let previous = existingPie?.schedule else { return true }
        return previous.cadence != cadence
    }

    /// `scheduleStartDay` must parse as a real calendar day and be no earlier than
    /// today's trading day — a schedule cannot be anchored in the past.
    private var isValidScheduleStartDay: Bool {
        guard PieSchedule.date(fromDay: scheduleStartDay, calendar: calendar) != nil else { return false }
        return scheduleStartDay >= calendar.tradingDay(of: now())
    }

    private static func text(for money: Money) -> String {
        NSDecimalNumber(decimal: money.amount).stringValue
    }
}
