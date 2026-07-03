package com.aptrade.desktop.designkit

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTextTest {
    @Test fun formatsGroupingAndTwoDecimals() {
        assertEquals("$1,234.50", formatMoney("1234.5"))
        assertEquals("$1,000,000.00", formatMoney("1000000"))
        assertEquals("$0.00", formatMoney("0"))
    }
    @Test fun roundsHalfEven() {
        assertEquals("$2.00", formatMoney("2.005"))     // 0 is even → stays
        assertEquals("$35,040.46", formatMoney("35040.455"))  // 5 is odd → up
        assertEquals("-$31.16", formatMoney("-31.155"))
    }
    @Test fun minusComesFromFormatterPlusFromSignedOnly() {
        assertEquals("-$1.00", formatMoney("-1"))
        assertEquals("+$254.46", signedMoney("254.455"))
        assertEquals("-$31.16", signedMoney("-31.155"))
        assertEquals("$0.00", signedMoney("0"))
    }
}
