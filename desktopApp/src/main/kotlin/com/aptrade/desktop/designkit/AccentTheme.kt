package com.aptrade.desktop.designkit

import androidx.compose.ui.graphics.Color

/** Identity moved to shared (com.aptrade.shared.settings.AccentTheme); the Compose color
 *  ramp stays desktop-local as extension properties so commonMain stays Compose-free.
 *  Hex stops are verbatim from the pre-promotion enum (guarded by AccentThemeTest). */
typealias AccentTheme = com.aptrade.shared.settings.AccentTheme

val AccentTheme.deep: Color get() = when (this) {
    AccentTheme.ChampagneGold -> Color(0xFFA9772A)
    AccentTheme.RoseGold -> Color(0xFF8E4A3C)
    AccentTheme.Sapphire -> Color(0xFF1C3F73)
    AccentTheme.Amethyst -> Color(0xFF512D78)
    AccentTheme.Platinum -> Color(0xFF646B78)
}

val AccentTheme.mid: Color get() = when (this) {
    AccentTheme.ChampagneGold -> Color(0xFFD4A94E)
    AccentTheme.RoseGold -> Color(0xFFCD846F)
    AccentTheme.Sapphire -> Color(0xFF417FD4)
    AccentTheme.Amethyst -> Color(0xFF8A5BC9)
    AccentTheme.Platinum -> Color(0xFFA3AAB6)
}

val AccentTheme.light: Color get() = when (this) {
    AccentTheme.ChampagneGold -> Color(0xFFF2DDA0)
    AccentTheme.RoseGold -> Color(0xFFEDC4B4)
    AccentTheme.Sapphire -> Color(0xFF9CC2F1)
    AccentTheme.Amethyst -> Color(0xFFC6A8ED)
    AccentTheme.Platinum -> Color(0xFFDADFE7)
}
