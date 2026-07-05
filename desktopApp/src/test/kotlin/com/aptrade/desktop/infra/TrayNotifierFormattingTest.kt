package com.aptrade.desktop.infra

import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.TradeSide
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure-function tests for the strings TrayNotifier sends into
 *  `Notification(title, message, type)`. Extracted so the message shapes (which must
 *  stay identical to macOS's UserNotificationAlertNotifier) are testable without a
 *  TrayState/AWT dependency. */
class TrayNotifierFormattingTest {

    private fun money(value: String) = Money(BigDecimal.parseString(value), "USD")

    @Test
    fun `alert title is SYMBOL alert`() {
        assertEquals("AAPL alert", formatAlertTitle("AAPL"))
    }

    @Test
    fun `alert body is condition summary, em dash, now price, parenthesized change`() {
        val alert = PriceAlert(
            id = "alert-1",
            symbol = "AAPL",
            condition = AlertCondition.PriceAbove(money("200.00")),
            createdAtEpochSeconds = 1_700_000_000L,
        )
        val quote = Quote(symbol = "AAPL", price = money("229.35"), previousClose = money("225.00"), changePercent = 1.93)
        // Money.formatted has known trailing-zero debt (see designkit/Formatting.kt) — a
        // whole-number amount like "200.00" renders as "$200", not "$200.00". This test
        // pins the actual current behavior of the shared Money.formatted this notifier
        // reuses, not an idealized one.
        assertEquals(
            "Price above $200 — now $229.35 (+1.93%)",
            formatAlertBody(alert, quote),
        )
    }

    @Test
    fun `alert body formats a negative change with a leading minus`() {
        val alert = PriceAlert(
            id = "alert-2",
            symbol = "TSLA",
            condition = AlertCondition.PriceBelow(money("150.00")),
            createdAtEpochSeconds = 1_700_000_000L,
        )
        val quote = Quote(symbol = "TSLA", price = money("149.10"), previousClose = money("160.00"), changePercent = -6.81)
        assertEquals(
            "Price below $150 — now $149.1 (-6.81%)",
            formatAlertBody(alert, quote),
        )
    }

    @Test
    fun `alert body renders a PercentChange condition summary`() {
        val alert = PriceAlert(
            id = "alert-3",
            symbol = "SPY",
            condition = AlertCondition.PercentChange(5.0),
            createdAtEpochSeconds = 1_700_000_000L,
        )
        val quote = Quote(symbol = "SPY", price = money("500.00"), previousClose = money("475.00"), changePercent = 5.26)
        assertEquals(
            "Moves 5.0% in a day — now $500 (+5.26%)",
            formatAlertBody(alert, quote),
        )
    }

    @Test
    fun `order fill title is Order filled`() {
        assertEquals("Order filled", formatOrderFillTitle())
    }

    @Test
    fun `order fill body for a buy uses Bought verb`() {
        assertEquals(
            "Bought 10 AAPL for $2,293.50",
            formatOrderFillBody(TradeSide.Buy, "AAPL", "10", "$2,293.50"),
        )
    }

    @Test
    fun `order fill body for a sell uses Sold verb`() {
        assertEquals(
            "Sold 3 TSLA for $447.30",
            formatOrderFillBody(TradeSide.Sell, "TSLA", "3", "$447.30"),
        )
    }

    @Test
    fun `order fill body upper-cases the symbol`() {
        assertEquals(
            "Bought 1 BTC-USD for $50,000.00",
            formatOrderFillBody(TradeSide.Buy, "btc-usd", "1", "$50,000.00"),
        )
    }

    @Test
    fun `market opened title and body`() {
        assertEquals("Market open", formatMarketStatusTitle(opened = true))
        assertEquals("US equities are now open for regular trading.", formatMarketStatusBody(opened = true))
    }

    @Test
    fun `market closed title and body`() {
        assertEquals("Market closed", formatMarketStatusTitle(opened = false))
        assertEquals("US equities have closed for the day.", formatMarketStatusBody(opened = false))
    }

    @Test
    fun `digest title is Daily digest`() {
        assertEquals("Daily digest", formatDigestTitle())
    }
}
