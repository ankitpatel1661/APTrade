package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Candle
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.aptrade.shared.domain.TradeError
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class InMemoryPortfolioStore : PortfolioStore {
    var stored: Portfolio? = null
    var saveCount = 0
        private set

    override suspend fun load(): Portfolio? = stored
    override suspend fun save(portfolio: Portfolio) {
        stored = portfolio
        saveCount++
    }
}

private class FakeMarketDataRepository(
    private val quotesBySymbol: Map<String, Quote> = emptyMap(),
    private val historiesBySymbol: Map<String, List<PricePoint>> = emptyMap(),
    private val failingSymbols: Set<String> = emptySet(),
    private val quotesError: QuoteError? = null,
) : MarketDataRepository {
    override suspend fun quotes(symbols: List<String>): List<Quote> {
        quotesError?.let { throw it }
        return symbols.mapNotNull { quotesBySymbol[it] }
    }

    override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> {
        if (symbol in failingSymbols) throw QuoteError.RateLimited
        return historiesBySymbol[symbol] ?: emptyList()
    }

    override suspend fun candles(symbol: String, timeframe: Timeframe): List<Candle> = emptyList()
    override suspend fun profile(symbol: String): Asset = Asset(symbol, symbol, AssetKind.Stock)
    override suspend fun search(query: String): List<Asset> = emptyList()
}

private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)

class PortfolioUseCasesTest {

