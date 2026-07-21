import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

// MARK: - Fakes

private final class MemorySnapshotStore: ScreenerSnapshotStore, @unchecked Sendable {
    var snapshot: ScreenerSnapshot?
    private(set) var saveCallCount = 0
    private(set) var lastSaved: ScreenerSnapshot?
    func load() -> ScreenerSnapshot? { snapshot }
    func save(_ snapshot: ScreenerSnapshot) {
        self.snapshot = snapshot
        lastSaved = snapshot
        saveCallCount += 1
    }
}

private final class MemoryScreenStore: ScreenStore, @unchecked Sendable {
    var screens: [CustomScreen] = []
    private(set) var saveCallCount = 0
    func load() -> [CustomScreen] { screens }
    func save(_ screens: [CustomScreen]) {
        self.screens = screens
        saveCallCount += 1
    }
}

/// Minimal `MarketDataRepository` fake: scripted per-symbol candle outcomes (defaulting to
/// a single flat bar), an optional artificial delay to force a scan to still be in-flight
/// when a test attempts a second concurrent `scan()`, and a thread-safe call counter.
private final class FakeMarket: MarketDataRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var _callCount = 0
    var callCount: Int { withLock { _callCount } }

    var candlesBySymbol: [String: [Candle]] = [:]
    var delayNanoseconds: UInt64 = 0

    // NSLock's lock()/unlock() are unavailable directly inside an `async` function body
    // (Swift 6 flags blocking-primitive calls there); routing through a plain synchronous
    // helper sidesteps that without changing the actual synchronization.
    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }

    func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        withLock { _callCount += 1 }
        if delayNanoseconds > 0 {
            try? await Task.sleep(nanoseconds: delayNanoseconds)
        }
        return candlesBySymbol[symbol] ?? [Self.candle(close: 100)]
    }

    static func candle(close: Double) -> Candle {
        Candle(date: Date(timeIntervalSince1970: 0), open: Money(amount: Decimal(close)),
               high: Money(amount: Decimal(close)), low: Money(amount: Decimal(close)),
               close: Money(amount: Decimal(close)), volume: 1_000)
    }

    /// A strictly monotonic price series long enough (20 bars) to yield a defined RSI-14 —
    /// ascending trends to an overbought RSI (100), descending to an oversold RSI (0).
    static func trendingCandles(ascending: Bool) -> [Candle] {
        let base = 100.0
        return (0..<20).map { i in
            let price = ascending ? base + Double(i) : base - Double(i)
            return candle(close: price)
        }
    }
}

/// Records every `sleep(ms)` call the engine makes; each call also snapshots the VM's
/// current `scanState` (hopping onto MainActor to read it) BEFORE returning. Because the
/// engine calls `onProgress` synchronously — enqueuing a `Task { @MainActor in ... }` state
/// update — immediately before awaiting this seam's `sleep`, and MainActor is a strict FIFO
/// serial executor, our own `MainActor.run` read below is guaranteed to be queued behind
/// (and thus observe) that just-enqueued progress update. `vm` is wired in after
/// construction since the engine (and thus this recorder) must exist before the view model
/// that owns it does.
private final class ProgressStateRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var _states: [ScreenerViewModel.ScanState] = []
    weak var vm: ScreenerViewModel?
    var states: [ScreenerViewModel.ScanState] { withLock { _states } }

    // See `FakeMarket.withLock` — NSLock's lock()/unlock() can't be called directly from
    // an `async` function body, so the critical section is routed through this plain
    // synchronous helper instead.
    private func withLock<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    func recordCurrentState() async {
        guard let vm else { return }
        let state = await MainActor.run { vm.scanState }
        withLock { _states.append(state) }
    }
}

// MARK: - Tests

@MainActor
final class ScreenerViewModelTests: XCTestCase {
    private let fixedNow = Date(timeIntervalSince1970: 1_753_000_000) // 2025-07-20

