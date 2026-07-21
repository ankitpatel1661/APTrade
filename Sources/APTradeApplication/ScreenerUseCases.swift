import Foundation
import APTradeDomain

/// Persists the most recent full-universe screener scan result.
public protocol ScreenerSnapshotStore: Sendable {
    func load() -> ScreenerSnapshot?
    func save(_ snapshot: ScreenerSnapshot)
}

/// Persists the user's saved custom screens.
public protocol ScreenStore: Sendable {
    func load() -> [CustomScreen]
    func save(_ screens: [CustomScreen])
}

/// Scans a symbol universe (e.g. the S&P 500) for technical snapshots, throttled to avoid
/// tripping the market data provider's rate limits.
///
/// Symbols are grouped into batches of `batchSize` and processed one batch at a time; within
/// a batch, all fetches run concurrently via a task group, so the concurrent-fetch
/// high-water mark never exceeds `batchSize` (batches never overlap with each other). A
/// per-symbol failure is recorded in the resulting snapshot's `failedSymbols` and does not
/// stop the scan. If any fetch in a batch fails with a rate-limit error, the engine sleeps
/// `rateLimitBackoffMs` and retries the ENTIRE batch once; symbols still failing after the
/// retry are recorded as failed. `onProgress` fires once per batch with
/// `(completedCount, total)`. Cancellation is checked between batches, so a cancelled scan
/// throws `CancellationError` promptly.
///
/// This engine never persists anything — snapshot/screen storage is the caller's
/// responsibility via `ScreenerSnapshotStore`/`ScreenStore`.
public struct ScreenerScanEngine: Sendable {
    public static let batchSize = 4
    public static let interBatchDelayMs = 150
    public static let rateLimitBackoffMs = 2_000

    private let market: MarketDataRepository
    private let calendar: MarketCalendar
    private let sleep: @Sendable (Int) async -> Void

    public init(
        market: MarketDataRepository,
        calendar: MarketCalendar,
        sleep: @escaping @Sendable (Int) async -> Void = { try? await Task.sleep(for: .milliseconds($0)) }
    ) {
        self.market = market
        self.calendar = calendar
        self.sleep = sleep
    }

    /// Scans `symbols` in order, `Self.batchSize` at a time concurrently, reporting
    /// `(completedCount, total)` via `onProgress` after each batch. `names` maps symbol →
    /// display name; a symbol missing from `names` falls back to itself. Row order in the
    /// returned snapshot follows `symbols`' order regardless of the concurrent fetches'
    /// completion order.
    public func scan(
        symbols: [String],
        names: [String: String],
        now: Date,
        onProgress: @escaping @Sendable (Int, Int) -> Void
    ) async throws -> ScreenerSnapshot {
        let total = symbols.count
        var completed = 0
        var resultsBySymbol: [String: Result<ScreenerSnapshotRow, AppError>] = [:]

        let batches = stride(from: 0, to: symbols.count, by: Self.batchSize).map { start in
            Array(symbols[start..<min(start + Self.batchSize, symbols.count)])
        }

        for (index, batch) in batches.enumerated() {
            try Task.checkCancellation()

            var batchResults = await runBatch(batch, names: names)

            let hasRateLimit = batchResults.values.contains {
                if case .failure(let error) = $0, error == .rateLimited { return true }
                return false
            }

            if hasRateLimit {
                await sleep(Self.rateLimitBackoffMs)
                batchResults = await runBatch(batch, names: names)
            }

            for (symbol, result) in batchResults {
                resultsBySymbol[symbol] = result
            }

            completed += batch.count
            onProgress(completed, total)

            if index < batches.count - 1 {
                await sleep(Self.interBatchDelayMs)
            }
        }

        var rows: [ScreenerSnapshotRow] = []
        var failedSymbols: [String] = []
        for symbol in symbols {
            switch resultsBySymbol[symbol] {
            case .success(let row): rows.append(row)
            case .failure, .none: failedSymbols.append(symbol)
            }
        }

        return ScreenerSnapshot(
            tradingDay: calendar.tradingDay(of: now),
            scannedAt: now,
            rows: rows,
            failedSymbols: failedSymbols
        )
    }

    /// Fetches every symbol in `batch` concurrently, returning each outcome keyed by symbol.
    /// Results are only written into `out` here — sequentially, from the single `for await`
    /// loop draining the task group — never from inside a concurrent child task, so two
    /// symbols in the same batch can never race on a shared append.
    private func runBatch(
        _ batch: [String],
        names: [String: String]
    ) async -> [String: Result<ScreenerSnapshotRow, AppError>] {
        await withTaskGroup(of: (String, Result<ScreenerSnapshotRow, AppError>).self) { group in
            for symbol in batch {
                group.addTask {
                    let name = names[symbol] ?? symbol
                    do {
                        let candles = try await market.candles(for: symbol, timeframe: .oneYear)
                        guard let row = ScreenerMath.snapshot(symbol: symbol, name: name, candles: candles) else {
                            return (symbol, .failure(.notFound))
                        }
                        return (symbol, .success(row))
                    } catch {
                        return (symbol, .failure((error as? AppError) ?? .network))
                    }
                }
            }
            var out: [String: Result<ScreenerSnapshotRow, AppError>] = [:]
            for await (symbol, result) in group {
                out[symbol] = result
            }
            return out
        }
    }
}
