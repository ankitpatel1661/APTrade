package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** How frequently a security pays dividends, inferred from historical ex-dates. */
enum class DividendCadence { Monthly, Quarterly, SemiAnnual, Annual }

private const val SECONDS_PER_DAY = 86_400L

/**
 * Pure dividend & income math: held-shares reconstruction from a transaction ledger,
 * trailing-twelve-month rate, cadence inference/projection, and cash aggregation. No
 * networking, no persistence. Transcribed from `Sources/APTradeDomain/DividendMath.swift`
 * (the shipped M8.1 Swift/macOS reference), AS-BUILT.
 *
 * Kotlin `commonMain` has no Foundation `Date`/`Calendar`; every date here is a Unix epoch-
 * seconds `Long` (UTC), and day-window arithmetic mirrors Swift's `Date.addingTimeInterval`
 * with plain `86_400L`-second steps.
 */
object DividendMath {

    /**
     * Shares held STRICTLY BEFORE [atEpochSeconds]: sum of buy quantities minus sell
     * quantities across [symbol]'s transactions with `txn.epochSeconds < atEpochSeconds`
     * (Dividend txns contribute nothing; DRIP buys count like any buy).
     */
    fun sharesHeld(symbol: String, atEpochSeconds: Long, transactions: List<Transaction>): BigDecimal {
        var net = BigDecimal.ZERO
        for (txn in transactions) {
            if (txn.symbol != symbol || txn.epochSeconds >= atEpochSeconds) continue
            net = when (txn.side) {
                TradeSide.Buy -> net + txn.quantity
                TradeSide.Sell -> net - txn.quantity
                TradeSide.Dividend -> net
            }
        }
        return net
    }

    /**
     * Sum of event amounts with exDate in (`asOf` − 365 days, `asOf`] — the −365d instant
     * itself is excluded, `asOf` itself is included. Zero (seeded from the first event's
     * currency, "USD" if there are none) when the window contains nothing.
     */
    fun trailingAnnualPerShare(events: List<DividendEvent>, asOfEpochSeconds: Long): Money {
        val windowStart = asOfEpochSeconds - 365 * SECONDS_PER_DAY
        val currency = events.firstOrNull()?.amountPerShare?.currencyCode ?: "USD"
        var total = Money(BigDecimal.ZERO, currency)
        for (event in events) {
            if (event.exDateEpochSeconds > windowStart && event.exDateEpochSeconds <= asOfEpochSeconds) {
                total += event.amountPerShare
            }
        }
        return total
    }

    /**
     * Median gap between consecutive ex-dates → cadence. `null` when fewer than 2 events.
     * Buckets (days): <= 45 Monthly, <= 135 Quarterly, <= 270 SemiAnnual, else Annual.
     */
    fun inferredCadence(events: List<DividendEvent>): DividendCadence? {
        if (events.size < 2) return null

        val sortedDates = events.map { it.exDateEpochSeconds }.sorted()
        val gapsInDays = (1 until sortedDates.size).map { i ->
            (sortedDates[i] - sortedDates[i - 1]).toDouble() / SECONDS_PER_DAY
        }
        val sortedGaps = gapsInDays.sorted()
        val count = sortedGaps.size
        val medianGapDays = if (count % 2 == 1) {
            sortedGaps[count / 2]
        } else {
            (sortedGaps[count / 2 - 1] + sortedGaps[count / 2]) / 2
        }

        return when {
            medianGapDays <= 45 -> DividendCadence.Monthly
            medianGapDays <= 135 -> DividendCadence.Quarterly
            medianGapDays <= 270 -> DividendCadence.SemiAnnual
            else -> DividendCadence.Annual
        }
    }

