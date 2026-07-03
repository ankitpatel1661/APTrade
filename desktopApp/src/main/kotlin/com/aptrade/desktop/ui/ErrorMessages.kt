package com.aptrade.desktop.ui

import com.aptrade.shared.application.QuoteError

fun QuoteError.userMessage(): String = when (this) {
    is QuoteError.RateLimited -> "Rate limited — try again in a moment."
    is QuoteError.NotFound -> "No data found for this symbol."
    is QuoteError.Network -> "Network problem: $reason"
}
