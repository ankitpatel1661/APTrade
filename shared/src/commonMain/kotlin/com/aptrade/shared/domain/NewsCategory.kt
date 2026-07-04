package com.aptrade.shared.domain

/**
 * A market-news category. Conceptual — the news-source query mapping lives in
 * infrastructure. Declaration order is the UI order.
 */
enum class NewsCategory(val finnhubValue: String, val displayName: String) {
    General("general", "General"),
    Crypto("crypto", "Crypto"),
    Merger("merger", "Merger"),
}
