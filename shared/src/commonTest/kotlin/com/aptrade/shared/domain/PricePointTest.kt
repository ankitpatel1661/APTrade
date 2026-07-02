package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class PricePointTest {
    @Test
    fun holdsEpochSecondsAndClose() {
        val point = PricePoint(epochSeconds = 1_700_000_000L, close = Money.usd("229.35"))
        assertEquals(1_700_000_000L, point.epochSeconds)
        assertEquals(Money.usd("229.35"), point.close)
    }
}
