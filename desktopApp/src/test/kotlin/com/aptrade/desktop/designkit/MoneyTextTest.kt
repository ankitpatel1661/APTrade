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

    // Pins a rounding-crosses-zero boundary (6d.2 Task 4 triage item): signedMoney/formatMoney
    // each compute a signum, but at DIFFERENT points in the pipeline, and a tiny negative
    // input can flip from one to the other.
    //
    //   signedMoney("-0.001"):
    //     1. signedMoney's OWN signum check runs on the RAW input string: BigDecimal("-0.001")
    //        .signum() == -1, so the "> 0" test is false -> no "+" is prepended. Correct so far
    //        for a negative input.
    //     2. The formatted body comes from formatMoney("-0.001"), which computes its OWN,
    //        SEPARATE signum — not on the raw input, but on the value AFTER
    //        .setScale(2, HALF_EVEN): -0.001 rounds to 0.00, and BigDecimal("0.00").signum()
    //        is 0 (neither positive nor negative), so formatMoney's `negative` flag is false
    //        and NO minus sign is emitted either.
    //
    //   Net result: "$0.00" — unsigned-looking, even though the input was strictly negative.
    //   This is arguably the least-surprising choice (a signed "-$0.00" would look like a
    //   display bug), but it means signedMoney's and formatMoney's two independent signum
    //   computations can each answer a DIFFERENT question ("is the raw input negative" vs.
    //   "is the rounded value negative") and silently agree on "no sign" only because the
    //   rounded magnitude collapsed to exactly zero. Flagging for final-review triage rather
    //   than changing production code, per the task brief.
    @Test fun signedMoneyAtTheRoundingCrossesZeroBoundaryPinsCurrentBehavior() {
        assertEquals("$0.00", signedMoney("-0.001"))
    }
}
