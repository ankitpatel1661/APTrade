package com.aptrade.shared.infrastructure

import com.aptrade.shared.settings.AccentTheme
import com.aptrade.shared.settings.AppSettings
import com.aptrade.shared.l10n.AppLanguage
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSettingsStoreTest {

    private fun tempFile() = createTempDirectory("aptrade-settings-test").resolve("settings.json")

    @Test
    fun `missing file loads defaults`() = runTest {
        val store = FileSettingsStore(tempFile())
        assertEquals(AppSettings(), store.load())
        assertEquals(AccentTheme.ChampagneGold, store.load().accent)
    }

    @Test
    fun `round-trips a non-default accent`() = runTest {
        val file = tempFile()
        val store = FileSettingsStore(file)
        store.save(AppSettings(accent = AccentTheme.Sapphire))
        assertTrue(file.exists())
        assertEquals(AccentTheme.Sapphire, store.load().accent)
    }

    @Test
    fun `persists the accent as its enum name`() = runTest {
        val file = tempFile()
        FileSettingsStore(file).save(AppSettings(accent = AccentTheme.Amethyst))
        assertTrue(file.readText().contains("\"Amethyst\""))
    }

    @Test
    fun `corrupt json loads defaults`() = runTest {
        val file = tempFile()
        file.writeText("{ this is not valid json ")
        assertEquals(AppSettings(), FileSettingsStore(file).load())
    }

    @Test
    fun `unknown accent name loads defaults`() = runTest {
        val file = tempFile()
        file.writeText("{\"accent\":\"NeonPink\"}")
        // Whole-blob fallback: an unmappable accent means the file is untrusted.
        assertEquals(AccentTheme.ChampagneGold, FileSettingsStore(file).load().accent)
    }

    @Test
    fun `unknown extra keys are ignored`() = runTest {
        val file = tempFile()
        file.writeText("{\"accent\":\"Platinum\",\"futureField\":42}")
        assertEquals(AccentTheme.Platinum, FileSettingsStore(file).load().accent)
    }

    @Test
    fun `empty object falls back to the default accent`() = runTest {
        val file = tempFile()
        file.writeText("{}")
        assertEquals(AccentTheme.ChampagneGold, FileSettingsStore(file).load().accent)
    }

    // --- Notification flags (increment 6d.1) ---

    @Test
    fun `defaults match macOS notification defaults`() = runTest {
        val defaults = AppSettings()
        assertEquals(true, defaults.priceAlerts)
        assertEquals(true, defaults.orderFills)
        assertEquals(false, defaults.marketOpenClose)
        assertEquals(true, defaults.newsDigest)
        assertEquals(false, defaults.emailNotifications)
    }

    @Test
    fun `old accent-only file loads fine with the new notification defaults`() = runTest {
        // Back-compat pin: a settings.json written before increment 6d.1 has only the
        // "accent" key. Lenient decode must still succeed and fill in the new flags'
        // defaults rather than failing the whole-blob load.
        val file = tempFile()
        file.writeText("""{"accent":"Sapphire"}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Sapphire, loaded.accent)
        assertEquals(true, loaded.priceAlerts)
        assertEquals(true, loaded.orderFills)
        assertEquals(false, loaded.marketOpenClose)
        assertEquals(true, loaded.newsDigest)
        assertEquals(false, loaded.emailNotifications)
    }

    @Test
    fun `round-trips all notification flags set to non-default values`() = runTest {
        val file = tempFile()
        val store = FileSettingsStore(file)
        val settings = AppSettings(
            accent = AccentTheme.ChampagneGold,
            priceAlerts = false,
            orderFills = false,
            marketOpenClose = true,
            newsDigest = false,
            emailNotifications = true,
        )
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun `missing notification keys in an otherwise valid file fall back to defaults`() = runTest {
        val file = tempFile()
        file.writeText("""{"accent":"Platinum","priceAlerts":false}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Platinum, loaded.accent)
        assertEquals(false, loaded.priceAlerts)
        // Everything else absent from the file: defaults.
        assertEquals(true, loaded.orderFills)
        assertEquals(false, loaded.marketOpenClose)
        assertEquals(true, loaded.newsDigest)
        assertEquals(false, loaded.emailNotifications)
    }

    // --- Theme (increment 6d.2) ---

    @Test
    fun `isDarkMode defaults to true`() = runTest {
        assertEquals(true, AppSettings().isDarkMode)
    }

    @Test
    fun `round-trips isDarkMode true`() = runTest {
        val file = tempFile()
        val store = FileSettingsStore(file)
        store.save(AppSettings(isDarkMode = true))
        assertEquals(true, store.load().isDarkMode)
    }

    @Test
    fun `round-trips isDarkMode false`() = runTest {
        val file = tempFile()
        val store = FileSettingsStore(file)
        store.save(AppSettings(isDarkMode = false))
        assertEquals(false, store.load().isDarkMode)
    }

    @Test
    fun `old file without isDarkMode loads fine with isDarkMode defaulting to true`() = runTest {
        // Back-compat pin (same family as 6d.1's accent-only test): a settings.json written
        // before increment 6d.2 has no "isDarkMode" key at all. Lenient decode must still
        // succeed and default the new flag to true (dark — the shipped identity) rather than
        // failing the whole-blob load.
        val file = tempFile()
        file.writeText("""{"accent":"Sapphire"}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Sapphire, loaded.accent)
        assertEquals(true, loaded.isDarkMode)
    }

    @Test
    fun `old notification-era file without isDarkMode still loads with isDarkMode defaulting to true`() = runTest {
        // A 6d.1 file has accent + all five notification flags but predates isDarkMode.
        val file = tempFile()
        file.writeText(
            """{"accent":"Platinum","priceAlerts":false,"orderFills":true,""" +
                """"marketOpenClose":true,"newsDigest":false,"emailNotifications":true}""",
        )
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Platinum, loaded.accent)
        assertEquals(false, loaded.priceAlerts)
        assertEquals(true, loaded.isDarkMode)
    }

    // --- Security & privacy (increment 6d.2 Task 3) ---

    @Test
    fun `security and privacy defaults match macOS AppSettings`() = runTest {
        val defaults = AppSettings()
        assertEquals(true, defaults.biometricLogin)
        assertEquals(true, defaults.requireAuthOnLaunch)
        assertEquals(true, defaults.confirmTrades)
        assertEquals(false, defaults.analyticsSharing)
    }

    @Test
    fun `round-trips all four security and privacy fields set to non-default values`() = runTest {
        val file = tempFile()
        val store = FileSettingsStore(file)
        val settings = AppSettings(
            biometricLogin = false,
            requireAuthOnLaunch = false,
            confirmTrades = false,
            analyticsSharing = true,
        )
        store.save(settings)
        assertEquals(settings, store.load())
    }

    @Test
    fun `old file without the four security keys loads fine with them defaulting per macOS`() = runTest {
        // Back-compat pin (same family as the isDarkMode test above): a settings.json written
        // before increment 6d.2 Task 3 has no biometricLogin/requireAuthOnLaunch/confirmTrades/
        // analyticsSharing keys at all. Lenient decode must still succeed and fill in the new
        // fields at their macOS-matching defaults rather than failing the whole-blob load.
        val file = tempFile()
        file.writeText("""{"accent":"Sapphire"}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Sapphire, loaded.accent)
        assertEquals(true, loaded.biometricLogin)
        assertEquals(true, loaded.requireAuthOnLaunch)
        assertEquals(true, loaded.confirmTrades)
        assertEquals(false, loaded.analyticsSharing)
    }

    @Test
    fun `pre-6d2-2 file with isDarkMode but no security keys still loads with security defaults`() = runTest {
        // A file written between Task 2 and Task 3 has accent + isDarkMode but predates the
        // four security/privacy fields.
        val file = tempFile()
        file.writeText("""{"accent":"Platinum","isDarkMode":false}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Platinum, loaded.accent)
        assertEquals(false, loaded.isDarkMode)
        assertEquals(true, loaded.biometricLogin)
        assertEquals(true, loaded.requireAuthOnLaunch)
        assertEquals(true, loaded.confirmTrades)
        assertEquals(false, loaded.analyticsSharing)
    }

    @Test
    fun `missing security keys in an otherwise valid file fall back to defaults independently`() = runTest {
        val file = tempFile()
        file.writeText("""{"accent":"Platinum","confirmTrades":false}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Platinum, loaded.accent)
        assertEquals(false, loaded.confirmTrades)
        // Everything else absent from the file: defaults.
        assertEquals(true, loaded.biometricLogin)
        assertEquals(true, loaded.requireAuthOnLaunch)
        assertEquals(false, loaded.analyticsSharing)
    }

    // --- Language (increment 6e Task 5) ---

    @Test
    fun `language defaults to English`() = runTest {
        assertEquals(AppLanguage.English, AppSettings().language)
    }

    @Test
    fun `round-trips each language by its code`() = runTest {
        for (language in AppLanguage.entries) {
            val file = tempFile()
            val store = FileSettingsStore(file)
            store.save(AppSettings(language = language))
            assertEquals(language, store.load().language)
        }
    }

    @Test
    fun `persists the language as its code`() = runTest {
        val file = tempFile()
        FileSettingsStore(file).save(AppSettings(language = AppLanguage.German))
        assertTrue(file.readText().contains("\"de\""))
    }

    @Test
    fun `unknown language code loads defaults to English`() = runTest {
        val file = tempFile()
        file.writeText("""{"accent":"Platinum","language":"xx"}""")
        val loaded = FileSettingsStore(file).load()
        // A language code we can't map is lenient (independent field), unlike the whole-blob
        // accent fallback: the rest of the file is still trusted.
        assertEquals(AccentTheme.Platinum, loaded.accent)
        assertEquals(AppLanguage.English, loaded.language)
    }

    @Test
    fun `old file without language key loads fine with language defaulting to English`() = runTest {
        // Back-compat pin (same family as isDarkMode/security tests above): a settings.json
        // written before increment 6e Task 5 has no "language" key at all. Lenient decode must
        // still succeed and default the new field to English rather than failing the whole-blob
        // load — real keyless-JSON fixture, no "language" key present anywhere.
        val file = tempFile()
        file.writeText("""{"accent":"Sapphire","isDarkMode":false,"confirmTrades":false}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Sapphire, loaded.accent)
        assertEquals(false, loaded.isDarkMode)
        assertEquals(false, loaded.confirmTrades)
        assertEquals(AppLanguage.English, loaded.language)
    }

    @Test
    fun `missing language key in an otherwise valid file falls back to English independently`() = runTest {
        val file = tempFile()
        file.writeText("""{"accent":"Platinum","priceAlerts":false}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Platinum, loaded.accent)
        assertEquals(false, loaded.priceAlerts)
        assertEquals(AppLanguage.English, loaded.language)
    }

    // --- Earnings calendar (calendar increment) ---

    @Test
    fun `earningsReports defaults to true`() = runTest {
        assertEquals(true, AppSettings().earningsReports)
    }

    @Test
    fun `round-trips earningsReports false`() = runTest {
        val file = tempFile()
        val store = FileSettingsStore(file)
        store.save(AppSettings(earningsReports = false))
        assertEquals(false, store.load().earningsReports)
    }

    @Test
    fun `old file without earningsReports key loads fine with it defaulting to true`() = runTest {
        // Back-compat pin (same family as isDarkMode/security/language tests above): a
        // settings.json written before the earnings-check field existed has no
        // "earningsReports" key at all. Lenient decode must still succeed and default the
        // new flag to true rather than failing the whole-blob load.
        val file = tempFile()
        file.writeText("""{"accent":"Sapphire","newsDigest":false}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Sapphire, loaded.accent)
        assertEquals(false, loaded.newsDigest)
        assertEquals(true, loaded.earningsReports)
    }

    // --- Plan contributions (M7.2 Task 9) ---

    @Test
    fun `pieContributions defaults to true`() = runTest {
        assertEquals(true, AppSettings().pieContributions)
    }

    @Test
    fun `round-trips pieContributions false`() = runTest {
        val file = tempFile()
        val store = FileSettingsStore(file)
        store.save(AppSettings(pieContributions = false))
        assertEquals(false, store.load().pieContributions)
    }

    @Test
    fun `old file without pieContributions key loads fine with it defaulting to true`() = runTest {
        // Back-compat pin (same family as earningsReports/isDarkMode/security/language tests
        // above): a settings.json written before the pie-contributions field existed has no
        // "pieContributions" key at all. Lenient decode must still succeed and default the new
        // flag to true rather than failing the whole-blob load.
        val file = tempFile()
        file.writeText("""{"accent":"Sapphire","newsDigest":false,"earningsReports":false}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(AccentTheme.Sapphire, loaded.accent)
        assertEquals(false, loaded.newsDigest)
        assertEquals(false, loaded.earningsReports)
        assertEquals(true, loaded.pieContributions)
    }
}
