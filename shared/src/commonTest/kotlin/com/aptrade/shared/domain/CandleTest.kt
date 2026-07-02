package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class CandleTest {
    @Test
    fun holdsOhlcvWithDefaultZeroVolume() {
        val candle = Candle(
            epochSeconds = 1_700_000_000L,
            open = Money.usd("100.00"), high = Money.usd("101.00"),
            low = Money.usd("99.00"), close = Money.usd("100.50"),
        )
        assertEquals(0.0, candle.volume)
        assertEquals(Money.usd("101.00"), candle.high)
    }

    @Test
    fun holdsExplicitVolume() {
        val candle = Candle(
            epochSeconds = 1_700_000_000L,
            open = Money.usd("100.00"), high = Money.usd("101.00"),
            low = Money.usd("99.00"), close = Money.usd("100.50"), volume = 500.0,
        )
        assertEquals(500.0, candle.volume)
    }
}
