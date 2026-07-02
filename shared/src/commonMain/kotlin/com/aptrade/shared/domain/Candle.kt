package com.aptrade.shared.domain

/** One OHLC bar. `volume` is 0.0 when the source doesn't report it. */
data class Candle(
    val epochSeconds: Long,
    val open: Money,
    val high: Money,
    val low: Money,
    val close: Money,
    val volume: Double = 0.0,
)
