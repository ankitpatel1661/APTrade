package com.aptrade.desktop

import com.aptrade.desktop.designkit.AccentTheme
import com.aptrade.desktop.infra.AppSettings
import com.aptrade.desktop.infra.FileSettingsStore
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `persistSettings` load-merge-save seam (review fix for Task 5) — extracted from
 * `Main.kt`'s local `persistSettings`/`selectAccent`/`updateNotificationSettings` functions,
 * which were previously untestable (nested inside `main() = application { ... }`).
 *
 * Task 5 fixed a real pre-existing bug: `selectAccent` used to call
 * `graph.settingsStore.save(AppSettings(accent = theme))`, constructing a fresh `AppSettings`
 * with every other field at its default — silently clobbering `priceAlerts`/`orderFills`/
 * `marketOpenClose`/`newsDigest`/`emailNotifications` back to defaults on every accent change.
 * The fix (load → mutate → save) was correct but shipped with zero test coverage; these tests
 * close that gap by exercising the REAL `persistSettings` function against a real
 * `FileSettingsStore` (temp-file backed, matching `AppGraphNotifyOrderFillTest`'s style) with
 * the exact two mutations `Main.kt` performs: `selectAccent`'s `it.copy(accent = theme)` and
 * `updateNotificationSettings`'s per-toggle `it.copy(...)`.
 */
class SettingsPersistTest {

    private fun tempSettingsStore() =
        FileSettingsStore(createTempDirectory("aptrade-settings-persist-test").resolve("settings.json"))

    @Test
    fun `an accent change preserves all five non-default notification flags`() = runTest {
        val store = tempSettingsStore()
        val nonDefaultFlags = AppSettings(
            accent = AccentTheme.ChampagneGold,
            priceAlerts = false,
            orderFills = false,
            marketOpenClose = true,
            newsDigest = false,
            emailNotifications = true,
        )
        store.save(nonDefaultFlags)

        // Exactly selectAccent's mutation: `it.copy(accent = theme)`.
        persistSettings(store) { it.copy(accent = AccentTheme.Sapphire) }

        val result = store.load()
        assertEquals(AccentTheme.Sapphire, result.accent)
        assertEquals(false, result.priceAlerts)
        assertEquals(false, result.orderFills)
        assertEquals(true, result.marketOpenClose)
        assertEquals(false, result.newsDigest)
        assertEquals(true, result.emailNotifications)
    }

    @Test
    fun `a notification-flag toggle preserves the accent and the other flags`() = runTest {
        val store = tempSettingsStore()
        val initial = AppSettings(
            accent = AccentTheme.Amethyst,
            priceAlerts = true,
            orderFills = false,
            marketOpenClose = true,
            newsDigest = false,
            emailNotifications = false,
        )
        store.save(initial)

        // Exactly updateNotificationSettings's mutation shape for one toggle (marketOpenClose).
        persistSettings(store) { it.copy(marketOpenClose = false) }

        val result = store.load()
        assertEquals(AccentTheme.Amethyst, result.accent)
        assertEquals(true, result.priceAlerts)
        assertEquals(false, result.orderFills)
        assertEquals(false, result.marketOpenClose)
        assertEquals(false, result.newsDigest)
        assertEquals(false, result.emailNotifications)
    }
}
