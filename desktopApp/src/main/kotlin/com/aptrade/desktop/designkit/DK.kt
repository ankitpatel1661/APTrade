package com.aptrade.desktop.designkit

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font   // the desktop overload that takes `resource`
import androidx.compose.ui.text.TextStyle

/** "Gold on black" (or champagne-on-ivory in light mode): the APTrade identity, transcribed
 *  from Sources/APTradeApp/Theme.swift — both mode palettes, hexes computed from its
 *  `Color(red:green:blue:)` components. Gains stay green, losses stay red —
 *  price-direction color is data, never branding, and never a mode signal. */
object DK {
    /** Light/dark mode — Compose snapshot state (same pattern as [accent]): every color getter
     *  below reads it, so a flip recomposes each consumer with no call-site changes. Defaults
     *  to dark, the shipped identity; persistence wiring arrives with the Appearance page. */
    val isDark = mutableStateOf(true)

    val bgTop: Color get() = if (isDark.value) Color(0xFF0C0B09) else Color(0xFFF8F6F2)
    val bgBottom: Color get() = if (isDark.value) Color(0xFF050504) else Color(0xFFF1EEE7)
    val surface: Color get() = if (isDark.value) Color(0xFF16140F) else Color(0xFFEAE6DE)
    val surfaceHi: Color get() = if (isDark.value) Color(0xFF211D15) else Color(0xFFDFD9CD)
    val hairline: Color get() =
        if (isDark.value) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.09f)

    val textPrimary: Color get() = if (isDark.value) Color(0xFFF4F1EA) else Color(0xFF1E1C18)
    val textSecondary: Color get() = if (isDark.value) Color(0xFF9C968A) else Color(0xFF605A4F)
    val textTertiary: Color get() = if (isDark.value) Color(0xFF615C51) else Color(0xFF8E8779)

    /** The active brand accent — Compose snapshot state so a change recomposes every
     *  reader of `gold`/`goldDeep`/`goldLight`/`goldGradient` with no call-site changes.
     *  Defaults to Champagne Gold, whose ramp is pixel-identical to the former constants. */
    val accent = mutableStateOf(AccentTheme.ChampagneGold)

    // The gold ramp derives from the active accent. `gold` is the mid stop (the primary
    // identity color), `goldDeep`/`goldLight` the shadow/highlight stops. Identical across
    // light/dark — the accent is brand color, not a mode signal (Theme.swift ramp comment).
    val goldDeep: Color get() = accent.value.deep
    val gold: Color get() = accent.value.mid
    val goldLight: Color get() = accent.value.light
    // Light silver #565148 from Theme.swift:110's components 0.337/0.318/0.282 × 255 = 86/81/72.
    val silver: Color get() = if (isDark.value) Color(0xFFD8D5CE) else Color(0xFF565148)

    // Price direction — semantic, deliberately outside the brand palette, identical in both modes.
    val up = Color(0xFF46C98A)
    val down = Color(0xFFE06A5E)

    fun changeColor(changePercent: Double?): Color = when {
        changePercent == null -> textSecondary
        changePercent > 0 -> up
        changePercent < 0 -> down
        else -> textSecondary
    }

    /** The logo's diagonal gold gradient (deep → mid → light, bottom-left → top-right).
     *  A getter so it tracks the active accent alongside the individual gold stops. */
    val goldGradient: Brush get() = Brush.linearGradient(listOf(goldDeep, gold, goldLight))

    /** A getter (like [goldGradient]) so the gradient tracks the active mode — a plain `val`
     *  would capture the class-load palette once and never follow an `isDark` flip. */
    val backgroundGradient: Brush get() = Brush.verticalGradient(listOf(bgTop, bgBottom))
}

val InterFamily = FontFamily(
    Font(resource = "fonts/Inter-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/Inter-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/Inter-SemiBold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/Inter-Bold.ttf", weight = FontWeight.Bold),
)

/** Constructs the Material color scheme per composition so it tracks the active accent AND
 *  mode: reading `DK.isDark.value` / `DK.accent.value` here makes a theme or accent switch
 *  recompose the whole tree (a top-level `val` would have captured the palette once at
 *  class-load). The switch is an instant colorScheme swap — no animation (recorded decision).
 *  The light branch mirrors the dark mapping field-for-field; the DK getters resolve to the
 *  light palette there. `onPrimary` stays black in both modes: the accent ramp is
 *  mode-independent, and every mid stop is a metallic that reads best under dark text. */
@Composable
private fun dkColorScheme() = if (DK.isDark.value) {
    darkColorScheme(
        primary = DK.accent.value.mid,
        onPrimary = Color.Black,
        background = DK.bgTop,
        onBackground = DK.textPrimary,
        surface = DK.surface,
        onSurface = DK.textPrimary,
        surfaceVariant = DK.surfaceHi,
        onSurfaceVariant = DK.textSecondary,
        outline = DK.hairline,
        error = DK.down,
    )
} else {
    lightColorScheme(
        primary = DK.accent.value.mid,
        onPrimary = Color.Black,
        background = DK.bgTop,
        onBackground = DK.textPrimary,
        surface = DK.surface,
        onSurface = DK.textPrimary,
        surfaceVariant = DK.surfaceHi,
        onSurfaceVariant = DK.textSecondary,
        outline = DK.hairline,
        error = DK.down,
    )
}

@Composable
fun APTradeDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = dkColorScheme(),
        typography = Typography(bodyLarge = TextStyle(fontFamily = InterFamily)),
        content = content,
    )
}
