package com.aptrade.desktop.screener

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.ScreenerScanEngine
import com.aptrade.shared.application.ScreenerSnapshotStore
import com.aptrade.shared.application.ScreenStore
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PresetScreen
import com.aptrade.shared.domain.ScreenCondition
import com.aptrade.shared.domain.ScreenComparison
import com.aptrade.shared.domain.ScreenSelection
import com.aptrade.shared.domain.ScreenerMetric
import com.aptrade.shared.domain.ScreenerSnapshot
import com.aptrade.shared.domain.ScreenerSnapshotRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Transcribed from `Tests/APTradeAppTests/ScreenerViewModelTests.swift` AS-BUILT (all 22
 *  tests), plus one extra test (23) covering the Kotlin-only `ScreenerScanAborted` ->
 *  `Failed` mapping — see `ScreenerViewModel`'s class doc "DOCUMENTED DIVERGENCE" note. */
class ScreenerViewModelTest {

    // MARK: - Fakes

    private class MemorySnapshotStore : ScreenerSnapshotStore {
        var snapshot: ScreenerSnapshot? = null
        var saveCallCount = 0
            private set

        override fun load(): ScreenerSnapshot? = snapshot
        override fun save(snapshot: ScreenerSnapshot) {
            this.snapshot = snapshot
            saveCallCount += 1
        }
    }

    private class MemoryScreenStore : ScreenStore {
        var screens: List<CustomScreen> = emptyList()
        var saveCallCount = 0
            private set

        override fun load(): List<CustomScreen> = screens
        override fun save(screens: List<CustomScreen>) {
            this.screens = screens
            saveCallCount += 1
        }
    }

    // MARK: - Fixtures

    /** 2025-07-20 08:26:40 UTC — matches the Swift fixture's `fixedNow` epoch exactly. */
    private val fixedNow: Long = 1_753_000_000L

    private fun row(
        symbol: String,
        close: Double = 100.0,
        dayChangePercent: Double? = null,
        rsi14: Double? = null,
        macdHistogram: Double? = null,
        pctVsSma50: Double? = null,
        bollingerBandwidth: Double? = null,
        pctTo52wHigh: Double? = null,
        pctTo52wLow: Double? = null,
    ): ScreenerSnapshotRow = ScreenerSnapshotRow(
        symbol = symbol, name = symbol, close = close, dayChangePercent = dayChangePercent, rsi14 = rsi14,
        macd = null, macdSignal = null, macdHistogram = macdHistogram, sma50 = null, sma200 = null, ema20 = null,
        pctVsSma50 = pctVsSma50, pctVsSma200 = null, bollingerPercentB = null, bollingerBandwidth = bollingerBandwidth,
        week52High = null, week52Low = null, pctTo52wHigh = pctTo52wHigh, pctTo52wLow = pctTo52wLow,
        relativeVolume = null, macdCrossedUp = false, macdCrossedDown = false, goldenCross = false, deathCross = false,
    )

    private fun candle(close: Double): Candle {
        val money = Money.usd(close.toString())
        return Candle(epochSeconds = 0L, open = money, high = money, low = money, close = money, volume = 1_000.0)
    }

    /** A strictly monotonic price series long enough (20 bars) to yield a defined RSI-14 —
     *  ascending trends to an overbought RSI (100), descending to an oversold RSI (0). */
    private fun trendingCandles(ascending: Boolean): List<Candle> {
        val base = 100.0
        return (0 until 20).map { i ->
            val price = if (ascending) base + i else base - i
            candle(price)
        }
    }

    private fun makeVm(
        market: FakeMarketDataRepository = FakeMarketDataRepository(),
        snapshotStore: MemorySnapshotStore = MemorySnapshotStore(),
        screenStore: MemoryScreenStore = MemoryScreenStore(),
        symbols: List<String> = listOf("SYM1", "SYM2"),
        scope: CoroutineScope,
        delayHook: suspend (Int) -> Unit = { kotlinx.coroutines.delay(it.toLong()) },
    ): ScreenerViewModel {
        val engine = ScreenerScanEngine(market = market, calendar = MarketCalendar(), delay = delayHook)
        return ScreenerViewModel(
            engine = engine,
            snapshotStore = snapshotStore,
            screenStore = screenStore,
            symbols = symbols,
            names = emptyMap(),
            scope = scope,
            calendar = MarketCalendar(),
            nowEpochSeconds = { fixedNow },
        )
    }

