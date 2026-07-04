package com.aptrade.shared.domain

import kotlin.math.abs
import kotlin.random.Random

/** The threshold a [PriceAlert] watches for. Pure value type — no notion of how the
 *  match is delivered to the user. */
sealed class AlertCondition {
    data class PriceAbove(val threshold: Money) : AlertCondition()
    data class PriceBelow(val threshold: Money) : AlertCondition()

    /** Fires once the day's `changePercent` magnitude reaches or exceeds this value,
     *  regardless of direction (e.g. 5.0 fires on either +5% or -5%). */
    data class PercentChange(val magnitude: Double) : AlertCondition()

    /** English summary matching the macOS transcription source exactly. */
    val summary: String
        get() = when (this) {
            is PriceAbove -> "Price above ${threshold.formatted}"
            is PriceBelow -> "Price below ${threshold.formatted}"
            is PercentChange -> "Moves ${abs(magnitude)}% in a day"
        }
}

/** Unique-enough alert id without a platform UUID dependency. 128 bits of randomness —
 *  collision-negligible, process-stable. Mirrors [generateTradeId]'s precedent. */
fun generateAlertId(): String =
    "alert-${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"

/** A user-defined watch on one symbol: fire when [condition] is met against a live
 *  quote. No framework imports, no networking, no persistence — pure business logic. */
data class PriceAlert(
    val id: String = generateAlertId(),
    val symbol: String,
    val condition: AlertCondition,
    val createdAtEpochSeconds: Long,
    val isTriggered: Boolean = false,
) {
    /** Pure check: does [quote] satisfy this alert's condition right now? */
    fun isMet(quote: Quote): Boolean = when (condition) {
        is AlertCondition.PriceAbove -> quote.price.amount >= condition.threshold.amount
        is AlertCondition.PriceBelow -> quote.price.amount <= condition.threshold.amount
        is AlertCondition.PercentChange -> abs(quote.changePercent) >= abs(condition.magnitude)
    }

    fun triggered(): PriceAlert = copy(isTriggered = true)
}