    private func row(
        symbol: String, close: Double = 100, dayChangePercent: Double? = nil, rsi14: Double? = nil,
        macdHistogram: Double? = nil, pctVsSma50: Double? = nil, bollingerBandwidth: Double? = nil,
        pctTo52wHigh: Double? = nil, pctTo52wLow: Double? = nil
    ) -> ScreenerSnapshotRow {
        ScreenerSnapshotRow(
            symbol: symbol, name: symbol, close: close, dayChangePercent: dayChangePercent, rsi14: rsi14,
            macd: nil, macdSignal: nil, macdHistogram: macdHistogram, sma50: nil, sma200: nil, ema20: nil,
            pctVsSma50: pctVsSma50, pctVsSma200: nil, bollingerPercentB: nil, bollingerBandwidth: bollingerBandwidth,
            week52High: nil, week52Low: nil, pctTo52wHigh: pctTo52wHigh, pctTo52wLow: pctTo52wLow,
            relativeVolume: nil, macdCrossedUp: false, macdCrossedDown: false, goldenCross: false, deathCross: false
        )
    }

    private func makeVM(
        market: FakeMarket = FakeMarket(),
        snapshotStore: MemorySnapshotStore = MemorySnapshotStore(),
        screenStore: MemoryScreenStore = MemoryScreenStore(),
        symbols: [String] = ["SYM1", "SYM2"],
        sleep: @escaping @Sendable (Int) async -> Void = { _ in }
    ) -> ScreenerViewModel {
        let engine = ScreenerScanEngine(market: market, calendar: MarketCalendar(), sleep: sleep)
        let now = fixedNow
        return ScreenerViewModel(
            engine: engine,
            snapshotStore: snapshotStore,
            screenStore: screenStore,
            symbols: symbols,
            names: [:],
            calendar: MarketCalendar(),
            now: { now }
        )
    }

    // MARK: (a) init restores persisted snapshot + screens

    func test_a_init_restoresPersistedSnapshotAndScreens() {
        let snapshot = ScreenerSnapshot(
            tradingDay: "2026-07-20", scannedAt: fixedNow,
            rows: [row(symbol: "AAPL", close: 150, dayChangePercent: 1)], failedSymbols: []
        )
        let snapshotStore = MemorySnapshotStore()
        snapshotStore.snapshot = snapshot
        let screenStore = MemoryScreenStore()
        let savedScreen = CustomScreen(id: "s1", name: "My Screen", conditions: [])
        screenStore.screens = [savedScreen]

        let vm = makeVM(snapshotStore: snapshotStore, screenStore: screenStore)

        XCTAssertEqual(vm.snapshot, snapshot)
        XCTAssertEqual(vm.savedScreens, [savedScreen])
    }

    // MARK: (b) scan success persists + results populate for the default preset

    func test_b_scanSuccess_persistsSnapshot_andPopulatesResultsForDefaultPreset() async {
        let market = FakeMarket()
        market.candlesBySymbol["OVER"] = FakeMarket.trendingCandles(ascending: false) // rsi14 == 0 (oversold)
        market.candlesBySymbol["NORM"] = FakeMarket.trendingCandles(ascending: true)   // rsi14 == 100 (not oversold)
        let snapshotStore = MemorySnapshotStore()
        let vm = makeVM(market: market, snapshotStore: snapshotStore, symbols: ["OVER", "NORM"])

        await vm.scan()

        XCTAssertEqual(vm.scanState, .idle)
        XCTAssertEqual(snapshotStore.saveCallCount, 1)
        XCTAssertNotNil(vm.snapshot)
        XCTAssertEqual(vm.snapshot?.rows.map(\.symbol).sorted(), ["NORM", "OVER"])
        // default selection is .preset(.rsiOversold) — only OVER (rsi14 == 0) should match
        XCTAssertEqual(vm.results.map(\.symbol), ["OVER"])
    }

    // MARK: (c) progress states observed in order

