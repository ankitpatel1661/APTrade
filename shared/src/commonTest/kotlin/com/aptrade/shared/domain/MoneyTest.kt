package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun addsSameCurrency() {
        assertEquals(Money.usd("15.00"), Money.usd("10.00") + Money.usd("5.00"))
    }

    @Test
    fun subtractsSameCurrency() {
        assertEquals(Money.usd("5.00"), Money.usd("10.00") - Money.usd("5.00"))
    }

    @Test
    fun rejectsCurrencyMismatchOnAdd() {
        assertFailsWith<IllegalArgumentException> {
            Money(BigDecimal.parseString("1.00"), "USD") +
                Money(BigDecimal.parseString("1.00"), "EUR")
        }
    }

    @Test
    fun formatsUsdWithTwoDecimals() {
        assertEquals("$1234.5", Money.usd("1234.50").formatted)
    }

    @Test
    fun amountTextIsPlainDecimalString() {
        assertEquals("229.35", Money.usd("229.35").amountText)
    }
}
