package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class AssetTest {
    @Test
    fun holdsSymbolNameAndKind() {
        val asset = Asset(symbol = "AAPL", name = "Apple Inc.", kind = AssetKind.Stock)
        assertEquals("AAPL", asset.symbol)
        assertEquals("Apple Inc.", asset.name)
        assertEquals(AssetKind.Stock, asset.kind)
    }
}
