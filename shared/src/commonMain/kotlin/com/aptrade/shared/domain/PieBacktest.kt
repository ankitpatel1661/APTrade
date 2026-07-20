package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/**
 * One data point in a DCA backtest report: cumulative amount invested and portfolio value as
 * of a given day.
 */
data class BacktestPoint(
    val day: String,
    val invested: Money,
    val value: Money,
)

/**
 * Result of replaying a Pie's contribution schedule over historical daily closes, alongside a
 * lump-sum comparison (investing the same total on day one instead of spreading it out over
 * the schedule).
 *
 * [totalReturnPP] is `(finalValue / totalInvested - 1) * 100`, in percentage points, rounded
 * to 2 decimal places (0 when [totalInvested] is zero). Kotlin's shared domain has no
 * `Percentage` value object (see [PieSlice.targetWeightPP]'s doc comment for the same
 * divergence) — a plain [BigDecimal] is used here instead.
 */
data class BacktestReport(
    val points: List<BacktestPoint>,
    val totalInvested: Money,
    val finalValue: Money,
    val totalReturnPP: BigDecimal,
    val lumpSumFinalValue: Money,
)

/**
 * Historical dollar-cost-averaging (DCA) backtest: replays a Pie's contribution schedule over
 * a table of historical daily closes, buying via [PieMath.distribute] on each executable due
 * day, and compares the result against a lump-sum baseline (investing the full total on day
 * one).
 *
 * Transcribed from `Sources/APTradeDomain/PieBacktest.swift` (the shipped M7.1 Swift/macOS
 * reference), AS-BUILT. Swift derives its "day before `startDay`" bound via a dedicated
 * Gregorian/ET `Calendar` (no `Date()` / current-time calls — every date is derived from the
 * `startDay` string parameter, so results stay fully deterministic). Kotlin's [MarketCalendar]
 * already works in epoch-day arithmetic throughout the shared core, so the same "one calendar
 * day earlier" step here is a plain `epochDay - 1` (see [dayBefore]) rather than a `Calendar`
 * round-trip — same semantics, native representation.
 */
object PieBacktest {

    private val ZERO: BigDecimal = BigDecimal.ZERO
    private val HUNDRED: BigDecimal = BigDecimal.parseString("100")

    /**
     * Replays [slices]' contribution schedule ([amount] every [cadence], starting at
     * [startDay]) over [dailyCloses], buying via [PieMath.distribute] on each executable due
     * day, and compares the result to a lump-sum baseline.
     *
     * **Anchor/afterDay choice:** [PieSchedule.dueDays] never treats its own anchor day as a
     * candidate — stepping starts at `anchor + 1×cadence` (see its doc comment: "the bare
     * anchor itself is never a `dueDays` candidate"). A DCA plan, however, must be able to
     * execute its very first contribution ON [startDay] itself when [startDay] is tradeable.
     * So the first due day is found via `PieSchedule.nextDueDay(anchorDay = startDay, afterDay
     * = <day before startDay>, ...)`, whose step-0 case *does* treat the anchor as eligible
     * (per its own doc comment: "Step 0 (the anchor itself) is eligible"). Every subsequent
     * due day then comes from `PieSchedule.dueDays(anchorDay = startDay, afterDay = <first due
     * day>, throughDay = endDay, ...)`, which correctly steps forward cadence-by-cadence from
     * there — its `afterDay` window check is against each step's *rolled* value, same as the
     * first due day, so the two calls stay consistent.
     *
     * @param slices Target allocation for the Pie being replayed.
     * @param amount Contribution amount on each due day.
     * @param cadence Contribution frequency.
     * @param startDay First possible contribution day, `yyyy-MM-dd`.
     * @param endDay Last day of the backtest window, `yyyy-MM-dd`.
     * @param dailyCloses `dailyCloses[symbol][day]` = closing price. A due day missing a close
     *   for ANY slice symbol is skipped entirely — no partial buy — mirroring live
     *   insufficient-data semantics.
     * @param calendar Market calendar for trading-day/holiday resolution.
     * @return `null` if no due day in the window is executable (every due day is missing a
     *   close for at least one slice symbol — "insufficient history").
     */
    fun dcaBacktest(
        slices: List<PieSlice>,
        amount: Money,
        cadence: PieCadence,
        startDay: String,
        endDay: String,
        dailyCloses: Map<String, Map<String, Money>>,
        calendar: MarketCalendar,
    ): BacktestReport? {
        if (slices.isEmpty()) return null
        val dayBeforeStart = dayBefore(startDay, calendar) ?: return null

        val firstDue = PieSchedule.nextDueDay(
            anchorDay = startDay, cadence = cadence, afterDay = dayBeforeStart, calendar = calendar,
        )
        // nextDueDay falls back to returning afterDay unchanged on malformed input or cap
        // exhaustion (both unreachable for well-formed callers); guard defensively since a
        // returned day before startDay cannot be a genuine due day.
        if (firstDue < startDay || firstDue > endDay) return null

        val laterDueDays = PieSchedule.dueDays(
            anchorDay = startDay, cadence = cadence, afterDay = firstDue, throughDay = endDay, calendar = calendar,
        )
        val allDueDays = (listOf(firstDue) + laterDueDays).sorted()

        val currencyCode = amount.currencyCode
        val quantities = LinkedHashMap<String, BigDecimal>()
        var totalInvested: BigDecimal = ZERO
        val points = mutableListOf<BacktestPoint>()
        var firstExecutedCloses: Map<String, Money>? = null

        for (day in allDueDays) {
            val closes = closesForAllSymbols(day, slices, dailyCloses)
                ?: continue // Missing (or non-positive) close for at least one slice: skip this contribution.

            val currentValues = LinkedHashMap<String, Money>()
            for (slice in slices) {
                val qty = quantities[slice.symbol] ?: ZERO
                val close = closes[slice.symbol]?.amount ?: ZERO
                currentValues[slice.symbol] = Money(qty * close, currencyCode)
            }

            val allocation = PieMath.distribute(amount, currentValues, slices)
            for (slice in slices) {
                val share = allocation[slice.symbol]?.amount ?: continue
                val close = closes[slice.symbol]?.amount ?: continue
                val bought = share.divide(close, MONEY_MATH) // full precision, never rounded.
                quantities[slice.symbol] = (quantities[slice.symbol] ?: ZERO) + bought
            }

            totalInvested += amount.amount
            if (firstExecutedCloses == null) {
                firstExecutedCloses = closes
            }

            val value = portfolioValue(quantities, closes, slices, currencyCode)
            points += BacktestPoint(
                day = day,
                invested = Money(PieMath.roundedCents(totalInvested), currencyCode),
                value = value,
            )
        }

        val executedCloses = firstExecutedCloses ?: return null // No due day was executable.

        // closesOnOrBefore(endDay, ...) is guaranteed to find at least the last executed day
        // above (it's <= endDay and already passed closesForAllSymbols), so this null branch
        // is unreachable in practice; kept as a defensive fallback rather than a `!!`.
        val (finalDay, finalCloses) = closesOnOrBefore(endDay, slices, dailyCloses) ?: return null

        val finalValue = portfolioValue(quantities, finalCloses, slices, currencyCode)
        points += BacktestPoint(
            day = finalDay,
            invested = Money(PieMath.roundedCents(totalInvested), currencyCode),
            value = finalValue,
        )

        val totalReturn = returnPercentage(totalInvested, finalValue.amount)

        // Lump sum: one distribute() of the whole total, on the first executable day, valued
        // at the same final closes used above.
        val lumpSumAllocation = PieMath.distribute(
            contribution = Money(totalInvested, currencyCode),
            currentValues = emptyMap(),
            targets = slices,
        )
        val lumpSumQuantities = LinkedHashMap<String, BigDecimal>()
        for (slice in slices) {
            val share = lumpSumAllocation[slice.symbol]?.amount ?: continue
            val close = executedCloses[slice.symbol]?.amount ?: continue
            if (close <= ZERO) continue
            lumpSumQuantities[slice.symbol] = share.divide(close, MONEY_MATH)
        }
        val lumpSumFinalValue = portfolioValue(lumpSumQuantities, finalCloses, slices, currencyCode)

        return BacktestReport(
            points = points,
            totalInvested = Money(PieMath.roundedCents(totalInvested), currencyCode),
            finalValue = finalValue,
            totalReturnPP = totalReturn,
            lumpSumFinalValue = lumpSumFinalValue,
        )
    }

