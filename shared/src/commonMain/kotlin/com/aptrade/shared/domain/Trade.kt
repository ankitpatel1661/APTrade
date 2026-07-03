package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.random.Random

enum class TradeSide { Buy, Sell }

sealed class TradeError(message: String) : Exception(message) {
    object InsufficientFunds : TradeError("Insufficient funds")
    object InsufficientShares : TradeError("Insufficient shares")
    object InvalidQuantity : TradeError("Invalid quantity")
}

/** One executed paper trade. `id` is caller-supplied (deterministic in tests). */
data class Transaction(
    val id: String,
    val symbol: String,
    val side: TradeSide,
    val quantity: BigDecimal,
    val price: Money,
    val epochSeconds: Long,
)

/** Unique-enough trade id without a platform UUID or kotlinx.datetime dependency.
 *  128 bits of randomness — collision-negligible, process-stable. */
fun generateTradeId(): String =
    "txn-${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"
