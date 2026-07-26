package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M11.2 Task 3. Kotlin twin of `Sources/APTradeApp/StartingBalanceInput.swift`, with TWO
 * deliberate divergences documented on [AmountInput] itself: the range is REQUIRED (Swift
 * defaults it, which is how the starting-balance bounds ended up policing goal targets —
 * carry-notes §1.5), and parsing is locale-INDEPENDENT (Kotlin commonMain has no NumberFormat,
 * and Swift's locale-aware parser has a recorded round-trip gap — carry-notes §4).
 */
class AmountInputTest {
    private val goalIncome = GoalKind.Income.targetRange
    private val goalValue = GoalKind.Value.targetRange
    private val balance = AmountInput.STARTING_BALANCE_RANGE

    @Test
    fun parsesPlainDigits() {
        assertEquals(Money.usd("25000"), AmountInput.parse("25000", balance))
    }

    @Test
    fun stripsGroupingSeparatorsAndWhitespace() {
        assertEquals(Money.usd("1250000"), AmountInput.parse(" 1,250,000 ", balance))
    }

    @Test
    fun acceptsASingleDecimalPoint() {
        assertEquals(Money.usd("1500.50"), AmountInput.parse("1500.50", balance))
    }

    @Test
    fun normalisesALeadingOrTrailingDecimalPoint() {
        val tiny = AmountRange(BigDecimal.parseString("0"), BigDecimal.parseString("1"))
        assertEquals(Money.usd("1500"), AmountInput.parse("1500.", balance))
        assertEquals(Money.usd("0.5"), AmountInput.parse(".5", tiny))
    }

    @Test
    fun rejectsEmptyGarbageAndMultipleDecimalPoints() {
        assertNull(AmountInput.parse("", balance))
        assertNull(AmountInput.parse("   ", balance))
        assertNull(AmountInput.parse("abc", balance))
        assertNull(AmountInput.parse("1.2.3", balance))
        assertNull(AmountInput.parse("-5000", balance))
        assertNull(AmountInput.parse(".", balance))
    }

    @Test
    fun enforcesTheSuppliedRangeInclusively() {
        assertEquals(Money.usd("1000"), AmountInput.parse("1000", balance))
        assertEquals(Money.usd("10000000"), AmountInput.parse("10000000", balance))
        assertNull(AmountInput.parse("999", balance))
        assertNull(AmountInput.parse("10000001", balance))
    }

    /** Carry-notes §1.5: a "$50/month in dividends" goal is $600/yr — an entirely ordinary
     *  first goal that the starting-balance range made unsettable. */
    @Test
    fun anIncomeGoalUnderOneThousandIsSettable() {
        assertEquals(Money.usd("600"), AmountInput.parse("600", goalIncome))
        assertNull(AmountInput.parse("600", balance))
    }

    /** …and the same ruling's other half: a value goal past $10M must be settable. */
    @Test
    fun aValueGoalAboveTenMillionIsSettable() {
        assertEquals(Money.usd("50000000"), AmountInput.parse("50000000", goalValue))
        assertNull(AmountInput.parse("50000000", balance))
    }

    @Test
    fun perKindRangesAreTheExactRuledBounds() {
        assertEquals(Money.usd("100"), AmountInput.parse("100", goalIncome))
        assertEquals(Money.usd("1000000"), AmountInput.parse("1000000", goalIncome))
        assertNull(AmountInput.parse("99", goalIncome))
        assertNull(AmountInput.parse("1000001", goalIncome))
        assertEquals(Money.usd("1000"), AmountInput.parse("1000", goalValue))
        assertEquals(Money.usd("100000000"), AmountInput.parse("100000000", goalValue))
        assertNull(AmountInput.parse("999", goalValue))
        assertNull(AmountInput.parse("100000001", goalValue))
    }
}
