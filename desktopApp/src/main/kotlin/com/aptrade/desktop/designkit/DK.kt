package com.aptrade.desktop.designkit

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

    val goldDeep = Color(0xFFA9772A)
    val gold = Color(0xFFD4A94E)
    val goldLight = Color(0xFFF2DDA0)
    val silver = Color(0xFFD8D5CE)

    val up = Color(0xFF46C98A)
    val down = Color(0xFFE06A5E)

    fun changeColor(changePercent: Double?): Color = when {
        changePercent == null -> textSecondary
        changePercent > 0 -> up
        changePercent < 0 -> down
        else -> textSecondary
    }

    /** The logo's diagonal gold gradient (deep → mid → light, bottom-left → top-right). */
    val goldGradient = Brush.linearGradient(listOf(goldDeep, gold, goldLight))

    val backgroundGradient = Brush.verticalGradient(listOf(bgTop, bgBottom))
}

val InterFamily = FontFamily(
    Font(resource = "fonts/Inter-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/Inter-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/Inter-SemiBold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/Inter-Bold.ttf", weight = FontWeight.Bold),
)

private val DKColorScheme = darkColorScheme(
    primary = DK.gold,
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
        colorScheme = DKColorScheme,
        typography = Typography(bodyLarge = TextStyle(fontFamily = InterFamily)),
        content = content,
    )
}
