package com.aptrade.desktop.ui

import com.aptrade.shared.domain.AssetKind

/** Inverse of `designkit.kindLabel` — rebuilds an [AssetKind] from its display label so a host
 *  can construct an Asset for a trade. Stock is the safe default for any unexpected label.
 *  Single-sourced here so Main.kt and the detail screen share one mapping. */
fun assetKindFromLabel(label: String?): AssetKind = when (label) {
    "ETF" -> AssetKind.Etf
    "Crypto" -> AssetKind.Crypto
    else -> AssetKind.Stock
}
