package com.aptrade.desktop

import com.aptrade.desktop.designkit.AccentTheme
import com.aptrade.desktop.infra.AppSettings
import com.aptrade.desktop.infra.FileSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `two concurrent disjoint-field mutations both survive`() = runBlocking(Dispatchers.Default) {
        // 6d.2 Task 4: persistSettings now serializes its load-merge-save under a file-level
        // Mutex (closing the RMW lost-update window 6d.1's final review recorded — the fix
        // above only re-orders fields for ONE call; a second call racing the first could
        // still load the same pre-mutation blob and clobber it on save). This test launches
        // two real, concurrently-scheduled mutations (Dispatchers.Default, real threads, no
        // virtual time) against the SAME store and asserts BOTH disjoint-field mutations
        // land — which requires them to actually be serialized rather than interleaved,
        // since either ordering that overlaps its load/save would drop one field's write.
        val store = tempSettingsStore()
        store.save(AppSettings(accent = AccentTheme.Amethyst, priceAlerts = true, orderFills = true))

        val mutationCount = 25
        val jobs = (0 until mutationCount).map { i ->
            async {
                if (i % 2 == 0) {
                    persistSettings(store) { it.copy(marketOpenClose = true) }
                } else {
                    persistSettings(store) { it.copy(newsDigest = false) }
                }
            }
        }
        jobs.awaitAll()

        // Every mutation targets a disjoint field pair from the seed; both survive only if
        // no writer's save() ever clobbered another's already-applied field.
        val result = store.load()
        assertEquals(AccentTheme.Amethyst, result.accent) // untouched by either mutation
        assertEquals(true, result.priceAlerts)            // untouched by either mutation
        assertEquals(true, result.marketOpenClose)         // set by the even-indexed mutators
        assertEquals(false, result.newsDigest)             // set by the odd-indexed mutators
    }
}
