package com.aptrade.desktop.designkit

import kotlin.test.Test
import kotlin.test.assertEquals

class ValueCardMathTest {
    @Test fun midpointMapsToNearestIndex() = assertEquals(2, crosshairIndex(50f, 100f, 5))
    @Test fun leftEdgeIsFirstIndex() = assertEquals(0, crosshairIndex(0f, 100f, 5))
    @Test fun rightEdgeIsLastIndex() = assertEquals(4, crosshairIndex(100f, 100f, 5))
    @Test fun overshootClampsToLast() = assertEquals(4, crosshairIndex(250f, 100f, 5))
    @Test fun negativeClampsToFirst() = assertEquals(0, crosshairIndex(-10f, 100f, 5))
    @Test fun degenerateCountFallsBackToLast() = assertEquals(0, crosshairIndex(50f, 100f, 1))
    @Test fun zeroWidthFallsBackToLast() = assertEquals(4, crosshairIndex(50f, 0f, 5))

    @Test fun deltaIsValueMinusStart() = assertEquals(7.5, percentPointDelta(listOf(0.0, 3.0, 7.5), 2))
    @Test fun deltaAtStartIsZero() = assertEquals(0.0, percentPointDelta(listOf(2.0, 3.0), 0))
    @Test fun deltaOnEmptyIsZero() = assertEquals(0.0, percentPointDelta(emptyList(), 3))
}
