package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** An inclusive amount interval.
 *
 *  Deliberately NOT `ClosedRange<BigDecimal>`: ionspin's `BigDecimal` implements
 *  `Comparable<Any>`, not `Comparable<BigDecimal>`, so Kotlin's `rangeTo`/`coerceIn`
 *  (`<T : Comparable<T>>`) do not resolve for it and `min..max` will not compile. The `<`/`>`
 *  operators DO work, which is all [contains] needs. */
data class AmountRange(val min: BigDecimal, val max: BigDecimal) {
    fun contains(value: BigDecimal): Boolean = value >= min && value <= max
}

/**
 * Parses and range-validates a money amount typed into a field. Pure — no framework, no locale
 * object, usable from every platform's UI layer.
 *
 * Kotlin twin of `Sources/APTradeApp/StartingBalanceInput.swift`, with TWO recorded divergences:
 *
 * 1. **[range] is REQUIRED, not defaulted.** Swift defaults it to the starting-balance bounds,
 *    which is exactly how those bounds ended up policing goal targets (carry-notes §1.5). A
 *    defaulted range whose omission silently validates the wrong quantity is a re-armed bug with
 *    a compiler that will never complain, so every caller names its range.
 * 2. **Parsing is locale-INDEPENDENT.** `commonMain` has no `NumberFormat`, and Swift's
 *    locale-aware parser has a recorded round-trip gap (an amount formatted with an
 *    unconditional "." is re-read as a grouping separator under `de_DE` — carry-notes §4).
 *    Here `,`, spaces, non-breaking spaces and apostrophes are grouping separators and are
 *    stripped; `.` is the one decimal point. USD-only per the milestone constraint.
 */
object AmountInput {
    /** The reset flow's opening-balance bounds — $1,000 … $10,000,000. */
    val STARTING_BALANCE_RANGE: AmountRange = AmountRange(
        BigDecimal.parseString("1000"),
        BigDecimal.parseString("10000000"),
    )

    fun parse(text: String, range: AmountRange): Money? {
        val builder = StringBuilder()
        for (ch in text) {
            when {
                ch in '0'..'9' -> builder.append(ch)
                ch == '.' -> builder.append(ch)
                ch == ',' || ch == ' ' || ch == ' ' || ch == '\'' -> Unit
                else -> return null
            }
        }
        var cleaned = builder.toString()
        if (cleaned.count { it == '.' } > 1) return null
        if (cleaned.endsWith('.')) cleaned = cleaned.dropLast(1)
        if (cleaned.startsWith('.')) cleaned = "0$cleaned"
        if (cleaned.isEmpty()) return null

        val amount = try {
            BigDecimal.parseString(cleaned)
        } catch (e: RuntimeException) {
            return null
        }
        if (!range.contains(amount)) return null
        return Money(amount, "USD")
    }
}
