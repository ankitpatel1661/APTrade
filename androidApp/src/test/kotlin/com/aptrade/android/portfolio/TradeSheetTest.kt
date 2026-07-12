package com.aptrade.android.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals

/** Regression coverage for the UAT round-2 confirm-trades crash: `estimateDisplay()` (the
 *  confirm layer's estimate line, `ConfirmLayer` in `TradeSheet.kt`) used to feed the already
 *  `money()`-formatted [com.aptrade.android.portfolio.TradeSheetInfo.priceText] straight into
 *  `Money.usd()`, which expects a raw decimal string. `Money.usd("$63,758.00")` threw
 *  `NumberFormatException: Invalid digit for radix $` from bignum's parser the instant the
 *  confirm layer first composed — crashing the app before any confirmation UI could appear.
 *
 *  These tests exercise `estimateDisplay`/`rawAmountText` directly (both `internal`, same module
 *  test source set — mirrors the existing `nearestIndex`/`crosshairReadout` pattern in
 *  `ui/chart/Charts.kt`) with the exact kind of formatted strings the view models actually
 *  produce, so a regression back to the naive `Money.usd(priceText)` call fails in CI rather
 *  than on a device. */
class TradeSheetTest {

    // --- rawAmountText: strips money()'s "$" prefix and "," grouping ---

    @Test
    fun `strips dollar prefix`() {
        assertEquals("315.32", rawAmountText("$315.32"))
    }

    @Test
    fun `strips thousands grouping commas`() {
        assertEquals("63758.00", rawAmountText("$63,758.00"))
    }

    @Test
    fun `preserves a leading minus sign`() {
        assertEquals("-12.50", rawAmountText("-$12.50"))
    }

    @Test
    fun `is a no-op on an already-raw decimal string`() {
        assertEquals("308.63", rawAmountText("308.63"))
    }

    // --- estimateDisplay: the actual crash site, driven with money()-formatted priceText ---

    @Test
    fun `computes the estimate from a dollar-and-comma formatted price without crashing`() {
        // The exact shape DetailViewModel/PortfolioViewModel hand to TradeSheetInfo.priceText:
        // com.aptrade.android.ui.money(quote.price.amountText).
        assertEquals("$127,516.00", estimateDisplay(priceText = "$63,758.00", quantityText = "2"))
    }

    @Test
    fun `computes the estimate from a plain-dollar formatted price`() {
        assertEquals("$1,576.60", estimateDisplay(priceText = "$315.32", quantityText = "5"))
    }

    @Test
    fun `returns the em dash when quantity is blank`() {
        assertEquals("—", estimateDisplay(priceText = "$315.32", quantityText = ""))
    }

    @Test
    fun `returns the em dash when quantity is zero or negative`() {
        assertEquals("—", estimateDisplay(priceText = "$315.32", quantityText = "0"))
        assertEquals("—", estimateDisplay(priceText = "$315.32", quantityText = "-1"))
    }

    @Test
    fun `returns the em dash when no live price is available`() {
        assertEquals("—", estimateDisplay(priceText = null, quantityText = "5"))
    }

    @Test
    fun `returns the em dash instead of crashing on malformed price text`() {
        assertEquals("—", estimateDisplay(priceText = "n/a", quantityText = "5"))
    }
}
