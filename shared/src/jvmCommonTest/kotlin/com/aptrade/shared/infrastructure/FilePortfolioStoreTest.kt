package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.Transaction
import com.aptrade.shared.domain.TradeSide
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePortfolioStoreTest {
    private val portfolio = Portfolio(
        cash = Money(BigDecimal.parseString("50000.50"), "USD"),
        positions = listOf(
            Position(
                asset = Asset("AAPL", "Apple Inc.", AssetKind.Stock),
                quantity = BigDecimal.parseString("10.5"),
                averageCost = Money(BigDecimal.parseString("150.25"), "USD"),
                realizedPnL = Money(BigDecimal.parseString("500.75"), "USD"),
            ),
        ),
        transactions = listOf(
            Transaction(
                id = "txn-001",
                symbol = "AAPL",
                side = TradeSide.Buy,
                quantity = BigDecimal.parseString("10.5"),
                price = Money(BigDecimal.parseString("150.25"), "USD"),
                epochSeconds = 1688169600,
            ),
        ),
    )

    @Test
    fun roundTripsPortfolioWithCashPositionsTransactions() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("portfolio.json")
        val store = FilePortfolioStore(file)
        store.save(portfolio)
        assertEquals(portfolio, store.load())
        assertEquals(portfolio, FilePortfolioStore(file).load())   // fresh instance, same file
    }

    @Test
    fun missingFileLoadsNull() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("nope.json")
        assertNull(FilePortfolioStore(file).load())
    }

    @Test
    fun corruptFileLoadsNull() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("portfolio.json")
        file.writeText("{not json[")
        assertNull(FilePortfolioStore(file).load())
    }

    @Test
    fun saveCreatesParentDirsAndLeavesNoTempFile() = runTest {
        val dir = createTempDirectory("aptrade-test").resolve("deep").resolve("nested")
        val file = dir.resolve("portfolio.json")
        FilePortfolioStore(file).save(portfolio)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("AAPL"))
        assertEquals(listOf("portfolio.json"), dir.toFile().list()!!.toList())  // temp file was renamed away
    }

    @Test
    fun unknownAssetKindOrTradeSideLoadsNull() = runTest {
        val file = createTempDirectory("aptrade-test").resolve("portfolio.json")
        file.writeText(
            """{"cash":{"amount":"50000.50","currency":"USD"},"positions":[{"asset":{"symbol":"AAPL","name":"Apple Inc.","kind":"Stock"},"quantity":"10.5","averageCost":{"amount":"150.25","currency":"USD"},"realizedPnL":{"amount":"500.75","currency":"USD"}}],"transactions":[{"id":"txn-001","symbol":"AAPL","side":"Unknown","quantity":"10.5","price":{"amount":"150.25","currency":"USD"},"epochSeconds":1688169600}]}""",
        )
        assertNull(FilePortfolioStore(file).load())
    }
}
