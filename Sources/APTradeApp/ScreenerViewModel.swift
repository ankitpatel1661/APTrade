import Foundation
import APTradeApplication
import APTradeDomain

/// Sortable columns for the screener results table.
///
/// `.activeMetric` resolves to whichever field the active selection's "headline" metric is
/// — see `ScreenerViewModel.activeMetricValue(_:)` for the exact preset → field mapping
/// (a custom screen uses its first condition's metric, falling back to `.price` when the
/// screen has zero conditions).
public enum ScreenerSortColumn: Equatable, Sendable, CaseIterable {
    case symbol, price, dayChange, activeMetric
}

/// Screener tab: orchestrates full-universe technical scans, holds the active screen
/// selection (preset or custom), and produces the sorted, filtered result rows the view
/// renders. Every scan is throttled and reported incrementally by `ScreenerScanEngine`;
/// this view model's job is purely to drive that engine, persist its output, and turn the
/// persisted snapshot + active selection + sort into `results`.
@MainActor
@Observable
public final class ScreenerViewModel {

    /// Lifecycle of a scan-in-progress. `.scanning`'s `done`/`total` mirror the engine's
    /// `onProgress(completedCount, total)` callback, batch by batch.
    public enum ScanState: Equatable, Sendable {
        case idle
        case scanning(done: Int, total: Int)
        case failed
    }

    public private(set) var scanState: ScanState = .idle
    /// The most recent full-universe scan, loaded from `snapshotStore` on init and
    /// replaced only when a scan succeeds (a total failure never touches this).
    public private(set) var snapshot: ScreenerSnapshot?
    public private(set) var savedScreens: [CustomScreen]

    /// The screen currently driving `results`. Settable directly (e.g. from a picker
    /// binding) or via `select(_:)` — either path re-evaluates `results`.
    public var selection: ScreenSelection {
        didSet { recomputeResults() }
    }
    public private(set) var results: [ScreenerSnapshotRow] = []

    public var sortColumn: ScreenerSortColumn {
        didSet { recomputeResults() }
    }
    public var sortAscending: Bool {
        didSet { recomputeResults() }
    }

    /// True when the persisted snapshot was scanned on today's trading day (market-local).
    /// False when there's no snapshot at all.
    public var isSnapshotFresh: Bool {
        guard let snapshot else { return false }
        return snapshot.tradingDay == calendar.tradingDay(of: now())
    }

    private let engine: ScreenerScanEngine
    private let snapshotStore: ScreenerSnapshotStore
    private let screenStore: ScreenStore
    private let symbols: [String]
    private let names: [String: String]
    private let calendar: MarketCalendar
    private let now: () -> Date

    /// Owns the unstructured scan task started by `startScan()`. Holding it here (rather
    /// than letting the view own an unstructured `Task { await viewModel.scan() }`) is
    /// what lets `cancelScan()` actually stop a scan when the view that started it is
    /// torn down (e.g. macOS tab switching destroys a `@State` view model) — a view-owned
    /// `Task` has no such hook and keeps running orphaned, which is how a return-and-
    /// rescan used to fire two overlapping engines.
    private var scanTask: Task<Void, Never>?

    public init(
        engine: ScreenerScanEngine,
        snapshotStore: ScreenerSnapshotStore,
        screenStore: ScreenStore,
        symbols: [String],
        names: [String: String],
        calendar: MarketCalendar = MarketCalendar(),
        now: @escaping () -> Date = Date.init
    ) {
        self.engine = engine
        self.snapshotStore = snapshotStore
        self.screenStore = screenStore
        self.symbols = symbols
        self.names = names
        self.calendar = calendar
        self.now = now
        self.snapshot = snapshotStore.load()
        self.savedScreens = screenStore.load()
        self.selection = .preset(.rsiOversold)
        self.sortColumn = .symbol
        self.sortAscending = true
        // Property observers never fire for a stored property's own initializing
        // assignment inside `init`, so `results` needs one explicit build here.
        recomputeResults()
    }

