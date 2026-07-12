package com.aptrade.android.watchlist

import com.aptrade.android.FakeMarketDataRepository
import com.aptrade.shared.application.AddToWatchlist
import com.aptrade.shared.application.AlertNotifier
import com.aptrade.shared.application.AlertStore
import com.aptrade.shared.application.CreatePriceAlert
import com.aptrade.shared.application.EvaluateAlerts
import com.aptrade.shared.application.FetchHistory
import com.aptrade.shared.application.FetchMarketQuotes
import com.aptrade.shared.application.FetchWatchlist
import com.aptrade.shared.application.LoadAlerts
import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.application.RemoveFromWatchlist
import com.aptrade.shared.application.RemovePriceAlert
import com.aptrade.shared.application.WatchlistStore
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.WatchlistEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeWatchlistStore(
    var entries: List<WatchlistEntry> = emptyList(),
) : WatchlistStore {
    override suspend fun load(): List<WatchlistEntry> = entries
    override suspend fun save(entries: List<WatchlistEntry>) { this.entries = entries }
}

/** In-memory [AlertStore] fake — mirrors [FakeWatchlistStore]'s shape for the alert side of
 *  the VM's poll. */
private class FakeAlertStore(
    var alerts: List<PriceAlert> = emptyList(),
) : AlertStore {
    override suspend fun load(): List<PriceAlert> = alerts
    override suspend fun save(alerts: List<PriceAlert>) { this.alerts = alerts }
}

/** Records every [notify] call instead of touching a real NotificationManager — lets a test
 *  assert exactly how many (and which) alerts were delivered. */
private class RecordingAlertNotifier : AlertNotifier {
    val notified = mutableListOf<Pair<PriceAlert, Quote>>()
    override suspend fun notify(alert: PriceAlert, quote: Quote) {
        notified += alert to quote
    }
}

class WatchlistViewModelTest {

    // FetchWatchlist's `defaults` param is mandatory (no default value on the shared use case) —
    // empty here since these tests always seed the store directly and never exercise first-launch
    // seeding (that's FetchWatchlistTest's job in shared/).
    private fun vm(
        store: FakeWatchlistStore,
        repo: FakeMarketDataRepository,
        alertStore: FakeAlertStore = FakeAlertStore(),
        notifier: AlertNotifier = RecordingAlertNotifier(),
    ) = WatchlistViewModel(
        fetchWatchlist = FetchWatchlist(store, emptyList()),
        addToWatchlist = AddToWatchlist(store),
        removeFromWatchlist = RemoveFromWatchlist(store),
        fetchMarketQuotes = FetchMarketQuotes(repo),
        fetchHistory = FetchHistory(repo),
        evaluateAlerts = EvaluateAlerts(alertStore, notifier, isNotifyEnabled = { true }),
        loadAlerts = LoadAlerts(alertStore),
        createPriceAlert = CreatePriceAlert(alertStore),
        removePriceAlert = RemovePriceAlert(alertStore),
        pollIntervalMs = Long.MAX_VALUE, // no re-poll during tests
    )

    @Test
    fun `load populates rows in watchlist order with quotes`() = runTest {
        val store = FakeWatchlistStore(listOf(
            WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
            WatchlistEntry("BTC-USD", "Bitcoin", AssetKind.Crypto),
        ))
        val repo = FakeMarketDataRepository()
        // The fake's default quotesImpl returns emptyList() regardless of input, which would
        // leave amountText null for every row — configure it to answer for whatever symbols the
        // VM asks for, mirroring how every other suite in this app drives the same fake.
        repo.quotesImpl = { symbols -> symbols.map { Quote(it, Money.usd("100.00"), Money.usd("100.00"), 0.0) } }
        val viewModel = vm(store, repo)
        viewModel.load()
        val state = viewModel.state.value
        assertEquals(listOf("AAPL", "BTC-USD"), state.rows.map { it.symbol })
        assertTrue(state.rows.first().amountText != null)
    }

    // --- spark (UAT round 2, defect 3: watchlist row mini sparklines, Windows/desktop parity) ---

    @Test
    fun `load populates each row's spark from its 1-day history`() = runTest {
        val store = FakeWatchlistStore(listOf(
            WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
            WatchlistEntry("BTC-USD", "Bitcoin", AssetKind.Crypto),
        ))
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { Quote(it, Money.usd("100.00"), Money.usd("100.00"), 0.0) } }
        repo.historyImpl = { symbol, _ ->
            when (symbol) {
                "AAPL" -> listOf(
                    PricePoint(1_700_000_000L, Money.usd("98.50")),
                    PricePoint(1_700_003_600L, Money.usd("100.00")),
                )
                else -> listOf(
                    PricePoint(1_700_000_000L, Money.usd("60000")),
                    PricePoint(1_700_003_600L, Money.usd("61000")),
                )
            }
        }
        val viewModel = vm(store, repo)

        viewModel.load()

