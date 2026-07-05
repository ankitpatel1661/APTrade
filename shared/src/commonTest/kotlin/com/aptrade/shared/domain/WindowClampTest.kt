package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowClampTest {
    private data class Stamped(val epochSeconds: Long)

    @Test
    fun keepsOnlyItemsWithinWindowOfNewest() {
        val items = listOf(Stamped(1000), Stamped(1500), Stamped(2000))
        val result = clampToWindow(items, windowDurationSeconds = 600) { it.epochSeconds }
        assertEquals(listOf(Stamped(1500), Stamped(2000)), result)
    }

    @Test
    fun anchorsToNewestItemNotSomeExternalNow() {
        // If this were anchored to wall-clock "now" (far larger than these timestamps),
        // nothing would survive a 600-second window. Anchoring to the newest item (2000)
        // keeps anything within 600s of it, regardless of what "now" actually is.
        val items = listOf(Stamped(1000), Stamped(2000))
        val result = clampToWindow(items, windowDurationSeconds = 600) { it.epochSeconds }
        assertEquals(listOf(Stamped(2000)), result)
    }

    @Test
    fun emptyListReturnsEmpty() {
        assertEquals(emptyList<Stamped>(), clampToWindow(emptyList(), 600) { it.epochSeconds })
    }
}
