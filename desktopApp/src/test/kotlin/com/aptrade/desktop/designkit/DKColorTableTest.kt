package com.aptrade.desktop.designkit

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the DK palette byte-for-byte in both modes against Sources/APTradeApp/Theme.swift
 * (the macOS source of truth — hexes computed from its `Color(red:green:blue:)` components).
 *
 * The dark table guards the "dark values stay byte-identical" requirement of the light-theme
 * work: if a dark literal ever drifts while adding a light branch, this fails.
 */
class DKColorTableTest {

    /** Runs [block] with `DK.isDark` forced to [dark], restoring the prior mode afterwards
     *  so the singleton's state never leaks into other tests. */
    private fun inMode(dark: Boolean, block: () -> Unit) {
        val previous = DK.isDark.value
        DK.isDark.value = dark
        try {
            block()
        } finally {
            DK.isDark.value = previous
        }
    }

    @Test
    fun darkPaletteIsByteIdenticalToShippedValues() = inMode(dark = true) {
        assertEquals(Color(0xFF0C0B09), DK.bgTop)
        assertEquals(Color(0xFF050504), DK.bgBottom)
        assertEquals(Color(0xFF16140F), DK.surface)
        assertEquals(Color(0xFF211D15), DK.surfaceHi)
        assertEquals(Color.White.copy(alpha = 0.07f), DK.hairline)
        assertEquals(Color(0xFFF4F1EA), DK.textPrimary)
        assertEquals(Color(0xFF9C968A), DK.textSecondary)
        assertEquals(Color(0xFF615C51), DK.textTertiary)
        assertEquals(Color(0xFFD8D5CE), DK.silver)
    }

    @Test
    fun lightPaletteMatchesThemeSwiftComponents() = inMode(dark = false) {
        assertEquals(Color(0xFFF8F6F2), DK.bgTop)
        assertEquals(Color(0xFFF1EEE7), DK.bgBottom)
        assertEquals(Color(0xFFEAE6DE), DK.surface)
        assertEquals(Color(0xFFDFD9CD), DK.surfaceHi)
        assertEquals(Color.Black.copy(alpha = 0.09f), DK.hairline)
        assertEquals(Color(0xFF1E1C18), DK.textPrimary)
        assertEquals(Color(0xFF605A4F), DK.textSecondary)
        assertEquals(Color(0xFF8E8779), DK.textTertiary)
        // #565148 — Theme.swift:110's components 0.337/0.318/0.282 × 255 = 86/81/72.
        // (An earlier plan draft said #564F47; that was a mis-conversion — components govern.)
        assertEquals(Color(0xFF565148), DK.silver)
    }

    @Test
    fun priceDirectionAndAccentRampAreModeIndependent() {
        // Recorded scope decision: up/down are data semantics and the accent ramp is brand,
        // neither is a mode signal — identical across light/dark.
        lateinit var darkValues: List<Color>
        lateinit var lightValues: List<Color>
        inMode(dark = true) {
            darkValues = listOf(DK.up, DK.down, DK.goldDeep, DK.gold, DK.goldLight)
        }
        inMode(dark = false) {
            lightValues = listOf(DK.up, DK.down, DK.goldDeep, DK.gold, DK.goldLight)
        }
        assertEquals(darkValues, lightValues)
        assertEquals(Color(0xFF46C98A), DK.up)
        assertEquals(Color(0xFFE06A5E), DK.down)
    }

    @Test
    fun isDarkDefaultsToDark() {
        // Persistence wiring is Task 2; until then the app must come up dark, exactly as shipped.
        assertTrue(DK.isDark.value, "DK.isDark must default to true (dark) until settings wiring lands")
    }
}
