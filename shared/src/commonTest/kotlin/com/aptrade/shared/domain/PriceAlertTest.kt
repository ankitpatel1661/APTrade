package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun quote(price: String, changePercent: Double = 0.0) = Quote(
    symbol = "AAPL",
    price = Money.usd(price),
    previousClose = Money.usd(price),
    changePercent = changePercent,
)

class PriceAlertTest {

    // -- isMet: PriceAbove --

    @Test
    fun priceAboveIsMetWhenQuoteExceedsThreshold() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        assertTrue(alert.isMet(quote("201.00")))
    }

    @Test
    fun priceAboveIsMetAtExactBoundaryEquality() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        assertTrue(alert.isMet(quote("200.00")))
    }

    @Test
    fun priceAboveIsNotMetWhenQuoteBelowThreshold() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 0L,
        )
        assertFalse(alert.isMet(quote("199.99")))
    }

    // -- isMet: PriceBelow --

    @Test
    fun priceBelowIsMetWhenQuoteUnderThreshold() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceBelow(Money.usd("100.00")),
            createdAtEpochSeconds = 0L,
        )
        assertTrue(alert.isMet(quote("99.00")))
    }

    @Test
    fun priceBelowIsMetAtExactBoundaryEquality() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceBelow(Money.usd("100.00")),
            createdAtEpochSeconds = 0L,
        )
        assertTrue(alert.isMet(quote("100.00")))
    }

    @Test
    fun priceBelowIsNotMetWhenQuoteAboveThreshold() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceBelow(Money.usd("100.00")),
            createdAtEpochSeconds = 0L,
        )
        assertFalse(alert.isMet(quote("100.01")))
    }

    // -- isMet: PercentChange (both directions + boundary) --

    @Test
    fun percentChangeIsMetOnPositiveDirection() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PercentChange(5.0),
            createdAtEpochSeconds = 0L,
        )
        assertTrue(alert.isMet(quote("100.00", changePercent = 6.0)))
    }

    @Test
    fun percentChangeIsMetOnNegativeDirection() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PercentChange(5.0),
            createdAtEpochSeconds = 0L,
        )
        assertTrue(alert.isMet(quote("100.00", changePercent = -6.0)))
    }

    @Test
    fun percentChangeIsMetAtExactBoundaryEqualityEitherSign() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PercentChange(5.0),
            createdAtEpochSeconds = 0L,
        )
        assertTrue(alert.isMet(quote("100.00", changePercent = 5.0)))
        assertTrue(alert.isMet(quote("100.00", changePercent = -5.0)))
    }

    @Test
    fun percentChangeIsNotMetBelowMagnitudeEitherSign() {
        val alert = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PercentChange(5.0),
            createdAtEpochSeconds = 0L,
        )
        assertFalse(alert.isMet(quote("100.00", changePercent = 4.99)))
        assertFalse(alert.isMet(quote("100.00", changePercent = -4.99)))
    }

    // -- triggered() --

    @Test
    fun triggeredReturnsCopyWithIsTriggeredTrueAndSameIdentity() {
        val alert = PriceAlert(
            id = "alert-1",
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("200.00")),
            createdAtEpochSeconds = 42L,
        )
        val result = alert.triggered()

        assertTrue(result.isTriggered)
        assertEquals(alert.id, result.id)
        assertEquals(alert.symbol, result.symbol)
        assertEquals(alert.condition, result.condition)
        assertEquals(alert.createdAtEpochSeconds, result.createdAtEpochSeconds)
    }

    // -- summary() --

    @Test
    fun priceAboveSummaryMatchesMacOSPhrasing() {
        assertEquals(
            "Price above ${Money.usd("200.00").formatted}",
            AlertCondition.PriceAbove(Money.usd("200.00")).summary,
        )
    }

    @Test
    fun priceBelowSummaryMatchesMacOSPhrasing() {
        assertEquals(
            "Price below ${Money.usd("100.00").formatted}",
            AlertCondition.PriceBelow(Money.usd("100.00")).summary,
        )
    }

    @Test
    fun percentChangeSummaryMatchesMacOSPhrasingAndUsesAbsoluteMagnitude() {
        assertEquals("Moves 5.0% in a day", AlertCondition.PercentChange(5.0).summary)
        assertEquals("Moves 5.0% in a day", AlertCondition.PercentChange(-5.0).summary)
    }

    // -- id generation precedent --

    @Test
    fun generatedAlertIdsAreUnique() {
        val ids = (1..20).map { generateAlertId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun defaultConstructorAssignsDifferentIdsToDifferentAlerts() {
        val a = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("1.00")),
            createdAtEpochSeconds = 0L,
        )
        val b = PriceAlert(
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(Money.usd("1.00")),
            createdAtEpochSeconds = 0L,
        )
        assertNotEquals(a.id, b.id)
    }
}
