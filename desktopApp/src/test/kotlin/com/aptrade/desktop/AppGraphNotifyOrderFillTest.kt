package com.aptrade.desktop

import com.aptrade.desktop.infra.AppSettings
import com.aptrade.desktop.infra.FileSettingsStore
import com.aptrade.shared.domain.TradeSide
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins `AppGraph`'s one Task-4-introduced gate: `buildNotifyOrderFill`'s
 * `if (settingsStore.load().orderFills) { deliver(...) }` check (extracted from
 * `AppGraph.notifyOrderFill`'s former inline closure — review fix, no behavior change).
 *
 * `PortfolioViewModelTest`'s order-fill tests only prove the ViewModel invokes whatever
 * closure it's given; they inject the closure directly and never touch this gate. This
 * file exercises the REAL gate built by `buildNotifyOrderFill` against a real
 * `FileSettingsStore` (temp-file backed, matching `FileSettingsStoreTest`'s style) with
 * a recording fake standing in for `TrayNotifier.notifyFill` — no `TrayState`/AWT
 * dependency needed, matching why `TrayNotifierFormattingTest` avoids constructing a
 * real `TrayNotifier` too.
 */
class AppGraphNotifyOrderFillTest {

    private fun tempSettingsStore() =
        FileSettingsStore(createTempDirectory("aptrade-notify-order-fill-test").resolve("settings.json"))

    @Test
    fun `delivers the notification when orderFills is enabled`() = runTest {
        val store = tempSettingsStore().apply { save(AppSettings(orderFills = true)) }
        val delivered = mutableListOf<List<String>>()
        val notify = buildNotifyOrderFill(store) { side, symbol, quantityText, amountFormatted ->
            delivered += listOf(side.name, symbol, quantityText, amountFormatted)
        }

        notify(TradeSide.Buy, "AAPL", "10", "$1,000.00")

        assertEquals(1, delivered.size)
        assertEquals(listOf("Buy", "AAPL", "10", "$1,000.00"), delivered.single())
    }

    @Test
    fun `does not deliver the notification when orderFills is disabled`() = runTest {
        val store = tempSettingsStore().apply { save(AppSettings(orderFills = false)) }
        var deliveredCount = 0
        val notify = buildNotifyOrderFill(store) { _, _, _, _ -> deliveredCount++ }

        notify(TradeSide.Sell, "AAPL", "4", "$400.00")

        assertEquals(0, deliveredCount)
    }

    @Test
    fun `reads the flag fresh on every call rather than caching it at construction`() = runTest {
        val store = tempSettingsStore().apply { save(AppSettings(orderFills = false)) }
        var deliveredCount = 0
        val notify = buildNotifyOrderFill(store) { _, _, _, _ -> deliveredCount++ }

        notify(TradeSide.Buy, "AAPL", "1", "$100.00")
        assertEquals(0, deliveredCount)

        store.save(AppSettings(orderFills = true))
        notify(TradeSide.Buy, "AAPL", "1", "$100.00")

        assertEquals(1, deliveredCount)
        assertTrue(deliveredCount == 1)
    }
}
