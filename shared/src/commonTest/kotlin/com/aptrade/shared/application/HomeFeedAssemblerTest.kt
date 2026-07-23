package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.MONEY_MATH
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PresetScreen
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.ScreenerSnapshot
import com.aptrade.shared.domain.ScreenerSnapshotRow
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class Boom : Exception("boom")

// Monday 2026-11-23 noon ET — the same reference instant the Swift HomeViewModelTests /
// CalendarViewModelTests anchor on (verified via `TZ=America/New_York date`).
private const val MONDAY_NOON_ET = 1_795_453_200L
private const val SATURDAY_NOON_ET = 1_795_280_400L
private const val MONDAY_CLOSE_ET = 1_795_467_600L // 2026-11-23 16:00 ET
private const val MONDAY_OPEN_ET = 1_795_444_200L // 2026-11-23 09:30 ET

private fun usd(value: String) = Money.usd(value)

private fun quote(symbol: String, price: String, previousClose: String): Quote {
    val p = BigDecimal.parseString(price)
    val prev = BigDecimal.parseString(previousClose)
    val changePercent = (p - prev).divide(prev, MONEY_MATH).doubleValue(false) * 100.0
    return Quote(symbol = symbol, price = Money(p), previousClose = Money(prev), changePercent = changePercent)
}

private fun row(symbol: String, rsi14: Double? = null): ScreenerSnapshotRow = ScreenerSnapshotRow(
    symbol = symbol, name = symbol, close = 100.0, dayChangePercent = null, rsi14 = rsi14,
    macd = null, macdSignal = null, macdHistogram = null, sma50 = null, sma200 = null, ema20 = null,
    pctVsSma50 = null, pctVsSma200 = null, bollingerPercentB = null, bollingerBandwidth = null,
    week52High = null, week52Low = null, pctTo52wHigh = null, pctTo52wLow = null,
    relativeVolume = null, macdCrossedUp = false, macdCrossedDown = false, goldenCross = false, deathCross = false,
)

/**
 * Every dependency defaults to an empty/degraded-but-harmless double so a single test only
 * has to override the sources it actually cares about — mirrors the Swift reference's
 * `makeVM` helper (`Tests/APTradeAppTests/HomeViewModelTests.swift`).
 */
private fun makeAssembler(
    portfolio: suspend () -> Portfolio = { Portfolio(cash = usd("100000")) },
    quotes: Map<String, Quote> = emptyMap(),
    fetchQuotes: (suspend (List<String>) -> Map<String, Quote>)? = null,
    ownSymbols: suspend () -> Set<String> = { emptySet() },
    incomeSummary: suspend () -> HomeIncomeSummary = { HomeIncomeSummary(usd("0"), null) },
    nextEarnings: suspend () -> EarningsEvent? = { null },
    screenerSnapshot: suspend () -> ScreenerSnapshot? = { null },
    alerts: suspend () -> List<PriceAlert> = { emptyList() },
    now: Long = MONDAY_NOON_ET,
): HomeFeedAssembler = HomeFeedAssembler(
    loadPortfolio = portfolio,
    fetchQuotes = fetchQuotes ?: { requested -> requested.mapNotNull { s -> quotes[s]?.let { s to it } }.toMap() },
    ownSymbols = ownSymbols,
    loadIncomeSummary = incomeSummary,
    fetchNextEarnings = nextEarnings,
    loadScreenerSnapshot = screenerSnapshot,
    loadAlerts = alerts,
    calendar = MarketCalendar(),
    nowEpochSeconds = { now },
)

class HomeFeedAssemblerTest {

    // MARK: - Hero: totalValue / dayChange / dayChangePercent

