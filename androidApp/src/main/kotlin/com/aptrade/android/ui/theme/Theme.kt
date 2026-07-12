package com.aptrade.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aptrade.shared.settings.AccentTheme
import com.aptrade.shared.settings.AppSettings

// "Gold on black" (or champagne-on-ivory in light mode): the APTrade identity. Both mode
// palettes are transcribed VERBATIM from desktopApp's DK.kt (the 6d.2 light-theme color
// tables, themselves computed from Sources/APTradeApp/Theme.swift's Color components and
// pinned byte-for-byte by desktop's DKColorTableTest). This replaces the earlier Android
// approximations (#D9B45B gold / #0B0B0E near-black) with the exact brand hexes.

// Dark mode (DK.kt isDark = true branch).
private val DarkBg = Color(0xFF0C0B09)          // DK.bgTop
private val DarkSurface = Color(0xFF16140F)     // DK.surface
private val DarkSurfaceHi = Color(0xFF211D15)   // DK.surfaceHi
private val DarkTextPrimary = Color(0xFFF4F1EA) // DK.textPrimary
private val DarkTextSecondary = Color(0xFF9C968A) // DK.textSecondary
private val DarkHairline = Color.White.copy(alpha = 0.07f) // DK.hairline

// Light mode (DK.kt isDark = false branch — the 6d.2 light table).
private val LightBg = Color(0xFFF8F6F2)          // DK.bgTop
private val LightSurface = Color(0xFFEAE6DE)     // DK.surface
private val LightSurfaceHi = Color(0xFFDFD9CD)   // DK.surfaceHi
private val LightTextPrimary = Color(0xFF1E1C18) // DK.textPrimary
private val LightTextSecondary = Color(0xFF605A4F) // DK.textSecondary
private val LightHairline = Color.Black.copy(alpha = 0.09f) // DK.hairline

// Price direction — semantic, deliberately outside the brand palette, identical in both
// modes (data, never branding). Pre-existing Android values, unchanged.
val GainGreen = Color(0xFF34C759)
val LossRed = Color(0xFFFF453A)

/** The accent color ramps — Android keeps its own copy of the desktop designkit extension
 *  values (desktopApp/.../designkit/AccentTheme.kt), same hexes verbatim, because the shared
 *  [AccentTheme] carries identity only (commonMain must stay Compose-free). [deep] is the
 *  shadow stop, [mid] the primary identity color, [light] the highlight stop. */
internal val AccentTheme.deep: Color get() = when (this) {
    AccentTheme.ChampagneGold -> Color(0xFFA9772A)
    AccentTheme.RoseGold -> Color(0xFF8E4A3C)
    AccentTheme.Sapphire -> Color(0xFF1C3F73)
    AccentTheme.Amethyst -> Color(0xFF512D78)
    AccentTheme.Platinum -> Color(0xFF646B78)
}

internal val AccentTheme.mid: Color get() = when (this) {
    AccentTheme.ChampagneGold -> Color(0xFFD4A94E)
    AccentTheme.RoseGold -> Color(0xFFCD846F)
    AccentTheme.Sapphire -> Color(0xFF417FD4)
    AccentTheme.Amethyst -> Color(0xFF8A5BC9)
    AccentTheme.Platinum -> Color(0xFFA3AAB6)
}

internal val AccentTheme.light: Color get() = when (this) {
    AccentTheme.ChampagneGold -> Color(0xFFF2DDA0)
    AccentTheme.RoseGold -> Color(0xFFEDC4B4)
    AccentTheme.Sapphire -> Color(0xFF9CC2F1)
    AccentTheme.Amethyst -> Color(0xFFC6A8ED)
    AccentTheme.Platinum -> Color(0xFFDADFE7)
}

// Slot mapping mirrors desktop DK.kt's dkColorScheme() field-for-field. `onPrimary` stays
// black in both modes: the accent ramp is mode-independent, and every mid stop is a
// metallic that reads best under dark text (DK.kt's recorded rationale).
private fun darkScheme(accent: AccentTheme) = darkColorScheme(
    primary = accent.mid,
    onPrimary = Color.Black,
    secondary = accent.mid,
    onSecondary = Color.Black,
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceHi,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkHairline,
    error = LossRed,
)

private fun lightScheme(accent: AccentTheme) = lightColorScheme(
    primary = accent.mid,
    onPrimary = Color.Black,
    secondary = accent.mid,
    onSecondary = Color.Black,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceHi,
    onSurfaceVariant = LightTextSecondary,
    outline = LightHairline,
    error = LossRed,
)

/**
 * Selects the color scheme from the live [settings]: dark/light table by
 * [AppSettings.isDarkMode], `primary`/`secondary` from [AppSettings.accent]'s ramp mid stop.
 * MainActivity feeds this from `SettingsViewModel.settings` state, so an appearance change
 * re-themes the whole tree instantly (a plain colorScheme swap — no animation, matching the
 * desktop's recorded decision). Defaults to `AppSettings()` (dark, champagne gold) so the
 * first frame before the persisted settings load resolves is pixel-identical to the shipped
 * identity.
 */
@Composable
fun APTradeTheme(settings: AppSettings = AppSettings(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (settings.isDarkMode) darkScheme(settings.accent) else lightScheme(settings.accent),
        content = content,
    )
}
