package com.aptrade.desktop.designkit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Timeframe

fun timeframeLabel(tf: Timeframe): String = when (tf) {
    Timeframe.OneDay -> "1D"; Timeframe.OneWeek -> "1W"
    Timeframe.OneMonth -> "1M"; Timeframe.OneYear -> "1Y"
}

fun kindLabel(kind: AssetKind): String = when (kind) {
    AssetKind.Stock -> "Stock"; AssetKind.Etf -> "ETF"; AssetKind.Crypto -> "Crypto"
}

private fun numericStyle(size: TextUnit, weight: FontWeight, color: Color) = TextStyle(
    fontFamily = InterFamily, fontSize = size, fontWeight = weight, color = color,
    fontFeatureSettings = "tnum",
)

/** A hand-drawn magnifier glyph (circle + handle) — SF Symbol "magnifyingglass"
 *  stand-in, so the app needs no material-icons dependency. */
@Composable
fun MagnifierIcon(tint: Color = DK.textSecondary, modifier: Modifier = Modifier.size(16.dp)) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        val r = size.minDimension * 0.32f
        val cx = size.width * 0.42f
        val cy = size.height * 0.42f
        drawCircle(
            color = tint,
            radius = r,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        val diag = r * 0.72f
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(cx + diag, cy + diag),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.9f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/**
 * The full "AP Trade" lockup — champagne original for the default accent in dark mode, otherwise
 * recolored per accent and mode (macOS `recoloredBrandImage` port): gold pixels onto the active
 * accent's ramp, neutral silver pixels darkened to charcoal in light mode.
 *
 * Only champagneGold + dark takes the zero-cost `painterResource` passthrough (pixel-identical
 * shipped artwork). Every other (accent, mode) combination decodes + remaps off the UI thread
 * ([tintedWordmark]); until that async tint lands the champagne original shows as the placeholder
 * — a brief beat of default, never a blank gap. Accent and mode are read from snapshot state
 * inside composition and key the producer, so switching either retints live.
 */
@Composable
fun BrandWordmark(height: Dp) {
    val accent = DK.accent.value
    val isDark = DK.isDark.value
    val tinted: ImageBitmap? by produceState<ImageBitmap?>(
        initialValue = BrandTintCache.get(accent, isDark),
        key1 = accent,
        key2 = isDark,
    ) {
        value = tintedWordmark(accent, isDark)
    }

    // tintedWordmark is null exactly when the shipped artwork applies (champagne + dark) or a
    // decode failed — both fall back to the untinted painterResource.
    val bitmap = tinted
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "APTrade",
            modifier = Modifier.height(height),
        )
    } else {
        Image(
            painter = painterResource("brand/AppWordmark.png"),
            contentDescription = "APTrade",
            modifier = Modifier.height(height),
        )
    }
}

/** Pulsing gold "LIVE" capsule — DesignKit.swift LiveBadge (1.1s ease pulse). */
@Composable
fun LiveBadge() {
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.gold.copy(alpha = 0.10f))
            .border(1.dp, DK.gold.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(6.dp).alpha(pulse).background(DK.gold, CircleShape))
        Text("LIVE", style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, color = DK.gold, letterSpacing = 1.6.sp))
    }
}

/** "$308⁶³" — symbol and cents at half size, raised to the top. */
@Composable
fun SuperscriptPrice(amountText: String, size: TextUnit = 34.sp, color: Color = DK.textPrimary) {
    val parts = splitPrice(amountText)
    Row(verticalAlignment = Alignment.Top) {
        Text(parts.symbol, style = numericStyle(size * 0.5f, FontWeight.SemiBold, DK.textSecondary),
            modifier = Modifier.padding(end = 1.dp))
        Text(parts.whole, style = numericStyle(size, FontWeight.SemiBold, color))
        Text(parts.fraction, style = numericStyle(size * 0.5f, FontWeight.SemiBold, color.copy(alpha = 0.85f)),
            modifier = Modifier.padding(start = 1.dp))
    }
}

/** Bordered, faintly tinted percent chip in its own direction color. */
@Composable
fun ChangePill(changePercent: Double?) {
    val color = DK.changeColor(changePercent)
    Text(
        formatPercent(changePercent),
        style = numericStyle(12.sp, FontWeight.SemiBold, color),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.24f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Stocks / ETFs / Crypto segmented capsule with per-kind counts. */
@Composable
fun KindToggle(selection: AssetKind, counts: Map<AssetKind, Int>, onSelect: (AssetKind) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        for (kind in listOf(AssetKind.Stock, AssetKind.Etf, AssetKind.Crypto)) {
            val selected = kind == selection
            val count = counts[kind] ?: 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) DK.surfaceHi else Color.Transparent)
                    .then(if (selected) Modifier.border(1.dp, DK.gold.copy(alpha = 0.40f), RoundedCornerShape(50)) else Modifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(kind) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    when (kind) { AssetKind.Stock -> "Stocks"; AssetKind.Etf -> "ETFs"; AssetKind.Crypto -> "Crypto" },
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (selected) DK.textPrimary else DK.textSecondary),
                )
                if (count > 0) Text("$count", style = numericStyle(11.sp, FontWeight.SemiBold,
                    if (selected) DK.gold else DK.textTertiary))
            }
        }
    }
}

/** Underline-selected 1D / 1W / 1M / 1Y row. */
@Composable
fun TimeframeBar(selection: Timeframe, onSelect: (Timeframe) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        for (tf in Timeframe.entries) {
            val selected = tf == selection
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tf) },
            ) {
                Text(timeframeLabel(tf), style = numericStyle(13.sp, FontWeight.SemiBold,
                    if (selected) DK.gold else DK.textSecondary))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.height(2.dp).fillMaxWidth(0.6f).clip(RoundedCornerShape(1.dp))
                    .background(if (selected) DK.gold else Color.Transparent))
            }
        }
    }
}

/** One labeled figure in the key-stats grid. */
@Composable
fun StatTile(label: String, value: String, valueColor: Color = DK.textPrimary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, color = DK.textTertiary, letterSpacing = 1.sp))
        Text(value, style = numericStyle(16.sp, FontWeight.SemiBold, valueColor))
    }
}

/** Thin advancers/decliners split capsule. */
@Composable
fun PulseBar(advancers: Int, decliners: Int, modifier: Modifier = Modifier) {
    val total = (advancers + decliners).coerceAtLeast(1)
    Row(modifier.height(4.dp).clip(RoundedCornerShape(50))) {
        Box(Modifier.weight(advancers.toFloat().coerceAtLeast(0.0001f)).fillMaxHeight().background(DK.up))
        Box(Modifier.weight((total - advancers).toFloat().coerceAtLeast(0.0001f)).fillMaxHeight().background(DK.down))
    }
}

/** Small pill-shaped on/off switch matching the gold-on-black theme — the Compose desktop
 *  equivalent of macOS's `Toggle(...).tint(Theme.gold)` (RootView's notifications rows).
 *  No `material3.Switch` is used here: that component pulls Material's own motion/shape
 *  defaults that don't match DK's flat, hairline-bordered idiom, and every other control in
 *  this design system (KindToggle, side toggles) is a hand-rolled capsule already — this
 *  follows that same precedent rather than fighting Material's theming for one control. */
@Composable
fun DKSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val trackColor = if (checked) DK.gold else DK.surfaceHi
    val thumbAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor.copy(alpha = if (checked) 0.9f else 1f))
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = thumbAlignment,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (checked) DK.bgBottom else DK.textSecondary),
        )
    }
}
