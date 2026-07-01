package com.aptrade.shared.application

sealed class QuoteError(message: String) : Exception(message) {
    object RateLimited : QuoteError("Rate limited by data provider")
    object NotFound : QuoteError("Quote not found")
    data class Network(val reason: String) : QuoteError("Network error: $reason")
}