    func test_c_scan_streamsProgressStatesInOrder() async {
        let market = FakeMarket()
        let recorder = ProgressStateRecorder()
        let symbols = (1...9).map { "SYM\($0)" } // batches of 4, 4, 1 -> two inter-batch sleeps
        let engine = ScreenerScanEngine(market: market, calendar: MarketCalendar(), sleep: { [recorder] _ in
            await recorder.recordCurrentState()
        })
        let now = fixedNow
        let vm = ScreenerViewModel(
            engine: engine,
            snapshotStore: MemorySnapshotStore(),
            screenStore: MemoryScreenStore(),
            symbols: symbols,
            names: [:],
            calendar: MarketCalendar(),
            now: { now }
        )
        recorder.vm = vm

        await vm.scan()

        XCTAssertEqual(recorder.states, [.scanning(done: 4, total: 9), .scanning(done: 8, total: 9)])
        XCTAssertEqual(vm.scanState, .idle)
    }

    // MARK: (d) cancellation -> .idle, nothing persisted, previous snapshot retained

    /// The engine only ever throws `CancellationError` (via cooperative
    /// `Task.checkCancellation()` between batches) — there is no genuine-failure path
    /// today. Per the M9.1 fix-wave spec, an interrupted scan must revert to `.idle`
    /// (nothing persisted), not `.failed`; `.failed` is reserved for a future
    /// non-cancellation engine throw, which doesn't exist yet. This was `.failed` before
    /// the fix, since a cancelled scan was the only way to observe the catch clause at
    /// all.
    func test_d_cancellation_revertsToIdle_andRetainsPreviousSnapshot() async {
        let priorSnapshot = ScreenerSnapshot(
            tradingDay: "2026-07-19", scannedAt: fixedNow.addingTimeInterval(-86_400),
            rows: [row(symbol: "OLD")], failedSymbols: []
        )
        let snapshotStore = MemorySnapshotStore()
        snapshotStore.snapshot = priorSnapshot
        let vm = makeVM(snapshotStore: snapshotStore)

        let task = Task { await vm.scan() }
        task.cancel()
        await task.value

        XCTAssertEqual(vm.scanState, .idle)
        XCTAssertEqual(vm.snapshot, priorSnapshot)
        XCTAssertEqual(snapshotStore.saveCallCount, 0)
    }

    // MARK: startScan/cancelScan — VM-owned scan task (fixes the orphaned-scan /
    // double-scan bug: macOS tab switching destroys the view's `@State` view model while
    // a view-owned unstructured `Task { await viewModel.scan() }` kept scanning; the next
    // scan then ran a SECOND overlapping engine).

    /// (a) Cancelling mid-progress must revert to `.idle` with nothing persisted —
    /// matching the spec's "interrupted → nothing persisted", exactly like a direct
    /// `scan()` cancellation (test d above), but driven through the VM-owned task.
    func test_cancelScan_midProgress_revertsToIdle_andPersistsNothing() async {
        let market = FakeMarket()
        market.delayNanoseconds = 30_000_000 // 30ms: keeps the first batch's fetch in flight
        let snapshotStore = MemorySnapshotStore()
        let symbols = (1...8).map { "SYM\($0)" } // two batches of 4
        let vm = makeVM(market: market, snapshotStore: snapshotStore, symbols: symbols)

        vm.startScan()
        while vm.scanState == .idle { await Task.yield() }

        vm.cancelScan()

        while case .scanning = vm.scanState { await Task.yield() }

        XCTAssertEqual(vm.scanState, .idle)
        XCTAssertNil(vm.snapshot)
        XCTAssertEqual(snapshotStore.saveCallCount, 0)
    }

    /// (b) A second `startScan()` while one is already owned by the view model must be a
    /// no-op — guarded by the STORED TASK, not `scanState` — so only one full scan's
    /// worth of fetches ever runs (the bug this fixes was exactly two full scans firing
    /// at once: 8 parallel fetches instead of 4).
    func test_startScan_reentrant_isIgnoredByStoredTaskGuard() async {
        let market = FakeMarket()
        market.delayNanoseconds = 30_000_000
        let vm = makeVM(market: market, symbols: ["SYM1", "SYM2"])

        vm.startScan()
        while vm.scanState == .idle { await Task.yield() }

        vm.startScan() // must be a no-op: a scan task is already stored

        while case .scanning = vm.scanState { await Task.yield() }

        XCTAssertEqual(market.callCount, 2, "only one scan's worth of fetches (2 symbols) ran")
        XCTAssertEqual(vm.scanState, .idle)
    }

