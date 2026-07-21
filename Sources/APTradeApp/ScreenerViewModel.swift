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

    /// Runs a full-universe scan. Ignored while a scan is already in flight. On success,
    /// the new snapshot is persisted and `results` are rebuilt; on failure (the engine can
    /// only throw via cooperative cancellation — every per-symbol fetch failure is instead
    /// recorded in `failedSymbols` and does not fail the scan) `scanState` becomes
    /// `.failed` and the previous `snapshot` is left untouched.
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
        } catch {
            scanState = .failed
        }
    }

    public func select(_ selection: ScreenSelection) {
        self.selection = selection
    }

    /// Insert-or-replace by `id`, then persists the full list.
    public func saveScreen(_ screen: CustomScreen) {
        if let index = savedScreens.firstIndex(where: { $0.id == screen.id }) {
            savedScreens[index] = screen
        } else {
            savedScreens.append(screen)
        }
        screenStore.save(savedScreens)
    }

    public func deleteScreen(id: String) {
        savedScreens.removeAll { $0.id == id }
        screenStore.save(savedScreens)
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
