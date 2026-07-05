package com.aptrade.desktop.designkit

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage

/**
 * Accent- and mode-tinted brand wordmark — the desktop port of macOS
 * `DesignKit.recoloredBrandImage` (Sources/APTradeApp/DesignKit.swift:95-151).
 *
 * The shipped `brand/AppWordmark.png` is champagne gold + silver on transparent, drawn for dark
 * backgrounds. Each *gold* pixel is remapped onto the active accent's three-stop ramp by
 * luminance, preserving the original gradient's light-to-dark shape in the new hue — identically
 * in both modes. *Neutral* (silver/white) pixels are mode-dependent: kept in dark mode, darkened
 * to charcoal ([BRAND_CHARCOAL], the light textPrimary) in light mode so they read on the ivory
 * ground. Only the champagne + dark combination passes through whole-image with no decode at all
 * (pixel-identical to the shipped artwork, zero cost).
 *
 * The pure classification/ramp/policy helpers ([isNeutralPixel], [tintPixel], …) are `internal`
 * and unit-tested without images; the decode-and-remap path is verified empirically against the
 * real resource so the Skia channel order (see [tintedWordmark]) is proven, not assumed.
 */

/** Champagne source-gold luminance span (deep #A9772A … light #F2DDA0). Gold pixels are placed
 *  on the target ramp by mapping their luminance from [LO_LUM, HI_LUM] → [0, 1]. */
private const val LO_LUM = 0.49
private const val HI_LUM = 0.86

/** Channel spread below which a pixel reads as neutral silver/white rather than gold: r − b < 40/255. */
private const val NEUTRAL_THRESHOLD = 40.0 / 255.0

/** Light-mode target for neutral pixels: charcoal RGB(30, 28, 24)/255 = #1E1C18 — the light-mode
 *  textPrimary, matching macOS `recoloredBrandImage`'s `charcoal` (DesignKit.swift:118). */
internal val BRAND_CHARCOAL = Triple(30.0 / 255.0, 28.0 / 255.0, 24.0 / 255.0)

/**
 * A pixel (straight-alpha, channels in 0..1) is *neutral* — silver/white, not brand gold — when its
 * red/blue spread is below [NEUTRAL_THRESHOLD]. Gold has a strong red-over-blue lean; silver does not.
 */
internal fun isNeutralPixel(r: Double, g: Double, b: Double): Boolean = r - b < NEUTRAL_THRESHOLD

/**
 * Maps a gold pixel's luminance to its position `t` in 0..1 along the target ramp, clamped to the
 * champagne source-gold luminance span.
 */
internal fun goldT(lum: Double): Double =
    ((lum - LO_LUM) / (HI_LUM - LO_LUM)).coerceIn(0.0, 1.0)

/** Rec. 601 luminance weights, matching the macOS port. */
internal fun luminance(r: Double, g: Double, b: Double): Double =
    0.299 * r + 0.587 * g + 0.114 * b

/**
 * Samples [accent]'s ramp at `t` in 0..1: deep at 0, mid at 0.5, light at 1, piecewise-linear
 * through the mid stop. Returns straight RGB in 0..1.
 */
internal fun sampleRamp(accent: AccentTheme, t: Double): Triple<Double, Double, Double> {
    val deep = accent.deep.toRgb()
    val mid = accent.mid.toRgb()
    val light = accent.light.toRgb()
    return if (t <= 0.5) lerp(deep, mid, t * 2.0) else lerp(mid, light, (t - 0.5) * 2.0)
}

private fun lerp(
    a: Triple<Double, Double, Double>,
    b: Triple<Double, Double, Double>,
    t: Double,
): Triple<Double, Double, Double> = Triple(
    a.first + (b.first - a.first) * t,
    a.second + (b.second - a.second) * t,
    a.third + (b.third - a.third) * t,
)

/** The straight sRGB components of a Compose [androidx.compose.ui.graphics.Color] in 0..1. */
private fun androidx.compose.ui.graphics.Color.toRgb(): Triple<Double, Double, Double> =
    Triple(red.toDouble(), green.toDouble(), blue.toDouble())

/**
 * The per-pixel brand policy — the pure core of macOS `recoloredBrandImage`'s pixel loop
 * (DesignKit.swift:129-139). Takes a straight-alpha pixel in 0..1 and returns its replacement
 * RGB, or `null` when the pixel is left byte-identical:
 *  - neutral + dark → `null` (the shipped silver is drawn for dark grounds);
 *  - neutral + light → [BRAND_CHARCOAL] (silver would vanish on ivory);
 *  - gold + champagne → `null` in both modes (the default brand artwork stays untouched);
 *  - gold + other accent → sampled from the accent's ramp by luminance, mode-independent.
 */
internal fun tintPixel(
    r: Double,
    g: Double,
    b: Double,
    accent: AccentTheme,
    isDark: Boolean,
): Triple<Double, Double, Double>? = when {
    isNeutralPixel(r, g, b) -> if (isDark) null else BRAND_CHARCOAL
    accent == AccentTheme.ChampagneGold -> null
    else -> sampleRamp(accent, goldT(luminance(r, g, b)))
}

