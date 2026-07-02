package com.aptrade.shared.domain

data class Quote(
    val symbol: String,
    val price: Money,
    val previousClose: Money,
    val changePercent: Double,
)
