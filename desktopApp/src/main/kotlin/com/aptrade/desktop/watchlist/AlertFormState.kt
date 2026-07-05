package com.aptrade.desktop.watchlist

import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.Money
import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** Accepts an optional leading '-', digits, and an optional '.' followed by 1-8 fraction
 *  digits — the same shape TradeFormState's QUANTITY_PATTERN validates, reused here for a
 *  decimal price/percent field rather than a quantity. */
private val DECIMAL_PATTERN = Regex("""-?\d+(\.\d{1,8})?""")

/** The three alert kinds the sheet's segmented control offers, mirroring
 *  `PriceAlertSheet.swift`'s view-local `Kind` enum (not a domain type — it only exists to
 *  drive which labeled field is shown and which [AlertCondition] gets built). */
enum class AlertKind { Above, Below, Percent }

/** Pure, immutable price-alert-sheet state: which field is active and whether its text
 *  parses to a valid decimal. No coroutines, no ViewModel — mirrors [com.aptrade.desktop
 *  .portfolio.TradeFormState]'s precedent so the "Add Alert" button's enabled state and the
 *  condition it builds both derive from one small value type, unit-tested in isolation.
 *
 *  Percent input is entered as a plain magnitude (e.g. "5" for a 5% move) — negative and
 *  zero values are rejected the same way TradeFormState rejects non-positive quantities,
 *  since a 0% or negative-magnitude alert can never usefully fire ([AlertCondition
 *  .PercentChange] compares `abs` magnitudes). Price fields only reject malformed text —
 *  zero/negative price thresholds are left to the user's judgment (a "price below $0" alert
 *  is legal, if useless, and macOS's Decimal(string:) parse imposes no positivity rule either). */
data class AlertFormState(
    val kind: AlertKind,
    val priceText: String,
    val percentText: String,
) {
    private fun parsedDecimal(text: String): BigDecimal? {
        val trimmed = text.trim()
        if (!DECIMAL_PATTERN.matches(trimmed)) return null
        return try {
            BigDecimal.parseString(trimmed)
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** True once the active field (price for above/below, percent for percent-move) parses
     *  to a valid decimal — percent additionally requires a strictly positive magnitude. */
    fun isValid(): Boolean = when (kind) {
        AlertKind.Above, AlertKind.Below -> parsedDecimal(priceText) != null
        AlertKind.Percent -> parsedDecimal(percentText)?.let { !it.isZero() && !it.isNegative } ?: false
    }

    /** Builds the [AlertCondition] for the current kind/text, or null when [isValid] is
     *  false. Callers should gate on [isValid] before calling (the "Add Alert" button does),
     *  but this stays defensive rather than throwing. */
    fun toCondition(): AlertCondition? = when (kind) {
        AlertKind.Above -> parsedDecimal(priceText)?.let { AlertCondition.PriceAbove(Money(it)) }
        AlertKind.Below -> parsedDecimal(priceText)?.let { AlertCondition.PriceBelow(Money(it)) }
        AlertKind.Percent -> parsedDecimal(percentText)
            ?.takeIf { !it.isZero() && !it.isNegative }
            ?.let { AlertCondition.PercentChange(it.doubleValue(false)) }
    }
}
