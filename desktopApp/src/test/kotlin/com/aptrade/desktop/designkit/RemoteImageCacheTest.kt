package com.aptrade.desktop.designkit

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.jetbrains.skia.Bitmap as SkiaBitmap

/**
 * Exercises [RemoteImageCache] directly (it's `internal` for exactly this reason) so the LRU
 * eviction policy is pinned without touching the network or the Compose runtime.
 */
class RemoteImageCacheTest {

    @BeforeTest fun clearBefore() = RemoteImageCache.clear()
    @AfterTest fun clearAfter() = RemoteImageCache.clear()

    private fun bitmap(): ImageBitmap =
        SkiaBitmap().apply { allocN32Pixels(1, 1, false) }.asComposeImageBitmap()

    @Test fun missReturnsNull() {
        assertNull(RemoteImageCache.get("https://example.com/a.png"))
    }

    @Test fun putThenGetReturnsSameEntry() {
        val bmp = bitmap()
        RemoteImageCache.put("https://example.com/a.png", bmp)
        assertSame(bmp, RemoteImageCache.get("https://example.com/a.png"))
    }

    @Test fun sizeTracksDistinctKeys() {
        RemoteImageCache.put("u1", bitmap())
        RemoteImageCache.put("u2", bitmap())
        RemoteImageCache.put("u1", bitmap()) // overwrite, not a new entry
        assertEquals(2, RemoteImageCache.size())
    }

    @Test fun evictsLeastRecentlyUsedPastCapacity() {
        // Capacity is 64: fill it, then touch entry "0" so it's most-recently-used, then push
        // one more entry in — "1" (the true LRU after the touch) should be the one evicted, not "0".
        val zero = bitmap()
        RemoteImageCache.put("u0", zero)
        repeat(63) { i -> RemoteImageCache.put("u${i + 1}", bitmap()) }
        assertEquals(64, RemoteImageCache.size())

        assertSame(zero, RemoteImageCache.get("u0")) // promote u0 to most-recently-used

        RemoteImageCache.put("u64", bitmap()) // triggers eviction of the new LRU

        assertEquals(64, RemoteImageCache.size())
        assertNull(RemoteImageCache.get("u1"), "u1 should have been evicted as least-recently-used")
        assertSame(zero, RemoteImageCache.get("u0")) // u0 survived the touch
    }
}