    // MARK: - Helpers

    /**
     * The calendar day immediately before [day], in the same `yyyy-MM-dd` shape. `null` on
     * malformed input.
     */
    private fun dayBefore(day: String, calendar: MarketCalendar): String? {
        val epochDay = PieSchedule.parseDay(day) ?: return null
        return calendar.dayString(epochDay - 1)
    }

    /**
     * Every slice symbol's close on [day], or `null` if ANY slice symbol is missing a close
     * (or has a non-positive close) on that day — mirrors the live "insufficient data"
     * behavior of skipping the whole contribution rather than buying partially.
     */
    private fun closesForAllSymbols(
        day: String,
        slices: List<PieSlice>,
        dailyCloses: Map<String, Map<String, Money>>,
    ): Map<String, Money>? {
        val result = LinkedHashMap<String, Money>()
        for (slice in slices) {
            val close = dailyCloses[slice.symbol]?.get(day) ?: return null
            if (close.amount <= ZERO) return null
            result[slice.symbol] = close
        }
        return result
    }

    /**
     * The most recent day <= [day] (by date-string comparison) on which every slice symbol
     * has a close, along with those closes. Candidate days are drawn from the first slice's
     * close map — a day can only be valid if that slice also has a close on it, so no
     * candidate is missed by restricting the scan to that map.
     */
    private fun closesOnOrBefore(
        day: String,
        slices: List<PieSlice>,
        dailyCloses: Map<String, Map<String, Money>>,
    ): Pair<String, Map<String, Money>>? {
        val firstSymbol = slices.firstOrNull()?.symbol ?: return null
        val firstMap = dailyCloses[firstSymbol] ?: return null
        val candidates = firstMap.keys.filter { it <= day }.sortedDescending()
        for (candidate in candidates) {
            val closes = closesForAllSymbols(candidate, slices, dailyCloses)
            if (closes != null) return candidate to closes
        }
        return null
    }

    /** Sum of quantity x close across [slices], rounded to 2 dp as a [Money]. */
    private fun portfolioValue(
        quantities: Map<String, BigDecimal>,
        closes: Map<String, Money>,
        slices: List<PieSlice>,
        currencyCode: String,
    ): Money {
        var total = ZERO
        for (slice in slices) {
            val close = closes[slice.symbol]?.amount ?: continue
            total += (quantities[slice.symbol] ?: ZERO) * close
        }
        return Money(PieMath.roundedCents(total), currencyCode)
    }

    /** `(finalValue / invested - 1) * 100`, rounded to 2 dp. Zero invested returns 0%. */
    private fun returnPercentage(invested: BigDecimal, finalValue: BigDecimal): BigDecimal {
        if (invested.isZero()) return ZERO
        val raw = (finalValue.divide(invested, MONEY_MATH) - BigDecimal.ONE) * HUNDRED
        return PieMath.roundedCents(raw)
    }
}