    // MARK: (e) re-entrant scan ignored

    func test_e_reentrantScan_isIgnored() async {
        let market = FakeMarket()
        market.delayNanoseconds = 20_000_000 // 20ms — keeps the first scan in-flight
        let vm = makeVM(market: market, symbols: ["SYM1", "SYM2"])

        let firstTask = Task { await vm.scan() }

        // Poll (cooperatively yielding) until the first scan has visibly entered
        // `.scanning` before attempting the reentrant call. This avoids depending on any
        // assumption about how `Task`/`async let` scheduling orders concurrent work —
        // the loop simply terminates as soon as the first scan has made progress, which
        // it must given the 20ms artificial fetch delay above.
        while vm.scanState == .idle {
            await Task.yield()
        }

        await vm.scan() // must be a no-op: scanState is already `.scanning`
        await firstTask.value

        // Only one full scan's worth of fetches should have happened (2 symbols), proving
        // the reentrant call was guarded rather than triggering a second full scan.
        XCTAssertEqual(market.callCount, 2)
    }

    // MARK: (f) selection switch re-evaluates

    func test_f_selectionSwitch_reevaluatesResults() {
        let snapshotStore = MemorySnapshotStore()
        snapshotStore.snapshot = ScreenerSnapshot(
            tradingDay: "2026-07-20", scannedAt: fixedNow,
            rows: [
                row(symbol: "A", rsi14: 20),  // oversold
                row(symbol: "B", rsi14: 80),  // overbought
            ],
            failedSymbols: []
        )
        let vm = makeVM(snapshotStore: snapshotStore)

        XCTAssertEqual(vm.results.map(\.symbol), ["A"]) // default .preset(.rsiOversold)

        vm.select(.preset(.rsiOverbought))

        XCTAssertEqual(vm.results.map(\.symbol), ["B"])
    }

    // MARK: (g) sort by column/direction, nil-metric rows sorted last regardless of direction

    func test_g_sortByDayChange_nilMetricRowsSortedLast_bothDirections() {
        let snapshotStore = MemorySnapshotStore()
        snapshotStore.snapshot = ScreenerSnapshot(
            tradingDay: "2026-07-20", scannedAt: fixedNow,
            rows: [
                row(symbol: "MID", close: 10, dayChangePercent: 0),
                row(symbol: "NILA", close: 10, dayChangePercent: nil),
                row(symbol: "HIGH", close: 10, dayChangePercent: 5),
                row(symbol: "LOW", close: 10, dayChangePercent: -5),
                row(symbol: "NILB", close: 10, dayChangePercent: nil),
            ],
            failedSymbols: []
        )
        let vm = makeVM(snapshotStore: snapshotStore)
        // A custom screen with a trivially-true condition matches every row, regardless of
        // dayChangePercent, so sorting can be observed across the full set including nils.
        vm.select(.custom(CustomScreen(
            id: "all", name: "All",
            conditions: [ScreenCondition(metric: .price, comparison: .above, threshold: -1)]
        )))

        vm.sortColumn = .dayChange
        vm.sortAscending = true
        XCTAssertEqual(vm.results.map(\.symbol), ["LOW", "MID", "HIGH", "NILA", "NILB"])

        vm.sortAscending = false
        XCTAssertEqual(vm.results.map(\.symbol), ["HIGH", "MID", "LOW", "NILA", "NILB"])
    }

    func test_g_sortBySymbol_ascendingAndDescending() {
        let snapshotStore = MemorySnapshotStore()
        snapshotStore.snapshot = ScreenerSnapshot(
            tradingDay: "2026-07-20", scannedAt: fixedNow,
            rows: [row(symbol: "B", rsi14: 10), row(symbol: "A", rsi14: 10), row(symbol: "C", rsi14: 10)],
            failedSymbols: []
        )
        let vm = makeVM(snapshotStore: snapshotStore)
        vm.select(.preset(.rsiOversold)) // all three match (rsi14 == 10 < 30)

        vm.sortColumn = .symbol
        vm.sortAscending = true
        XCTAssertEqual(vm.results.map(\.symbol), ["A", "B", "C"])

        vm.sortAscending = false
        XCTAssertEqual(vm.results.map(\.symbol), ["C", "B", "A"])
    }

