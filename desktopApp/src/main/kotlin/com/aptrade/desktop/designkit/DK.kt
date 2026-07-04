package com.aptrade.desktop.designkit

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font   // the desktop overload that takes `resource`
import androidx.compose.ui.text.TextStyle

/** "Gold on black": the APTrade identity, transcribed from Sources/APTradeApp/Theme.swift
 *  (dark-mode values; light theme is increment 6d). Gains stay green, losses stay red —
 *  price-direction color is data, never branding. */
object DK {
    val bgTop = Color(0xFF0C0B09)
    val bgBottom = Color(0xFF050504)
    val surface = Color(0xFF16140F)
    val surfaceHi = Color(0xFF211D15)
    val hairline = Color.White.copy(alpha = 0.07f)

    val textPrimary = Color(0xFFF4F1EA)
    val textSecondary = Color(0xFF9C968A)
    val textTertiary = Color(0xFF615C51)

    /** The active brand accent — Compose snapshot state so a change recomposes every
     *  reader of `gold`/`goldDeep`/`goldLight`/`goldGradient` with no call-site changes.
     *  Defaults to Champagne Gold, whose ramp is pixel-identical to the former constants. */
    val accent = mutableStateOf(AccentTheme.ChampagneGold)

    // The gold ramp derives from the active accent. `gold` is the mid stop (the primary
    // identity color), `goldDeep`/`goldLight` the shadow/highlight stops.
    val goldDeep: Color get() = accent.value.deep
    val gold: Color get() = accent.value.mid
    val goldLight: Color get() = accent.value.light
    val silver = Color(0xFFD8D5CE)

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

    val backgroundGradient = Brush.verticalGradient(listOf(bgTop, bgBottom))
}

val InterFamily = FontFamily(
    Font(resource = "fonts/Inter-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/Inter-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/Inter-SemiBold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/Inter-Bold.ttf", weight = FontWeight.Bold),
)

/** Constructs the Material color scheme per composition so `primary` tracks the active
 *  accent: reading `DK.accent.value` here makes an accent switch recompose the theme (a
 *  top-level `val` would have captured the gold stop once at class-load). */
@Composable
private fun dkColorScheme() = darkColorScheme(
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

@Composable
fun APTradeDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = dkColorScheme(),
        typography = Typography(bodyLarge = TextStyle(fontFamily = InterFamily)),
        content = content,
    )
}