    // MARK: (a) init restores persisted snapshot + screens

    @Test
    fun initRestoresPersistedSnapshotAndScreens() = runTest {
        val snapshot = ScreenerSnapshot(
            tradingDay = "2026-07-20", scannedAtEpochSeconds = fixedNow,
            rows = listOf(row(symbol = "AAPL", close = 150.0, dayChangePercent = 1.0)), failedSymbols = emptyList(),
        )
        val snapshotStore = MemorySnapshotStore().apply { this.snapshot = snapshot }
        val savedScreen = CustomScreen(id = "s1", name = "My Screen", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(savedScreen) }

        val vm = makeVm(snapshotStore = snapshotStore, screenStore = screenStore, scope = backgroundScope)

        assertEquals(snapshot, vm.state.value.snapshot)
        assertEquals(listOf(savedScreen), vm.state.value.savedScreens)
    }

    // MARK: (b) scan success persists + results populate for the default preset

    @Test
    fun scanSuccessPersistsSnapshotAndPopulatesResultsForDefaultPreset() = runTest {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { symbol, _ ->
            when (symbol) {
                "OVER" -> trendingCandles(ascending = false) // rsi14 == 0 (oversold)
                "NORM" -> trendingCandles(ascending = true) // rsi14 == 100 (not oversold)
                else -> listOf(candle(100.0))
            }
        }
        val snapshotStore = MemorySnapshotStore()
        val vm = makeVm(market = market, snapshotStore = snapshotStore, symbols = listOf("OVER", "NORM"), scope = backgroundScope)

        vm.scan()

        assertEquals(ScreenerScanState.Idle, vm.state.value.scanState)
        assertEquals(1, snapshotStore.saveCallCount)
        assertEquals(listOf("NORM", "OVER"), vm.state.value.snapshot?.rows?.map { it.symbol }?.sorted())
        // default selection is Preset(RsiOversold) -- only OVER (rsi14 == 0) should match
        assertEquals(listOf("OVER"), vm.state.value.results.map { it.symbol })
    }

    // MARK: (b2) total network failure -> Failed, previous snapshot + results retained,
    // nothing (re-)persisted.

    @Test
    fun scanTotalNetworkFailureSetsFailedStateAndRetainsPreviousSnapshot() = runTest {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { _, _ -> throw QuoteError.Network("offline") }
        val priorSnapshot = ScreenerSnapshot(
            tradingDay = "2026-07-19", scannedAtEpochSeconds = fixedNow - 86_400,
            rows = listOf(row(symbol = "OLD", close = 42.0)), failedSymbols = emptyList(),
        )
        val snapshotStore = MemorySnapshotStore().apply { this.snapshot = priorSnapshot }
        val vm = makeVm(market = market, snapshotStore = snapshotStore, symbols = listOf("SYM1", "SYM2"), scope = backgroundScope)
        val previousResults = vm.state.value.results

        vm.scan()

        assertEquals(ScreenerScanState.Failed, vm.state.value.scanState)
        assertEquals(0, snapshotStore.saveCallCount, "an all-failed scan must not persist")
        assertEquals(priorSnapshot, vm.state.value.snapshot, "the previous good snapshot must survive")
        assertEquals(
            previousResults.map { it.symbol },
            vm.state.value.results.map { it.symbol },
            "previous results retained",
        )
    }

    /** The empty-rows-but-no-failures case (e.g. a genuinely empty symbol universe) is NOT
     *  the offline case above and must still save normally, per the spec carve-out. */
    @Test
    fun scanEmptyRowsWithNoFailuresStillSaves() = runTest {
        val market = FakeMarketDataRepository()
        val snapshotStore = MemorySnapshotStore()
        val vm = makeVm(market = market, snapshotStore = snapshotStore, symbols = emptyList(), scope = backgroundScope)

        vm.scan()

        assertEquals(ScreenerScanState.Idle, vm.state.value.scanState)
        assertEquals(1, snapshotStore.saveCallCount)
        assertEquals(emptyList(), vm.state.value.snapshot?.rows)
        assertEquals(emptyList(), vm.state.value.snapshot?.failedSymbols)
    }

    // MARK: (c) progress states observed in order

