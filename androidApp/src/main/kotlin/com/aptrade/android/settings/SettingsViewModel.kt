package com.aptrade.android.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.android.l10n.LocalizationManager
import com.aptrade.shared.infrastructure.FileSettingsStore
import com.aptrade.shared.infrastructure.FinnhubKeyConfig
import com.aptrade.shared.l10n.AppLanguage
import com.aptrade.shared.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes [persistSettings]'s load-merge-save — the Android port of desktop
 *  `AppGraph.kt`'s `settingsMutex` (6d.2 Task 4, closing the RMW lost-update window its
 *  6d.1 review recorded): the blob is re-loaded from the store *inside* the lock, never
 *  taken from a possibly-stale caller snapshot, so two concurrent mutators (an accent
 *  change racing a notification-toggle flip) serialize against the single-blob store
 *  instead of one clobbering the other's write. A single file-level [Mutex] is correct
 *  here because `AppGraph.settingsStore` is a single process-wide instance shared by
 *  every caller — the same shape as the shared `portfolioMutex` guarding the portfolio
 *  store's RMW. */
private val settingsMutex = Mutex()

/**
 * The settings load-merge-save seam — the Android port of desktop `AppGraph.kt`'s
 * `persistSettings` (which fixed a real bug: an early `selectAccent` wrote a fresh
 * `AppSettings(accent = theme)`, silently resetting every notification flag to its
 * default on each accent change). Reads the persisted blob, applies [mutate], writes the
 * result back, and returns it — all under [settingsMutex]. Package-visible (not private
 * to the ViewModel) so `SettingsViewModelTest` can exercise the REAL sequence against a
 * real temp-file [FileSettingsStore] with concurrently-scheduled mutators, mirroring
 * desktop's `SettingsPersistTest`.
 */
internal suspend fun persistSettings(
    settingsStore: FileSettingsStore,
    mutate: (AppSettings) -> AppSettings,
): AppSettings = settingsMutex.withLock {
    val next = mutate(settingsStore.load())
    settingsStore.save(next)
    next
}

/**
 * Holds the live [AppSettings] the whole app themes from (MainActivity collects [settings]
 * and feeds `APTradeTheme(settings)`, so an accent/theme tap re-themes instantly) and
 * routes every mutation through the one Mutex-serialized load-merge-save seam above.
 *
 * [update] flips the in-memory state first (desktop Main.kt's "flip the live state, then
 * persist" pattern — instant colorScheme swap, no flash) and then persists fire-and-forget:
 * a failed persist leaves the in-memory state applied, it just won't survive a restart —
 * not worth interrupting the user over a cosmetic write (same recorded decision as desktop).
 */
class SettingsViewModel(
    private val settingsStore: FileSettingsStore,
    private val finnhubKeyConfig: FinnhubKeyConfig? = null,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** The stored Finnhub API key ("" while none is saved) for the Account Settings
     *  key-entry field. Lives OUTSIDE [AppSettings] deliberately: the key's home is
     *  config.json (the file `AppGraph`'s news wiring and the manual file-drop path both
     *  read), not settings.json — one source of truth, no second persistence path. */
    private val _finnhubKey = MutableStateFlow("")
    val finnhubKey: StateFlow<String> = _finnhubKey.asStateFlow()

    init {
        // Load persisted settings once at startup. The defaults ARE AppSettings()'s
        // defaults (dark, champagne gold, English), so there is nothing to flash while
        // this resolves — same reasoning as desktop Main.kt's startup LaunchedEffect.
        viewModelScope.launch {
            try {
                val loaded = settingsStore.load()
                _settings.value = loaded
                LocalizationManager.current.value = loaded.language
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Corrupt/unreadable settings already fall back to defaults inside the
                // store; any other unexpected failure keeps the defaults rather than
                // crashing startup.
            }
        }
        finnhubKeyConfig?.let { config ->
            viewModelScope.launch {
                try {
                    _finnhubKey.value = config.finnhubApiKey().orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Unreadable config keeps the empty-field default, same tolerance as
                    // the settings load above.
                }
            }
        }
    }

    /** Persists [raw] (trimmed; blank clears the key) into config.json through
     *  [FinnhubKeyConfig.saveFinnhubApiKey], flipping the in-memory state first — the same
     *  "flip live state, persist fire-and-forget" shape as [update]. AppGraph's news wiring
     *  re-reads config.json per News-tab entry, so no refresh hook is needed here. */
    fun saveFinnhubKey(raw: String) {
        val config = finnhubKeyConfig ?: return
        _finnhubKey.value = raw.trim()
        viewModelScope.launch {
            try {
                config.saveFinnhubApiKey(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Failed persist: in-memory state stays applied (see class KDoc).
            }
        }
    }

    /** Applies [transform] to the live state immediately, then persists it through the
     *  Mutex-serialized load-merge-save. The StateFlow is finally set from the merged
     *  persist result, so rapid successive updates converge on the store's truth. */
    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        viewModelScope.launch {
            try {
                _settings.value = persistSettings(settingsStore, transform)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Failed persist: in-memory state stays applied (see class KDoc).
            }
        }
    }

    /** Mirrors desktop Main.kt's `selectLanguage` exactly: flip [LocalizationManager.current]
     *  first — synchronously, so every `tr()` reader recomposes before any IO — then persist
     *  through the same seam as every other settings mutation. No second persistence path. */
    fun selectLanguage(language: AppLanguage) {
        LocalizationManager.current.value = language
        update { it.copy(language = language) }
    }
}