    /**
     * Last exDate + cadence interval (Monthly 30d, Quarterly 91d, SemiAnnual 182d, Annual
     * 365d), amount = last event's amount. `null` when [inferredCadence] is `null`.
     *
     * NOTE: no now-awareness — callers filter stale projections (`exDate > asOf`) themselves,
     * exactly as `IncomeViewModel.swift` and `AssetDetailViewModel.swift` do (M8.1 review
     * precedent carried over unchanged for the Kotlin desktop callers).
     */
    fun nextProjected(events: List<DividendEvent>): DividendEvent? {
        val cadence = inferredCadence(events) ?: return null
        // maxByOrNull keeps the FIRST element on a tie (only replaces on strict `<`),
        // matching Swift's `events.max(by: { $0.exDate < $1.exDate })` semantics exactly.
        val last = events.maxByOrNull { it.exDateEpochSeconds } ?: return null

        val intervalDays = when (cadence) {
            DividendCadence.Monthly -> 30L
            DividendCadence.Quarterly -> 91L
            DividendCadence.SemiAnnual -> 182L
            DividendCadence.Annual -> 365L
        }

        return DividendEvent(
            symbol = last.symbol,
            exDateEpochSeconds = last.exDateEpochSeconds + intervalDays * SECONDS_PER_DAY,
            amountPerShare = last.amountPerShare,
        )
    }

    /**
     * Received dividend cash per UTC calendar month (`"yyyy-MM"` keys) from Dividend
     * transactions only.
     *
     * CARRY-NOTE 5 (binding, recorded divergence from Swift): the month key comes from
     * epoch-day civil-date math ([monthKey] below), NOT from any per-call
     * formatter/clock/locale object — Swift's reference used `DateFormatter` +
     * `Calendar(identifier: .gregorian)` + `TimeZone.gmt` per call, which Kotlin
     * `commonMain` has no equivalent of, and which would be needlessly re-allocated per
     * transaction anyway.
     */
    fun monthlyReceived(transactions: List<Transaction>): Map<String, Money> {
        val result = mutableMapOf<String, Money>()
        for (txn in transactions) {
            if (txn.side != TradeSide.Dividend) continue
            val key = monthKey(txn.epochSeconds)
            val cash = Money(txn.price.amount * txn.quantity, txn.price.currencyCode)
            val running = result[key] ?: Money(BigDecimal.ZERO, cash.currencyCode)
            result[key] = running + cash
        }
        return result
    }

    /**
     * [trailingAnnualPerShare] x shares, per held symbol, summed. Symbols absent from
     * [eventsBySymbol] contribute zero. Zero seeds from the first contribution's currency
     * (falling back to "USD" for an empty/all-zero-event [positions] list).
     */
    fun projectedAnnualIncome(
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        asOfEpochSeconds: Long,
    ): Money {
        var total: Money? = null
        for (position in positions) {
            val events = eventsBySymbol[position.asset.symbol] ?: emptyList()
            val perShare = trailingAnnualPerShare(events, asOfEpochSeconds)
            val contribution = Money(perShare.amount * position.quantity, perShare.currencyCode)
            total = (total ?: Money(BigDecimal.ZERO, contribution.currencyCode)) + contribution
        }
        return total ?: Money(BigDecimal.ZERO, "USD")
    }

    // --- UTC epoch-day civil-date math (private copy) -------------------------------------
    // PieSchedule.kt (shared/src/commonMain/kotlin/com/aptrade/shared/domain/PieSchedule.kt,
    // M7.2) already implements this exact Hinnant civil-date algorithm, privately — but its
    // file doc establishes the house precedent that each type keeps its OWN private copy
    // rather than widening visibility to share it (see also PieContributionUseCases.kt's
    // private `fetchClosesByDay`, replicated rather than shared for the same reason). Only
    // the minimal pieces this file needs (UTC epoch-seconds -> epoch-day -> civil
    // year/month) are reproduced here.

    private fun monthKey(epochSeconds: Long): String {
        val epochDay = floorDiv(epochSeconds, SECONDS_PER_DAY)
        val (year, month, _) = civilFromDays(epochDay)
        return "$year-${month.toString().padStart(2, '0')}"
    }

    private fun floorDiv(x: Long, y: Long): Long {
        val q = x / y
        return if ((x xor y) < 0 && q * y != x) q - 1 else q
    }

    private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097L // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
        val mp = (5 * doy + 2) / 153 // [0, 11]
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt() // [1, 31]
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt() // [1, 12]
        val year = if (m <= 2) y + 1 else y
        return Triple(year, m, d)
    }
}
