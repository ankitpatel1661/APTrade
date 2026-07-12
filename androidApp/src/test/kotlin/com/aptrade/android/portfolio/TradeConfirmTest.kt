package com.aptrade.android.portfolio

import com.aptrade.shared.domain.TradeSide
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Android port of desktop's `TradeConfirmTest`
 *  (`desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/TradeConfirmTest.kt`) — same
 *  cases, ported verbatim against the Android `TradeConfirm.kt` (which resolves the identical
 *  shared L10n keys through Android's own `tr`/`trf`). */
class TradeConfirmTest {

    // --- attemptSubmit gating truth table (mirrors desktop TradeSheet.swift:60-66) ---

    @Test
    fun `confirmTrades on shows the confirm layer`() {
        assertEquals(SubmitAction.ShowConfirm, attemptSubmit(confirmTrades = true))
    }

    @Test
    fun `confirmTrades off submits directly`() {
        assertEquals(SubmitAction.SubmitDirectly, attemptSubmit(confirmTrades = false))
    }

    // --- confirmTitle (L10n .confirmBuyTitleFormat/.confirmSellTitleFormat) ---

    @Test
    fun `buy title substitutes quantity then uppercased symbol`() {
        assertEquals("Buy 10 AAPL?", confirmTitle(TradeSide.Buy, "10", "aapl"))
    }

    @Test
    fun `sell title substitutes quantity then uppercased symbol`() {
        assertEquals("Sell 2.5 BTC?", confirmTitle(TradeSide.Sell, "2.5", "btc"))
    }

    @Test
    fun `title uppercases an already-uppercase symbol without change`() {
        assertEquals("Buy 1 NVDA?", confirmTitle(TradeSide.Buy, "1", "NVDA"))
    }

    // --- confirmMessage (L10n .confirmEstimateFormat) ---

    @Test
    fun `buy message uses the estimated cost label`() {
        assertEquals("Estimated cost: \$1,052.63", confirmMessage(TradeSide.Buy, "\$1,052.63"))
    }

    @Test
    fun `sell message uses the estimated proceeds label`() {
        assertEquals("Estimated proceeds: \$500.00", confirmMessage(TradeSide.Sell, "\$500.00"))
    }

    @Test
    fun `message passes through the em dash placeholder untouched`() {
        assertEquals("Estimated cost: —", confirmMessage(TradeSide.Buy, "—"))
    }

    // --- confirmActionLabel (L10n .confirmBuy/.confirmSell) ---

    @Test
    fun `buy action label is Confirm Buy`() {
        assertEquals("Confirm Buy", confirmActionLabel(TradeSide.Buy))
    }

    @Test
    fun `sell action label is Confirm Sell`() {
        assertEquals("Confirm Sell", confirmActionLabel(TradeSide.Sell))
    }
}
