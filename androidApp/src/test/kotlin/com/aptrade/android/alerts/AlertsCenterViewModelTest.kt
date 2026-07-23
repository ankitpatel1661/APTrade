package com.aptrade.android.alerts

import com.aptrade.android.l10n.trf
import com.aptrade.android.ui.alertSummary
import com.aptrade.shared.application.AlertStore
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.LoadAlerts
import com.aptrade.shared.application.RemovePriceAlert
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.WatchlistEntry
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeAlertStore(initial: List<PriceAlert> = emptyList()) : AlertStore {
    var alerts: List<PriceAlert> = initial
    override suspend fun load(): List<PriceAlert> = alerts
    override suspend fun save(alerts: List<PriceAlert>) {
        this.alerts = alerts
    }
}

private class FakeWatchlistStore(initial: List<WatchlistEntry> = emptyList()) : WatchlistStore {
    var entries: List<WatchlistEntry> = initial
    override suspend fun load(): List<WatchlistEntry> = entries
    override suspend fun save(entries: List<WatchlistEntry>) {
        this.entries = entries
    }
}

private class Boom : Throwable()

/**
 * Android twin of desktopApp/src/test/kotlin/com/aptrade/desktop/alerts/AlertsCenterViewModelTest.kt,
 * transcribing its 10 SEMANTICS pins verbatim — see [AlertsCenterViewModel]'s own KDoc for the
 * reference this is a Compose-port of. [AlertsCenterViewModel] is an androidx ViewModel using
 * `viewModelScope` (Dispatchers.Main.immediate), mirroring
 * [com.aptrade.android.plans.PlansViewModelTest]'s scheduler discipline: a [StandardTestDispatcher]
 * installed as Main, with `runCurrent()` after every non-suspend event-handler call.
 */
class AlertsCenterViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun usd(s: String) = Money.usd(s)

    private fun vm(
        alertStore: AlertStore = FakeAlertStore(),
        watchlistStore: WatchlistStore = FakeWatchlistStore(),
        loadAlertsOverride: (suspend () -> List<PriceAlert>)? = null,
    ): AlertsCenterViewModel = AlertsCenterViewModel(
        loadAlerts = loadAlertsOverride ?: { LoadAlerts(alertStore).execute() },
        removeAlert = { id -> RemovePriceAlert(alertStore).execute(id) },
        loadWatchlist = { FetchWatchlist(watchlistStore, emptyList()).execute() },
    )

    // MARK: - Load + group by symbol

    @Test
    fun loadGroupsBySymbolAlphabetical() = runTest(dispatcher.scheduler) {
        val store = FakeAlertStore(
            listOf(
                PriceAlert(symbol = "TSLA", condition = AlertCondition.PriceAbove(usd("300")), createdAtEpochSeconds = 1L),
                PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceAbove(usd("200")), createdAtEpochSeconds = 2L),
                PriceAlert(symbol = "MSFT", condition = AlertCondition.PriceBelow(usd("100")), createdAtEpochSeconds = 3L),
            ),
        )
        val viewModel = vm(alertStore = store)

        viewModel.load()
        runCurrent()

        assertEquals(listOf("AAPL", "MSFT", "TSLA"), viewModel.state.value.groups.map { it.symbol })
    }

    @Test
    fun loadAlertsWithinSymbolKeepStoredOrder() = runTest(dispatcher.scheduler) {
        val first = PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceAbove(usd("200")), createdAtEpochSeconds = 1L)
        val second = PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceBelow(usd("150")), createdAtEpochSeconds = 2L)
        val store = FakeAlertStore(listOf(first, second))
        val viewModel = vm(alertStore = store)

        viewModel.load()
        runCurrent()

        assertEquals(listOf(first.id, second.id), viewModel.state.value.groups.first().alerts.map { it.id })
    }

    // MARK: - Empty state

    @Test
    fun emptyWhenNoAlerts() = runTest(dispatcher.scheduler) {
        val viewModel = vm()

        viewModel.load()
        runCurrent()

        assertTrue(viewModel.state.value.isEmpty)
        assertTrue(viewModel.state.value.groups.isEmpty())
    }

    @Test
    fun notEmptyWhenAlertsExist() = runTest(dispatcher.scheduler) {
        val store = FakeAlertStore(
            listOf(PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceAbove(usd("200")), createdAtEpochSeconds = 1L)),
        )
        val viewModel = vm(alertStore = store)

        viewModel.load()
        runCurrent()

        assertFalse(viewModel.state.value.isEmpty)
    }

    // MARK: - Remove persists via the injected path AND updates state

    @Test
    fun removePersistsViaInjectedPathAndUpdatesState() = runTest(dispatcher.scheduler) {
        val toRemove = PriceAlert(symbol = "AAPL", condition = AlertCondition.PriceAbove(usd("200")), createdAtEpochSeconds = 1L)
        val toKeep = PriceAlert(symbol = "MSFT", condition = AlertCondition.PriceBelow(usd("100")), createdAtEpochSeconds = 2L)
        val store = FakeAlertStore(listOf(toRemove, toKeep))
        val viewModel = vm(alertStore = store)
        viewModel.load()
        runCurrent()

        viewModel.remove(toRemove.id)
        runCurrent()

        // Persisted: the backing store no longer has it.
        assertEquals(listOf(toKeep.id), store.alerts.map { it.id })
        // State: the VM's own groups reflect the removal immediately.
        assertEquals(listOf(toKeep.id), viewModel.state.value.groups.flatMap { it.alerts }.map { it.id })
    }

    // MARK: - Condition summary reuses the SAME helper PriceAlertSheet uses

    @Test
    fun conditionSummaryMatchesSharedHelperForEveryCase() {
        val above = AlertCondition.PriceAbove(usd("150"))
        val below = AlertCondition.PriceBelow(usd("120"))
        val percent = AlertCondition.PercentChange(5.0)

        assertEquals(trf(L10n.Key.PriceAboveSummaryFormat, usd("150").formatted), alertSummary(above))
        assertEquals(trf(L10n.Key.PriceBelowSummaryFormat, usd("120").formatted), alertSummary(below))
        assertEquals(trf(L10n.Key.PercentMoveSummaryFormat, kotlin.math.abs(5.0)), alertSummary(percent))
    }

    // MARK: - Store-load failure degrades gracefully (no crash, empty)

    @Test
    fun loadFailureDegradesToEmptyNoCrash() = runTest(dispatcher.scheduler) {
        val viewModel = vm(loadAlertsOverride = { throw Boom() })

        viewModel.load()
        runCurrent()

        assertTrue(viewModel.state.value.isEmpty)
        assertTrue(viewModel.state.value.groups.isEmpty())
    }

    // MARK: - Asset lookup for tap-through

    @Test
    fun assetResolvesFromWatchlistWhenPresent() = runTest(dispatcher.scheduler) {
        val aapl = WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)
        val viewModel = vm(watchlistStore = FakeWatchlistStore(listOf(aapl)))
        viewModel.load()
        runCurrent()

        val resolved = viewModel.asset("AAPL")

        assertEquals("AAPL", resolved.symbol)
        assertEquals("Apple Inc.", resolved.name)
        assertEquals(AssetKind.Stock, resolved.kind)
    }

    @Test
    fun assetFallsBackGracefullyWhenNotOnWatchlist() = runTest(dispatcher.scheduler) {
        val viewModel = vm(watchlistStore = FakeWatchlistStore(emptyList()))
        viewModel.load()
        runCurrent()

        val asset = viewModel.asset("ZZZZ")

        assertEquals("ZZZZ", asset.symbol)
    }

    @Test
    fun assetInfersCryptoKindFromUSDSuffixWhenNotOnWatchlist() = runTest(dispatcher.scheduler) {
        val viewModel = vm(watchlistStore = FakeWatchlistStore(emptyList()))
        viewModel.load()
        runCurrent()

        val cryptoAsset = viewModel.asset("BTC-USD")
        val stockAsset = viewModel.asset("AAPL")

        assertEquals(AssetKind.Crypto, cryptoAsset.kind)
        assertEquals(AssetKind.Stock, stockAsset.kind)
    }
}
