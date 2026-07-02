package com.aptrade.android.ui

import com.aptrade.shared.application.QuoteError

fun QuoteError.userMessage(): String = when (this) {
    is QuoteError.RateLimited -> "Rate limited by the data provider — try again shortly."
    is QuoteError.NotFound -> "No data found."
    is QuoteError.Network -> "Network error — check your connection and retry."
}