    @Test
    fun totalValue_equalsMarketValuePlusCash() = runTest {
        val aapl = Asset(symbol = "AAPL", name = "Apple Inc.", kind = AssetKind.Stock)
        val portfolio = Portfolio(cash = usd("1000"))
            .buying(aapl, quantity = BigDecimal.parseString("2"), price = usd("100"), epochSeconds = 1L)
        // costs 200 -> cash left 800
        val quotes = mapOf("AAPL" to quote("AAPL", "150", "140"))
        val assembler = makeAssembler(portfolio = { portfolio }, quotes = quotes)

        val state = assembler.refresh()

        // holdingsValue = 150 x 2 = 300; totalValue = 300 + 800 cash = 1100.
        assertEquals(usd("1100"), state.totalValue)
        assertEquals(usd("800"), state.cash)
    }

    @Test
    fun dayChange_summedFromHoldingsQuoteChanges() = runTest {
        val aapl = Asset(symbol = "AAPL", name = "Apple Inc.", kind = AssetKind.Stock)
        val msft = Asset(symbol = "MSFT", name = "Microsoft", kind = AssetKind.Stock)
        val portfolio = Portfolio(cash = usd("1000"))
            .buying(aapl, quantity = BigDecimal.parseString("2"), price = usd("100"), epochSeconds = 1L)
            .buying(msft, quantity = BigDecimal.parseString("1"), price = usd("200"), epochSeconds = 1L)
        val quotes = mapOf(
            "AAPL" to quote("AAPL", "150", "140"), // +10 x 2 = +20
            "MSFT" to quote("MSFT", "190", "200"), // -10 x 1 = -10
        )
        val assembler = makeAssembler(portfolio = { portfolio }, quotes = quotes)

        val state = assembler.refresh()

        assertEquals(usd("10"), state.dayChange) // +20 + (-10)
    }

    @Test
    fun dayChangePercent_zeroGuardedWhenPreviousTotalIsZero() = runTest {
        // An all-cash, zero-cash portfolio: totalValue and dayChange both zero, so the
        // previous-day total is zero too -- must degrade to 0.0, never divide by zero.
        val assembler = makeAssembler(portfolio = { Portfolio(cash = Money(BigDecimal.ZERO)) })

        val state = assembler.refresh()

        assertEquals(0.0, state.dayChangePercent)
    }

    // MARK: - Movers: top gainer / loser across holdings ∪ watchlist, deduped

    @Test
    fun movers_dedupedAcrossHoldingsAndWatchlist() = runTest {
        // AAPL is both held AND watched -- must contribute one row, not two.
        val quotes = mapOf(
            "AAPL" to quote("AAPL", "110", "100"), // +10%
            "TSLA" to quote("TSLA", "90", "100"), // -10%
            "SPY" to quote("SPY", "101", "100"), // +1%
        )
        val assembler = makeAssembler(quotes = quotes, ownSymbols = { setOf("AAPL", "TSLA", "SPY") })

        val state = assembler.refresh()

        val gainer = state.feed.filterIsInstance<HomeFeedItem.TopGainer>()
        val loser = state.feed.filterIsInstance<HomeFeedItem.TopLoser>()
        assertEquals(1, gainer.size) // exactly one gainer row -- no duplicate AAPL row from the union
        assertEquals("AAPL", gainer.single().symbol)
        assertTrue(gainer.single().changePercent > 0)
        assertEquals("TSLA", loser.single().symbol)
    }

    @Test
    fun movers_loserAbsentWhenOnlyOneQuotedSymbol() = runTest {
        val quotes = mapOf("AAPL" to quote("AAPL", "110", "100"))
        val assembler = makeAssembler(quotes = quotes, ownSymbols = { setOf("AAPL") })

        val state = assembler.refresh()

        assertEquals(1, state.feed.filterIsInstance<HomeFeedItem.TopGainer>().size)
        assertTrue(state.feed.filterIsInstance<HomeFeedItem.TopLoser>().isEmpty())
    }

    @Test
    fun movers_absentWhenOwnSymbolsEmpty() = runTest {
        val assembler = makeAssembler(ownSymbols = { emptySet() })

        val state = assembler.refresh()

        assertTrue(state.feed.filterIsInstance<HomeFeedItem.TopGainer>().isEmpty())
        assertTrue(state.feed.filterIsInstance<HomeFeedItem.TopLoser>().isEmpty())
    }

