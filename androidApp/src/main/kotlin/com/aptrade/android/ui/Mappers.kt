package com.aptrade.android.ui

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.AssetKind

fun QuoteError.userMessage(): String = when (this) {
    is QuoteError.RateLimited -> "Rate limited by the data provider — try again shortly."
    is QuoteError.NotFound -> "No data found."
    is QuoteError.Network -> "Network error — check your connection and retry."
}

fun AssetKind.label(): String = when (this) {
    AssetKind.Stock -> "Stock"
    AssetKind.Etf -> "ETF"
    AssetKind.Crypto -> "Crypto"
}
