package com.aptrade.desktop.portfolio

import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.TradeSide
import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** Accepts an optional leading '-', digits, and an optional '.' followed by 1-8 fraction
 *  digits. The leading '-' is matched so negative input is rejected explicitly (rather than
 *  falling through to "malformed") once parsed and found ≤ 0. */
private val QUANTITY_PATTERN = Regex("""-?\d+(\.\d{1,8})?""")

/** Pure, immutable trade-dialog state: quantity-text parsing and the derived cost estimate.
 *  No coroutines, no ViewModel — a small value type the dialog UI can render directly and
 *  PortfolioViewModel.buy/sell consume for their own parsing. Unit-tested in isolation. */
data class TradeFormState(
    val side: TradeSide,
    val priceText: String?,
    val quantityText: String,
) {
    /** The exact quantity as BigDecimal, or null when the text is empty, malformed,
     *  non-positive, or has more than 8 fraction digits. Never uses Double. */
    fun parsedQuantity(): BigDecimal? {
        val text = quantityText.trim()
        if (!QUANTITY_PATTERN.matches(text)) return null
        val value = try {
            BigDecimal.parseString(text)
        } catch (e: ArithmeticException) {
            return null
        } catch (e: NumberFormatException) {
            return null
        }
        if (value.isZero() || value.isNegative) return null
        return value
    }

    /** Quantity × price as an exact decimal string, or null when either side is missing. */
    fun estimateText(price: Money?): String? {
        val quantity = parsedQuantity() ?: return null
        val amount = price?.amount ?: return null
        return (amount * quantity).toStringExpanded()
    }

    /** True only when the quantity parses and a live price is present. */
    fun canSubmit(): Boolean = parsedQuantity() != null && priceText != null
}
