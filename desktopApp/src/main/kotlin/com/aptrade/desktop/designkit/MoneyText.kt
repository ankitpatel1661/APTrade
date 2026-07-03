package com.aptrade.desktop.designkit

import java.math.BigDecimal
import java.math.RoundingMode

/** macOS Money.formatted parity: en_US currency — "$", comma grouping, exactly 2 decimals,
 *  HALF_EVEN (NSNumberFormatter default), minus before the "$". */
fun formatMoney(amountText: String): String {
    val value = BigDecimal(amountText).setScale(2, RoundingMode.HALF_EVEN)
    val negative = value.signum() < 0
    val abs = value.abs().toPlainString()
    val whole = abs.substringBefore('.')
    val fraction = abs.substringAfter('.')
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-$" else "$") + grouped + "." + fraction
}

/** "+" only for strictly positive values (call-site convention on macOS); zero unsigned. */
fun signedMoney(amountText: String): String {
    val formatted = formatMoney(amountText)
    return if (BigDecimal(amountText).signum() > 0) "+$formatted" else formatted
}