    // MARK: (h) save/delete screen persists through the store fake

    func test_h_saveScreen_insertsThenReplaces_andPersists() {
        let screenStore = MemoryScreenStore()
        let vm = makeVM(screenStore: screenStore)

        let screen = CustomScreen(id: "s1", name: "Original", conditions: [])
        vm.saveScreen(screen)
        XCTAssertEqual(vm.savedScreens, [screen])
        XCTAssertEqual(screenStore.screens, [screen])
        XCTAssertEqual(screenStore.saveCallCount, 1)

        let replaced = CustomScreen(id: "s1", name: "Renamed", conditions: [])
        vm.saveScreen(replaced)
        XCTAssertEqual(vm.savedScreens, [replaced])
        XCTAssertEqual(screenStore.screens, [replaced])
        XCTAssertEqual(screenStore.saveCallCount, 2)
    }

    func test_h_deleteScreen_removes_andPersists() {
        let screenStore = MemoryScreenStore()
        let screenA = CustomScreen(id: "a", name: "A", conditions: [])
        let screenB = CustomScreen(id: "b", name: "B", conditions: [])
        screenStore.screens = [screenA, screenB]
        let vm = makeVM(screenStore: screenStore)

        vm.deleteScreen(id: "a")

        XCTAssertEqual(vm.savedScreens, [screenB])
        XCTAssertEqual(screenStore.screens, [screenB])
    }

    // MARK: (h) selection re-sync — saveScreen/deleteScreen keep `selection` consistent
    // with `savedScreens` rather than leaving a stale/ghost `.custom` value behind.

    /// Editing the ACTIVE custom screen must re-select the updated value (same id, new
    /// conditions) so `results` reflect the edit immediately rather than showing
    /// pre-edit matches with the chip no longer highlighted (the old and new
    /// `CustomScreen` values are `!=` to each other despite sharing an id).
    func test_h_saveScreen_editingTheActiveSelection_reSelectsUpdatedScreen_andReevaluatesResults() {
        let screenStore = MemoryScreenStore()
        let original = CustomScreen(id: "s1", name: "Screen",
                                    conditions: [ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30)])
        screenStore.screens = [original]
        let snapshotStore = MemorySnapshotStore()
        snapshotStore.snapshot = ScreenerSnapshot(
            tradingDay: "2026-07-20", scannedAt: fixedNow,
            rows: [row(symbol: "A", rsi14: 20), row(symbol: "B", rsi14: 80)],
            failedSymbols: []
        )
        let vm = makeVM(snapshotStore: snapshotStore, screenStore: screenStore)
        vm.select(.custom(original))
        XCTAssertEqual(vm.results.map(\.symbol), ["A"]) // rsi14 < 30

        // Same id, tightened threshold — now excludes "A" too (rsi14 20 is not < 10).
        let edited = CustomScreen(id: "s1", name: "Screen",
                                  conditions: [ScreenCondition(metric: .rsi14, comparison: .below, threshold: 10)])
        vm.saveScreen(edited)

