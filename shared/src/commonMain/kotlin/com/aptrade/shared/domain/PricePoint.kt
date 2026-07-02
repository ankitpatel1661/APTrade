package com.aptrade.shared.domain

data class PricePoint(
    val epochSeconds: Long,
    val close: Money,
)