    // MARK: - Per-source failure isolation

    @Test
    fun portfolioLoadFailure_stillShowsMarketStatus_andZeroesHero() = runTest {
        val assembler = makeAssembler(portfolio = { throw Boom() })

        val state = assembler.refresh()

        assertTrue(state.feed.any { it is HomeFeedItem.MarketStatus })
        assertEquals(Money(BigDecimal.ZERO), state.totalValue)
    }

    @Test
    fun portfolioLoadFailure_cancellationIsRethrownNotSwallowed() = runTest {
        val assembler = makeAssembler(portfolio = { throw CancellationException("cancelled") })

        assertFailsWith<CancellationException> { assembler.refresh() }
    }

    @Test
    fun incomeFailure_dropsOnlyDividendRow_marketStatusUnaffected() = runTest {
        val assembler = makeAssembler(incomeSummary = { throw Boom() })

        val state = assembler.refresh()

        assertFalse(state.feed.any { it is HomeFeedItem.Dividend })
        assertTrue(state.feed.any { it is HomeFeedItem.MarketStatus })
    }

    @Test
    fun earningsFailure_dropsOnlyEarningsRow_marketStatusUnaffected() = runTest {
        val assembler = makeAssembler(nextEarnings = { throw Boom() })

        val state = assembler.refresh()

        assertFalse(state.feed.any { it is HomeFeedItem.Earnings })
        assertTrue(state.feed.any { it is HomeFeedItem.MarketStatus })
    }

    // MARK: - Market open / closed

    @Test
    fun marketStatus_openDuringSession_nextTransitionIsSameDayClose() = runTest {
        val assembler = makeAssembler(now = MONDAY_NOON_ET)

        val state = assembler.refresh()

        val status = state.feed.first() as HomeFeedItem.MarketStatus
        assertTrue(status.isOpen)
        assertEquals(MONDAY_CLOSE_ET, status.nextTransitionEpochSeconds)
    }

    @Test
    fun marketStatus_closedOnWeekend_nextTransitionIsMondayOpen() = runTest {
        val assembler = makeAssembler(now = SATURDAY_NOON_ET)

        val state = assembler.refresh()

        val status = state.feed.first() as HomeFeedItem.MarketStatus
        assertFalse(status.isOpen)
        assertEquals(MONDAY_OPEN_ET, status.nextTransitionEpochSeconds)
    }

    // MARK: - Screener freshness

    @Test
    fun screenerRow_absentWhenSnapshotStale() = runTest {
        val stale = ScreenerSnapshot(
            tradingDay = "2020-01-01", scannedAtEpochSeconds = 0,
            rows = listOf(row("AAPL", rsi14 = 20.0)), failedSymbols = emptyList(),
        )
        val assembler = makeAssembler(screenerSnapshot = { stale }, now = MONDAY_NOON_ET)

        val state = assembler.refresh()

        assertFalse(state.feed.any { it is HomeFeedItem.ScreenerFresh })
    }

    @Test
    fun screenerRow_presentWhenFresh_withPresetAndMatchCount() = runTest {
        // RsiOversold (the first/default preset) matches rows with rsi14 < 30.
        val fresh = ScreenerSnapshot(
            tradingDay = "2026-11-23", scannedAtEpochSeconds = MONDAY_NOON_ET,
            rows = listOf(row("AAPL", rsi14 = 20.0), row("MSFT", rsi14 = 25.0), row("SPY", rsi14 = 80.0)),
            failedSymbols = emptyList(),
        )
        val assembler = makeAssembler(screenerSnapshot = { fresh }, now = MONDAY_NOON_ET)

        val state = assembler.refresh()

        val screenerItem = state.feed.filterIsInstance<HomeFeedItem.ScreenerFresh>().single()
        assertEquals(PresetScreen.RsiOversold, screenerItem.preset)
        assertEquals(2, screenerItem.matches)
    }

