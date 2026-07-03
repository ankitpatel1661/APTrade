package com.aptrade.desktop.designkit

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
    @Test fun splitsWholeAndCents() =
        assertEquals(PriceParts("$", "308", "63"), splitPrice("308.63"))

    @Test fun groupsThousands() =
        assertEquals(PriceParts("$", "61,369", "17"), splitPrice("61369.17"))

    @Test fun padsSingleFractionDigit() =                     // Money.formatted drops trailing zeros
        assertEquals(PriceParts("$", "1,694", "20"), splitPrice("1694.2"))

    @Test fun padsMissingFraction() =
        assertEquals(PriceParts("$", "42", "00"), splitPrice("42"))

    @Test fun truncatesLongFractionToTwoDigits() =
        assertEquals(PriceParts("$", "0", "12"), splitPrice("0.129"))

    @Test fun keepsNegativeSignOnWhole() =
        assertEquals(PriceParts("$", "-1,234", "50"), splitPrice("-1234.5"))

    @Test fun formatsPositivePercentWithSign() = assertEquals("+4.84%", formatPercent(4.84))
    @Test fun formatsNegativePercent() = assertEquals("-0.13%", formatPercent(-0.13))
    @Test fun formatsZeroPercentWithoutSign() = assertEquals("0.00%", formatPercent(0.0))
    @Test fun formatsNullPercentAsDash() = assertEquals("—", formatPercent(null))
}