    /// Runs a full-universe scan. Ignored while a scan is already in flight (a same-state
    /// guard that covers a caller invoking `scan()` directly, independent of the
    /// stored-task guard `startScan()` adds below for view-driven calls). On success, the
    /// new snapshot is persisted and `results` are rebuilt. On cancellation — the engine
    /// only ever throws `CancellationError`, via cooperative `Task.checkCancellation()`
    /// between batches; every per-symbol fetch failure is instead recorded in
    /// `failedSymbols` and does not fail the scan — `scanState` reverts to `.idle` and
    /// nothing is persisted, matching the spec's "interrupted → nothing persisted."
    /// `.failed` is reserved for a genuine, non-cancellation engine throw: no such path
    /// exists today, but the branch stays in place for when one does.
    public func scan() async {
        if case .scanning = scanState { return }
        scanState = .scanning(done: 0, total: symbols.count)
        do {
            let newSnapshot = try await engine.scan(
                symbols: symbols,
                names: names,
                now: now(),
                onProgress: { [weak self] done, total in
                    // `onProgress` fires from the engine's own (non-MainActor) execution
                    // context, so mutating `scanState` — a MainActor-isolated property —
                    // must hop explicitly. MainActor is a strict FIFO serial executor, so
                    // these hops apply in the same order they're enqueued here.
                    Task { @MainActor in
                        self?.scanState = .scanning(done: done, total: total)
                    }
                }
            )
            snapshotStore.save(newSnapshot)
            snapshot = newSnapshot
            scanState = .idle
            recomputeResults()
        } catch is CancellationError {
            scanState = .idle
        } catch {
            scanState = .failed
        }
    }

    /// Owns the unstructured scan task so the view never has to. Re-entry guard is the
    /// stored task itself — a second `startScan()` call while one is already owned here
    /// is silently ignored — independent of (and in addition to) `scan()`'s own
    /// state-based guard. Pair every call site with `cancelScan()` in the view's
    /// `.onDisappear` so a scan started on this tab can't keep running (and racing a
    /// fresh scan) after the view itself is torn down by tab switching.
    public func startScan() {
        guard scanTask == nil else { return }
        scanTask = Task { @MainActor [weak self] in
            await self?.scan()
            self?.scanTask = nil
        }
    }

    /// Cancels the in-flight scan task, if any, and immediately releases ownership of it
    /// (so a subsequent `startScan()` isn't blocked by a task that's merely winding down).
    /// Cancellation itself is cooperative — the engine only notices at its next
    /// `Task.checkCancellation()` between batches — so the visible `scanState` reversion
    /// to `.idle` happens asynchronously inside `scan()`'s catch clause, not here.
    public func cancelScan() {
        scanTask?.cancel()
        scanTask = nil
    }

    public func select(_ selection: ScreenSelection) {
        self.selection = selection
    }

    /// Insert-or-replace by `id`, then persists the full list.
    ///
    /// Also re-syncs `selection` so it can never point at a stale value: if the screen
    /// being saved was ALREADY the active selection (matched by `id` — the old and new
    /// `CustomScreen` values are otherwise `!=` the moment a condition changes), the
    /// active selection is replaced with the freshly-saved value so `results` reflect
    /// the edit immediately rather than showing pre-edit matches against a chip that no
    /// longer even renders as selected. Symmetrically, saving a BRAND-NEW screen (one
    /// `savedScreens` didn't already contain) auto-selects it, so a user who just built
    /// a screen sees its results without an extra tap. Editing some OTHER, non-active
    /// screen leaves `selection` untouched in both cases.
    public func saveScreen(_ screen: CustomScreen) {
        let wasNew = !savedScreens.contains { $0.id == screen.id }
        let wasActiveSelection: Bool
        if case .custom(let active) = selection {
            wasActiveSelection = active.id == screen.id
        } else {
            wasActiveSelection = false
        }

        if let index = savedScreens.firstIndex(where: { $0.id == screen.id }) {
            savedScreens[index] = screen
        } else {
            savedScreens.append(screen)
        }
        screenStore.save(savedScreens)

        if wasNew || wasActiveSelection {
            select(.custom(screen))
        }
    }

