package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)
private fun bpoint(epoch: Long, close: String) = PricePoint(epoch, Money.usd(close))
private fun buy(epoch: Long, price: String, quantity: String, id: String = "txn-$epoch") =
    Transaction(id, "AAPL", TradeSide.Buy, qty(quantity), Money.usd(price), epoch)
private fun sell(epoch: Long, price: String, quantity: String, id: String = "txn-$epoch") =
    Transaction(id, "AAPL", TradeSide.Sell, qty(quantity), Money.usd(price), epoch)
private fun dividend(epoch: Long, amountPerShare: String, shares: String, id: String = "txn-$epoch") =
    Transaction(id, "AAPL", TradeSide.Dividend, qty(shares), Money.usd(amountPerShare), epoch)

class BenchmarkTwinTest {

    @Test
    fun singleBuyAccumulatesUnitsAndValuesAtLaterClose() {
        // $1000 buy at benchmark close 100 -> U = 10. Valued later at close 110 -> 1100.
        val transactions = listOf(buy(epoch = 100, price = "100", quantity = "10"))
        val benchmarkPoints = listOf(bpoint(100, "100"), bpoint(200, "110"))
        val cash = Money.usd("0")

        val series = benchmarkTwinSeries(transactions, benchmarkPoints, cash, curveDates = listOf(100L, 200L))

        assertEquals(2, series!!.size)
        assertEquals(BigDecimal.parseString("1000"), series[0].amount)
        assertEquals(BigDecimal.parseString("1100"), series[1].amount)
    }

    @Test
    fun twoBuysAtDifferentClosesAccumulateUnitsExactly() {
        // Buy $1000 at close 100 -> U=10. Buy $500 at close 50 -> U += 10 -> U=20.
        val transactions = listOf(
            buy(epoch = 100, price = "100", quantity = "10", id = "txn-1"),
            buy(epoch = 200, price = "50", quantity = "10", id = "txn-2"),
        )
        val benchmarkPoints = listOf(bpoint(100, "100"), bpoint(200, "50"), bpoint(300, "10"))
        val cash = Money.usd("0")

        val series = benchmarkTwinSeries(transactions, benchmarkPoints, cash, curveDates = listOf(300L))

        // U = 20, close@300 = 10 -> 200
        assertEquals(BigDecimal.parseString("200"), series!![0].amount)
    }

    @Test
    fun sellClampsUnitsAtZeroNeverNegative() {
        // Buy $1000 at close 100 -> U=10 (worth 1000). Sell proceeds of $5000 at close 100
        // would imply selling 50 units, far more than the 10 held -> clamp to 0, not negative.
        val transactions = listOf(
            buy(epoch = 100, price = "100", quantity = "10", id = "txn-1"),
            sell(epoch = 200, price = "100", quantity = "50", id = "txn-2"),
        )
        val benchmarkPoints = listOf(bpoint(100, "100"), bpoint(200, "100"))
        val cash = Money.usd("500")

        val series = benchmarkTwinSeries(transactions, benchmarkPoints, cash, curveDates = listOf(200L))

        // U clamped to 0 -> value is just cash.
        assertEquals(BigDecimal.parseString("500"), series!![0].amount)
    }

    @Test
    fun transactionBeforeFirstBenchmarkCandleUsesFirstCandleClose() {
        // Transaction at epoch 50 predates the first benchmark candle at epoch 100;
        // per the documented approximation, the first candle's close (100) is used.
        val transactions = listOf(buy(epoch = 50, price = "100", quantity = "10"))
        val benchmarkPoints = listOf(bpoint(100, "100"), bpoint(200, "200"))
        val cash = Money.usd("0")

        val series = benchmarkTwinSeries(transactions, benchmarkPoints, cash, curveDates = listOf(200L))

        // amount = 1000, closeAt(50) falls back to first candle close 100 -> U = 10.
        // valued at closeAt(200) = 200 -> 2000.
        assertEquals(BigDecimal.parseString("2000"), series!![0].amount)
    }

    @Test
    fun forwardFillUsesEarlierCloseBetweenCandles() {
        val transactions = listOf(buy(epoch = 100, price = "100", quantity = "10"))
        val benchmarkPoints = listOf(bpoint(100, "100"), bpoint(300, "300"))
        val cash = Money.usd("0")

        // curveDate 200 lies strictly between the two candles -> forward-filled to the
        // candle at epoch 100 (close 100), not the later one.
        val series = benchmarkTwinSeries(transactions, benchmarkPoints, cash, curveDates = listOf(200L))

        assertEquals(BigDecimal.parseString("1000"), series!![0].amount)
    }

    @Test
    fun emptyBenchmarkPointsReturnsNull() {
        val transactions = listOf(buy(epoch = 100, price = "100", quantity = "10"))
        val series = benchmarkTwinSeries(transactions, emptyList(), Money.usd("0"), curveDates = listOf(100L))
        assertNull(series)
    }

    @Test
    fun outputSizeAlwaysMatchesCurveDatesSize() {
        val transactions = listOf(buy(epoch = 100, price = "100", quantity = "10"))
        val benchmarkPoints = listOf(bpoint(100, "100"))
        val curveDates = listOf(100L, 150L, 200L, 250L)

        val series = benchmarkTwinSeries(transactions, benchmarkPoints, Money.usd("0"), curveDates)

        assertEquals(curveDates.size, series!!.size)
    }

    @Test
    fun dividendTransactionDoesNotAlterUnitsCashAlreadyReflectsTheCredit() {
        // Buy $1000 at close 100 -> U = 10. A $0.50/share dividend on the 10 held shares
        // must NOT itself change U — a non-exhaustive `side == Buy ? +amount : -amount`
        // reconstruction would wrongly treat the dividend as a benchmark trade (subtracting
        // units, as `Sell` does). Mirrors the Swift equity-curve regression (buy-then-
        // dividend, PortfolioEquityCurveTests.swift) adapted to this twin: [cash] here is the
        // CURRENT portfolio cash (see class KDoc) — it already includes the dividend's $5
        // credit, since the twin never re-derives historical cash balances. So the dividend's
        // only visible effect on the twin's value is via the externally-supplied [cash], not
        // via [units].
        val transactions = listOf(
            buy(epoch = 100, price = "100", quantity = "10", id = "txn-1"),
            dividend(epoch = 150, amountPerShare = "0.50", shares = "10", id = "txn-2"),
        )
        val benchmarkPoints = listOf(bpoint(100, "100"), bpoint(200, "110"))
        val cash = Money.usd("5")   // simulates portfolio.cash already having received the dividend credit

        val series = benchmarkTwinSeries(transactions, benchmarkPoints, cash, curveDates = listOf(200L))

        // U unaffected by the dividend: still 10. Valued at close 110 -> 1100, plus cash 5 -> 1105.
        assertEquals(BigDecimal.parseString("1105"), series!![0].amount)
    }

    @Test
    fun divisionUsesMoneyMathModeAndDoesNotThrowOnNonTerminatingDivision() {
        // 1000 / 3 doesn't terminate in plain decimal division; without an explicit
        // DecimalMode ionspin BigDecimal throws. This pins that MONEY_MATH is applied.
        val transactions = listOf(buy(epoch = 100, price = "1", quantity = "1000"))
        val benchmarkPoints = listOf(bpoint(100, "3"))

        val series = benchmarkTwinSeries(transactions, benchmarkPoints, Money.usd("0"), curveDates = listOf(100L))

        // No exception thrown; a defined (rounded) value is produced.
        assertEquals(true, series!![0].amount.compareTo(BigDecimal.ZERO) > 0)
    }
}
