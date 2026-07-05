package com.aptrade.desktop.designkit

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Image as SkiaImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrandTintTest {
    private val eps = 1e-9

    private fun assertRgb(
        expected: Triple<Double, Double, Double>,
        actual: Triple<Double, Double, Double>,
    ) {
        assertEquals(expected.first, actual.first, eps)
        assertEquals(expected.second, actual.second, eps)
        assertEquals(expected.third, actual.third, eps)
    }

    // --- isNeutralPixel: threshold 40/255 ≈ 0.156862745 on both sides ---

    @Test fun neutralBelowThresholdIsNeutral() {
        // pure silver r == b → spread 0 < threshold
        assertTrue(isNeutralPixel(0.8, 0.8, 0.8))
        // r-b just under 40/255
        assertTrue(isNeutralPixel(0.5 + 0.15, 0.5, 0.5))
    }

    @Test fun goldAboveThresholdIsNotNeutral() {
        // strong red-over-blue lean (gold) → spread ≥ threshold → not neutral
        assertFalse(isNeutralPixel(0.66, 0.5, 0.31)) // r-b = 0.35
        // r-b exactly at threshold is NOT < threshold → not neutral
        assertFalse(isNeutralPixel(40.0 / 255.0, 0.0, 0.0))
    }

    // --- goldT: clamp at 0.49 / 0.86 and outside ---

    @Test fun goldTClampsAtSpanEnds() {
        assertEquals(0.0, goldT(0.49), eps)
        assertEquals(1.0, goldT(0.86), eps)
        assertEquals(0.5, goldT(0.675), eps) // midpoint of [0.49, 0.86]
    }

    @Test fun goldTClampsOutsideSpan() {
        assertEquals(0.0, goldT(0.30), eps) // below span
        assertEquals(0.0, goldT(-1.0), eps)
        assertEquals(1.0, goldT(0.95), eps) // above span
        assertEquals(1.0, goldT(2.0), eps)
    }

    // --- sampleRamp: endpoints exact, midpoint lerps (sapphire hand-computed) ---

    // Reference stops read from the ramp itself. Compose stores Color components in float32, so
    // 0x73/255.0 as a double (0.4509803921…) differs from the stored float (0.4509803950…) past
    // ~1e-7; taking the stops from AccentTheme keeps the lerp assertions exact to double precision.
    private fun stop(c: androidx.compose.ui.graphics.Color) =
        Triple(c.red.toDouble(), c.green.toDouble(), c.blue.toDouble())

    private fun halfway(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>) =
        Triple((a.first + b.first) / 2, (a.second + b.second) / 2, (a.third + b.third) / 2)

    @Test fun sampleRampEndpointsHitExactStops() {
        assertRgb(stop(AccentTheme.Sapphire.deep), sampleRamp(AccentTheme.Sapphire, 0.0))
        assertRgb(stop(AccentTheme.Sapphire.mid), sampleRamp(AccentTheme.Sapphire, 0.5))
        assertRgb(stop(AccentTheme.Sapphire.light), sampleRamp(AccentTheme.Sapphire, 1.0))
    }

    @Test fun sampleRampMidpointsLerpHalfway() {
        val deep = stop(AccentTheme.Sapphire.deep)
        val mid = stop(AccentTheme.Sapphire.mid)
        val light = stop(AccentTheme.Sapphire.light)
        // t=0.25 → halfway deep→mid; t=0.75 → halfway mid→light. Sanity-check against the
        // hand-computed 8-bit values so the ramp identity is pinned, not just self-consistent.
        assertEquals(0.18235294, halfway(deep, mid).first, 1e-6)
        assertEquals(0.88823529, halfway(mid, light).third, 1e-6)
        assertRgb(halfway(deep, mid), sampleRamp(AccentTheme.Sapphire, 0.25))
        assertRgb(halfway(mid, light), sampleRamp(AccentTheme.Sapphire, 0.75))
    }

    @Test fun luminanceUsesRec601Weights() {
        assertEquals(0.299, luminance(1.0, 0.0, 0.0), eps)
        assertEquals(0.587, luminance(0.0, 1.0, 0.0), eps)
        assertEquals(0.114, luminance(0.0, 0.0, 1.0), eps)
    }

    // --- tintPixel: the per-pixel brand policy (macOS recoloredBrandImage:120-139 core) ---
    // Same fixture pixels, both modes: neutrals pass through in dark and remap to charcoal
    // in light; gold handling is identical in both modes.

    private val neutralFixture = Triple(0.8, 0.8, 0.8)   // silver: r == b, spread 0
    private val goldFixture = Triple(0.66, 0.5, 0.31)    // gold: r − b = 0.35 ≥ threshold

    @Test fun tintPixelKeepsNeutralInDarkMode() {
        val (r, g, b) = neutralFixture
        assertEquals(null, tintPixel(r, g, b, AccentTheme.Sapphire, isDark = true))
        assertEquals(null, tintPixel(r, g, b, AccentTheme.ChampagneGold, isDark = true))
    }

    @Test fun tintPixelRemapsNeutralToCharcoalInLightMode() {
        // Charcoal = light-mode textPrimary #1E1C18 = RGB(30, 28, 24)/255 (DesignKit.swift:118).
        val charcoal = Triple(30.0 / 255.0, 28.0 / 255.0, 24.0 / 255.0)
        val (r, g, b) = neutralFixture
        assertRgb(charcoal, tintPixel(r, g, b, AccentTheme.Sapphire, isDark = false)!!)
        // The neutral→charcoal remap applies regardless of accent — champagne included.
        assertRgb(charcoal, tintPixel(r, g, b, AccentTheme.ChampagneGold, isDark = false)!!)
    }

    @Test fun tintPixelGoldHandlingIsModeIndependent() {
        val (r, g, b) = goldFixture
        // Non-default accent: the same gold pixel samples the same ramp point in both modes.
        val dark = tintPixel(r, g, b, AccentTheme.Sapphire, isDark = true)!!
        val light = tintPixel(r, g, b, AccentTheme.Sapphire, isDark = false)!!
        assertRgb(dark, light)
        assertRgb(sampleRamp(AccentTheme.Sapphire, goldT(luminance(r, g, b))), dark)
        // Champagne default: gold pixels pass through untouched in both modes.
        assertEquals(null, tintPixel(r, g, b, AccentTheme.ChampagneGold, isDark = true))
        assertEquals(null, tintPixel(r, g, b, AccentTheme.ChampagneGold, isDark = false))
    }

    // --- Empirical resource-pixel test: proves the Skia channel order is handled correctly. ---
    // A wrong B/R swap would misclassify gold as neutral (r−b would invert), so the sapphire
    // remap would leave the gold region unchanged and this test would fail.

    /** Reads the real wordmark, returns the raw N32 pixel bytes at its bitmap's colorType. */
    private fun decodeWordmarkPixels(): Pair<SkiaBitmap, ByteArray> {
        val bytes = BrandTintCache::class.java.classLoader!!
            .getResourceAsStream(WORDMARK_RESOURCE)!!.use { it.readBytes() }
        val image = SkiaImage.makeFromEncoded(bytes)
        val bitmap = SkiaBitmap().apply {
            allocN32Pixels(image.width, image.height)
            image.readPixels(this)
        }
        return bitmap to bitmap.readPixels()!!
    }

    /** Index of the first non-transparent, gold (non-neutral) pixel, or -1. Uses production
     *  channel-order logic so the test and impl agree on how bytes map to r/g/b/a. */
    private fun firstGoldPixelIndex(bitmap: SkiaBitmap, px: ByteArray): Int {
        val rgba = bitmap.imageInfo.colorInfo.colorType == org.jetbrains.skia.ColorType.RGBA_8888
        val rOff = if (rgba) 0 else 2
        val bOff = if (rgba) 2 else 0
        var i = 0
        while (i < px.size) {
            val a = px[i + 3].toInt() and 0xFF
            if (a != 0) {
                val af = a / 255.0
                val r = (px[i + rOff].toInt() and 0xFF) / 255.0 / af
                val g = (px[i + 1].toInt() and 0xFF) / 255.0 / af
                val b = (px[i + bOff].toInt() and 0xFF) / 255.0 / af
                if (!isNeutralPixel(r, g, b)) return i
            }
            i += 4
        }
        return -1
    }

    private fun firstTransparentIndex(px: ByteArray): Int {
        var i = 0
        while (i < px.size) {
            if ((px[i + 3].toInt() and 0xFF) == 0) return i
            i += 4
        }
        return -1
    }

    /** Index of the first non-transparent, neutral (silver/white) pixel, or -1 — the silver
     *  "Trade" wordmark / P-stroke region that light mode must darken to charcoal. */
    private fun firstNeutralOpaqueIndex(bitmap: SkiaBitmap, px: ByteArray): Int {
        val rgba = bitmap.imageInfo.colorInfo.colorType == org.jetbrains.skia.ColorType.RGBA_8888
        val rOff = if (rgba) 0 else 2
        val bOff = if (rgba) 2 else 0
        var i = 0
        while (i < px.size) {
            val a = px[i + 3].toInt() and 0xFF
            if (a != 0) {
                val af = a / 255.0
                val r = (px[i + rOff].toInt() and 0xFF) / 255.0 / af
                val g = (px[i + 1].toInt() and 0xFF) / 255.0 / af
                val b = (px[i + bOff].toInt() and 0xFF) / 255.0 / af
                if (isNeutralPixel(r, g, b)) return i
            }
            i += 4
        }
        return -1
    }

    /** Hand-rolls the exact production remap (un-premultiply → classify → charcoal/ramp →
     *  re-premultiply, respecting channel order) into a fresh copy of [original]. Written
     *  independently of `tintPixel` so it stays a genuine reference the production output is
     *  byte-compared against — mirrors macOS recoloredBrandImage's branch structure. */
    private fun handRolledRemap(
        bitmap: SkiaBitmap,
        original: ByteArray,
        accent: AccentTheme,
        isDark: Boolean,
    ): ByteArray {
        val mutated = original.copyOf()
        val rgba = bitmap.imageInfo.colorInfo.colorType == org.jetbrains.skia.ColorType.RGBA_8888
        val rOff = if (rgba) 0 else 2
        val bOff = if (rgba) 2 else 0
        var i = 0
        while (i < mutated.size) {
            val a = mutated[i + 3].toInt() and 0xFF
            if (a != 0) {
                val af = a / 255.0
                val r = (mutated[i + rOff].toInt() and 0xFF) / 255.0 / af
                val g = (mutated[i + 1].toInt() and 0xFF) / 255.0 / af
                val b = (mutated[i + bOff].toInt() and 0xFF) / 255.0 / af
                val out: Triple<Double, Double, Double>? = if (isNeutralPixel(r, g, b)) {
                    if (isDark) null else Triple(30.0 / 255.0, 28.0 / 255.0, 24.0 / 255.0)
                } else if (accent == AccentTheme.ChampagneGold) {
                    null
                } else {
                    sampleRamp(accent, goldT(luminance(r, g, b)))
                }
                if (out != null) {
                    mutated[i + rOff] = (out.first.coerceIn(0.0, 1.0) * af * 255.0).toInt().toByte()
                    mutated[i + 1] = (out.second.coerceIn(0.0, 1.0) * af * 255.0).toInt().toByte()
                    mutated[i + bOff] = (out.third.coerceIn(0.0, 1.0) * af * 255.0).toInt().toByte()
                }
            }
            i += 4
        }
        return mutated
    }

    @Test fun resourceContainsAClassifiableGoldRegion() {
        val (bitmap, px) = decodeWordmarkPixels()
        assertTrue(
            firstGoldPixelIndex(bitmap, px) >= 0,
            "expected the champagne wordmark to contain gold pixels; " +
                "colorType=${bitmap.imageInfo.colorInfo.colorType}",
        )
    }

    @Test fun sapphireRemapChangesGoldPixelsButNotTransparentOnes() = runBlocking {
        BrandTintCache.clear()
        val (bitmap, original) = decodeWordmarkPixels()
        val goldIdx = firstGoldPixelIndex(bitmap, original)
        assertTrue(goldIdx >= 0, "need a gold pixel to test the remap")
        val transIdx = firstTransparentIndex(original)

        val tinted = tintedWordmark(AccentTheme.Sapphire, isDark = true)
        assertNotNull(tinted, "sapphire wordmark should decode")

        // Re-decode via the same path the impl used and remap by hand to compare bytes.
        val (b2, decoded) = decodeWordmarkPixels()
        val rgba = b2.imageInfo.colorInfo.colorType == org.jetbrains.skia.ColorType.RGBA_8888
        val rOff = if (rgba) 0 else 2
        val bOff = if (rgba) 2 else 0
        val mutated = handRolledRemap(b2, decoded, AccentTheme.Sapphire, isDark = true)

        // (a) the gold region's bytes actually changed under sapphire
        assertFalse(
            original[goldIdx + rOff] == mutated[goldIdx + rOff] &&
                original[goldIdx + 1] == mutated[goldIdx + 1] &&
                original[goldIdx + bOff] == mutated[goldIdx + bOff],
            "sapphire remap must change a gold pixel's RGB",
        )

        // (c) fully-transparent pixels stay byte-identical
        if (transIdx >= 0) {
            for (k in 0 until 4) {
                assertEquals(
                    original[transIdx + k], mutated[transIdx + k],
                    "transparent pixel byte $k must be untouched",
                )
            }
        }
    }

    @Test fun sapphireProductionOutputMatchesHandRolledRemapByteForByte() = runBlocking {
        // Closes the plumbing gap: read the PRODUCTION tintedWordmark(Sapphire) pixels back out
        // and byte-compare against the hand-rolled reference remap. This proves the whole decode
        // → remapGoldPixels → installPixels → asComposeImageBitmap path produces exactly the
        // expected bytes, not just that "some gold pixel changed".
        BrandTintCache.clear()
        val (refBitmap, decoded) = decodeWordmarkPixels()
        val expected = handRolledRemap(refBitmap, decoded, AccentTheme.Sapphire, isDark = true)

        val tinted = tintedWordmark(AccentTheme.Sapphire, isDark = true)
        assertNotNull(tinted, "sapphire wordmark should decode")

        val producedBytes = tinted.asSkiaBitmap().readPixels()!!
        assertEquals(expected.size, producedBytes.size, "pixel buffer sizes must match")
        assertTrue(
            expected.contentEquals(producedBytes),
            "production remap output must equal the hand-rolled reference byte-for-byte",
        )
    }

    @Test fun champagneLightProductionOutputMatchesHandRolledRemap() = runBlocking {
        // Light mode has no passthrough, even for champagne: the silver neutrals must darken
        // to charcoal or they vanish on the ivory ground. Byte-compare production against the
        // independent reference remap, and prove the remap actually changed something.
        BrandTintCache.clear()
        val (refBitmap, decoded) = decodeWordmarkPixels()
        assertTrue(
            firstNeutralOpaqueIndex(refBitmap, decoded) >= 0,
            "expected the wordmark to contain neutral silver pixels",
        )
        val expected = handRolledRemap(refBitmap, decoded, AccentTheme.ChampagneGold, isDark = false)
        assertFalse(
            expected.contentEquals(decoded),
            "the light-mode remap must change the neutral silver pixels",
        )

        val tinted = tintedWordmark(AccentTheme.ChampagneGold, isDark = false)
        assertNotNull(tinted, "champagne light-mode wordmark should decode")
        assertTrue(
            expected.contentEquals(tinted.asSkiaBitmap().readPixels()!!),
            "champagne light-mode output must equal the hand-rolled reference byte-for-byte",
        )
    }

    @Test fun champagnePassthroughDoesNotDecodeAndReturnsNullInDarkMode() = runBlocking {
        // (b) champagne + dark is the passthrough path: no decode, cache never populated.
        BrandTintCache.clear()
        assertEquals(null, tintedWordmark(AccentTheme.ChampagneGold, isDark = true))
        assertEquals(null, BrandTintCache.get(AccentTheme.ChampagneGold, isDark = true))
    }

    @Test fun tintedWordmarkIsCachedPerAccent() = runBlocking {
        BrandTintCache.clear()
        assertEquals(null, BrandTintCache.get(AccentTheme.Amethyst, isDark = true))
        val first = tintedWordmark(AccentTheme.Amethyst, isDark = true)
        assertNotNull(first)
        assertTrue(first === BrandTintCache.get(AccentTheme.Amethyst, isDark = true))
        // second call returns the identical cached instance
        assertTrue(first === tintedWordmark(AccentTheme.Amethyst, isDark = true))
    }

    @Test fun tintCacheIsKeyedByAccentAndMode() = runBlocking {
        // "accent-isDark" key, matching macOS BrandImage (DesignKit.swift:81): the same accent
        // in different modes yields distinct cached bitmaps.
        BrandTintCache.clear()
        val dark = tintedWordmark(AccentTheme.Sapphire, isDark = true)
        val light = tintedWordmark(AccentTheme.Sapphire, isDark = false)
        assertNotNull(dark)
        assertNotNull(light)
        assertFalse(dark === light, "dark and light tints must be distinct cache entries")
        assertTrue(dark === BrandTintCache.get(AccentTheme.Sapphire, isDark = true))
        assertTrue(light === BrandTintCache.get(AccentTheme.Sapphire, isDark = false))
    }
}