    // MARK: - Dividend row

    @Test
    fun dividendRow_absentWithNoUpcoming_incomeYTDStillPopulated() = runTest {
        val assembler = makeAssembler(incomeSummary = { HomeIncomeSummary(usd("50"), null) })

        val state = assembler.refresh()

        assertFalse(state.feed.any { it is HomeFeedItem.Dividend })
        assertEquals(usd("50"), state.incomeYTD)
    }

    @Test
    fun dividendRow_presentWithUpcoming_dayIsLocalDate() = runTest {
        val exDate = LocalDate(2026, 12, 1)
        val assembler = makeAssembler(
            incomeSummary = {
                HomeIncomeSummary(
                    receivedYTD = usd("0"),
                    nextDividend = HomeUpcomingDividend(symbol = "AAPL", estimatedAmount = usd("12.40"), estimatedExDate = exDate),
                )
            },
        )

        val state = assembler.refresh()

        val dividend = state.feed.filterIsInstance<HomeFeedItem.Dividend>().single()
        assertEquals("AAPL", dividend.symbol)
        assertEquals(usd("12.40"), dividend.amount)
        assertEquals(exDate, dividend.day)
    }

    // MARK: - Feed ordering

    @Test
    fun feedOrdering_isFixed_statusThenMoversThenEarningsThenDividendThenScreener() = runTest {
        val quotes = mapOf(
            "AAPL" to quote("AAPL", "110", "100"),
            "TSLA" to quote("TSLA", "90", "100"),
        )
        val freshSnapshot = ScreenerSnapshot(
            tradingDay = "2026-11-23", scannedAtEpochSeconds = MONDAY_NOON_ET,
            rows = listOf(row("AAPL", rsi14 = 20.0)), failedSymbols = emptyList(),
        )
        val msftEarnings = EarningsEvent(
            symbol = "MSFT", companyName = "Microsoft", day = "2026-11-24",
            session = EarningsSession.AfterClose, epsEstimate = null, epsActual = null,
        )
        val assembler = makeAssembler(
            quotes = quotes,
            ownSymbols = { setOf("AAPL", "TSLA") },
            incomeSummary = {
                HomeIncomeSummary(
                    receivedYTD = usd("0"),
                    nextDividend = HomeUpcomingDividend(
                        symbol = "AAPL", estimatedAmount = usd("5"), estimatedExDate = LocalDate(2026, 12, 1),
                    ),
                )
            },
            nextEarnings = { msftEarnings },
            screenerSnapshot = { freshSnapshot },
            now = MONDAY_NOON_ET,
        )

        val state = assembler.refresh()

        val kinds = state.feed.map { item ->
            when (item) {
                is HomeFeedItem.MarketStatus -> "status"
                is HomeFeedItem.TopGainer -> "gainer"
                is HomeFeedItem.TopLoser -> "loser"
                is HomeFeedItem.Earnings -> "earnings"
                is HomeFeedItem.Dividend -> "dividend"
                is HomeFeedItem.ScreenerFresh -> "screener"
            }
        }
        assertEquals(listOf("status", "gainer", "loser", "earnings", "dividend", "screener"), kinds)
    }

    // MARK: - Alerts

    @Test
    fun alertCount_countsOnlyArmedAlerts() = runTest {
        val armed = PriceAlert(
            symbol = "AAPL", condition = AlertCondition.PriceAbove(usd("200")), createdAtEpochSeconds = 1L,
        )
        val triggered = PriceAlert(
            symbol = "MSFT", condition = AlertCondition.PriceBelow(usd("100")), createdAtEpochSeconds = 1L,
            isTriggered = true,
        )
        val assembler = makeAssembler(alerts = { listOf(armed, triggered) })

        val state = assembler.refresh()

        assertEquals(1, state.alertCount) // only the armed one counts -- constraint 8
    }
}