    @Test
    fun scanStreamsProgressStatesInOrder() = runTest {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { _, _ -> listOf(candle(100.0)) }
        val states = mutableListOf<ScreenerScanState>()
        lateinit var vm: ScreenerViewModel
        val symbols = (1..9).map { "SYM$it" } // batches of 4, 4, 1 -> two inter-batch delays
        val engine = ScreenerScanEngine(market = market, calendar = MarketCalendar()) { _ ->
            states.add(vm.state.value.scanState)
        }
        vm = ScreenerViewModel(
            engine = engine,
            snapshotStore = MemorySnapshotStore(),
            screenStore = MemoryScreenStore(),
            symbols = symbols,
            names = emptyMap(),
            scope = backgroundScope,
            calendar = MarketCalendar(),
            nowEpochSeconds = { fixedNow },
        )

        vm.scan()

        assertEquals(
            listOf<ScreenerScanState>(ScreenerScanState.Scanning(4, 9), ScreenerScanState.Scanning(8, 9)),
            states,
        )
        assertEquals(ScreenerScanState.Idle, vm.state.value.scanState)
    }

    // MARK: (d) cancellation -> Idle, nothing persisted, previous snapshot retained

    @Test
    fun cancellationRevertsToIdleAndRetainsPreviousSnapshot() = runTest {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { _, _ -> delay(30); listOf(candle(100.0)) }
        val priorSnapshot = ScreenerSnapshot(
            tradingDay = "2026-07-19", scannedAtEpochSeconds = fixedNow - 86_400,
            rows = listOf(row(symbol = "OLD")), failedSymbols = emptyList(),
        )
        val snapshotStore = MemorySnapshotStore().apply { this.snapshot = priorSnapshot }
        val vm = makeVm(market = market, snapshotStore = snapshotStore, scope = backgroundScope)

        val job = launch { vm.scan() }
        runCurrent() // enter Scanning, park at the in-flight candles() delay
        job.cancel()
        // A cancelled job's continuation resumption is enqueued onto the scheduler rather
        // than run inline by `cancel()` itself -- `runCurrent()` drains it. No virtual-time
        // advance is needed for cancellation itself, so `runCurrent()` alone is sufficient
        // (and more deterministic here than `advanceUntilIdle()`).
        runCurrent()

        assertEquals(ScreenerScanState.Idle, vm.state.value.scanState)
        assertEquals(priorSnapshot, vm.state.value.snapshot)
        assertEquals(0, snapshotStore.saveCallCount)
    }

    // MARK: startScan/cancelScan -- VM-owned scan job

    /** (a) Cancelling mid-progress must revert to Idle with nothing persisted -- matching
     *  the spec's "interrupted -> nothing persisted", exactly like a direct `scan()`
     *  cancellation (test above), but driven through the VM-owned job. */
    @Test
    fun cancelScanMidProgressRevertsToIdleAndPersistsNothing() = runTest {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { _, _ -> delay(30); listOf(candle(100.0)) } // keeps the first batch's fetch in flight
        val snapshotStore = MemorySnapshotStore()
        val symbols = (1..8).map { "SYM$it" } // two batches of 4
        val vm = makeVm(market = market, snapshotStore = snapshotStore, symbols = symbols, scope = backgroundScope)

        vm.startScan()
        runCurrent() // enter Scanning, park at the in-flight candles() delay

        vm.cancelScan()
        runCurrent()

        assertEquals(ScreenerScanState.Idle, vm.state.value.scanState)
        assertNull(vm.state.value.snapshot)
        assertEquals(0, snapshotStore.saveCallCount)
    }

    /** (b) A second `startScan()` while one is already owned by the view model must be a
     *  no-op -- guarded by the STORED JOB, not `scanState` -- so only one full scan's worth
     *  of fetches ever runs. */
    @Test
    fun startScanReentrantIsIgnoredByStoredJobGuard() = runTest {
        val market = FakeMarketDataRepository()
        var candlesCallCount = 0
        market.candlesImpl = { _, _ -> candlesCallCount++; delay(30); listOf(candle(100.0)) }
        val vm = makeVm(market = market, symbols = listOf("SYM1", "SYM2"), scope = backgroundScope)

        vm.startScan()
        runCurrent() // enter Scanning, park at the in-flight candles() delay

        vm.startScan() // must be a no-op: a scan job is already stored
        runCurrent()
        advanceTimeBy(31) // let the in-flight candles() delay elapse so the scan completes
        runCurrent()

        assertEquals(2, candlesCallCount, "only one scan's worth of fetches (2 symbols) ran")
        assertEquals(ScreenerScanState.Idle, vm.state.value.scanState)
    }

