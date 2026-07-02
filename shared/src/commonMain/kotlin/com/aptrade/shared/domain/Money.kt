package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.RoundingMode

data class Money(
    val amount: BigDecimal,
    val currencyCode: String = "USD",
) {
    operator fun plus(other: Money): Money {
        require(currencyCode == other.currencyCode) { "currency mismatch" }
        return Money(amount + other.amount, currencyCode)
    }

    operator fun minus(other: Money): Money {
        require(currencyCode == other.currencyCode) { "currency mismatch" }
        return Money(amount - other.amount, currencyCode)
    }

    // NOTE: locale-accurate currency formatting is a presentation concern handled
    // by each native UI. This is a minimal display helper for the skeleton harness.
    val formatted: String
        get() {
            val rounded = amount.roundToDigitPositionAfterDecimalPoint(
                2, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO,
            )
            return if (currencyCode == "USD") {
                "$" + rounded.toStringExpanded()
            } else {
                rounded.toStringExpanded() + " " + currencyCode
            }
        }

    /**
     * Plain decimal string of the exact amount (e.g. "229.35") — the lossless
     * cross-language bridge format (Swift reads it via Decimal(string:)).
     */
    val amountText: String
        get() = amount.toStringExpanded()

    companion object {
        fun usd(value: String): Money = Money(BigDecimal.parseString(value), "USD")
    }
}
