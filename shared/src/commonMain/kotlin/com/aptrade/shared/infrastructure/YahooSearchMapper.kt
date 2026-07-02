package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind

object YahooSearchMapper {
    fun assets(response: YahooSearchResponse): List<Asset> =
        (response.quotes ?: emptyList()).mapNotNull(::asset)

    private fun asset(item: YahooSearchResponse.Item): Asset? {
        val symbol = item.symbol ?: return null
        val kind = kind(item.quoteType) ?: return null
        val name = item.shortname ?: item.longname ?: symbol
        return Asset(symbol = symbol, name = name, kind = kind)
    }

    private fun kind(type: String?): AssetKind? = when (type?.uppercase()) {
        "EQUITY" -> AssetKind.Stock
        "ETF" -> AssetKind.Etf
        "CRYPTOCURRENCY" -> AssetKind.Crypto
        else -> null // INDEX, FUTURE, CURRENCY, OPTION, … unsupported — filtered out
    }
}
