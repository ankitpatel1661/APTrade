package com.aptrade.shared.application

import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun quote(symbol: String, price: String, changePercent: Double = 0.0) = Quote(
    symbol = symbol,
    price = Money.usd(price),
    previousClose = Money.usd(price),
    changePercent = changePercent,
)

private class FakeAlertStore(initial: List<PriceAlert> = emptyList()) : AlertStore {
    var saved: List<PriceAlert>? = null
        private set
    var saveCallCount: Int = 0
        private set
    private var stored: List<PriceAlert> = initial

    override suspend fun load(): List<PriceAlert> = stored

    override suspend fun save(alerts: List<PriceAlert>) {
        stored = alerts
        saved = alerts
        saveCallCount += 1
    }
}

private class FakeAlertNotifier : AlertNotifier {
    val notified = mutableListOf<Pair<PriceAlert, Quote>>()

    override suspend fun notify(alert: PriceAlert, quote: Quote) {
        notified += alert to quote
    }
}

class AlertUseCasesTest {

    @Test
    fun loadAlertsDelegatesToStore() = runTest {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        val useCase = LoadAlerts(FakeAlertStore(initial = listOf(alert)))

        assertEquals(listOf(alert), useCase.execute())
    }

    @Test
    fun createPriceAlertAppendsAndSaves() = runTest {
        val existing = PriceAlert(
            symbol = "MSFT",
            condition = AlertCondition.PriceAbove(Money.usd("300.00")),
            createdAtEpochSeconds = 0L,
        )
        val store = FakeAlertStore(initial = listOf(existing))
        val useCase = CreatePriceAlert(store)

        val result = useCase.execute(
            symbol = "AAPL",
            condition = AlertCondition.PriceBelow(Money.usd("150.00")),
            createdAtEpochSeconds = 100L,
        )

        assertEquals(2, result.size)
        assertEquals(existing, result[0])
        assertEquals("AAPL", result[1].symbol)
        assertEquals(AlertCondition.PriceBelow(Money.usd("150.00")), result[1].condition)
        assertEquals(100L, result[1].createdAtEpochSeconds)
        assertEquals(result, store.saved)
    }

    @Test
    fun removePriceAlertFiltersByIdAndSaves() = runTest {
        val keep = PriceAlert(
            id = "keep",
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        val remove = PriceAlert(
            id = "remove",
            symbol = "MSFT",
            condition = AlertCondition.PriceAbove(Money.usd("300.00")),
            createdAtEpochSeconds = 0L,
        )
        val store = FakeAlertStore(initial = listOf(keep, remove))
        val useCase = RemovePriceAlert(store)

        val result = useCase.execute(id = "remove")

        assertEquals(listOf(keep), result)
        assertEquals(listOf(keep), store.saved)
    }

    @Test
    fun evaluateAlertsMarksMatchesAsTriggeredAndPersists() = runTest {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        val store = FakeAlertStore(initial = listOf(alert))
        val notifier = FakeAlertNotifier()
        val useCase = EvaluateAlerts(store, notifier) { true }

        val result = useCase.execute(mapOf("AAPL" to quote("AAPL", "201.00")))

        assertTrue(result.single().isTriggered)
        assertEquals(result, store.saved)
    }

    @Test
    fun evaluateAlertsDoesNotSaveWhenNothingMatched() = runTest {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        val store = FakeAlertStore(initial = listOf(alert))
        val notifier = FakeAlertNotifier()
        val useCase = EvaluateAlerts(store, notifier) { true }

        val result = useCase.execute(mapOf("AAPL" to quote("AAPL", "199.00")))

        assertFalse(result.single().isTriggered)
        assertEquals(0, store.saveCallCount)
    }

    @Test
    fun evaluateAlertsNotifiesWhenNotificationsEnabled() = runTest {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        val store = FakeAlertStore(initial = listOf(alert))
        val notifier = FakeAlertNotifier()
        val useCase = EvaluateAlerts(store, notifier) { true }

        val q = quote("AAPL", "201.00")
        useCase.execute(mapOf("AAPL" to q))

        assertEquals(1, notifier.notified.size)
        assertEquals(alert.symbol, notifier.notified.single().first.symbol)
        assertEquals(q, notifier.notified.single().second)
    }

    @Test
    fun evaluateAlertsTriggersAndPersistsButSuppressesNotifyWhenDisabled() = runTest {
        // macOS parity: triggering + persisting happen regardless of the notify gate —
        // only the outward push is suppressed.
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        val store = FakeAlertStore(initial = listOf(alert))
        val notifier = FakeAlertNotifier()
        val useCase = EvaluateAlerts(store, notifier) { false }

        val result = useCase.execute(mapOf("AAPL" to quote("AAPL", "201.00")))

        assertTrue(result.single().isTriggered)
        assertEquals(result, store.saved)
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun evaluateAlertsSkipsAlreadyTriggeredAlerts() = runTest {
        val alreadyTriggered = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
            isTriggered = true,
        )
        val store = FakeAlertStore(initial = listOf(alreadyTriggered))
        val notifier = FakeAlertNotifier()
        val useCase = EvaluateAlerts(store, notifier) { true }

        val result = useCase.execute(mapOf("AAPL" to quote("AAPL", "201.00")))

        assertEquals(listOf(alreadyTriggered), result)
        assertEquals(0, store.saveCallCount)
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun evaluateAlertsSkipsSymbolsMissingFromQuotesMap() = runTest {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        val store = FakeAlertStore(initial = listOf(alert))
        val notifier = FakeAlertNotifier()
        val useCase = EvaluateAlerts(store, notifier) { true }

        val result = useCase.execute(emptyMap())

        assertFalse(result.single().isTriggered)
        assertEquals(0, store.saveCallCount)
    }

    @Test
    fun evaluateAlertsHandlesMultipleAlertsNotifyingOnlyNewlyTriggered() = runTest {
        val toTrigger = PriceAlert(
            id = "trigger",
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        val toStayUntriggered = PriceAlert(
            id = "stay",
            symbol = "MSFT",
            condition = AlertCondition.PriceAbove(Money.usd("500.00")),
            createdAtEpochSeconds = 0L,
        )
        val store = FakeAlertStore(initial = listOf(toTrigger, toStayUntriggered))
        val notifier = FakeAlertNotifier()
        val useCase = EvaluateAlerts(store, notifier) { true }

        val result = useCase.execute(
            mapOf(
                "AAPL" to quote("AAPL", "201.00"),
                "MSFT" to quote("MSFT", "400.00"),
            ),
        )

        assertTrue(result.first { it.id == "trigger" }.isTriggered)
        assertFalse(result.first { it.id == "stay" }.isTriggered)
        assertEquals(1, notifier.notified.size)
        assertEquals("trigger", notifier.notified.single().first.id)
    }
}
