import XCTest
@testable import APTradeApplication
import APTradeDomain

// MARK: - Fakes

/// Per-symbol canned outcome for one fetch attempt.
private enum FakeOutcome {
    case success([Candle])
    case rateLimited
    case generic
}

/// Tracks concurrent-call high-water mark. A real (tiny) sleep between increment and
/// decrement forces same-batch fetches to genuinely overlap so the assertion is meaningful —
/// this is independent of the engine's injected `sleep` seam, which is never exercised here.
private actor ConcurrencyCounter {
    private var current = 0
    private(set) var highWaterMark = 0

    func increment() {
        current += 1
        highWaterMark = max(highWaterMark, current)
    }

    func decrement() {
        current -= 1
    }
}

/// Tracks how many times `candles` has been called per symbol, so a test can script
/// different outcomes for the first attempt vs. the retry attempt.
private actor AttemptTracker {
    private var counts: [String: Int] = [:]

    func nextAttempt(for symbol: String) -> Int {
        let n = (counts[symbol] ?? 0) + 1
        counts[symbol] = n
        return n
    }
}

private final class FakeScreenerMarket: MarketDataRepository, @unchecked Sendable {
    /// outcomes[symbol] is indexed by attempt (1-based). Attempts beyond the array's count
    /// repeat the last entry.
    var outcomesBySymbol: [String: [FakeOutcome]] = [:]
    let concurrency = ConcurrencyCounter()
    private let tracker = AttemptTracker()

    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 1), previousClose: Money(amount: 1))
    }

    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }

    func candles(for symbol: String, timeframe: Timeframe) async throws -> [Candle] {
        await concurrency.increment()
        try? await Task.sleep(nanoseconds: 20_000_000) // 20ms — forces same-batch overlap
        await concurrency.decrement()

        let attempt = await tracker.nextAttempt(for: symbol)
        let outcomes = outcomesBySymbol[symbol] ?? [.success([Self.candle(close: 100)])]
        let outcome = outcomes[min(attempt, outcomes.count) - 1]
        switch outcome {
        case .success(let candles): return candles
        case .rateLimited: throw AppError.rateLimited
        case .generic: throw AppError.network
        }
    }

    static func candle(close: Double) -> Candle {
        Candle(date: Date(timeIntervalSince1970: 0), open: Money(amount: Decimal(close)),
               high: Money(amount: Decimal(close)), low: Money(amount: Decimal(close)),
               close: Money(amount: Decimal(close)), volume: 1_000)
    }
}

/// Records every `sleep(ms)` call the engine makes, without ever really sleeping.
private final class SleepRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var _calls: [Int] = []
    var calls: [Int] {
        lock.lock(); defer { lock.unlock() }
        return _calls
    }
    func record(_ ms: Int) {
        lock.lock(); _calls.append(ms); lock.unlock()
    }
}

/// Records every `onProgress(completed, total)` call in order. `onProgress` is only ever
/// invoked sequentially from the engine's single scanning task (never from a batch's
/// concurrent child tasks), so a plain lock-protected array is sufficient.
private final class ProgressRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var _calls: [(Int, Int)] = []
    var calls: [(Int, Int)] {
        lock.lock(); defer { lock.unlock() }
        return _calls
    }
    func record(_ completed: Int, _ total: Int) {
        lock.lock(); _calls.append((completed, total)); lock.unlock()
    }
}

// MARK: - Tests

final class ScreenerScanEngineTests: XCTestCase {
    private func symbols(_ n: Int) -> [String] { (1...n).map { "SYM\($0)" } }

    private func makeEngine(
        market: FakeScreenerMarket,
        recorder: SleepRecorder
    ) -> ScreenerScanEngine {
        ScreenerScanEngine(market: market, calendar: MarketCalendar(), sleep: { ms in recorder.record(ms) })
    }

    // (a) all succeed → rows for all, failedSymbols empty, progress sequence [(4,N)...(N,N)]
    func test_a_allSucceed_producesRowsForAll_andProgressSequence() async throws {
        let syms = symbols(10)
        let market = FakeScreenerMarket()
        let recorder = SleepRecorder()
        let progress = ProgressRecorder()
        let engine = makeEngine(market: market, recorder: recorder)

        let snapshot = try await engine.scan(
            symbols: syms, names: [:], now: Date(),
            onProgress: { c, t in progress.record(c, t) }
        )

        XCTAssertEqual(snapshot.rows.map(\.symbol), syms)
        XCTAssertTrue(snapshot.failedSymbols.isEmpty)
        XCTAssertEqual(progress.calls.map(\.0), [4, 8, 10])
        XCTAssertTrue(progress.calls.allSatisfy { $0.1 == 10 })
    }

    // (b) max concurrent fetches never exceeds 4 (high-water mark via actor counter)
    func test_b_neverExceedsBatchSizeConcurrentFetches() async throws {
        let syms = symbols(8) // two full batches of 4
        let market = FakeScreenerMarket()
        let recorder = SleepRecorder()
        let engine = makeEngine(market: market, recorder: recorder)

        _ = try await engine.scan(symbols: syms, names: [:], now: Date(), onProgress: { _, _ in })

        let highWaterMark = await market.concurrency.highWaterMark
        XCTAssertLessThanOrEqual(highWaterMark, ScreenerScanEngine.batchSize)
        XCTAssertEqual(highWaterMark, ScreenerScanEngine.batchSize, "expected full-batch overlap given the forced 20ms delay")
    }

