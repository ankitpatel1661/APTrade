package com.aptrade.shared.domain

enum class AssetKind { Stock, Etf, Crypto }

data class Asset(
    val symbol: String,
    val name: String,
    val kind: AssetKind,
)