    @Test
    fun fetchReturnsStartingPortfolioWithoutPersistingWhenNeverSaved() = runTest {
        val store = InMemoryPortfolioStore()
        val result = FetchPortfolio(store).execute()
        assertEquals(Portfolio.starting(), result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun fetchReturnsTheStoredPortfolioVerbatim() = runTest {
        val saved = Portfolio.starting().buying(aapl, BigDecimal.parseString("2"), Money.usd("100.00"), 1000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = saved }
        val result = FetchPortfolio(store).execute()
        assertEquals(saved, result)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun buyExecutesAtLiveQuotePriceAndPersists() = runTest {
        val store = InMemoryPortfolioStore()
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("150.00"), Money.usd("148.00"), 1.5)),
        )
        val result = BuyAsset(repository, store).execute(aapl, BigDecimal.parseString("10"), 5000L)

        assertEquals(Money.usd("100000").amount - BigDecimal.parseString("1500.00"), result.cash.amount)
        val position = result.positionFor("AAPL")
        assertEquals(BigDecimal.parseString("10"), position?.quantity)
        assertEquals(Money.usd("150.00"), position?.averageCost)
        assertEquals(1, result.transactions.size)
        assertEquals(5000L, result.transactions.first().epochSeconds)
        assertEquals(result, store.stored)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun buyPropagatesInsufficientFundsWithoutSaving() = runTest {
        val store = InMemoryPortfolioStore()
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("1000000.00"), Money.usd("999000.00"), 0.1)),
        )
        assertFailsWith<TradeError.InsufficientFunds> {
            BuyAsset(repository, store).execute(aapl, BigDecimal.parseString("10"), 5000L)
        }
        assertEquals(0, store.saveCount)
    }

    @Test
    fun buyPropagatesQuoteErrorWithoutSaving() = runTest {
        val store = InMemoryPortfolioStore()
        val repository = FakeMarketDataRepository(quotesError = QuoteError.RateLimited)
        assertFailsWith<QuoteError.RateLimited> {
            BuyAsset(repository, store).execute(aapl, BigDecimal.parseString("10"), 5000L)
        }
        assertEquals(0, store.saveCount)
    }

    @Test
    fun sellRealizesAndPersistsAndPropagatesInsufficientSharesWithoutSaving() = runTest {
        val bought = Portfolio.starting().buying(aapl, BigDecimal.parseString("10"), Money.usd("100.00"), 1000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = bought }
        val repository = FakeMarketDataRepository(
            quotesBySymbol = mapOf("AAPL" to Quote("AAPL", Money.usd("150.00"), Money.usd("148.00"), 1.5)),
        )
        val result = SellAsset(repository, store).execute("AAPL", BigDecimal.parseString("4"), 6000L)

        assertEquals(BigDecimal.parseString("6"), result.positionFor("AAPL")?.quantity)
        assertEquals(2, result.transactions.size)
        assertEquals(6000L, result.transactions.last().epochSeconds)
        assertEquals(result, store.stored)
        assertEquals(1, store.saveCount)

        assertFailsWith<TradeError.InsufficientShares> {
            SellAsset(repository, store).execute("AAPL", BigDecimal.parseString("100"), 7000L)
        }
        assertEquals(1, store.saveCount) // the failed oversell did not persist
    }

    @Test
    fun resetSavesAndReturnsStarting() = runTest {
        val bought = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = bought }
        val result = ResetPortfolio(store).execute()
        assertEquals(Portfolio.starting(), result)
        assertEquals(Portfolio.starting(), store.stored)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun performanceFetchesHistoriesForEachHeldSymbolConcurrentlyAndBuildsSeries() = runTest {
        val msft = Asset("MSFT", "Microsoft Corp.", AssetKind.Stock)
        var portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        portfolio = portfolio.buying(msft, BigDecimal.parseString("1"), Money.usd("200.00"), 1000L, "txn-2")
        val store = InMemoryPortfolioStore().apply { stored = portfolio }
        val repository = FakeMarketDataRepository(
            historiesBySymbol = mapOf(
                "AAPL" to listOf(PricePoint(86400L, Money.usd("110.00"))),
                "MSFT" to listOf(PricePoint(86400L, Money.usd("210.00"))),
            ),
        )

        val result = FetchPortfolioPerformance(repository, store).execute(Timeframe.OneMonth)

        assertEquals(1, result.size)
        assertEquals(86400L, result.first().epochSeconds)
        // cash after buying 1 AAPL@100 + 1 MSFT@200 = 100000 - 100 - 200 = 99700; holdings = 110 + 210 = 320.
        assertEquals(Money.usd("100000").amount - BigDecimal.parseString("300.00") + BigDecimal.parseString("320.00"), result.first().value.amount)
    }

    @Test
    fun performanceTreatsAPerSymbolFailureAsEmptyHistory() = runTest {
        val msft = Asset("MSFT", "Microsoft Corp.", AssetKind.Stock)
        var portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 1000L, "txn-1")
        portfolio = portfolio.buying(msft, BigDecimal.parseString("1"), Money.usd("200.00"), 1000L, "txn-2")
        val store = InMemoryPortfolioStore().apply { stored = portfolio }
        val repository = FakeMarketDataRepository(
            historiesBySymbol = mapOf(
                "MSFT" to listOf(PricePoint(86400L, Money.usd("210.00"))),
            ),
            failingSymbols = setOf("AAPL"),
        )

        val result = FetchPortfolioPerformance(repository, store).execute(Timeframe.OneMonth)

        assertEquals(1, result.size)
        assertEquals(86400L, result.first().epochSeconds)
        // cash after buying 1 AAPL@100 + 1 MSFT@200 = 99700. Only MSFT priced in — AAPL
        // contributed an empty history (its per-symbol failure), so only MSFT's 210 counts.
        assertEquals(Money.usd("100000").amount - BigDecimal.parseString("300.00") + BigDecimal.parseString("210.00"), result.first().value.amount)
    }

    @Test
    fun performanceSinceInceptionTrimsToFirstTransactionDay() = runTest {
        var portfolio = Portfolio.starting().buying(aapl, BigDecimal.parseString("1"), Money.usd("100.00"), 200_000L, "txn-1")
        val store = InMemoryPortfolioStore().apply { stored = portfolio }
        val repository = FakeMarketDataRepository(
            historiesBySymbol = mapOf(
                "AAPL" to listOf(
                    PricePoint(86_400L * 1, Money.usd("110.00")),
                    PricePoint(86_400L * 3, Money.usd("120.00")),
                ),
            ),
        )

        val result = FetchPortfolioPerformance(repository, store).execute(Timeframe.OneMonth, sinceInception = true)

        // first-txn day = (200000 / 86400) * 86400 = 172800; the 86400 point is dropped.
        assertEquals(1, result.size)
        assertEquals(86_400L * 3, result.first().epochSeconds)
    }
}
