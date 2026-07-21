package com.aptrade.shared.application

import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.ScreenerMath
import com.aptrade.shared.domain.ScreenerSnapshot
import com.aptrade.shared.domain.ScreenerSnapshotRow
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/** Persists the most recent full-universe screener scan result. Transcribed from
 *  `Sources/APTradeApplication/ScreenerUseCases.swift`'s `ScreenerSnapshotStore`. */
interface ScreenerSnapshotStore {
    fun load(): ScreenerSnapshot?
    fun save(snapshot: ScreenerSnapshot)
}

/** Persists the user's saved custom screens. Transcribed from the Swift twin's `ScreenStore`. */
interface ScreenStore {
    fun load(): List<CustomScreen>
    fun save(screens: List<CustomScreen>)
}

/**
 * Thrown by [ScreenerScanEngine.scan] when the scan aborts after too many CONSECUTIVE
 * rate-limited batches (see the class doc's "CONSECUTIVE-429 ABORT" divergence). Nothing is
 * returned when this is thrown — the caller (the desktop `ScreenerViewModel`, Task 6) is
 * expected to catch this specifically and map it to a `Failed` scan state, distinct from a
 * scan that merely completed with some symbols in `failedSymbols`.
 */
class ScreenerScanAborted(
    message: String = "Screener scan aborted: 3 consecutive batches were rate-limited",
) : Exception(message)

/**
 * Scans a symbol universe (e.g. the S&P 500) for technical snapshots, throttled to avoid
 * tripping the market data provider's rate limits. Transcribed from
 * `Sources/APTradeApplication/ScreenerUseCases.swift` (the shipped M9.1 Swift/macOS
 * reference) — semantics match that engine except for two DOCUMENTED DIVERGENCES below.
 *
 * Symbols are grouped into batches of [batchSize] and processed one batch at a time; within
 * a batch, all fetches run concurrently (`coroutineScope` + `async` per symbol), so the
 * concurrent-fetch high-water mark never exceeds [batchSize] (batches never overlap with each
 * other). A per-symbol failure is recorded in the resulting snapshot's `failedSymbols` and
 * does not stop the scan. `onProgress` fires once per batch with `(completedCount, total)`.
 * [ensureActive] is checked between batches, so a cancelled scan throws
 * [CancellationException] promptly; that exception is never swallowed — it is always
 * rethrown, never degraded to a per-symbol failure.
 *
 * This engine never persists anything — snapshot/screen storage is the caller's
 * responsibility via [ScreenerSnapshotStore]/[ScreenStore].
 *
 * DOCUMENTED DIVERGENCE 1 — TARGETED RETRY: the Swift reference retries the ENTIRE batch when
 * any symbol in it comes back rate-limited, which can flip an attempt-1 SUCCESS into an
 * attempt-2 FAILURE (or vice versa) for a symbol that never had a problem. This Kotlin port
 * instead retries ONLY the symbols that are still failing after attempt 1 — attempt-1
 * successes keep their already-fetched row and are never refetched, so they cannot be
 * clobbered by a degrading retry.
 *
 * DOCUMENTED DIVERGENCE 2 — CONSECUTIVE-429 ABORT: the Swift reference has no ceiling on how
 * many batches can be rate-limited in a row; it just keeps going, batch after batch, forever.
 * This Kotlin port aborts the whole scan — throwing [ScreenerScanAborted], returning nothing —
 * after [maxConsecutiveRateLimitedBatches] CONSECUTIVE batches whose POST-RETRY outcome still
 * contains a rate-limited symbol. A batch whose post-retry outcome has no rate-limited symbol
 * left (whether or not a retry was needed to get there) is "clean" and resets the counter to
 * zero — the streak must be unbroken.
 */
