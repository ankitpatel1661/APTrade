package com.aptrade.desktop.designkit

import androidx.compose.ui.graphics.Color

/** The selectable brand accent. Gold remains the default identity; the alternates are
 *  all metallic/jewel tones chosen to read as premium and to stay clear of the
 *  green/red used for price direction, which must never be spent on branding.
 *  Transcribed from Sources/APTradeDomain/AccentTheme.swift — the macOS app owns the
 *  canonical ramp; this is the desktop mirror (deep → mid → light hexes verbatim). */
enum class AccentTheme(
    val displayName: String,
    val tagline: String,
    /** The accent's three-stop ramp, deep → mid → light. `mid` is the primary gold,
     *  `deep` the shadow stop, `light` the highlight stop. */
    val deep: Color,
    val mid: Color,
    val light: Color,
) {
    ChampagneGold(
        displayName = "Champagne Gold",
        tagline = "Default — gold on black",
        deep = Color(0xFFA9772A),
        mid = Color(0xFFD4A94E),
        light = Color(0xFFF2DDA0),
    ),
    RoseGold(
        displayName = "Rose Gold",
        tagline = "Warm copper blush",
        deep = Color(0xFF8E4A3C),
        mid = Color(0xFFCD846F),
        light = Color(0xFFEDC4B4),
    ),
    Sapphire(
        displayName = "Sapphire",
        tagline = "Deep cobalt blue",
        deep = Color(0xFF1C3F73),
        mid = Color(0xFF417FD4),
        light = Color(0xFF9CC2F1),
    ),
    Amethyst(
        displayName = "Amethyst",
        tagline = "Regal violet",
        deep = Color(0xFF512D78),
        mid = Color(0xFF8A5BC9),
        light = Color(0xFFC6A8ED),
    ),
    Platinum(
        displayName = "Platinum",
        tagline = "Cool brushed silver",
        deep = Color(0xFF646B78),
        mid = Color(0xFFA3AAB6),
        light = Color(0xFFDADFE7),
    ),
}
