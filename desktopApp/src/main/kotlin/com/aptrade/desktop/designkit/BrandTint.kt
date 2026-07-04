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
 * Accent-tinted brand wordmark — the desktop port of macOS `DesignKit.recoloredBrandImage`
 * (Sources/APTradeApp/DesignKit.swift:95-162).
 *
 * The shipped `brand/AppWordmark.png` is champagne gold on transparent. Each *gold* pixel is
 * remapped onto the active accent's three-stop ramp by luminance, preserving the original
 * gradient's light-to-dark shape in the new hue; *neutral* (silver/white) pixels are left
 * untouched (the desktop is dark-only, so the macOS `isDark == true` branch applies); the
 * champagne default passes through whole-image with no decode at all (pixel-identical, zero cost).
 *
 * The pure classification/ramp helpers are `internal` and unit-tested without images; the
 * decode-and-remap path is verified empirically against the real resource so the Skia channel
 * order (see [tintedWordmark]) is proven, not assumed.
 */

/** Champagne source-gold luminance span (deep #A9772A … light #F2DDA0). Gold pixels are placed
 *  on the target ramp by mapping their luminance from [LO_LUM, HI_LUM] → [0, 1]. */
private const val LO_LUM = 0.49
private const val HI_LUM = 0.86

/** Channel spread below which a pixel reads as neutral silver/white rather than gold: r − b < 40/255. */
private const val NEUTRAL_THRESHOLD = 40.0 / 255.0

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
 * Process-lifetime cache of decoded, accent-tinted wordmarks, keyed by [AccentTheme] (≤5 entries).
 * `synchronized` because the decode runs on a background dispatcher while composition reads.
 * champagneGold is never stored here — it takes the no-decode passthrough path in `BrandWordmark`.
 */
internal object BrandTintCache {
    private val entries = HashMap<AccentTheme, ImageBitmap>()

    @Synchronized
    fun get(accent: AccentTheme): ImageBitmap? = entries[accent]

    @Synchronized
    fun put(accent: AccentTheme, bitmap: ImageBitmap) {
        entries[accent] = bitmap
    }

    @Synchronized
    fun clear() = entries.clear()
}

/** Classloader path of the wordmark resource — the same root `painterResource("brand/AppWordmark.png")` uses. */
internal const val WORDMARK_RESOURCE = "brand/AppWordmark.png"

/**
 * Decodes [WORDMARK_RESOURCE] and remaps its gold pixels onto [accent]'s ramp, returning a tinted
 * [ImageBitmap]; `null` on any decode failure. Cached per accent for the process lifetime.
 *
 * The Skia N32 buffer is **premultiplied** and its channel order is platform-dependent
 * ([ColorType.RGBA_8888] vs [ColorType.BGRA_8888]); this reads `bitmap.imageInfo.colorInfo.colorType`
 * and picks the red/blue byte offsets accordingly, so the `r − b` gold classification is never
 * inverted by a channel swap. Runs on [Dispatchers.IO] — decoding + a full pixel pass is off-UI work.
 */
internal suspend fun tintedWordmark(accent: AccentTheme): ImageBitmap? {
    if (accent == AccentTheme.ChampagneGold) return null // passthrough — caller uses painterResource
    BrandTintCache.get(accent)?.let { return it }

    return try {
        withContext(Dispatchers.IO) {
            val bytes = loadWordmarkBytes() ?: return@withContext null
            val skiaImage = SkiaImage.makeFromEncoded(bytes)
            val skiaBitmap = SkiaBitmap().apply {
                allocN32Pixels(skiaImage.width, skiaImage.height)
                skiaImage.readPixels(this)
            }
            remapGoldPixels(skiaBitmap, accent)
            val bitmap = skiaBitmap.asComposeImageBitmap()
            BrandTintCache.put(accent, bitmap)
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
 * In-place remap of a decoded N32 [bitmap]: each gold pixel → [accent]'s ramp by luminance; neutral
 * and fully-transparent pixels are left byte-identical. Reads/writes the raw premultiplied buffer,
 * un-premultiplying before classification and re-premultiplying on write, respecting the platform
 * channel order.
 */
private fun remapGoldPixels(bitmap: SkiaBitmap, accent: AccentTheme) {
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

        if (isNeutralPixel(r, g, b)) { i += 4; continue } // silver/white — kept (dark-only)

        val (or, og, ob) = sampleRamp(accent, goldT(luminance(r, g, b)))
        pixels[i + rOff] = ((or.coerceIn(0.0, 1.0) * af * 255.0)).toInt().toByte()
        pixels[i + gOff] = ((og.coerceIn(0.0, 1.0) * af * 255.0)).toInt().toByte()
        pixels[i + bOff] = ((ob.coerceIn(0.0, 1.0) * af * 255.0)).toInt().toByte()
        i += 4
    }

    bitmap.installPixels(pixels)
}
