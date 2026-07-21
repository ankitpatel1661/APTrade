package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// MARK: - Fakes

/** Per-symbol canned outcome for one fetch attempt. Transcribed from the Swift twin's private
 *  `FakeOutcome` enum (a sealed class here since Kotlin enum entries can't carry payloads). */
private sealed class FakeOutcome {
    data class Success(val candles: List<Candle>) : FakeOutcome()
    object RateLimited : FakeOutcome()
    object Generic : FakeOutcome()
}

/**
 * Tracks concurrent-call high-water mark, mutex-guarded rather than actor-isolated (Kotlin has
 * no actor type). Under `runTest`'s virtual-time scheduler, four `async` children launched
 * from the same batch all run their pre-`delay` code (the increment) before any of them
 * resumes past `delay` (the decrement) — see [FakeScreenerMarket.candles]' doc — so the forced
 * 20ms delay makes the same-batch overlap genuinely observable here, independent of the
 * engine's injected `delay` seam, which this counter never touches.
 */
private class ConcurrencyCounter {
    private val mutex = Mutex()
    private var current = 0
    var highWaterMark = 0
        private set

    suspend fun increment() = mutex.withLock {
        current += 1
        if (current > highWaterMark) highWaterMark = current
    }

    suspend fun decrement() = mutex.withLock { current -= 1 }
}

/** Tracks how many times `candles` has been called per symbol, so a test can script different
 *  outcomes for the first attempt vs. the retry attempt, and (test k) assert a symbol was
 *  fetched exactly once. Transcribed from the Swift twin's private actor `AttemptTracker`. */
private class AttemptTracker {
    private val mutex = Mutex()
    private val counts = mutableMapOf<String, Int>()

    /** Returns the 1-based attempt number for this call, incrementing the stored count. */
    suspend fun nextAttempt(symbol: String): Int = mutex.withLock {
        val n = (counts[symbol] ?: 0) + 1
        counts[symbol] = n
        n
    }

    suspend fun callCount(symbol: String): Int = mutex.withLock { counts[symbol] ?: 0 }
}

private class FakeScreenerMarket : MarketDataRepository {
    /** outcomesBySymbol[symbol] is indexed by attempt (1-based). Attempts beyond the list's
     *  size repeat the last entry. */
    val outcomesBySymbol: MutableMap<String, List<FakeOutcome>> = mutableMapOf()
    val concurrency = ConcurrencyCounter()
    private val tracker = AttemptTracker()

    suspend fun callCount(symbol: String): Int = tracker.callCount(symbol)

    override suspend fun quotes(symbols: List<String>): List<Quote> = emptyList()
    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> = emptyList()
    override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
    override suspend fun search(query: String): List<Asset> = emptyList()

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> {
        concurrency.increment()
        delay(20) // virtual delay — forces same-batch overlap under runTest's scheduler
        concurrency.decrement()

        val attempt = tracker.nextAttempt(symbol)
        val outcomes = outcomesBySymbol[symbol] ?: listOf(FakeOutcome.Success(listOf(candle(100.0))))
        val outcome = outcomes[minOf(attempt, outcomes.size) - 1]
        return when (outcome) {
            is FakeOutcome.Success -> outcome.candles
            FakeOutcome.RateLimited -> throw QuoteError.RateLimited
            FakeOutcome.Generic -> throw QuoteError.Network("generic")
        }
    }

    companion object {
        fun candle(close: Double): Candle {
            val money = Money.usd(close.toString())
            return Candle(epochSeconds = 0L, open = money, high = money, low = money, close = money, volume = 1_000.0)
        }
    }
}

/** Records every `delay(ms)` call the engine makes, without ever really delaying. */
private class DelayRecorder {
    private val mutex = Mutex()
    private val _calls = mutableListOf<Int>()
    val calls: List<Int> get() = _calls

    suspend fun record(ms: Int) = mutex.withLock { _calls += ms }
}

/** Records every `onProgress(completed, total)` call in order. `onProgress` is only ever
 *  invoked sequentially from the engine's single scanning coroutine (never from a batch's
 *  concurrent children), so a plain list is sufficient — no lock needed. */
private class ProgressRecorder {
    private val _calls = mutableListOf<Pair<Int, Int>>()
    val calls: List<Pair<Int, Int>> get() = _calls
    fun record(completed: Int, total: Int) { _calls += completed to total }
}

// MARK: - Tests

class ScreenerScanEngineTest {
    private fun symbols(n: Int): List<String> = (1..n).map { "SYM$it" }

    private fun makeEngine(market: FakeScreenerMarket, recorder: DelayRecorder): ScreenerScanEngine =
        ScreenerScanEngine(market = market, calendar = MarketCalendar()) { ms -> recorder.record(ms) }

