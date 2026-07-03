package com.aptrade.desktop.ui

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.TradeError

fun QuoteError.userMessage(): String = when (this) {
    is QuoteError.RateLimited -> "Rate limited — try again in a moment."
    is QuoteError.NotFound -> "No data found for this symbol."
    is QuoteError.Network -> "Network problem: $reason"
}

fun TradeError.userMessage(): String = when (this) {
    is TradeError.InsufficientFunds -> "Insufficient funds."
    is TradeError.InsufficientShares -> "Insufficient shares."
    is TradeError.InvalidQuantity -> "Enter a valid quantity."
}
