package com.aptrade.android.watchlist

import com.aptrade.shared.domain.AlertCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertFormStateTest {

    @Test
    fun aboveAndBelowValidOnParseablePrice() {
        assertTrue(AlertFormState(AlertKind.Above, "100.25", "5").isValid())
        assertTrue(AlertFormState(AlertKind.Below, "0.01", "5").isValid())
    }

    @Test
    fun aboveAndBelowRejectMalformedOrEmptyPrice() {
        assertFalse(AlertFormState(AlertKind.Above, "", "5").isValid())
        assertFalse(AlertFormState(AlertKind.Above, "abc", "5").isValid())
        assertFalse(AlertFormState(AlertKind.Below, "1.123456789", "5").isValid())  // 9 fraction digits
    }

    @Test
    fun aboveAndBelowAllowZeroOrNegativePriceText() {
        // Unlike percent, a price threshold has no positivity rule — mirrors macOS's
        // Decimal(string:) parse, which imposes none either.
        assertTrue(AlertFormState(AlertKind.Above, "0", "5").isValid())
        assertTrue(AlertFormState(AlertKind.Below, "-5", "5").isValid())
    }

    @Test
    fun percentRequiresPositiveMagnitude() {
        assertTrue(AlertFormState(AlertKind.Percent, "100", "5").isValid())
        assertFalse(AlertFormState(AlertKind.Percent, "100", "0").isValid())
        assertFalse(AlertFormState(AlertKind.Percent, "100", "-5").isValid())
        assertFalse(AlertFormState(AlertKind.Percent, "100", "").isValid())
        assertFalse(AlertFormState(AlertKind.Percent, "100", "abc").isValid())
    }

    @Test
    fun exactlyEightFractionDigitsIsAllowed() {
        assertTrue(AlertFormState(AlertKind.Above, "1.12345678", "5").isValid())
    }

    @Test
    fun toConditionBuildsPriceAboveFromPriceText() {
        // Money.amountText drops trailing zeros (known shared-core debt, same as
        // WatchlistViewModelTest's `quote()` helper note) — "150.50" round-trips as "150.5".
        val condition = AlertFormState(AlertKind.Above, "150.50", "5").toCondition()
        assertEquals(AlertCondition.PriceAbove::class, condition!!::class)
        assertEquals("150.5", (condition as AlertCondition.PriceAbove).threshold.amountText)
    }

    @Test
    fun toConditionBuildsPriceBelowFromPriceText() {
        val condition = AlertFormState(AlertKind.Below, "99.99", "5").toCondition()
        assertEquals(AlertCondition.PriceBelow::class, condition!!::class)
        assertEquals("99.99", (condition as AlertCondition.PriceBelow).threshold.amountText)
    }

    @Test
    fun toConditionBuildsPercentChangeFromPercentText() {
        val condition = AlertFormState(AlertKind.Percent, "100", "7.5").toCondition()
        assertEquals(AlertCondition.PercentChange::class, condition!!::class)
        assertEquals(7.5, (condition as AlertCondition.PercentChange).magnitude)
    }

    @Test
    fun toConditionIsNullWhenInvalid() {
        assertNull(AlertFormState(AlertKind.Above, "abc", "5").toCondition())
        assertNull(AlertFormState(AlertKind.Percent, "100", "0").toCondition())
    }
}