        XCTAssertEqual(vm.selection, .custom(edited))
        XCTAssertEqual(vm.results.map(\.symbol), [], "results must re-evaluate against the edited conditions")
    }

    /// Editing a screen that ISN'T the active selection must not perturb `selection` at
    /// all — the auto-select behavior is scoped to "was active" or "brand new", never
    /// "any save."
    func test_h_saveScreen_editingANonActiveScreen_leavesSelectionUnchanged() {
        let screenStore = MemoryScreenStore()
        let active = CustomScreen(id: "active", name: "Active", conditions: [])
        let other = CustomScreen(id: "other", name: "Other", conditions: [])
        screenStore.screens = [active, other]
        let vm = makeVM(screenStore: screenStore)
        vm.select(.custom(active))

        let editedOther = CustomScreen(id: "other", name: "Other Renamed", conditions: [])
        vm.saveScreen(editedOther)

        XCTAssertEqual(vm.selection, .custom(active))
    }

    /// Saving a screen that wasn't previously in `savedScreens` at all (a brand-new
    /// screen) auto-selects it, so the user immediately sees its results rather than
    /// staying on whatever preset/screen was active before opening the builder.
    func test_h_saveScreen_brandNewScreen_autoSelectsIt() {
        let vm = makeVM() // default selection is .preset(.rsiOversold)

        let created = CustomScreen(id: "new", name: "New Screen", conditions: [])
        vm.saveScreen(created)

        XCTAssertEqual(vm.selection, .custom(created))
    }

    /// Deleting the ACTIVE custom screen must fall back to the default preset — leaving
    /// `selection` pointed at a screen that no longer exists in `savedScreens` would
    /// strand `results` on a screen the user can no longer even see a chip for.
    func test_h_deleteScreen_deletingTheActiveSelection_resetsToDefaultPreset() {
        let screenStore = MemoryScreenStore()
        let active = CustomScreen(id: "s1", name: "Screen", conditions: [])
        screenStore.screens = [active]
        let vm = makeVM(screenStore: screenStore)
        vm.select(.custom(active))

        vm.deleteScreen(id: "s1")

        XCTAssertEqual(vm.selection, .preset(.rsiOversold))
    }

    /// Deleting a screen that ISN'T the active selection leaves `selection` untouched.
    func test_h_deleteScreen_deletingANonActiveScreen_leavesSelectionUnchanged() {
        let screenStore = MemoryScreenStore()
        let active = CustomScreen(id: "active", name: "Active", conditions: [])
        let other = CustomScreen(id: "other", name: "Other", conditions: [])
        screenStore.screens = [active, other]
        let vm = makeVM(screenStore: screenStore)
        vm.select(.custom(active))

        vm.deleteScreen(id: "other")

        XCTAssertEqual(vm.selection, .custom(active))
    }

    // MARK: (i) matchCount live against the current snapshot

    func test_i_matchCount_evaluatesAdHocConditionsAgainstSnapshot() {
        let snapshotStore = MemorySnapshotStore()
        snapshotStore.snapshot = ScreenerSnapshot(
            tradingDay: "2026-07-20", scannedAt: fixedNow,
            rows: [
                row(symbol: "A", rsi14: 20),
                row(symbol: "B", rsi14: 25),
                row(symbol: "C", rsi14: 80),
            ],
            failedSymbols: []
        )
        let vm = makeVM(snapshotStore: snapshotStore)

        let count = vm.matchCount(for: [ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30)])
        XCTAssertEqual(count, 2)

        XCTAssertEqual(vm.matchCount(for: []), 0, "an unbuilt (empty-condition) screen matches nothing")
    }

    func test_i_matchCount_noSnapshot_isZero() {
        let vm = makeVM(snapshotStore: MemorySnapshotStore())
        XCTAssertEqual(vm.matchCount(for: [ScreenCondition(metric: .price, comparison: .above, threshold: -1)]), 0)
    }

    // MARK: isSnapshotFresh

    func test_isSnapshotFresh_trueWhenTradingDayMatchesToday_falseOtherwise() {
        let calendar = MarketCalendar()
        let snapshotStore = MemorySnapshotStore()
        snapshotStore.snapshot = ScreenerSnapshot(
            tradingDay: calendar.tradingDay(of: fixedNow), scannedAt: fixedNow, rows: [], failedSymbols: []
        )
        let freshVM = makeVM(snapshotStore: snapshotStore)
        XCTAssertTrue(freshVM.isSnapshotFresh)

        let staleStore = MemorySnapshotStore()
        staleStore.snapshot = ScreenerSnapshot(
            tradingDay: "2000-01-01", scannedAt: fixedNow, rows: [], failedSymbols: []
        )
        let staleVM = makeVM(snapshotStore: staleStore)
        XCTAssertFalse(staleVM.isSnapshotFresh)

        let noSnapshotVM = makeVM(snapshotStore: MemorySnapshotStore())
        XCTAssertFalse(noSnapshotVM.isSnapshotFresh)
    }
}