        val rowsBySymbol = viewModel.state.value.rows.associateBy { it.symbol }
        assertEquals(listOf(98.5, 100.0), rowsBySymbol.getValue("AAPL").spark)
        assertEquals(listOf(60000.0, 61000.0), rowsBySymbol.getValue("BTC-USD").spark)
    }

    @Test
    fun `a history failure for one symbol leaves its spark empty without erroring the row or the list`() = runTest {
        val store = FakeWatchlistStore(listOf(
            WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
            WatchlistEntry("BTC-USD", "Bitcoin", AssetKind.Crypto),
        ))
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { Quote(it, Money.usd("100.00"), Money.usd("100.00"), 0.0) } }
        repo.historyImpl = { symbol, _ ->
            if (symbol == "AAPL") {
                throw QuoteError.Network("history unavailable")
            } else {
                listOf(PricePoint(1_700_000_000L, Money.usd("60000")), PricePoint(1_700_003_600L, Money.usd("61000")))
            }
        }
        val viewModel = vm(store, repo)

        viewModel.load()

        val state = viewModel.state.value
        assertEquals(null, state.error) // history failures never surface as a watchlist error banner
        val rowsBySymbol = state.rows.associateBy { it.symbol }
        // The row itself is intact — symbol/name/amountText/changePercent all still populated —
        // only its spark is empty.
        assertEquals("AAPL", rowsBySymbol.getValue("AAPL").symbol)
        assertTrue(rowsBySymbol.getValue("AAPL").amountText != null)
        assertEquals(emptyList(), rowsBySymbol.getValue("AAPL").spark)
        assertEquals(listOf(60000.0, 61000.0), rowsBySymbol.getValue("BTC-USD").spark)
    }

    @Test
    fun `remove drops the row's spark along with the row`() = runTest {
        val store = FakeWatchlistStore(listOf(WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)))
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { Quote(it, Money.usd("100.00"), Money.usd("100.00"), 0.0) } }
        repo.historyImpl = { _, _ ->
            listOf(PricePoint(1_700_000_000L, Money.usd("98.50")), PricePoint(1_700_003_600L, Money.usd("100.00")))
        }
        val viewModel = vm(store, repo)
        viewModel.load()
        assertTrue(viewModel.state.value.rows.single().spark.isNotEmpty())

        viewModel.remove("AAPL")

        assertEquals(emptyList(), viewModel.state.value.rows)
    }

    @Test
    fun `remove drops the row and persists`() = runTest {
        val store = FakeWatchlistStore(listOf(WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)))
        val viewModel = vm(store, FakeMarketDataRepository())
        viewModel.load()
        viewModel.remove("AAPL")
        assertEquals(emptyList(), viewModel.state.value.rows)
        assertEquals(emptyList(), store.entries)
    }

    @Test
    fun `triggered alert fires the notifier once and stays fired on re-evaluation`() = runTest {
        val store = FakeWatchlistStore(listOf(WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock)))
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { Quote(it, Money.usd("150.00"), Money.usd("150.00"), 0.0) } }
        val alertStore = FakeAlertStore(listOf(
            PriceAlert(
                symbol = "AAPL",
                condition = AlertCondition.PriceAbove(Money.usd("100.00")),
                createdAtEpochSeconds = 0L,
            ),
        ))
        val notifier = RecordingAlertNotifier()
        val viewModel = vm(store, repo, alertStore, notifier)

        viewModel.load()
        assertEquals(1, notifier.notified.size)
        assertEquals("AAPL", notifier.notified.single().first.symbol)

        // A second evaluation pass must not re-fire: EvaluateAlerts persists `isTriggered` and
        // skips already-triggered alerts on every subsequent pass.
        viewModel.load()
        assertEquals(1, notifier.notified.size)
    }

    @Test
    fun `alertCounts reflects untriggered alerts grouped by symbol`() = runTest {
        val store = FakeWatchlistStore(listOf(
            WatchlistEntry("AAPL", "Apple Inc.", AssetKind.Stock),
            WatchlistEntry("BTC-USD", "Bitcoin", AssetKind.Crypto),
        ))
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { Quote(it, Money.usd("100.00"), Money.usd("100.00"), 0.0) } }
        val alertStore = FakeAlertStore(listOf(
            PriceAlert(
                symbol = "AAPL",
                condition = AlertCondition.PriceAbove(Money.usd("500.00")), // not met at $100
                createdAtEpochSeconds = 0L,
            ),
            PriceAlert(
                symbol = "AAPL",
                condition = AlertCondition.PriceBelow(Money.usd("1.00")), // not met at $100
                createdAtEpochSeconds = 1L,
            ),
            PriceAlert(
                symbol = "BTC-USD",
                condition = AlertCondition.PriceAbove(Money.usd("999999.00")), // not met at $100
                createdAtEpochSeconds = 2L,
            ),
            PriceAlert(
                symbol = "BTC-USD",
                condition = AlertCondition.PriceAbove(Money.usd("1.00")),
                createdAtEpochSeconds = 3L,
                isTriggered = true, // already triggered — excluded from the untriggered count
            ),
        ))
        val viewModel = vm(store, repo, alertStore)

        viewModel.load()

        assertEquals(mapOf("AAPL" to 2, "BTC-USD" to 1), viewModel.state.value.alertCounts)
        val rowsBySymbol = viewModel.state.value.rows.associateBy { it.symbol }
        assertEquals(2, rowsBySymbol.getValue("AAPL").alertCount)
        assertEquals(1, rowsBySymbol.getValue("BTC-USD").alertCount)
    }
}