    /// Removes the screen, persists the list, then — if the deleted screen was the
    /// active selection — falls back to the default preset. Leaving `selection` pointed
    /// at a `.custom` screen no longer present in `savedScreens` would strand `results`
    /// on a screen with no corresponding chip left to show it was ever active.
    public func deleteScreen(id: String) {
        let wasActiveSelection: Bool
        if case .custom(let active) = selection {
            wasActiveSelection = active.id == id
        } else {
            wasActiveSelection = false
        }

        savedScreens.removeAll { $0.id == id }
        screenStore.save(savedScreens)

        if wasActiveSelection {
            select(.preset(.rsiOversold))
        }
    }

    /// Live match count for the builder sheet: how many rows in the CURRENT snapshot
    /// would pass `conditions` if saved as a screen right now. Mirrors
    /// `ScreenSelection.custom`'s "empty conditions match nothing" rule — an unbuilt
    /// screen should never claim to match the whole universe. Zero (not the universe
    /// size) when there's no snapshot yet.
    public func matchCount(for conditions: [ScreenCondition]) -> Int {
        guard let snapshot, !conditions.isEmpty else { return 0 }
        return snapshot.rows.filter { row in conditions.allSatisfy { $0.matches(row) } }.count
    }

    // MARK: - Results

    private func recomputeResults() {
        guard let snapshot else {
            results = []
            return
        }
        results = sortRows(selection.evaluate(snapshot.rows))
    }

    private func sortRows(_ rows: [ScreenerSnapshotRow]) -> [ScreenerSnapshotRow] {
        switch sortColumn {
        case .symbol:
            return rows.sorted { sortAscending ? $0.symbol < $1.symbol : $0.symbol > $1.symbol }
        case .price:
            // `close` is never nil, so every row sorts as a normal value here.
            return rows.sorted { sortAscending ? $0.close < $1.close : $0.close > $1.close }
        case .dayChange, .activeMetric:
            return sortByNilableMetric(rows)
        }
    }

    /// Shared nil-last sort for the two columns backed by an optional `Double`: rows with a
    /// defined value sort by it (respecting `sortAscending`); rows with a nil value always
    /// sort after every defined row, in both directions, in their original relative order.
    private func sortByNilableMetric(_ rows: [ScreenerSnapshotRow]) -> [ScreenerSnapshotRow] {
        let keyed = rows.map { ($0, metricValue(for: $0)) }
        let withValue = keyed.filter { $0.1 != nil }.sorted { lhs, rhs in
            let l = lhs.1!, r = rhs.1!
            return sortAscending ? l < r : l > r
        }
        let withoutValue = keyed.filter { $0.1 == nil }
        return (withValue + withoutValue).map(\.0)
    }

    private func metricValue(for row: ScreenerSnapshotRow) -> Double? {
        switch sortColumn {
        case .symbol, .price: return nil // unreachable: handled directly in `sortRows`
        case .dayChange: return row.dayChangePercent
        case .activeMetric: return activeMetricValue(row)
        }
    }

    /// The column value implied by the active selection's "headline" metric — the number a
    /// user of that screen cares about most.
    ///
    /// Preset mapping (each is the metric that defines the signal):
    /// - `.rsiOversold` / `.rsiOverbought` → `rsi14`
    /// - `.macdBullishCross` / `.macdBearishCross` → `macdHistogram`
    /// - `.goldenCross` / `.deathCross` → `pctVsSma50` (the faster average's relation to
    ///   price is the more sensitive read on a moving-average cross)
    /// - `.bollingerSqueeze` → `bollingerBandwidth`
    /// - `.near52wHigh` → `pctTo52wHigh`
    /// - `.near52wLow` → `pctTo52wLow`
    ///
    /// Custom screens use their first condition's metric column; a screen with zero
    /// conditions (unbuilt) falls back to `.price` rather than an undefined column.
    private func activeMetricValue(_ row: ScreenerSnapshotRow) -> Double? {
        switch selection {
        case .preset(let preset):
            switch preset {
            case .rsiOversold, .rsiOverbought: return row.rsi14
            case .macdBullishCross, .macdBearishCross: return row.macdHistogram
            case .goldenCross, .deathCross: return row.pctVsSma50
            case .bollingerSqueeze: return row.bollingerBandwidth
            case .near52wHigh: return row.pctTo52wHigh
            case .near52wLow: return row.pctTo52wLow
            }
        case .custom(let screen):
            guard let metric = screen.conditions.first?.metric else { return row.close }
            return metric.value(in: row)
        }
    }
}
