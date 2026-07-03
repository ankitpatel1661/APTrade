package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

data class Position(
    val asset: Asset,
    val quantity: BigDecimal,
    val averageCost: Money,
    val realizedPnL: Money,
) {
    fun marketValue(at: Money): Money =
        Money(at.amount * quantity, at.currencyCode)

    fun unrealizedPnL(at: Money): Money =
        Money((at.amount - averageCost.amount) * quantity, at.currencyCode)
}
