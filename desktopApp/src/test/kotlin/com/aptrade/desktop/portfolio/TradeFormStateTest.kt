package com.aptrade.desktop.portfolio

import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.TradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradeFormStateTest {

    @Test
    fun parsesIntegerAndFractionalQuantities() {
        assertEquals("10", TradeFormState(TradeSide.Buy, "100.00", "10").parsedQuantity()?.toStringExpanded())
        assertEquals(
            "10.5",
            TradeFormState(TradeSide.Buy, "100.00", "10.5").parsedQuantity()?.toStringExpanded(),
        )
    }

    @Test
    fun rejectsZeroNegativeGarbageAndTooManyFractionDigits() {
        assertNull(TradeFormState(TradeSide.Buy, "100.00", "0").parsedQuantity())
        assertNull(TradeFormState(TradeSide.Buy, "100.00", "-1").parsedQuantity())
        assertNull(TradeFormState(TradeSide.Buy, "100.00", "abc").parsedQuantity())
        assertNull(TradeFormState(TradeSide.Buy, "100.00", "").parsedQuantity())
        assertNull(TradeFormState(TradeSide.Buy, "100.00", "1.123456789").parsedQuantity())  // 9 fraction digits
        // exactly 8 fraction digits is allowed
        assertEquals(
            "1.12345678",
            TradeFormState(TradeSide.Buy, "100.00", "1.12345678").parsedQuantity()?.toStringExpanded(),
        )
    }

    @Test
    fun estimateIsQuantityTimesPriceExactText() {
        val form = TradeFormState(TradeSide.Buy, "100.25", "10.5")
        assertEquals("1052.625", form.estimateText(Money.usd("100.25")))
    }

    @Test
    fun nullPriceYieldsNullEstimate() {
        val form = TradeFormState(TradeSide.Buy, null, "10")
        assertNull(form.estimateText(null))
    }

    @Test
    fun canSubmitOnlyWhenParseableAndPricePresent() {
        assertTrue(TradeFormState(TradeSide.Buy, "100.00", "10").canSubmit())
        assertFalse(TradeFormState(TradeSide.Buy, null, "10").canSubmit())          // no price
        assertFalse(TradeFormState(TradeSide.Buy, "100.00", "0").canSubmit())       // unparseable qty
        assertFalse(TradeFormState(TradeSide.Buy, "100.00", "").canSubmit())        // empty qty
    }
}