class ScreenerScanEngine(
    private val market: MarketDataRepository,
    private val calendar: MarketCalendar,
    private val delay: suspend (Int) -> Unit = { kotlinx.coroutines.delay(it.toLong()) },
) {
    companion object {
        const val batchSize = 4
        const val interBatchDelayMs = 150
        const val rateLimitBackoffMs = 2_000
        const val maxConsecutiveRateLimitedBatches = 3
    }

    /**
     * Scans [symbols] in order, [batchSize] at a time concurrently, reporting
     * `(completedCount, total)` via [onProgress] after each batch. [names] maps symbol ->
     * display name; a symbol missing from [names] falls back to itself. Row order in the
     * returned snapshot follows [symbols]' order regardless of the concurrent fetches'
     * completion order (a deterministic walk over [symbols] at the end, not insertion order
     * into the intermediate results map).
     *
     * @throws ScreenerScanAborted after [maxConsecutiveRateLimitedBatches] consecutive
     *   rate-limited batches (see class doc). Nothing is returned in that case.
     * @throws CancellationException promptly if the calling scope is cancelled between
     *   batches — never swallowed.
     */
    suspend fun scan(
        symbols: List<String>,
        names: Map<String, String>,
        nowEpochSeconds: Long,
        onProgress: (Int, Int) -> Unit,
    ): ScreenerSnapshot = coroutineScope {
        val total = symbols.size
        var completed = 0
        val resultsBySymbol = mutableMapOf<String, Result<ScreenerSnapshotRow>>()

        val batches = symbols.chunked(batchSize)
        var consecutiveRateLimitedBatches = 0

        batches.forEachIndexed { index, batch ->
            ensureActive()

            val attempt1 = runBatch(batch, names)
            val hasRateLimitAttempt1 = attempt1.values.any { isRateLimited(it) }

            val finalResults = if (hasRateLimitAttempt1) {
                delay(rateLimitBackoffMs)
                // TARGETED RETRY (divergence 1): only the symbols still failing after attempt
                // 1 are refetched — attempt-1 successes are copied through untouched below via
                // the `attempt1 + retry` merge (Map `+` lets later entries — the retry's —
                // override earlier ones, so only retried keys actually change).
                val stillFailing = attempt1.filterValues { it.isFailure }.keys.toList()
                val retry = runBatch(stillFailing, names)
                attempt1 + retry
            } else {
                attempt1
            }

            // CONSECUTIVE-429 ABORT (divergence 2): counted from the POST-RETRY outcome, so a
            // batch that rate-limited on attempt 1 but came back clean on retry does NOT count
            // against the streak.
            val finalHasRateLimit = finalResults.values.any { isRateLimited(it) }
            if (finalHasRateLimit) {
                consecutiveRateLimitedBatches += 1
                if (consecutiveRateLimitedBatches >= maxConsecutiveRateLimitedBatches) {
                    throw ScreenerScanAborted()
                }
            } else {
                consecutiveRateLimitedBatches = 0
            }

            resultsBySymbol += finalResults

            completed += batch.size
            onProgress(completed, total)

            if (index < batches.lastIndex) {
                delay(interBatchDelayMs)
            }
        }

        val rows = mutableListOf<ScreenerSnapshotRow>()
        val failedSymbols = mutableListOf<String>()
        for (symbol in symbols) {
            val result = resultsBySymbol[symbol]
            if (result != null && result.isSuccess) {
                rows += result.getOrThrow()
            } else {
                failedSymbols += symbol
            }
        }

        ScreenerSnapshot(
            tradingDay = calendar.tradingDay(nowEpochSeconds),
            scannedAtEpochSeconds = nowEpochSeconds,
            rows = rows,
            failedSymbols = failedSymbols,
        )
    }

    private fun isRateLimited(result: Result<ScreenerSnapshotRow>): Boolean =
        result.exceptionOrNull() is QuoteError.RateLimited

    /**
     * Fetches every symbol in [batch] concurrently, returning each outcome keyed by symbol.
     * Results are only written into the returned map here — sequentially, via `associate`
     * after every `async` has already been launched and awaited in list order — never from
     * inside a concurrent child coroutine, so two symbols in the same batch can never race on
     * a shared write.
     */
    private suspend fun runBatch(
        batch: List<String>,
        names: Map<String, String>,
    ): Map<String, Result<ScreenerSnapshotRow>> = coroutineScope {
        batch
            .map { symbol -> symbol to async { fetchOne(symbol, names) } }
            .associate { (symbol, deferred) -> symbol to deferred.await() }
    }

    private suspend fun fetchOne(symbol: String, names: Map<String, String>): Result<ScreenerSnapshotRow> {
        val name = names[symbol] ?: symbol
        return try {
            val candles = market.candles(symbol, Timeframe.OneYear)
            val row = ScreenerMath.snapshot(symbol, name, candles)
            if (row != null) Result.success(row) else Result.failure(QuoteError.NotFound)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
