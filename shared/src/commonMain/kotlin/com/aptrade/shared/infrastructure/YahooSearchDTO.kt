package com.aptrade.shared.infrastructure

import kotlinx.serialization.Serializable

@Serializable
data class YahooSearchResponse(val quotes: List<Item>? = null) {
    @Serializable
    data class Item(
        val symbol: String? = null,
        val shortname: String? = null,
        val longname: String? = null,
        val quoteType: String? = null,
    )
}