    // (c) one symbol throws a generic error → in failedSymbols, others present
    func test_c_genericFailure_isIsolatedToThatSymbol() async throws {
        let syms = symbols(4)
        let market = FakeScreenerMarket()
        market.outcomesBySymbol["SYM2"] = [.generic]
        let recorder = SleepRecorder()
        let engine = makeEngine(market: market, recorder: recorder)

        let snapshot = try await engine.scan(symbols: syms, names: [:], now: Date(), onProgress: { _, _ in })

        XCTAssertEqual(snapshot.failedSymbols, ["SYM2"])
        XCTAssertEqual(snapshot.rows.map(\.symbol).sorted(), ["SYM1", "SYM3", "SYM4"])
    }

    // (d) rate-limit on a batch → sleep(2000) recorded, batch retried once, succeeds on retry → no failures
    func test_d_rateLimitOnce_retriesBatch_andSucceeds() async throws {
        let syms = symbols(4)
        let market = FakeScreenerMarket()
        market.outcomesBySymbol["SYM2"] = [.rateLimited, .success([FakeScreenerMarket.candle(close: 50)])]
        let recorder = SleepRecorder()
        let engine = makeEngine(market: market, recorder: recorder)

        let snapshot = try await engine.scan(symbols: syms, names: [:], now: Date(), onProgress: { _, _ in })

        XCTAssertTrue(snapshot.failedSymbols.isEmpty)
        XCTAssertEqual(snapshot.rows.map(\.symbol).sorted(), syms.sorted())
        XCTAssertTrue(recorder.calls.contains(ScreenerScanEngine.rateLimitBackoffMs))
    }

    // (e) rate-limit twice → symbol failed, scan continues (other batches still run)
    func test_e_rateLimitTwice_recordsFailure_andScanContinues() async throws {
        let syms = symbols(8) // batch 1: SYM1-4 (rate-limited symbol here), batch 2: SYM5-8
        let market = FakeScreenerMarket()
        market.outcomesBySymbol["SYM2"] = [.rateLimited, .rateLimited]
        let recorder = SleepRecorder()
        let progress = ProgressRecorder()
        let engine = makeEngine(market: market, recorder: recorder)

        let snapshot = try await engine.scan(
            symbols: syms, names: [:], now: Date(),
            onProgress: { c, t in progress.record(c, t) }
        )

        XCTAssertEqual(snapshot.failedSymbols, ["SYM2"])
        XCTAssertEqual(snapshot.rows.map(\.symbol).sorted(), ["SYM1", "SYM3", "SYM4", "SYM5", "SYM6", "SYM7", "SYM8"])
        XCTAssertEqual(progress.calls.map(\.0), [4, 8], "scan must continue past the failed batch")
    }

    // (f) inter-batch sleep(150) recorded between batches
    func test_f_interBatchDelayRecorded_betweenButNotAfterLastBatch() async throws {
        let syms = symbols(9) // three batches: 4, 4, 1 → two inter-batch gaps
        let market = FakeScreenerMarket()
        let recorder = SleepRecorder()
        let engine = makeEngine(market: market, recorder: recorder)

        _ = try await engine.scan(symbols: syms, names: [:], now: Date(), onProgress: { _, _ in })

        let interBatchCalls = recorder.calls.filter { $0 == ScreenerScanEngine.interBatchDelayMs }
        XCTAssertEqual(interBatchCalls.count, 2)
    }

    // (g) tradingDay/scannedAt stamped from `now`
    func test_g_stampsTradingDayAndScannedAt_fromNow() async throws {
        var comps = DateComponents()
        comps.year = 2026; comps.month = 3; comps.day = 10; comps.hour = 12
        comps.timeZone = TimeZone(identifier: "America/New_York")
        let now = Calendar(identifier: .gregorian).date(from: comps)!
        let syms = symbols(2)
        let market = FakeScreenerMarket()
        let recorder = SleepRecorder()
        let engine = makeEngine(market: market, recorder: recorder)
        let calendar = MarketCalendar()

        let snapshot = try await engine.scan(symbols: syms, names: [:], now: now, onProgress: { _, _ in })

        XCTAssertEqual(snapshot.scannedAt, now)
        XCTAssertEqual(snapshot.tradingDay, calendar.tradingDay(of: now))
        XCTAssertEqual(snapshot.tradingDay, "2026-03-10")
    }

    // names fallback: missing display name falls back to the symbol itself
    func test_namesFallback_toSymbol_whenMissing() async throws {
        let market = FakeScreenerMarket()
        market.outcomesBySymbol["SYM1"] = [.success([FakeScreenerMarket.candle(close: 10)])]
        let recorder = SleepRecorder()
        let engine = makeEngine(market: market, recorder: recorder)

        let snapshot = try await engine.scan(
            symbols: ["SYM1"], names: ["SYM1": "Symbol One"], now: Date(), onProgress: { _, _ in }
        )
        XCTAssertEqual(snapshot.rows.first?.name, "Symbol One")

        let snapshot2 = try await engine.scan(
            symbols: ["SYM1"], names: [:], now: Date(), onProgress: { _, _ in }
        )
        XCTAssertEqual(snapshot2.rows.first?.name, "SYM1")
    }
}