    // (a) all succeed -> rows for all, failedSymbols empty, progress sequence [(4,N)...(N,N)]
    @Test
    fun aAllSucceedProducesRowsForAllAndProgressSequence() = runTest {
        val syms = symbols(10)
        val market = FakeScreenerMarket()
        val recorder = DelayRecorder()
        val progress = ProgressRecorder()
        val engine = makeEngine(market, recorder)

        val snapshot = engine.scan(syms, emptyMap(), nowEpochSeconds = 0L) { c, t -> progress.record(c, t) }

        assertEquals(syms, snapshot.rows.map { it.symbol })
        assertTrue(snapshot.failedSymbols.isEmpty())
        assertEquals(listOf(4, 8, 10), progress.calls.map { it.first })
        assertTrue(progress.calls.all { it.second == 10 })
    }

    // (b) max concurrent fetches never exceeds 4 (high-water mark via mutex-guarded counter)
    @Test
    fun bNeverExceedsBatchSizeConcurrentFetches() = runTest {
        val syms = symbols(8) // two full batches of 4
        val market = FakeScreenerMarket()
        val recorder = DelayRecorder()
        val engine = makeEngine(market, recorder)

        engine.scan(syms, emptyMap(), nowEpochSeconds = 0L) { _, _ -> }

        assertTrue(market.concurrency.highWaterMark <= ScreenerScanEngine.batchSize)
        assertEquals(
            ScreenerScanEngine.batchSize, market.concurrency.highWaterMark,
            "expected full-batch overlap given the forced 20ms delay",
        )
    }

    // (c) one symbol throws a generic error -> in failedSymbols, others present
    @Test
    fun cGenericFailureIsIsolatedToThatSymbol() = runTest {
        val syms = symbols(4)
        val market = FakeScreenerMarket()
        market.outcomesBySymbol["SYM2"] = listOf(FakeOutcome.Generic)
        val recorder = DelayRecorder()
        val engine = makeEngine(market, recorder)

        val snapshot = engine.scan(syms, emptyMap(), nowEpochSeconds = 0L) { _, _ -> }

        assertEquals(listOf("SYM2"), snapshot.failedSymbols)
        assertEquals(listOf("SYM1", "SYM3", "SYM4"), snapshot.rows.map { it.symbol }.sorted())
    }

    // (d) rate-limit on a batch -> delay(2000) recorded, batch retried once, succeeds on retry -> no failures
    @Test
    fun dRateLimitOnceRetriesBatchAndSucceeds() = runTest {
        val syms = symbols(4)
        val market = FakeScreenerMarket()
        market.outcomesBySymbol["SYM2"] = listOf(FakeOutcome.RateLimited, FakeOutcome.Success(listOf(FakeScreenerMarket.candle(50.0))))
        val recorder = DelayRecorder()
        val engine = makeEngine(market, recorder)

        val snapshot = engine.scan(syms, emptyMap(), nowEpochSeconds = 0L) { _, _ -> }

        assertTrue(snapshot.failedSymbols.isEmpty())
        assertEquals(syms.sorted(), snapshot.rows.map { it.symbol }.sorted())
        assertTrue(recorder.calls.contains(ScreenerScanEngine.rateLimitBackoffMs))
    }

    // (e) rate-limit twice -> symbol failed, scan continues (other batches still run)
    @Test
    fun eRateLimitTwiceRecordsFailureAndScanContinues() = runTest {
        val syms = symbols(8) // batch 1: SYM1-4 (rate-limited symbol here), batch 2: SYM5-8
        val market = FakeScreenerMarket()
        market.outcomesBySymbol["SYM2"] = listOf(FakeOutcome.RateLimited, FakeOutcome.RateLimited)
        val recorder = DelayRecorder()
        val progress = ProgressRecorder()
        val engine = makeEngine(market, recorder)

        val snapshot = engine.scan(syms, emptyMap(), nowEpochSeconds = 0L) { c, t -> progress.record(c, t) }

        assertEquals(listOf("SYM2"), snapshot.failedSymbols)
        assertEquals(
            listOf("SYM1", "SYM3", "SYM4", "SYM5", "SYM6", "SYM7", "SYM8"),
            snapshot.rows.map { it.symbol }.sorted(),
        )
        assertEquals(listOf(4, 8), progress.calls.map { it.first }, "scan must continue past the failed batch")
    }

    // (f) inter-batch delay(150) recorded between batches
    @Test
    fun fInterBatchDelayRecordedBetweenButNotAfterLastBatch() = runTest {
        val syms = symbols(9) // three batches: 4, 4, 1 -> two inter-batch gaps
        val market = FakeScreenerMarket()
        val recorder = DelayRecorder()
        val engine = makeEngine(market, recorder)

        engine.scan(syms, emptyMap(), nowEpochSeconds = 0L) { _, _ -> }

        val interBatchCalls = recorder.calls.filter { it == ScreenerScanEngine.interBatchDelayMs }
        assertEquals(2, interBatchCalls.size)
    }

    // (g) tradingDay/scannedAt stamped from `nowEpochSeconds` (2026-03-10 12:00 America/New_York,
    // after that year's DST start (2nd Sunday of March = Mar 8) -> EDT/UTC-4 -> 16:00 UTC)
    @Test
    fun gStampsTradingDayAndScannedAtFromNow() = runTest {
        val now = 1_773_158_400L
        val syms = symbols(2)
        val market = FakeScreenerMarket()
        val recorder = DelayRecorder()
        val engine = makeEngine(market, recorder)
        val calendar = MarketCalendar()

        val snapshot = engine.scan(syms, emptyMap(), nowEpochSeconds = now) { _, _ -> }

        assertEquals(now, snapshot.scannedAtEpochSeconds)
        assertEquals(calendar.tradingDay(now), snapshot.tradingDay)
        assertEquals("2026-03-10", snapshot.tradingDay)
    }

