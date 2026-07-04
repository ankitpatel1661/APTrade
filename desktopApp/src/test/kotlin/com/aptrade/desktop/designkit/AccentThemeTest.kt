package com.aptrade.desktop.designkit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals

/** Locks the accent ramps to the exact hexes transcribed from
 *  Sources/APTradeDomain/AccentTheme.swift. A silent transcription drift fails here. */
class AccentThemeTest {

    private fun hex(color: Color): String =
        "#%06X".format(color.toArgb() and 0xFFFFFF)

    @Test
    fun `champagne gold ramp matches the macOS source`() {
        assertEquals("#A9772A", hex(AccentTheme.ChampagneGold.deep))
        assertEquals("#D4A94E", hex(AccentTheme.ChampagneGold.mid))
        assertEquals("#F2DDA0", hex(AccentTheme.ChampagneGold.light))
    }

    @Test
    fun `rose gold ramp matches the macOS source`() {
        assertEquals("#8E4A3C", hex(AccentTheme.RoseGold.deep))
        assertEquals("#CD846F", hex(AccentTheme.RoseGold.mid))
        assertEquals("#EDC4B4", hex(AccentTheme.RoseGold.light))
    }

    @Test
    fun `sapphire ramp matches the macOS source`() {
        assertEquals("#1C3F73", hex(AccentTheme.Sapphire.deep))
        assertEquals("#417FD4", hex(AccentTheme.Sapphire.mid))
        assertEquals("#9CC2F1", hex(AccentTheme.Sapphire.light))
    }

    @Test
    fun `amethyst ramp matches the macOS source`() {
        assertEquals("#512D78", hex(AccentTheme.Amethyst.deep))
        assertEquals("#8A5BC9", hex(AccentTheme.Amethyst.mid))
        assertEquals("#C6A8ED", hex(AccentTheme.Amethyst.light))
    }

    @Test
    fun `platinum ramp matches the macOS source`() {
        assertEquals("#646B78", hex(AccentTheme.Platinum.deep))
        assertEquals("#A3AAB6", hex(AccentTheme.Platinum.mid))
        assertEquals("#DADFE7", hex(AccentTheme.Platinum.light))
    }

    @Test
    fun `default accent is champagne gold and equals the former DK gold constants`() {
        // The guard: DK's default gold ramp must stay pixel-identical to Champagne Gold.
        assertEquals(AccentTheme.ChampagneGold, DK.accent.value)
        assertEquals("#D4A94E", hex(AccentTheme.ChampagneGold.mid))
    }

    @Test
    fun `display names and taglines match the macOS source`() {
        assertEquals("Champagne Gold", AccentTheme.ChampagneGold.displayName)
        assertEquals("Default — gold on black", AccentTheme.ChampagneGold.tagline)
        assertEquals("Rose Gold", AccentTheme.RoseGold.displayName)
        assertEquals("Warm copper blush", AccentTheme.RoseGold.tagline)
        assertEquals("Sapphire", AccentTheme.Sapphire.displayName)
        assertEquals("Deep cobalt blue", AccentTheme.Sapphire.tagline)
        assertEquals("Amethyst", AccentTheme.Amethyst.displayName)
        assertEquals("Regal violet", AccentTheme.Amethyst.tagline)
        assertEquals("Platinum", AccentTheme.Platinum.displayName)
        assertEquals("Cool brushed silver", AccentTheme.Platinum.tagline)
    }
}
