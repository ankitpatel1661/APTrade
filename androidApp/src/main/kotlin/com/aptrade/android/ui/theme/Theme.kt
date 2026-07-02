package com.aptrade.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Champagne-gold-on-black, approximating the macOS DesignKit brand ramp
// (deep #A9772A … light #F2DDA0); the mid-tone is used as the accent.
private val ChampagneGold = Color(0xFFD9B45B)
private val NearBlack = Color(0xFF0B0B0E)
private val DarkSurface = Color(0xFF16161B)
private val OffWhite = Color(0xFFEDEDF0)

val GainGreen = Color(0xFF34C759)
val LossRed = Color(0xFFFF453A)

private val APTradeColors = darkColorScheme(
    primary = ChampagneGold,
    onPrimary = Color.Black,
    secondary = ChampagneGold,
    onSecondary = Color.Black,
    background = NearBlack,
    onBackground = OffWhite,
    surface = DarkSurface,
    onSurface = OffWhite,
    surfaceVariant = Color(0xFF1E1E24),
    onSurfaceVariant = Color(0xFFB9B9C0),
    error = LossRed,
)

@Composable
fun APTradeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = APTradeColors, content = content)
}
