package com.aptrade.android.ui

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** [formatShares] display contract: at most 4 decimal places, HALF_AWAY_FROM_ZERO rounding,
 *  trailing zeros trimmed. Ledger precision is never touched — this is a render-only helper
 *  (PortfolioViewModel rows, IncomeScreen share labels). Transcribed from desktop's
 *  `designkit/ShareFormattingTest.kt` — same 5 cases, same expectations. */
class ShareFormattingTest {

    @Test
    fun wholeNumbersStayWhole() {
        assertEquals("10", formatShares(BigDecimal.parseString("10")))
        assertEquals("6", formatShares(BigDecimal.parseString("6")))
    }

    @Test
    fun longDripFractionRoundsToFourDecimals() {
        // 2/12 = 0.1666…67 at 38-digit engine precision → displays as 0.1667.
        assertEquals(
            "0.1667",
            formatShares(BigDecimal.parseString("0.16666666666666666666666666666666666667")),
        )
    }

    @Test
    fun trailingZerosAreTrimmed() {
        assertEquals("0.05", formatShares(BigDecimal.parseString("0.0500")))
        assertEquals("12.3", formatShares(BigDecimal.parseString("12.3000")))
    }

    @Test
    fun fourDecimalValuesPassThroughUnchanged() {
        assertEquals("0.0523", formatShares(BigDecimal.parseString("0.0523")))
    }

    @Test
    fun fifthDecimalRoundsHalfAwayFromZero() {
        assertEquals("1.2346", formatShares(BigDecimal.parseString("1.23455")))
    }
}
