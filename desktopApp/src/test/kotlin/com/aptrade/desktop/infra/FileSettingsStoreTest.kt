package com.aptrade.desktop.infra

import com.aptrade.desktop.designkit.AccentTheme
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
}
