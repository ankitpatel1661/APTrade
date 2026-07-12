package com.aptrade.android.portfolio

import com.aptrade.android.l10n.LocalizationManager
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.l10n.AppLanguage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    // --- Every confirm string, format-built through the real Android trf() path, in every
    // shipped language (spec: "format mismatch fails in CI, not on a device"). A placeholder
    // count/type mismatch between a catalog entry and its trf() call site throws
    // MissingFormatArgumentException/IllegalFormatConversionException — this would have caught
    // that class of bug even though it turned out NOT to be UAT round 2's actual root cause
    // (that was a NumberFormatException from Money.usd parsing a money()-formatted "$"/","
    // string — see TradeSheetTest). ---

    @AfterTest
    fun resetLanguage() {
        LocalizationManager.current.value = AppLanguage.English
    }

    @Test
    fun `every confirm string format-builds without throwing in every shipped language`() {
        for (language in AppLanguage.entries) {
            LocalizationManager.current.value = language
            for (side in TradeSide.entries) {
                val title = confirmTitle(side, "10", "aapl")
                val message = confirmMessage(side, "\$1,052.63")
                val actionLabel = confirmActionLabel(side)
                assertTrue(title.isNotBlank(), "title blank for $language/$side")
                assertTrue(message.isNotBlank(), "message blank for $language/$side")
                assertTrue(actionLabel.isNotBlank(), "action label blank for $language/$side")
                // The quantity/symbol/estimate args must land in the template, not be dropped.
                assertTrue(title.contains("10"), "quantity missing from title for $language/$side")
                assertTrue(title.contains("AAPL"), "symbol missing from title for $language/$side")
                assertTrue(message.contains("1,052.63"), "estimate missing from message for $language/$side")
            }
        }
    }
}