    // MARK: (e) re-entrant scan ignored

    @Test
    fun reentrantScanIsIgnored() = runTest {
        val market = FakeMarketDataRepository()
        var candlesCallCount = 0
        market.candlesImpl = { _, _ -> candlesCallCount++; delay(20); listOf(candle(100.0)) }
        val vm = makeVm(market = market, symbols = listOf("SYM1", "SYM2"), scope = backgroundScope)

        val firstJob = launch { vm.scan() }
        runCurrent() // let the first scan visibly enter Scanning before the reentrant call

        vm.scan() // must be a no-op: scanState is already Scanning
        runCurrent()
        advanceTimeBy(21) // let the in-flight candles() delay elapse so the scan completes
        runCurrent()
        firstJob.join()

        // Only one full scan's worth of fetches should have happened (2 symbols), proving
        // the reentrant call was guarded rather than triggering a second full scan.
        assertEquals(2, candlesCallCount)
    }

    // MARK: (f) selection switch re-evaluates

    @Test
    fun selectionSwitchReevaluatesResults() = runTest {
        val snapshotStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = "2026-07-20", scannedAtEpochSeconds = fixedNow,
                rows = listOf(
                    row(symbol = "A", rsi14 = 20.0), // oversold
                    row(symbol = "B", rsi14 = 80.0), // overbought
                ),
                failedSymbols = emptyList(),
            )
        }
        val vm = makeVm(snapshotStore = snapshotStore, scope = backgroundScope)

        assertEquals(listOf("A"), vm.state.value.results.map { it.symbol }) // default Preset(RsiOversold)

        vm.select(ScreenSelection.Preset(PresetScreen.RsiOverbought))

        assertEquals(listOf("B"), vm.state.value.results.map { it.symbol })
    }

    // MARK: (g) sort by column/direction, nil-metric rows sorted last regardless of direction

    @Test
    fun sortByDayChangeNilMetricRowsSortedLastBothDirections() = runTest {
        val snapshotStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = "2026-07-20", scannedAtEpochSeconds = fixedNow,
                rows = listOf(
                    row(symbol = "MID", close = 10.0, dayChangePercent = 0.0),
                    row(symbol = "NILA", close = 10.0, dayChangePercent = null),
                    row(symbol = "HIGH", close = 10.0, dayChangePercent = 5.0),
                    row(symbol = "LOW", close = 10.0, dayChangePercent = -5.0),
                    row(symbol = "NILB", close = 10.0, dayChangePercent = null),
                ),
                failedSymbols = emptyList(),
            )
        }
        val vm = makeVm(snapshotStore = snapshotStore, scope = backgroundScope)
        // A custom screen with a trivially-true condition matches every row, regardless of
        // dayChangePercent, so sorting can be observed across the full set including nulls.
        vm.select(
            ScreenSelection.Custom(
                CustomScreen(
                    id = "all", name = "All",
                    conditions = listOf(ScreenCondition(ScreenerMetric.price, ScreenComparison.Above, -1.0)),
                ),
            ),
        )

        vm.setSortColumn(ScreenerSortColumn.DayChange)
        vm.setSortAscending(true)
        assertEquals(listOf("LOW", "MID", "HIGH", "NILA", "NILB"), vm.state.value.results.map { it.symbol })

        vm.setSortAscending(false)
        assertEquals(listOf("HIGH", "MID", "LOW", "NILA", "NILB"), vm.state.value.results.map { it.symbol })
    }

    @Test
    fun sortBySymbolAscendingAndDescending() = runTest {
        val snapshotStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = "2026-07-20", scannedAtEpochSeconds = fixedNow,
                rows = listOf(
                    row(symbol = "B", rsi14 = 10.0), row(symbol = "A", rsi14 = 10.0), row(symbol = "C", rsi14 = 10.0),
                ),
                failedSymbols = emptyList(),
            )
        }
        val vm = makeVm(snapshotStore = snapshotStore, scope = backgroundScope)
        vm.select(ScreenSelection.Preset(PresetScreen.RsiOversold)) // all three match (rsi14 == 10 < 30)

        vm.setSortColumn(ScreenerSortColumn.Symbol)
        vm.setSortAscending(true)
        assertEquals(listOf("A", "B", "C"), vm.state.value.results.map { it.symbol })

        vm.setSortAscending(false)
        assertEquals(listOf("C", "B", "A"), vm.state.value.results.map { it.symbol })
    }

    // MARK: (h) save/delete screen persists through the store fake

    @Test
    fun saveScreenInsertsThenReplacesAndPersists() = runTest {
        val screenStore = MemoryScreenStore()
        val vm = makeVm(screenStore = screenStore, scope = backgroundScope)

        val screen = CustomScreen(id = "s1", name = "Original", conditions = emptyList())
        vm.saveScreen(screen)
        assertEquals(listOf(screen), vm.state.value.savedScreens)
        assertEquals(listOf(screen), screenStore.screens)
        assertEquals(1, screenStore.saveCallCount)

        val replaced = CustomScreen(id = "s1", name = "Renamed", conditions = emptyList())
        vm.saveScreen(replaced)
        assertEquals(listOf(replaced), vm.state.value.savedScreens)
        assertEquals(listOf(replaced), screenStore.screens)
        assertEquals(2, screenStore.saveCallCount)
    }

    @Test
    fun deleteScreenRemovesAndPersists() = runTest {
        val screenA = CustomScreen(id = "a", name = "A", conditions = emptyList())
        val screenB = CustomScreen(id = "b", name = "B", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(screenA, screenB) }
        val vm = makeVm(screenStore = screenStore, scope = backgroundScope)

        vm.deleteScreen("a")

        assertEquals(listOf(screenB), vm.state.value.savedScreens)
        assertEquals(listOf(screenB), screenStore.screens)
    }

    // MARK: (h) selection re-sync -- saveScreen/deleteScreen keep `selection` consistent
    // with `savedScreens` rather than leaving a stale/ghost Custom value behind.

    @Test
    fun saveScreenEditingTheActiveSelectionReSelectsUpdatedScreenAndReevaluatesResults() = runTest {
        val original = CustomScreen(
            id = "s1", name = "Screen",
            conditions = listOf(ScreenCondition(ScreenerMetric.rsi14, ScreenComparison.Below, 30.0)),
        )
        val screenStore = MemoryScreenStore().apply { screens = listOf(original) }
        val snapshotStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = "2026-07-20", scannedAtEpochSeconds = fixedNow,
                rows = listOf(row(symbol = "A", rsi14 = 20.0), row(symbol = "B", rsi14 = 80.0)),
                failedSymbols = emptyList(),
            )
        }
        val vm = makeVm(snapshotStore = snapshotStore, screenStore = screenStore, scope = backgroundScope)
        vm.select(ScreenSelection.Custom(original))
        assertEquals(listOf("A"), vm.state.value.results.map { it.symbol }) // rsi14 < 30

        // Same id, tightened threshold -- now excludes "A" too (rsi14 20 is not < 10).
        val edited = CustomScreen(
            id = "s1", name = "Screen",
            conditions = listOf(ScreenCondition(ScreenerMetric.rsi14, ScreenComparison.Below, 10.0)),
        )
        vm.saveScreen(edited)

        assertEquals(ScreenSelection.Custom(edited), vm.state.value.selection)
        assertEquals(emptyList(), vm.state.value.results.map { it.symbol }, "results must re-evaluate against the edited conditions")
    }

    @Test
    fun saveScreenEditingANonActiveScreenLeavesSelectionUnchanged() = runTest {
        val active = CustomScreen(id = "active", name = "Active", conditions = emptyList())
        val other = CustomScreen(id = "other", name = "Other", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(active, other) }
        val vm = makeVm(screenStore = screenStore, scope = backgroundScope)
        vm.select(ScreenSelection.Custom(active))

        val editedOther = CustomScreen(id = "other", name = "Other Renamed", conditions = emptyList())
        vm.saveScreen(editedOther)

        assertEquals(ScreenSelection.Custom(active), vm.state.value.selection)
    }

    @Test
    fun saveScreenBrandNewScreenAutoSelectsIt() = runTest {
        val vm = makeVm(scope = backgroundScope) // default selection is Preset(RsiOversold)

        val created = CustomScreen(id = "new", name = "New Screen", conditions = emptyList())
        vm.saveScreen(created)

        assertEquals(ScreenSelection.Custom(created), vm.state.value.selection)
    }

    @Test
    fun deleteScreenDeletingTheActiveSelectionResetsToDefaultPreset() = runTest {
        val active = CustomScreen(id = "s1", name = "Screen", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(active) }
        val vm = makeVm(screenStore = screenStore, scope = backgroundScope)
        vm.select(ScreenSelection.Custom(active))

        vm.deleteScreen("s1")

        assertEquals(ScreenSelection.Preset(PresetScreen.RsiOversold), vm.state.value.selection)
    }

    @Test
    fun deleteScreenDeletingANonActiveScreenLeavesSelectionUnchanged() = runTest {
        val active = CustomScreen(id = "active", name = "Active", conditions = emptyList())
        val other = CustomScreen(id = "other", name = "Other", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(active, other) }
        val vm = makeVm(screenStore = screenStore, scope = backgroundScope)
        vm.select(ScreenSelection.Custom(active))

        vm.deleteScreen("other")

        assertEquals(ScreenSelection.Custom(active), vm.state.value.selection)
    }

    // MARK: (i) matchCount live against the current snapshot

    @Test
    fun matchCountEvaluatesAdHocConditionsAgainstSnapshot() = runTest {
        val snapshotStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = "2026-07-20", scannedAtEpochSeconds = fixedNow,
                rows = listOf(
                    row(symbol = "A", rsi14 = 20.0),
                    row(symbol = "B", rsi14 = 25.0),
                    row(symbol = "C", rsi14 = 80.0),
                ),
                failedSymbols = emptyList(),
            )
        }
        val vm = makeVm(snapshotStore = snapshotStore, scope = backgroundScope)

        val count = vm.matchCount(listOf(ScreenCondition(ScreenerMetric.rsi14, ScreenComparison.Below, 30.0)))
        assertEquals(2, count)

        assertEquals(0, vm.matchCount(emptyList()), "an unbuilt (empty-condition) screen matches nothing")
    }

    @Test
    fun matchCountNoSnapshotIsZero() = runTest {
        val vm = makeVm(snapshotStore = MemorySnapshotStore(), scope = backgroundScope)
        assertEquals(0, vm.matchCount(listOf(ScreenCondition(ScreenerMetric.price, ScreenComparison.Above, -1.0))))
    }

    // MARK: isSnapshotFresh

    @Test
    fun isSnapshotFreshTrueWhenTradingDayMatchesTodayFalseOtherwise() = runTest {
        val calendar = MarketCalendar()
        val snapshotStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = calendar.tradingDay(fixedNow), scannedAtEpochSeconds = fixedNow,
                rows = emptyList(), failedSymbols = emptyList(),
            )
        }
        val freshVm = makeVm(snapshotStore = snapshotStore, scope = backgroundScope)
        assertTrue(freshVm.isSnapshotFresh())

        val staleStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = "2000-01-01", scannedAtEpochSeconds = fixedNow,
                rows = emptyList(), failedSymbols = emptyList(),
            )
        }
        val staleVm = makeVm(snapshotStore = staleStore, scope = backgroundScope)
        assertFalse(staleVm.isSnapshotFresh())

        val noSnapshotVm = makeVm(snapshotStore = MemorySnapshotStore(), scope = backgroundScope)
        assertFalse(noSnapshotVm.isSnapshotFresh())
    }

    // MARK: (extra) ScreenerScanAborted -> Failed, previous snapshot retained (Kotlin-only
    // engine path -- see ScreenerViewModel's class doc "DOCUMENTED DIVERGENCE" note).

    @Test
    fun scanAbortedMapsToFailedStateAndRetainsPreviousSnapshot() = runTest {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { _, _ -> throw QuoteError.RateLimited }
        val priorSnapshot = ScreenerSnapshot(
            tradingDay = "2026-07-19", scannedAtEpochSeconds = fixedNow - 86_400,
            rows = listOf(row(symbol = "OLD", close = 42.0)), failedSymbols = emptyList(),
        )
        val snapshotStore = MemorySnapshotStore().apply { this.snapshot = priorSnapshot }
        // 12 symbols -> 3 full batches of 4, all rate-limited on every attempt -> 3
        // consecutive rate-limited batches aborts the scan.
        val symbols = (1..12).map { "SYM$it" }
        val vm = makeVm(market = market, snapshotStore = snapshotStore, symbols = symbols, scope = backgroundScope)

        vm.scan()

        assertEquals(ScreenerScanState.Failed, vm.state.value.scanState)
        assertEquals(0, snapshotStore.saveCallCount, "an aborted scan must not persist")
        assertEquals(priorSnapshot, vm.state.value.snapshot, "the previous good snapshot must survive")
    }
}
