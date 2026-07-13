package com.aptrade.android

import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.infrastructure.FileSettingsStore
import com.aptrade.shared.settings.AppSettings
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins `AppGraph`'s order-fill gate: `buildNotifyOrderFill`'s
 * `if (settingsStore.load().orderFills) { deliver(...) }` check — the Android port of
 * desktop's `AppGraphNotifyOrderFillTest` (`desktopApp/src/test/kotlin/com/aptrade/desktop/
 * AppGraphNotifyOrderFillTest.kt`), exercised against a real [FileSettingsStore] (temp-file
 * backed, matching `FileSettingsStoreTest`'s style) with a recording fake standing in for
 * [com.aptrade.android.alerts.AndroidAlertNotifier.notifyFill] — no `Context`/`NotificationManager`
 * dependency needed.
 */
class AppGraphNotifyOrderFillTest {

    private fun tempSettingsStore() =
        FileSettingsStore(createTempDirectory("aptrade-android-notify-order-fill-test").resolve("settings.json"))

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
