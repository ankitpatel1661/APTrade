package com.aptrade.shared.domain

/**
 * A single dividend paid by [symbol] on its ex-dividend date, expressed as an
 * exact per-share amount. Pure domain type — no framework imports.
 */
data class DividendEvent(
    val symbol: String,
    val exDateEpochSeconds: Long,
    val amountPerShare: Money,
)
