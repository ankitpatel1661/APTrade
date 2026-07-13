package com.aptrade.android.settings

import com.aptrade.android.l10n.LocalizationManager
import com.aptrade.shared.infrastructure.FileSettingsStore
import com.aptrade.shared.l10n.AppLanguage
import com.aptrade.shared.settings.AccentTheme
import com.aptrade.shared.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Android settings persist seam — the port of desktop's `SettingsPersistTest`
 * (desktopApp/src/test/kotlin/com/aptrade/desktop/SettingsPersistTest.kt), which guards the
 * two bugs the desktop shipped and then closed:
 *
 *  1. Lost fields on save: an early desktop `selectAccent` wrote a fresh
 *     `AppSettings(accent = theme)`, silently resetting every notification flag to its
 *     default. The fix is load-merge-save; the field-preservation tests here pin the same
 *     shape against the REAL [FileSettingsStore] (temp-file backed).
 *  2. Lost updates across concurrent mutators: load-merge-save alone still lets two
 *     concurrent callers load the same pre-mutation blob and clobber each other. Desktop
 *     serialized the sequence under a file-level Mutex (6d.2 Task 4); the concurrency test
 *     here launches real, concurrently-scheduled mutations (Dispatchers.Default, real
 *     threads, no virtual time) against the SAME store and asserts BOTH disjoint-field
 *     mutations land.
 *
 * The ViewModel-level tests additionally pin the Android-only seam: language selection must
 * flip [LocalizationManager.current] (so every `tr()` reader recomposes immediately) AND
 * persist through the same load-merge-save — one persistence path, no second copy.
 *
 * Harness note: [FileSettingsStore] hops to the REAL `Dispatchers.IO` internally, so a plain
 * `advanceUntilIdle()` would race it (the virtual scheduler goes idle while the IO
 * round-trip is still in flight). [awaitUntil] instead polls the condition on
 * `Dispatchers.Default` in REAL time; while the test body is suspended there, `runTest`
 * keeps processing the viewModelScope resumptions the IO threads enqueue on the test-Main
 * dispatcher — bounded, condition-based waiting with no virtual-time spin.
 */
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        // LocalizationManager is process-global snapshot state; never leak a language
        // selection from one test into another (same rationale as DKColorTableTest's inMode).
        LocalizationManager.current.value = AppLanguage.English
    }

    private fun tempSettingsStore() =
        FileSettingsStore(createTempDirectory("aptrade-android-settings-test").resolve("settings.json"))

    /** Polls [condition] every 10ms of REAL time (Dispatchers.Default — never the virtual
     *  scheduler, which would fast-forward the timeout in microseconds), failing after 5s. */
    private suspend fun awaitUntil(condition: suspend () -> Boolean) =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!condition()) delay(10)
            }
        }

    // MARK: - ViewModel: toggle persists, other fields preserved

    @Test
    fun `a notification-flag toggle persists and preserves the accent and the other flags`() =
        runTest(dispatcher.scheduler) {
            val store = tempSettingsStore()
            store.save(
                AppSettings(
                    accent = AccentTheme.Amethyst,
                    priceAlerts = true,
                    orderFills = false,
                    marketOpenClose = true,
                    newsDigest = false,
                    emailNotifications = false,
                )
            )
            val vm = SettingsViewModel(store)
            awaitUntil { vm.settings.value.accent == AccentTheme.Amethyst } // initial load done

            vm.update { it.copy(marketOpenClose = false) }
            awaitUntil { !store.load().marketOpenClose }

            val persisted = store.load()
            assertEquals(false, persisted.marketOpenClose)
            assertEquals(AccentTheme.Amethyst, persisted.accent)
            assertEquals(true, persisted.priceAlerts)
            assertEquals(false, persisted.orderFills)
            assertEquals(false, persisted.newsDigest)
            assertEquals(false, persisted.emailNotifications)
            // The live StateFlow mirrors what was persisted — MainActivity themes from it.
            assertEquals(persisted, vm.settings.value)
        }

    @Test
    fun `an accent change persists and preserves non-default flags and theme mode`() =
        runTest(dispatcher.scheduler) {
            val store = tempSettingsStore()
            store.save(
                AppSettings(
                    accent = AccentTheme.ChampagneGold,
                    marketOpenClose = true,
                    emailNotifications = true,
                    isDarkMode = false,
                )
            )
            val vm = SettingsViewModel(store)
            awaitUntil { !vm.settings.value.isDarkMode } // initial load done

            vm.update { it.copy(accent = AccentTheme.Sapphire) }
            awaitUntil { store.load().accent == AccentTheme.Sapphire }

            val persisted = store.load()
            assertEquals(AccentTheme.Sapphire, persisted.accent)
            assertEquals(true, persisted.marketOpenClose)
            assertEquals(true, persisted.emailNotifications)
            assertEquals(false, persisted.isDarkMode)
            assertEquals(persisted, vm.settings.value)
        }

    // MARK: - ViewModel: language

    @Test
    fun `language selection updates LocalizationManager immediately and persists`() =
        runTest(dispatcher.scheduler) {
            // Seed a non-default accent purely as an init-load completion marker: in the
            // real app the startup load resolves long before the user can reach the
            // language page, so the test waits it out too — otherwise the init load's
            // LocalizationManager write would land AFTER the selection and clobber it.
            val store = tempSettingsStore()
            store.save(AppSettings(accent = AccentTheme.Platinum))
            val vm = SettingsViewModel(store)
            awaitUntil { vm.settings.value.accent == AccentTheme.Platinum }

            vm.selectLanguage(AppLanguage.German)
            // The live flip is synchronous — every tr() reader must recompose before any IO.
            assertEquals(AppLanguage.German, LocalizationManager.current.value)

            awaitUntil { store.load().language == AppLanguage.German }
            assertEquals(AppLanguage.German, vm.settings.value.language)
            // The persist merged, not clobbered: the seeded accent survived.
            assertEquals(AccentTheme.Platinum, store.load().accent)
        }

    @Test
    fun `initial load applies the persisted language to LocalizationManager`() =
        runTest(dispatcher.scheduler) {
            val store = tempSettingsStore()
            store.save(AppSettings(language = AppLanguage.Italian))

            val vm = SettingsViewModel(store)
            awaitUntil { LocalizationManager.current.value == AppLanguage.Italian }

            assertEquals(AppLanguage.Italian, vm.settings.value.language)
        }

    // MARK: - Mutex: concurrent mutations both land

    @Test
    fun `two concurrent disjoint-field mutations both survive`() = runBlocking(Dispatchers.Default) {
        // Mirror of desktop SettingsPersistTest's Mutex test: real threads, no virtual time,
        // exercising the REAL persistSettings seam the ViewModel routes every update through.
        // Either overlapping load/save ordering would drop one field's write — both landing
        // requires the load-merge-save sequences to actually serialize.
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

        val result = store.load()
        assertEquals(AccentTheme.Amethyst, result.accent) // untouched by either mutation
        assertEquals(true, result.priceAlerts)            // untouched by either mutation
        assertEquals(true, result.marketOpenClose)        // set by the even-indexed mutators
        assertEquals(false, result.newsDigest)            // set by the odd-indexed mutators
    }
}
