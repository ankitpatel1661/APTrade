package com.aptrade.android.screener

import com.aptrade.android.FakeMarketDataRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android twin of desktopApp/src/test/kotlin/com/aptrade/desktop/screener/ScreenerViewModelTest.kt,
 *  transcribed near-verbatim (fakes, fixtures, and 23 of its 24 test names — 22 transcribed
 *  from the Swift original via the desktop Kotlin twin, plus the desktop twin's own extra
 *  Kotlin-only test (23) covering `ScreenerScanAborted` -> `Failed`). Test 24,
 *  `saveScreenTwoMutationsInSameTickBothPersistNoLostUpdate`, is Android-only and pins the
 *  M9.3 Task 2 apply-then-persist hardening — see that test's own comment and
 *  [ScreenerViewModel]'s "APPLY-THEN-PERSIST ORDERING" class doc.
 *
 *  [ScreenerViewModel] is an androidx ViewModel using `viewModelScope`
 *  (Dispatchers.Main.immediate), mirroring [com.aptrade.android.plans.PlansViewModelTest]'s
 *  scheduler discipline: a [StandardTestDispatcher] installed as Main, with `runCurrent()`
 *  after each VM call that dispatches work.
 *
 *  The one Android-only wrinkle (see [ScreenerViewModel]'s "DIVERGENCE — STRICTMODE-SAFE I/O"
 *  KDoc): the VM's `ioDispatcher` param is injected here as the SAME [dispatcher] installed as
 *  Main, so the init-time store loads and every store write (post-scan snapshot save,
 *  saveScreen/deleteScreen persistence) run on the SAME virtual scheduler as the rest of the
 *  test — `runCurrent()` deterministically drains them, exactly like any other
 *  `viewModelScope.launch`. Every test constructs the VM through [vm], then calls
 *  `runCurrent()` once before its first assertion to let the init load land (constructor
 *  fixtures otherwise race the default empty/`null` initial state); [saveScreen]/[deleteScreen]
 *  calls need their own follow-up `runCurrent()` for the same reason, since both now hop
 *  through `ioDispatcher` too. Nothing else about the transcribed fixtures or assertions
 *  changes. */
class ScreenerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

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

    /** 2025-07-20 08:26:40 UTC — matches the desktop fixture's `fixedNow` epoch exactly. */
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

    private fun vm(
        market: FakeMarketDataRepository = FakeMarketDataRepository(),
        snapshotStore: MemorySnapshotStore = MemorySnapshotStore(),
        screenStore: MemoryScreenStore = MemoryScreenStore(),
        symbols: List<String> = listOf("SYM1", "SYM2"),
        delayHook: suspend (Int) -> Unit = { kotlinx.coroutines.delay(it.toLong()) },
    ): ScreenerViewModel {
        val engine = ScreenerScanEngine(market = market, calendar = MarketCalendar(), delay = delayHook)
        return ScreenerViewModel(
            engine = engine,
            snapshotStore = snapshotStore,
            screenStore = screenStore,
            symbols = symbols,
            names = emptyMap(),
            calendar = MarketCalendar(),
            nowEpochSeconds = { fixedNow },
            ioDispatcher = dispatcher,
        )
    }

    // MARK: (a) init restores persisted snapshot + screens

    @Test
    fun initRestoresPersistedSnapshotAndScreens() = runTest(dispatcher.scheduler) {
        val snapshot = ScreenerSnapshot(
            tradingDay = "2026-07-20", scannedAtEpochSeconds = fixedNow,
            rows = listOf(row(symbol = "AAPL", close = 150.0, dayChangePercent = 1.0)), failedSymbols = emptyList(),
        )
        val snapshotStore = MemorySnapshotStore().apply { this.snapshot = snapshot }
        val savedScreen = CustomScreen(id = "s1", name = "My Screen", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(savedScreen) }

        val viewModel = vm(snapshotStore = snapshotStore, screenStore = screenStore)
        runCurrent() // let the init-time store loads (hopped through ioDispatcher) land

        assertEquals(snapshot, viewModel.state.value.snapshot)
        assertEquals(listOf(savedScreen), viewModel.state.value.savedScreens)
    }

    // MARK: (b) scan success persists + results populate for the default preset

    @Test
    fun scanSuccessPersistsSnapshotAndPopulatesResultsForDefaultPreset() = runTest(dispatcher.scheduler) {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { symbol, _ ->
            when (symbol) {
                "OVER" -> trendingCandles(ascending = false) // rsi14 == 0 (oversold)
                "NORM" -> trendingCandles(ascending = true) // rsi14 == 100 (not oversold)
                else -> listOf(candle(100.0))
            }
        }
        val snapshotStore = MemorySnapshotStore()
        val viewModel = vm(market = market, snapshotStore = snapshotStore, symbols = listOf("OVER", "NORM"))
        runCurrent()

        viewModel.scan()

        assertEquals(ScreenerScanState.Idle, viewModel.state.value.scanState)
        assertEquals(1, snapshotStore.saveCallCount)
        assertEquals(listOf("NORM", "OVER"), viewModel.state.value.snapshot?.rows?.map { it.symbol }?.sorted())
        // default selection is Preset(RsiOversold) -- only OVER (rsi14 == 0) should match
        assertEquals(listOf("OVER"), viewModel.state.value.results.map { it.symbol })
    }

    // MARK: (b2) total network failure -> Failed, previous snapshot + results retained,
    // nothing (re-)persisted.

    @Test
    fun scanTotalNetworkFailureSetsFailedStateAndRetainsPreviousSnapshot() = runTest(dispatcher.scheduler) {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { _, _ -> throw QuoteError.Network("offline") }
        val priorSnapshot = ScreenerSnapshot(
            tradingDay = "2026-07-19", scannedAtEpochSeconds = fixedNow - 86_400,
            rows = listOf(row(symbol = "OLD", close = 42.0)), failedSymbols = emptyList(),
        )
        val snapshotStore = MemorySnapshotStore().apply { this.snapshot = priorSnapshot }
        val viewModel = vm(market = market, snapshotStore = snapshotStore, symbols = listOf("SYM1", "SYM2"))
        runCurrent()
        val previousResults = viewModel.state.value.results

        viewModel.scan()

        assertEquals(ScreenerScanState.Failed, viewModel.state.value.scanState)
        assertEquals(0, snapshotStore.saveCallCount, "an all-failed scan must not persist")
        assertEquals(priorSnapshot, viewModel.state.value.snapshot, "the previous good snapshot must survive")
        assertEquals(
            previousResults.map { it.symbol },
            viewModel.state.value.results.map { it.symbol },
            "previous results retained",
        )
    }

    /** The empty-rows-but-no-failures case (e.g. a genuinely empty symbol universe) is NOT
     *  the offline case above and must still save normally, per the spec carve-out. */
    @Test
    fun scanEmptyRowsWithNoFailuresStillSaves() = runTest(dispatcher.scheduler) {
        val market = FakeMarketDataRepository()
        val snapshotStore = MemorySnapshotStore()
        val viewModel = vm(market = market, snapshotStore = snapshotStore, symbols = emptyList())
        runCurrent()

        viewModel.scan()

        assertEquals(ScreenerScanState.Idle, viewModel.state.value.scanState)
        assertEquals(1, snapshotStore.saveCallCount)
        assertEquals(emptyList(), viewModel.state.value.snapshot?.rows)
        assertEquals(emptyList(), viewModel.state.value.snapshot?.failedSymbols)
    }

    // MARK: (c) progress states observed in order

    @Test
    fun scanStreamsProgressStatesInOrder() = runTest(dispatcher.scheduler) {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { _, _ -> listOf(candle(100.0)) }
        val states = mutableListOf<ScreenerScanState>()
        lateinit var viewModel: ScreenerViewModel
        val symbols = (1..9).map { "SYM$it" } // batches of 4, 4, 1 -> two inter-batch delays
        val engine = ScreenerScanEngine(market = market, calendar = MarketCalendar()) { _ ->
            states.add(viewModel.state.value.scanState)
        }
        viewModel = ScreenerViewModel(
            engine = engine,
            snapshotStore = MemorySnapshotStore(),
            screenStore = MemoryScreenStore(),
            symbols = symbols,
            names = emptyMap(),
            calendar = MarketCalendar(),
            nowEpochSeconds = { fixedNow },
            ioDispatcher = dispatcher,
        )
        runCurrent()

        viewModel.scan()

        assertEquals(
            listOf<ScreenerScanState>(ScreenerScanState.Scanning(4, 9), ScreenerScanState.Scanning(8, 9)),
            states,
        )
        assertEquals(ScreenerScanState.Idle, viewModel.state.value.scanState)
    }

    // MARK: (d) cancellation -> Idle, nothing persisted, previous snapshot retained

    @Test
    fun cancellationRevertsToIdleAndRetainsPreviousSnapshot() = runTest(dispatcher.scheduler) {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { _, _ -> delay(30); listOf(candle(100.0)) }
        val priorSnapshot = ScreenerSnapshot(
            tradingDay = "2026-07-19", scannedAtEpochSeconds = fixedNow - 86_400,
            rows = listOf(row(symbol = "OLD")), failedSymbols = emptyList(),
        )
        val snapshotStore = MemorySnapshotStore().apply { this.snapshot = priorSnapshot }
        val viewModel = vm(market = market, snapshotStore = snapshotStore)
        runCurrent()

        val job = launch { viewModel.scan() }
        runCurrent() // enter Scanning, park at the in-flight candles() delay
        job.cancel()
        // A cancelled job's continuation resumption is enqueued onto the scheduler rather
        // than run inline by `cancel()` itself -- `runCurrent()` drains it. No virtual-time
        // advance is needed for cancellation itself, so `runCurrent()` alone is sufficient
        // (and more deterministic here than `advanceUntilIdle()`).
        runCurrent()

        assertEquals(ScreenerScanState.Idle, viewModel.state.value.scanState)
        assertEquals(priorSnapshot, viewModel.state.value.snapshot)
        assertEquals(0, snapshotStore.saveCallCount)
    }

    // MARK: startScan/cancelScan -- VM-owned scan job

    /** (a) Cancelling mid-progress must revert to Idle with nothing persisted -- matching
     *  the spec's "interrupted -> nothing persisted", exactly like a direct `scan()`
     *  cancellation (test above), but driven through the VM-owned job. */
    @Test
    fun cancelScanMidProgressRevertsToIdleAndPersistsNothing() = runTest(dispatcher.scheduler) {
        val market = FakeMarketDataRepository()
        market.candlesImpl = { _, _ -> delay(30); listOf(candle(100.0)) } // keeps the first batch's fetch in flight
        val snapshotStore = MemorySnapshotStore()
        val symbols = (1..8).map { "SYM$it" } // two batches of 4
        val viewModel = vm(market = market, snapshotStore = snapshotStore, symbols = symbols)
        runCurrent()

        viewModel.startScan()
        runCurrent() // enter Scanning, park at the in-flight candles() delay

        viewModel.cancelScan()
        runCurrent()

        assertEquals(ScreenerScanState.Idle, viewModel.state.value.scanState)
        assertNull(viewModel.state.value.snapshot)
        assertEquals(0, snapshotStore.saveCallCount)
    }

    /** (b) A second `startScan()` while one is already owned by the view model must be a
     *  no-op -- guarded by the STORED JOB, not `scanState` -- so only one full scan's worth
     *  of fetches ever runs. */
    @Test
    fun startScanReentrantIsIgnoredByStoredJobGuard() = runTest(dispatcher.scheduler) {
        val market = FakeMarketDataRepository()
        var candlesCallCount = 0
        market.candlesImpl = { _, _ -> candlesCallCount++; delay(30); listOf(candle(100.0)) }
        val viewModel = vm(market = market, symbols = listOf("SYM1", "SYM2"))
        runCurrent()

        viewModel.startScan()
        runCurrent() // enter Scanning, park at the in-flight candles() delay

        viewModel.startScan() // must be a no-op: a scan job is already stored
        runCurrent()
        advanceTimeBy(31) // let the in-flight candles() delay elapse so the scan completes
        runCurrent()

        assertEquals(2, candlesCallCount, "only one scan's worth of fetches (2 symbols) ran")
        assertEquals(ScreenerScanState.Idle, viewModel.state.value.scanState)
    }

    // MARK: (e) re-entrant scan ignored

    @Test
    fun reentrantScanIsIgnored() = runTest(dispatcher.scheduler) {
        val market = FakeMarketDataRepository()
        var candlesCallCount = 0
        market.candlesImpl = { _, _ -> candlesCallCount++; delay(20); listOf(candle(100.0)) }
        val viewModel = vm(market = market, symbols = listOf("SYM1", "SYM2"))
        runCurrent()

        val firstJob = launch { viewModel.scan() }
        runCurrent() // let the first scan visibly enter Scanning before the reentrant call

        viewModel.scan() // must be a no-op: scanState is already Scanning
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
    fun selectionSwitchReevaluatesResults() = runTest(dispatcher.scheduler) {
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
        val viewModel = vm(snapshotStore = snapshotStore)
        runCurrent()

        assertEquals(listOf("A"), viewModel.state.value.results.map { it.symbol }) // default Preset(RsiOversold)

        viewModel.select(ScreenSelection.Preset(PresetScreen.RsiOverbought))

        assertEquals(listOf("B"), viewModel.state.value.results.map { it.symbol })
    }

    // MARK: (g) sort by column/direction, nil-metric rows sorted last regardless of direction

    @Test
    fun sortByDayChangeNilMetricRowsSortedLastBothDirections() = runTest(dispatcher.scheduler) {
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
        val viewModel = vm(snapshotStore = snapshotStore)
        runCurrent()
        // A custom screen with a trivially-true condition matches every row, regardless of
        // dayChangePercent, so sorting can be observed across the full set including nulls.
        viewModel.select(
            ScreenSelection.Custom(
                CustomScreen(
                    id = "all", name = "All",
                    conditions = listOf(ScreenCondition(ScreenerMetric.price, ScreenComparison.Above, -1.0)),
                ),
            ),
        )

        viewModel.setSortColumn(ScreenerSortColumn.DayChange)
        viewModel.setSortAscending(true)
        assertEquals(listOf("LOW", "MID", "HIGH", "NILA", "NILB"), viewModel.state.value.results.map { it.symbol })

        viewModel.setSortAscending(false)
        assertEquals(listOf("HIGH", "MID", "LOW", "NILA", "NILB"), viewModel.state.value.results.map { it.symbol })
    }

    @Test
    fun sortBySymbolAscendingAndDescending() = runTest(dispatcher.scheduler) {
        val snapshotStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = "2026-07-20", scannedAtEpochSeconds = fixedNow,
                rows = listOf(
                    row(symbol = "B", rsi14 = 10.0), row(symbol = "A", rsi14 = 10.0), row(symbol = "C", rsi14 = 10.0),
                ),
                failedSymbols = emptyList(),
            )
        }
        val viewModel = vm(snapshotStore = snapshotStore)
        runCurrent()
        viewModel.select(ScreenSelection.Preset(PresetScreen.RsiOversold)) // all three match (rsi14 == 10 < 30)

        viewModel.setSortColumn(ScreenerSortColumn.Symbol)
        viewModel.setSortAscending(true)
        assertEquals(listOf("A", "B", "C"), viewModel.state.value.results.map { it.symbol })

        viewModel.setSortAscending(false)
        assertEquals(listOf("C", "B", "A"), viewModel.state.value.results.map { it.symbol })
    }

    // MARK: (h) save/delete screen persists through the store fake

    @Test
    fun saveScreenInsertsThenReplacesAndPersists() = runTest(dispatcher.scheduler) {
        val screenStore = MemoryScreenStore()
        val viewModel = vm(screenStore = screenStore)
        runCurrent()

        val screen = CustomScreen(id = "s1", name = "Original", conditions = emptyList())
        viewModel.saveScreen(screen)
        runCurrent()
        assertEquals(listOf(screen), viewModel.state.value.savedScreens)
        assertEquals(listOf(screen), screenStore.screens)
        assertEquals(1, screenStore.saveCallCount)

        val replaced = CustomScreen(id = "s1", name = "Renamed", conditions = emptyList())
        viewModel.saveScreen(replaced)
        runCurrent()
        assertEquals(listOf(replaced), viewModel.state.value.savedScreens)
        assertEquals(listOf(replaced), screenStore.screens)
        assertEquals(2, screenStore.saveCallCount)
    }

    @Test
    fun deleteScreenRemovesAndPersists() = runTest(dispatcher.scheduler) {
        val screenA = CustomScreen(id = "a", name = "A", conditions = emptyList())
        val screenB = CustomScreen(id = "b", name = "B", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(screenA, screenB) }
        val viewModel = vm(screenStore = screenStore)
        runCurrent()

        viewModel.deleteScreen("a")
        runCurrent()

        assertEquals(listOf(screenB), viewModel.state.value.savedScreens)
        assertEquals(listOf(screenB), screenStore.screens)
    }

    // MARK: (h) selection re-sync -- saveScreen/deleteScreen keep `selection` consistent
    // with `savedScreens` rather than leaving a stale/ghost Custom value behind.

    @Test
    fun saveScreenEditingTheActiveSelectionReSelectsUpdatedScreenAndReevaluatesResults() = runTest(dispatcher.scheduler) {
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
        val viewModel = vm(snapshotStore = snapshotStore, screenStore = screenStore)
        runCurrent()
        viewModel.select(ScreenSelection.Custom(original))
        assertEquals(listOf("A"), viewModel.state.value.results.map { it.symbol }) // rsi14 < 30

        // Same id, tightened threshold -- now excludes "A" too (rsi14 20 is not < 10).
        val edited = CustomScreen(
            id = "s1", name = "Screen",
            conditions = listOf(ScreenCondition(ScreenerMetric.rsi14, ScreenComparison.Below, 10.0)),
        )
        viewModel.saveScreen(edited)
        runCurrent()

        assertEquals(ScreenSelection.Custom(edited), viewModel.state.value.selection)
        assertEquals(emptyList(), viewModel.state.value.results.map { it.symbol }, "results must re-evaluate against the edited conditions")
    }

    @Test
    fun saveScreenEditingANonActiveScreenLeavesSelectionUnchanged() = runTest(dispatcher.scheduler) {
        val active = CustomScreen(id = "active", name = "Active", conditions = emptyList())
        val other = CustomScreen(id = "other", name = "Other", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(active, other) }
        val viewModel = vm(screenStore = screenStore)
        runCurrent()
        viewModel.select(ScreenSelection.Custom(active))

        val editedOther = CustomScreen(id = "other", name = "Other Renamed", conditions = emptyList())
        viewModel.saveScreen(editedOther)
        runCurrent()

        assertEquals(ScreenSelection.Custom(active), viewModel.state.value.selection)
    }

    @Test
    fun saveScreenBrandNewScreenAutoSelectsIt() = runTest(dispatcher.scheduler) {
        val viewModel = vm() // default selection is Preset(RsiOversold)
        runCurrent()

        val created = CustomScreen(id = "new", name = "New Screen", conditions = emptyList())
        viewModel.saveScreen(created)
        runCurrent()

        assertEquals(ScreenSelection.Custom(created), viewModel.state.value.selection)
    }

    @Test
    fun deleteScreenDeletingTheActiveSelectionResetsToDefaultPreset() = runTest(dispatcher.scheduler) {
        val active = CustomScreen(id = "s1", name = "Screen", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(active) }
        val viewModel = vm(screenStore = screenStore)
        runCurrent()
        viewModel.select(ScreenSelection.Custom(active))

        viewModel.deleteScreen("s1")
        runCurrent()

        assertEquals(ScreenSelection.Preset(PresetScreen.RsiOversold), viewModel.state.value.selection)
    }

    @Test
    fun deleteScreenDeletingANonActiveScreenLeavesSelectionUnchanged() = runTest(dispatcher.scheduler) {
        val active = CustomScreen(id = "active", name = "Active", conditions = emptyList())
        val other = CustomScreen(id = "other", name = "Other", conditions = emptyList())
        val screenStore = MemoryScreenStore().apply { screens = listOf(active, other) }
        val viewModel = vm(screenStore = screenStore)
        runCurrent()
        viewModel.select(ScreenSelection.Custom(active))

        viewModel.deleteScreen("other")
        runCurrent()

        assertEquals(ScreenSelection.Custom(active), viewModel.state.value.selection)
    }

    // MARK: (h, Android-only) apply-then-persist hardening -- pins the M9.3 Task 2
    // lost-update fix for the IO-hop race in saveScreen/deleteScreen. NOT transcribed from
    // the desktop twin, which has no suspension between capturing state and persisting it
    // and so cannot exhibit this race at all -- see ScreenerViewModel's "APPLY-THEN-PERSIST
    // ORDERING" class doc.
    //
    // Pre-fix, saveScreen captured `current = _state.value` at the very top of the launched
    // coroutine, computed `updatedScreens` from that stale snapshot, suspended at
    // `withContext(ioDispatcher)`, and only THEN applied `_state.update` using the
    // pre-capture list. Issuing saveScreen(screenA) then saveScreen(screenB) in the same
    // tick (before any runCurrent()) meant both coroutines captured the SAME empty
    // `savedScreens`, each computed its own single-screen list, and B's terminal
    // `_state.update` (using its stale list) clobbered A's already-applied change --
    // `state.savedScreens` and the persisted store list would end up holding ONLY screenB.
    @Test
    fun saveScreenTwoMutationsInSameTickBothPersistNoLostUpdate() = runTest(dispatcher.scheduler) {
        val screenStore = MemoryScreenStore()
        val viewModel = vm(screenStore = screenStore)
        runCurrent() // let the init-time store loads land

        val screenA = CustomScreen(id = "a", name = "A", conditions = emptyList())
        val screenB = CustomScreen(id = "b", name = "B", conditions = emptyList())
        // Both calls issued in the same tick, before any runCurrent() drains either one's
        // IO hop -- this is exactly the racing-mutation window the fix closes.
        viewModel.saveScreen(screenA)
        viewModel.saveScreen(screenB)
        runCurrent()

        assertEquals(
            setOf("a", "b"),
            viewModel.state.value.savedScreens.map { it.id }.toSet(),
            "neither save may clobber the other in state -- both screens must survive",
        )
        assertEquals(
            setOf("a", "b"),
            screenStore.screens.map { it.id }.toSet(),
            "the persisted store list must also retain both screens, not just whichever write resumed last",
        )
    }

    // MARK: (i) matchCount live against the current snapshot

    @Test
    fun matchCountEvaluatesAdHocConditionsAgainstSnapshot() = runTest(dispatcher.scheduler) {
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
        val viewModel = vm(snapshotStore = snapshotStore)
        runCurrent()

        val count = viewModel.matchCount(listOf(ScreenCondition(ScreenerMetric.rsi14, ScreenComparison.Below, 30.0)))
        assertEquals(2, count)

        assertEquals(0, viewModel.matchCount(emptyList()), "an unbuilt (empty-condition) screen matches nothing")
    }

    @Test
    fun matchCountNoSnapshotIsZero() = runTest(dispatcher.scheduler) {
        val viewModel = vm(snapshotStore = MemorySnapshotStore())
        runCurrent()
        assertEquals(0, viewModel.matchCount(listOf(ScreenCondition(ScreenerMetric.price, ScreenComparison.Above, -1.0))))
    }

    // MARK: isSnapshotFresh

    @Test
    fun isSnapshotFreshTrueWhenTradingDayMatchesTodayFalseOtherwise() = runTest(dispatcher.scheduler) {
        val calendar = MarketCalendar()
        val snapshotStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = calendar.tradingDay(fixedNow), scannedAtEpochSeconds = fixedNow,
                rows = emptyList(), failedSymbols = emptyList(),
            )
        }
        val freshVm = vm(snapshotStore = snapshotStore)
        runCurrent()
        assertTrue(freshVm.isSnapshotFresh())

        val staleStore = MemorySnapshotStore().apply {
            snapshot = ScreenerSnapshot(
                tradingDay = "2000-01-01", scannedAtEpochSeconds = fixedNow,
                rows = emptyList(), failedSymbols = emptyList(),
            )
        }
        val staleVm = vm(snapshotStore = staleStore)
        runCurrent()
        assertFalse(staleVm.isSnapshotFresh())

        val noSnapshotVm = vm(snapshotStore = MemorySnapshotStore())
        runCurrent()
        assertFalse(noSnapshotVm.isSnapshotFresh())
    }

    // MARK: (extra) ScreenerScanAborted -> Failed, previous snapshot retained (Kotlin-only
    // engine path -- see ScreenerViewModel's class doc "DOCUMENTED DIVERGENCE" note).

    @Test
    fun scanAbortedMapsToFailedStateAndRetainsPreviousSnapshot() = runTest(dispatcher.scheduler) {
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
        val viewModel = vm(market = market, snapshotStore = snapshotStore, symbols = symbols)
        runCurrent()

        viewModel.scan()

        assertEquals(ScreenerScanState.Failed, viewModel.state.value.scanState)
        assertEquals(0, snapshotStore.saveCallCount, "an aborted scan must not persist")
        assertEquals(priorSnapshot, viewModel.state.value.snapshot, "the previous good snapshot must survive")
    }
}
