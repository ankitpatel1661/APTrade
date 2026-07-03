package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
private val btc = Asset("BTC-USD", "Bitcoin USD", AssetKind.Crypto)
private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)
private fun quote(symbol: String, price: String, prevClose: String) =
    Quote(symbol, Money.usd(price), Money.usd(prevClose), 0.0)

private fun fixturePortfolio(): Portfolio {
    val aaplPosition = Position(aapl, qty("10"), Money.usd("300"), Money.usd("0"))
    val btcPosition = Position(btc, qty("0.1"), Money.usd("60000"), Money.usd("0"))
    return Portfolio(cash = Money.usd("91000"), positions = listOf(aaplPosition, btcPosition))
}

private fun fixtureQuotes(): Map<String, Quote> = mapOf(
    "AAPL" to quote("AAPL", "310", "305"),
    // BTC-USD intentionally has no quote -> cost-basis fallback.
)

class PortfolioExportTest {

    @Test
    fun snapshotOrdersHoldingsByMarketValueAndComputesAllocation() {
        val export = PortfolioExport.from(
            portfolio = fixturePortfolio(),
            quotes = fixtureQuotes(),
            accountName = "Test Account",
            generatedAtEpochSeconds = 1_700_000_000L,
        )

        assertEquals(BigDecimal.parseString("100100"), export.totalValue.amount)
        assertEquals(BigDecimal.parseString("91000"), export.cash.amount)
        assertEquals(BigDecimal.parseString("9100"), export.holdingsValue.amount)
        assertEquals(BigDecimal.parseString("50"), export.dayChange.amount)
        assertEquals(BigDecimal.parseString("100"), export.unrealizedPnL.amount)

        // BTC-USD market value (6000) exceeds AAPL (3100) -> sorted first.
        assertEquals(listOf("BTC-USD", "AAPL"), export.holdings.map { it.symbol })

        val btcRow = export.holdings[0]
        assertEquals(BigDecimal.parseString("6000"), btcRow.marketValue)
        assertEquals(BigDecimal.parseString("6000"), btcRow.costBasis)
        assertEquals(BigDecimal.parseString("0"), btcRow.unrealizedPnL)
        assertTrue(kotlin.math.abs(btcRow.allocation - 6000.0 / 100100.0) < 1e-9)

        val aaplRow = export.holdings[1]
        assertEquals(BigDecimal.parseString("3100"), aaplRow.marketValue)
        assertEquals(BigDecimal.parseString("3000"), aaplRow.costBasis)
        assertEquals(BigDecimal.parseString("100"), aaplRow.unrealizedPnL)
        assertTrue(kotlin.math.abs(aaplRow.allocation - 3100.0 / 100100.0) < 1e-9)
    }

    @Test
    fun csvGoldenString() {
        val export = PortfolioExport.from(
            portfolio = fixturePortfolio(),
            quotes = fixtureQuotes(),
            accountName = "Test Account",
            generatedAtEpochSeconds = 1_700_000_000L,
        )

        val expected = listOf(
            "Account,Test Account",
            "Generated At (epoch seconds),1700000000",
            "Currency,USD",
            "",
            "Symbol,Name,Kind,Quantity,Average Cost,Last Price,Market Value,Cost Basis,Unrealized PnL,Allocation",
            "BTC-USD,Bitcoin USD,Crypto,0.1,60000,60000,6000,6000,0,0.05994",
            "AAPL,Apple Inc.,Stock,10,300,310,3100,3000,100,0.030969",
            "",
            "Total Value,100100",
            "Cash,91000",
            "Holdings Value,9100",
            "Day Change,50",
            "Unrealized PnL,100",
        ).joinToString("\n")

        assertEquals(expected, export.renderCsv())
    }

    @Test
    fun jsonRoundTripsThroughKotlinxSerialization() {
        val export = PortfolioExport.from(
            portfolio = fixturePortfolio(),
            quotes = fixtureQuotes(),
            accountName = "Test Account",
            generatedAtEpochSeconds = 1_700_000_000L,
        )

        val json = export.renderJson()
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals("Test Account", root["accountName"]!!.jsonPrimitive.content)
        assertEquals("USD", root["currencyCode"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000L, root["generatedAtEpochSeconds"]!!.jsonPrimitive.content.toLong())
        assertEquals("100100", root["totalValue"]!!.jsonPrimitive.content)
        assertEquals("91000", root["cash"]!!.jsonPrimitive.content)

        val holdings = root["holdings"]!!.jsonArray
        assertEquals(2, holdings.size)
        val first = holdings[0].jsonObject
        assertEquals("BTC-USD", first["symbol"]!!.jsonPrimitive.content)
        assertEquals("6000", first["marketValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun tinyAllocationRenderAsPlainDecimalNotScientificNotation() {
        // Boundary: allocation of 0.000001 (1 micro-unit) must render as "0.000001", not "1.0E-6"
        val export = PortfolioExport(
            generatedAtEpochSeconds = 1_700_000_000L,
            accountName = "Boundary Test",
            currencyCode = "USD",
            totalValue = Money.usd("1000000"),
            cash = Money.usd("999999"),
            holdingsValue = Money.usd("1"),
            dayChange = Money.usd("0"),
            unrealizedPnL = Money.usd("0"),
            holdings = listOf(
                PortfolioExport.Holding(
                    symbol = "TINY",
                    name = "Tiny Holding",
                    kind = "Stock",
                    quantity = qty("1"),
                    averageCost = qty("1"),
                    lastPrice = qty("1"),
                    marketValue = qty("1"),
                    costBasis = qty("1"),
                    unrealizedPnL = qty("0"),
                    allocation = 0.000001,
                )
            ),
        )
        val csv = export.renderCsv()
        assertTrue(csv.contains("0.000001"), "CSV should contain plain decimal '0.000001' for tiny allocation")
        assertTrue(!csv.contains("E-"), "CSV should not contain scientific notation 'E-'")
    }

    @Test
    fun zeroAllocationRendersAsZero() {
        // Boundary: allocation of 0.0 must render as "0"
        val export = PortfolioExport(
            generatedAtEpochSeconds = 1_700_000_000L,
            accountName = "Zero Test",
            currencyCode = "USD",
            totalValue = Money.usd("1000"),
            cash = Money.usd("1000"),
            holdingsValue = Money.usd("0"),
            dayChange = Money.usd("0"),
            unrealizedPnL = Money.usd("0"),
            holdings = listOf(),
        )
        val csv = export.renderCsv()
        // Verify zero rendering doesn't introduce artifacts
        assertTrue(!csv.contains("E-"), "CSV should not contain scientific notation")
    }
}