/**
 * Process-lifetime cache of decoded, tinted wordmarks, keyed by (accent, isDark) — the same
 * "accent-isDark" key macOS `BrandImage` uses (DesignKit.swift:81); ≤10 entries.
 * `synchronized` because the decode runs on a background dispatcher while composition reads.
 * champagneGold + dark is never stored here — it takes the no-decode passthrough path in
 * `BrandWordmark`; champagne + light IS cached (its neutrals are remapped to charcoal).
 */
internal object BrandTintCache {
    private val entries = HashMap<Pair<AccentTheme, Boolean>, ImageBitmap>()

    @Synchronized
    fun get(accent: AccentTheme, isDark: Boolean): ImageBitmap? = entries[accent to isDark]

    @Synchronized
    fun put(accent: AccentTheme, isDark: Boolean, bitmap: ImageBitmap) {
        entries[accent to isDark] = bitmap
    }

    @Synchronized
    fun clear() = entries.clear()
}

/** Classloader path of the wordmark resource — the same root `painterResource("brand/AppWordmark.png")` uses. */
internal const val WORDMARK_RESOURCE = "brand/AppWordmark.png"

/**
 * Decodes [WORDMARK_RESOURCE] and remaps its pixels per [tintPixel] for the given accent and
 * mode, returning a tinted [ImageBitmap]; `null` on any decode failure. Cached per
 * (accent, isDark) for the process lifetime.
 *
 * The Skia N32 buffer is **premultiplied** and its channel order is platform-dependent
 * ([ColorType.RGBA_8888] vs [ColorType.BGRA_8888]); this reads `bitmap.imageInfo.colorInfo.colorType`
 * and picks the red/blue byte offsets accordingly, so the `r − b` gold classification is never
 * inverted by a channel swap. Runs on [Dispatchers.IO] — decoding + a full pixel pass is off-UI work.
 */
internal suspend fun tintedWordmark(accent: AccentTheme, isDark: Boolean): ImageBitmap? {
    // Champagne + dark is the shipped artwork exactly — passthrough, caller uses painterResource.
    // (In light mode even champagne must decode: its silver neutrals darken to charcoal.)
    if (accent == AccentTheme.ChampagneGold && isDark) return null
    BrandTintCache.get(accent, isDark)?.let { return it }

    return try {
        withContext(Dispatchers.IO) {
            val bytes = loadWordmarkBytes() ?: return@withContext null
            val skiaImage = SkiaImage.makeFromEncoded(bytes)
            val skiaBitmap = SkiaBitmap().apply {
                allocN32Pixels(skiaImage.width, skiaImage.height)
                skiaImage.readPixels(this)
            }
            remapBrandPixels(skiaBitmap, accent, isDark)
            val bitmap = skiaBitmap.asComposeImageBitmap()
            BrandTintCache.put(accent, isDark, bitmap)
            bitmap
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private fun loadWordmarkBytes(): ByteArray? =
    BrandTintCache::class.java.classLoader
        ?.getResourceAsStream(WORDMARK_RESOURCE)
        ?.use { it.readBytes() }

/**
 * In-place remap of a decoded N32 [bitmap] per [tintPixel]: gold → [accent]'s ramp by luminance
 * (champagne gold untouched), neutrals → charcoal in light mode / untouched in dark mode;
 * fully-transparent pixels are always left byte-identical. Reads/writes the raw premultiplied
 * buffer, un-premultiplying before classification and re-premultiplying on write, respecting
 * the platform channel order.
 */
private fun remapBrandPixels(bitmap: SkiaBitmap, accent: AccentTheme, isDark: Boolean) {
    val info = bitmap.imageInfo
    val pixels = bitmap.readPixels() ?: return

    // Channel byte offsets within each 4-byte pixel. N32 is either RGBA or BGRA; green and alpha
    // are always at offsets 1 and 3, only red/blue swap.
    val rgba = info.colorInfo.colorType == ColorType.RGBA_8888
    val rOff = if (rgba) 0 else 2
    val gOff = 1
    val bOff = if (rgba) 2 else 0
    val aOff = 3

    var i = 0
    while (i < pixels.size) {
        val a = pixels[i + aOff].toInt() and 0xFF
        if (a == 0) { i += 4; continue } // fully transparent — untouched
        val af = a / 255.0

        // Un-premultiply to straight alpha so classification/luminance ignore opacity.
        val r = (pixels[i + rOff].toInt() and 0xFF) / 255.0 / af
        val g = (pixels[i + gOff].toInt() and 0xFF) / 255.0 / af
        val b = (pixels[i + bOff].toInt() and 0xFF) / 255.0 / af

        val out = tintPixel(r, g, b, accent, isDark)
        if (out == null) { i += 4; continue } // untouched: neutral-in-dark or champagne gold

        val (or, og, ob) = out
        pixels[i + rOff] = ((or.coerceIn(0.0, 1.0) * af * 255.0)).toInt().toByte()
        pixels[i + gOff] = ((og.coerceIn(0.0, 1.0) * af * 255.0)).toInt().toByte()
        pixels[i + bOff] = ((ob.coerceIn(0.0, 1.0) * af * 255.0)).toInt().toByte()
        i += 4
    }

    bitmap.installPixels(pixels)
}