    // names fallback: missing display name falls back to the symbol itself
    @Test
    fun namesFallbackToSymbolWhenMissing() = runTest {
        val market = FakeScreenerMarket()
        market.outcomesBySymbol["SYM1"] = listOf(FakeOutcome.Success(listOf(FakeScreenerMarket.candle(10.0))))
        val recorder = DelayRecorder()
        val engine = makeEngine(market, recorder)

        val snapshot = engine.scan(listOf("SYM1"), mapOf("SYM1" to "Symbol One"), nowEpochSeconds = 0L) { _, _ -> }
        assertEquals("Symbol One", snapshot.rows.first().name)

        val snapshot2 = engine.scan(listOf("SYM1"), emptyMap(), nowEpochSeconds = 0L) { _, _ -> }
        assertEquals("SYM1", snapshot2.rows.first().name)
    }

    // (k) MANDATED IMPROVEMENT 1 -- TARGETED RETRY: only the still-failed symbol of a batch is
    // refetched; succeeded symbols are fetched exactly once and keep their attempt-1 row even
    // though the retry, had it touched them, would have "degraded" their outcome.
    @Test
    fun kTargetedRetryFetchesOnlyStillFailedSymbolAndSurvivingRowsAreUntouched() = runTest {
        val syms = symbols(4)
        val market = FakeScreenerMarket()
        // SYM1 would flip from close=100 to close=999 if it were ever refetched.
        market.outcomesBySymbol["SYM1"] = listOf(
            FakeOutcome.Success(listOf(FakeScreenerMarket.candle(100.0))),
            FakeOutcome.Success(listOf(FakeScreenerMarket.candle(999.0))),
        )
        market.outcomesBySymbol["SYM2"] = listOf(FakeOutcome.RateLimited, FakeOutcome.Success(listOf(FakeScreenerMarket.candle(50.0))))
        val recorder = DelayRecorder()
        val engine = makeEngine(market, recorder)

        val snapshot = engine.scan(syms, emptyMap(), nowEpochSeconds = 0L) { _, _ -> }

        assertTrue(snapshot.failedSymbols.isEmpty())
        assertEquals(100.0, snapshot.rows.first { it.symbol == "SYM1" }.close, "SYM1's attempt-1 row must survive untouched")
        assertEquals(50.0, snapshot.rows.first { it.symbol == "SYM2" }.close)

        assertEquals(1, market.callCount("SYM1"))
        assertEquals(1, market.callCount("SYM3"))
        assertEquals(1, market.callCount("SYM4"))
        assertEquals(2, market.callCount("SYM2"), "only the still-failed symbol is refetched on retry")
    }

    // (l) MANDATED IMPROVEMENT 2 -- CONSECUTIVE-429 ABORT: 3 consecutive rate-limited batches
    // (post-retry) abort the scan; nothing is returned.
    @Test
    fun lThreeConsecutiveRateLimitedBatchesAbortsScan() = runTest {
        val syms = symbols(12) // three batches of 4
        val market = FakeScreenerMarket()
        // Persistently rate-limited (both attempt 1 and the retry) in every batch.
        market.outcomesBySymbol["SYM2"] = listOf(FakeOutcome.RateLimited, FakeOutcome.RateLimited)
        market.outcomesBySymbol["SYM6"] = listOf(FakeOutcome.RateLimited, FakeOutcome.RateLimited)
        market.outcomesBySymbol["SYM10"] = listOf(FakeOutcome.RateLimited, FakeOutcome.RateLimited)
        val recorder = DelayRecorder()
        val engine = makeEngine(market, recorder)

        assertFailsWith<ScreenerScanAborted> {
            engine.scan(syms, emptyMap(), nowEpochSeconds = 0L) { _, _ -> }
        }
    }

    // (l) control: a clean batch between two rate-limited batches resets the consecutive
    // counter, so the scan completes normally instead of aborting.
    @Test
    fun lCleanBatchBetweenLimitedBatchesResetsCounterScanCompletes() = runTest {
        val syms = symbols(12) // batch1: SYM1-4, batch2 (clean): SYM5-8, batch3: SYM9-12
        val market = FakeScreenerMarket()
        market.outcomesBySymbol["SYM2"] = listOf(FakeOutcome.RateLimited, FakeOutcome.RateLimited)
        market.outcomesBySymbol["SYM10"] = listOf(FakeOutcome.RateLimited, FakeOutcome.RateLimited)
        val recorder = DelayRecorder()
        val engine = makeEngine(market, recorder)

        val snapshot = engine.scan(syms, emptyMap(), nowEpochSeconds = 0L) { _, _ -> }

        assertEquals(listOf("SYM2", "SYM10"), snapshot.failedSymbols)
    }
}
