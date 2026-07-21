package com.aptrade.android.ui

import com.aptrade.shared.application.QuoteError
import com.aptrade.shared.domain.TradeError
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import java.text.NumberFormat
import java.util.Locale

fun QuoteError.userMessage(): String = when (this) {
    is QuoteError.RateLimited -> "Rate limited by the data provider — try again shortly."
    is QuoteError.NotFound -> "No data found."
    is QuoteError.Network -> "Network error — check your connection and retry."
}

fun TradeError.userMessage(): String = when (this) {
    is TradeError.InsufficientFunds -> "Insufficient funds."
    is TradeError.InsufficientShares -> "Insufficient shares."
    is TradeError.InvalidQuantity -> "Enter a valid quantity."
}

/** en_US currency formatting of a lossless decimal string (`Money.amountText`): grouped
 *  thousands, exactly 2 fraction digits, "$" prefix — e.g. "35040.46" -> "$35,040.46",
 *  "-12.5" -> "-$12.50". The exact decimal never rides through Double: we parse the string
 *  into a [java.math.BigDecimal] and hand that straight to [NumberFormat]. */
fun money(amountText: String): String =
    NumberFormat.getCurrencyInstance(Locale.US).format(java.math.BigDecimal(amountText))

/** [money] with a forced "+" when the amount is strictly positive (day change, unrealized/
 *  realized P&L). Zero and negatives keep their natural sign. */
fun signedMoney(amountText: String): String {
    val formatted = money(amountText)
    return if (java.math.BigDecimal(amountText).signum() > 0) "+$formatted" else formatted
}

/** "+4.84%" / "-0.13%" / "0.00%" / "—" — matches the desktop `formatPercent` / Swift
 *  `Percentage.formatted`: rounded to 2 decimals, sign forced only when non-zero. */
fun formatPercent(value: Double?): String {
    if (value == null) return "—"
    val rounded = kotlin.math.round(value * 100) / 100
    val body = buildString {
        val abs = kotlin.math.abs(rounded)
        val whole = abs.toLong()
        val cents = kotlin.math.round((abs - whole) * 100).toLong().toString().padStart(2, '0')
        append(whole.toString().reversed().chunked(3).joinToString(",").reversed())
        append('.').append(cents)
    }
    val sign = if (rounded > 0) "+" else if (rounded < 0) "-" else ""
    return "$sign$body%"
}

/** Share quantities for DISPLAY: rounded to at most 4 decimal places (same
 *  HALF_AWAY_FROM_ZERO mode as Money's display rounding), trailing zeros trimmed —
 *  "10", "0.05", "0.1667". Display-only: ledger math (PortfolioViewModel, IncomeViewModel)
 *  keeps full precision, so a DRIP fraction like 0.16666…67 renders "0.1667" without
 *  touching the stored value. Android twin of desktop `designkit/Formatting.kt`'s
 *  `formatShares` — identical algorithm and test vectors (see `ShareFormattingTest`). */
fun formatShares(quantity: BigDecimal): String {
    val rounded = quantity.roundToDigitPositionAfterDecimalPoint(4, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
    val text = rounded.toStringExpanded()
    return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
}
