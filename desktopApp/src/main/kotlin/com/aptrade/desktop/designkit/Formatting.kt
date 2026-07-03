package com.aptrade.desktop.designkit

/** Pieces of the SuperscriptPrice treatment: "$" + "61,369" + raised "17". */
data class PriceParts(val symbol: String, val whole: String, val fraction: String)

/** Splits Money.amountText (a plain decimal string, possibly with dropped trailing
 *  zeros — known Money.formatted debt) into grouped whole + exactly-2-digit fraction.
 *  Pure string math: the exact decimal never rides through Double. */
fun splitPrice(amountText: String, currencySymbol: String = "$"): PriceParts {
    val negative = amountText.startsWith("-")
    val unsigned = amountText.removePrefix("-")
    val whole = unsigned.substringBefore('.')
    val fraction = unsigned.substringAfter('.', "").padEnd(2, '0').take(2)
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return PriceParts(currencySymbol, (if (negative) "-" else "") + grouped, fraction)
}

/** "+4.84%" / "-0.13%" / "0.00%" / "—" — matches Swift Percentage.formatted. */
fun formatPercent(value: Double?): String {
    if (value == null) return "—"
    val rounded = kotlin.math.round(value * 100) / 100
    val body = buildString {
        val abs = kotlin.math.abs(rounded)
        val whole = abs.toLong()
        val cents = kotlin.math.round((abs - whole) * 100).toLong().toString().padStart(2, '0')
        // group thousands in the whole part (e.g. +1,234.50%)
        append(whole.toString().reversed().chunked(3).joinToString(",").reversed())
        append('.').append(cents)
    }
    val sign = if (rounded > 0) "+" else if (rounded < 0) "-" else ""
    return "$sign$body%"
}
