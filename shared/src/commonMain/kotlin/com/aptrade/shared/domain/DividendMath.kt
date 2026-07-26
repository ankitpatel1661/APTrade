package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.math.pow

/** How frequently a security pays dividends, inferred from historical ex-dates. */
enum class DividendCadence { Monthly, Quarterly, SemiAnnual, Annual }

/** One projected year of dividend income.
 *
 *  [yearOffset] 1 is the trailing twelve-month rate carried forward with NO growth applied — a
 *  deliberately conservative first year. Growth compounds from [yearOffset] 2 onward. (The Swift
 *  wave shipped a comment claiming the opposite and had to correct it; carry-notes §3.2.) */
data class ForecastYear(val yearOffset: Int, val income: Money)

/** One projected dividend payment for a held symbol. ESTIMATED, never declared — the upstream
 *  data contains historical ex-dates only, so nothing in naming, comments, or UI copy derived
 *  from this type may imply an announced date (carry-notes §3.7). */
data class ScheduledDividend(
    val symbol: String,
    val exDateEpochSeconds: Long,
    val perShare: Money,
    val estimatedAmount: Money,
)

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

        val intervalDays = cadenceIntervalDays(cadence)

        return DividendEvent(
            symbol = last.symbol,
            exDateEpochSeconds = last.exDateEpochSeconds + intervalDays * SECONDS_PER_DAY,
            amountPerShare = last.amountPerShare,
        )
    }

    /** Calendar interval in DAYS for a cadence bucket (Monthly 30, Quarterly 91, SemiAnnual 182,
     *  Annual 365). Shared by [nextProjected] and [projectedSchedule] so the step sizes are
     *  defined in exactly one place — the M8 "next projected" path already owned these constants
     *  inline and a second copy is explicitly forbidden. */
    private fun cadenceIntervalDays(cadence: DividendCadence): Long = when (cadence) {
        DividendCadence.Monthly -> 30L
        DividendCadence.Quarterly -> 91L
        DividendCadence.SemiAnnual -> 182L
        DividendCadence.Annual -> 365L
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

    /** Lower bound on per-symbol annual dividend growth used by forecasts. */
    val MIN_DIVIDEND_GROWTH: BigDecimal = BigDecimal.parseString("-0.20")

    /** Upper bound on per-symbol annual dividend growth used by forecasts.
     *
     *  NOTE (carry-notes §3.6): these two are INDEPENDENT of `GoalMath.MIN/MAX_ANNUAL_GROWTH`
     *  (−0.50 … 1.00), which clamp a whole portfolio's value growth. They are easy to conflate
     *  and were deliberately kept separate; both pairs are pinned by exact-equality tests. */
    val MAX_DIVIDEND_GROWTH: BigDecimal = BigDecimal.parseString("0.25")

    private const val SECONDS_PER_YEAR = 365.25 * 86_400.0

    /**
     * Annualized growth of a symbol's dividend, measured over at most the last five years of
     * history and clamped to [MIN_DIVIDEND_GROWTH] … [MAX_DIVIDEND_GROWTH]. Returns 0 when there
     * is too little history to measure honestly (fewer than two years spanned, or fewer than two
     * payments).
     *
     * Compares the AVERAGE PER-PAYMENT amount across the oldest half of the window against the
     * newest half (payment-count-normalized) rather than a raw day-windowed sum sampled at two
     * instants, so a cadence change (e.g. quarterly -> monthly) doesn't read as growth. The
     * annualization exponent's divisor is the elapsed time between the two quantities actually
     * being compared: the gap, in years, between each half's CENTROID (mean ex-date) — not the
     * window's total first-to-last span.
     *
     * CARRY-NOTE 6 (human ruling, M11.2 Task 5 review Finding 1, corrected by Finding A — ported to
     * Swift by `feature/m11-swift-backport-parity`, closing carry-notes §4c): the original transcription compared
     * [trailingAnnualPerShare] sampled at two instants (`asOf`, and `window.first() + 1 year`).
     * That is an aliasing bug: whether a rolling 365-day window catches 4 or 5 payments of an
     * exact quarterly (91-day) cadence depends entirely on where `asOf` happens to fall relative
     * to an ex-date — four 91-day gaps span only 364 days, one day short of the window — so a
     * genuinely FLAT payer could read anywhere from −20%…+25%/yr purely from ex-date phase,
     * compounding to a ~45x divergence in reported income at a 30-year horizon. Comparing
     * per-payment AVERAGES over count-matched halves of the window is immune to this: a flat
     * payer's early-half and late-half averages are equal regardless of which instant `asOf`
     * happens to be, because no day-count window boundary is computed at all. (The original
     * approach also had a second, compounding defect: its early anchor was offset by
     * `SECONDS_PER_YEAR` = 365.25 days while [trailingAnnualPerShare]'s window is a strict 365
     * days — a structural 21,600-second/6-hour mismatch that could exclude the anchor's own
     * boundary event. That class of bug cannot recur here, since no window-membership cutoff is
     * computed by this function at all.)
     *
     * The FIRST cut of this fix (Finding 1) removed the aliasing but kept `totalSpanYears - 1.0`
     * as the annualization divisor — a holdover from the OLD design, where the early anchor
     * genuinely sat one year after the first event, so `span − 1` really was the gap between the
     * two sampled points. Comparing half-window CENTROIDS instead (Finding A) changed what is
     * being compared without updating the divisor to match: centroids are separated by roughly
     * `span / 2`, not `span − 1`, and those coincide only at exactly a 2-year span. Off that one
     * point the result was systematically biased (further off from a 2-year span, and flipping
     * sign at exactly 2 years) — silent and calibrated-looking, unlike the aliasing artifact it
     * replaced, and just as capable of compounding to a multiple-x divergence at a 30-year
     * horizon. The divisor is now the actual measured interval — the centroid gap — so the
     * annualization recovers a known true per-payment CAGR exactly (see
     * `dividendGrowthRateRecoversAKnownTruePerPaymentCagr` in the test suite).
     *
     * RESIDUAL LIMITATION (review Finding 6): count-matched halves are immune to phase relative
     * to `asOf` (the window-membership aliasing this fix removed) -- they are NOT immune to phase
     * relative to a payer's OWN cadence when that payer mixes an irregular, much larger payment
     * into an otherwise-regular schedule. A flat quarterly payer that also makes an annual 5x
     * special distribution can land that one oversized payment in either half depending on where
     * the 5-year window happens to start/end, and was measured to read anywhere from 0.0 to
     * roughly -7%/yr at different phases of the SAME underlying (flat regular + annual special)
     * series -- same class of artifact as the bug this milestone fixed, one order of magnitude
     * smaller, and bounded by [MIN_DIVIDEND_GROWTH]/[MAX_DIVIDEND_GROWTH] like everything else this
     * function returns. Real-world instances: REITs and BDCs that pay a regular quarterly/monthly
     * rate plus a year-end "top-up" special distribution. Not fixed here; recorded so a future
     * reader does not mistake this function's phase-immunity for unconditional.
     *
     * FRACTIONAL EXPONENTIATION (carry-notes §4): ionspin's `BigDecimal.pow` takes Int/Long only,
     * so the n-th root routes through Double exactly as the Swift twin does. Tolerance-covered by
     * the tests; the clamp bounds the blast radius of any Double drift.
     *
     * MEASURED-ZERO VS. UNMEASURABLE (review Finding 3): every early-return below reports
     * `BigDecimal.ZERO` regardless of WHY -- a genuinely flat payer with two full years of history
     * and a payer with only one payment ever both read identically as 0.0. That conflation is fine
     * for [incomeForecast] (a forecast that can't measure growth should compound at 0%, same as a
     * forecast that measured exactly 0% growth), but it is NOT fine for a caller that needs to
     * distinguish "nothing to report" from "reported a real zero" -- see [hasMeasurableGrowth],
     * which shares every precondition below via [measuredDividendGrowthRate] so the two functions
     * cannot drift apart.
     */
    fun dividendGrowthRate(events: List<DividendEvent>, asOfEpochSeconds: Long): BigDecimal =
        measuredDividendGrowthRate(events, asOfEpochSeconds) ?: BigDecimal.ZERO

    /**
     * True when [events] carries enough history for [dividendGrowthRate] to actually MEASURE a
     * rate, as opposed to defaulting to `BigDecimal.ZERO` purely for want of data (review Finding
     * 3). Shares [measuredDividendGrowthRate]'s preconditions exactly -- a genuinely flat payer
     * with sufficient history returns `true` here (with [dividendGrowthRate] reporting a real,
     * measured `0.0`); a payer with too little history returns `false` (with [dividendGrowthRate]
     * ALSO reporting `0.0`, indistinguishably, unless this function is consulted separately).
     *
     * Lets [incomeProjection][GoalMath.incomeProjection] tell "measured zero growth" (a genuinely
     * flat, seasoned payer -- legitimately not on track) from "growth could not be measured at
     * all" (e.g. a payer with only a few months of payment history -- insufficient history, the
     * same honest reading [GoalMath.valueProjection] already gives an equivalently young account).
     */
    fun hasMeasurableGrowth(events: List<DividendEvent>, asOfEpochSeconds: Long): Boolean =
        measuredDividendGrowthRate(events, asOfEpochSeconds) != null

    /**
     * True when at least one position [incomeForecast] would actually project (positive trailing
     * income, positive quantity -- its own identical inclusion test, reproduced here) has
     * [hasMeasurableGrowth] history for its own events. Lets a caller aggregate the per-symbol
     * measurability question across an entire portfolio in one call, the same way
     * [projectedAnnualIncome] aggregates the per-symbol income sum.
     */
    fun anyPositionHasMeasurableGrowth(
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        asOfEpochSeconds: Long,
    ): Boolean = positions.any { position ->
        val events = eventsBySymbol[position.asset.symbol] ?: emptyList()
        trailingAnnualPerShare(events, asOfEpochSeconds).amount > BigDecimal.ZERO &&
            position.quantity > BigDecimal.ZERO &&
            hasMeasurableGrowth(events, asOfEpochSeconds)
    }

    /** The shared body behind [dividendGrowthRate] and [hasMeasurableGrowth]: `null` wherever
     *  there is too little data to measure honestly, a real (possibly zero) rate otherwise. See
     *  [dividendGrowthRate]'s KDoc for the full derivation this implements. */
    private fun measuredDividendGrowthRate(events: List<DividendEvent>, asOfEpochSeconds: Long): BigDecimal? {
        val windowStart = asOfEpochSeconds - (5.0 * SECONDS_PER_YEAR).toLong()
        val window = events
            .filter { it.exDateEpochSeconds in windowStart..asOfEpochSeconds }
            .sortedBy { it.exDateEpochSeconds }
        if (window.size < 2) return null

        val totalSpanYears = (window.last().exDateEpochSeconds - window.first().exDateEpochSeconds).toDouble() /
            SECONDS_PER_YEAR
        if (totalSpanYears < 2.0) return null

        // Count-normalized halves, NOT a day-windowed sum sampled at an instant — see the
        // CARRY-NOTE 6 KDoc above for why that distinction is the entire fix.
        val half = window.size / 2
        val earlyHalf = window.subList(0, half)
        val lateHalf = window.subList(window.size - half, window.size)
        val earlyAvg = averagePerPayment(earlyHalf)
        val lateAvg = averagePerPayment(lateHalf)
        if (earlyAvg <= 0.0 || lateAvg <= 0.0) return null

        // The annualization exponent's divisor: the elapsed time between what's actually being
        // compared — each half's centroid (mean ex-date) — in years, NOT the total window span
        // (see CARRY-NOTE 6's Finding A addendum above for why that distinction is the fix).
        val centroidGapYears = (meanEpochSeconds(lateHalf) - meanEpochSeconds(earlyHalf)) / SECONDS_PER_YEAR
        if (centroidGapYears <= 0.0 || !centroidGapYears.isFinite()) return null

        val ratio = lateAvg / earlyAvg
        if (ratio <= 0.0) return null
        val rate = ratio.pow(1.0 / centroidGapYears) - 1.0
        if (!rate.isFinite()) return null

        return clampGrowth(BigDecimal.fromDouble(rate))
    }

    /**
     * Average per-share payment amount across [events] (assumed non-empty; callers only pass
     * non-empty half-windows), as a Double — see the fractional-exponentiation note on
     * [dividendGrowthRate] for why this computation is Double-based rather than exact BigDecimal.
     */
    private fun averagePerPayment(events: List<DividendEvent>): Double {
        var total = BigDecimal.ZERO
        for (event in events) total += event.amountPerShare.amount
        return total.divide(BigDecimal.fromInt(events.size), MONEY_MATH).doubleValue(false)
    }

    /** Mean ex-date (epoch seconds) across [events] (assumed non-empty), as a Double — the
     *  "centroid" [dividendGrowthRate] compares between its early and late halves. */
    private fun meanEpochSeconds(events: List<DividendEvent>): Double {
        var total = 0.0
        for (event in events) total += event.exDateEpochSeconds.toDouble()
        return total / events.size
    }

    private fun clampGrowth(value: BigDecimal): BigDecimal = when {
        value < MIN_DIVIDEND_GROWTH -> MIN_DIVIDEND_GROWTH
        value > MAX_DIVIDEND_GROWTH -> MAX_DIVIDEND_GROWTH
        else -> value
    }

    /**
     * Projects annual dividend income forward, per holding, summed.
     *
     * Year 1 ([ForecastYear.yearOffset] 1) holds each symbol at its current trailing
     * twelve-month per-share rate — NO growth applied yet, a deliberately conservative choice, and
     * numerically identical to [projectedAnnualIncome] so a goal card's progress and a forecast
     * chart never disagree (carry-notes §3.1/§3.2). From year 2 each symbol grows at its clamped
     * historical [dividendGrowthRate].
     *
     * With DRIP on, each year's dividends buy more shares at that symbol's QUOTED price
     * ([pricesBySymbol]), with the assumed price itself growing at the same rate — a stated
     * simplification the UI surfaces as a caption.
     *
     * [pricesBySymbol] IS REQUIRED AND SITS SECOND, BESIDE [positions] — BINDING (carry-notes
     * §1.1/§5). It is not defaulted and never trails: with it omitted, every symbol would silently
     * reinvest at cost basis, so a holding bought at $50 and now trading at $150 would buy THREE
     * TIMES too many shares per year and overstate year-30 income by roughly 66%. The per-symbol
     * `?: averageCost` fallback below is for a symbol genuinely missing a quote — it must never
     * become the behaviour for every symbol via an omitted argument. A defaulted parameter whose
     * omission is a correctness bug is not a default; it is a re-armed bug with a compiler that
     * will never complain.
     */
    fun incomeForecast(
        positions: List<Position>,
        pricesBySymbol: Map<String, Money>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        years: Int,
        dripEnabled: Boolean,
        asOfEpochSeconds: Long,
    ): List<ForecastYear> {
        if (years <= 0) return emptyList()

        class Projection(
            var shares: BigDecimal,
            var perShare: BigDecimal,
            var price: BigDecimal,
            val growth: BigDecimal,
            val currencyCode: String,
        )

        val projections = mutableListOf<Projection>()
        for (position in positions) {
            val events = eventsBySymbol[position.asset.symbol] ?: emptyList()
            val trailing = trailingAnnualPerShare(events, asOfEpochSeconds)
            // The `quantity > 0` guard is inert while the portfolio model forbids a zero-quantity
            // position (selling removes the position at zero; overselling is rejected) — which is
            // exactly what makes year 1 equal projectedAnnualIncome. Keep it: if that model ever
            // changes, this is where the divergence would start (carry-notes §3.1).
            if (trailing.amount <= BigDecimal.ZERO || position.quantity <= BigDecimal.ZERO) continue
            projections += Projection(
                shares = position.quantity,
                perShare = trailing.amount,
                price = pricesBySymbol[position.asset.symbol]?.amount ?: position.averageCost.amount,
                growth = dividendGrowthRate(events, asOfEpochSeconds),
                currencyCode = trailing.currencyCode,
            )
        }

        val out = mutableListOf<ForecastYear>()
        for (offset in 1..years) {
            // Accumulated through Money.plus (which `require`s matching currency codes), NOT a
            // raw BigDecimal var with a last-writer-wins currency tag — otherwise a genuine
            // multi-currency mix would silently blend amounts here while its sibling
            // [projectedAnnualIncome] throws on the exact same input (M11.2 Task 5 review
            // Finding 5). USD-only today, but the two share a year-1-equality invariant and must
            // fail the same way.
            var total: Money? = null
            for (projection in projections) {
                if (offset > 1) {
                    val factor = BigDecimal.ONE + projection.growth
                    projection.perShare = projection.perShare * factor
                    projection.price = projection.price * factor
                }
                val income = Money(projection.shares * projection.perShare, projection.currencyCode)
                total = (total ?: Money(BigDecimal.ZERO, income.currencyCode)) + income
                if (dripEnabled && projection.price > BigDecimal.ZERO) {
                    projection.shares += income.amount.divide(projection.price, MONEY_MATH)
                }
            }
            out += ForecastYear(offset, total ?: Money(BigDecimal.ZERO, "USD"))
        }
        return out
    }

    /**
     * Rolls each holding's inferred payment cadence forward from its last real event, emitting
     * ESTIMATED payments in `(asOf, through]`, ascending by ex-date.
     *
     * Every emitted row is a projection: the upstream feed exposes no forward-declared dividend
     * dates, so the UI must label each row an estimate (carry-notes §3.7).
     *
     * Applies the SAME inclusion test [incomeForecast] does (review Finding 2): a position whose
     * [trailingAnnualPerShare] is zero contributes nothing to the forecast, `SummaryCards`, or the
     * "Upcoming Dividends" list -- this calendar must agree, or a holding whose payer has gone
     * stale/suspended (its last REAL ex-date old enough to have fallen out of the trailing-annual
     * window, but still recent enough for [inferredCadence] to infer a cadence from) prints
     * confident dollar rows on the one surface that valued it at zero everywhere else. The
     * `while (next <= asOfEpochSeconds) next += step` roll-forward below has no staleness bound of
     * its own -- it will happily roll a payer that stopped paying a year ago into next quarter --
     * so the zero-trailing-income guard is what actually bounds "how stale is too stale to
     * project," matching the bound every other income surface already uses.
     */
    fun projectedSchedule(
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        throughEpochSeconds: Long,
        asOfEpochSeconds: Long,
    ): List<ScheduledDividend> {
        val out = mutableListOf<ScheduledDividend>()
        for (position in positions) {
            if (position.quantity <= BigDecimal.ZERO) continue
            val events = eventsBySymbol[position.asset.symbol] ?: emptyList()
            if (trailingAnnualPerShare(events, asOfEpochSeconds).amount <= BigDecimal.ZERO) continue
            val seed = nextProjected(events) ?: continue
            val cadence = inferredCadence(events) ?: continue

            val step = cadenceIntervalDays(cadence) * SECONDS_PER_DAY
            var next = seed.exDateEpochSeconds
            // Roll a stale projection forward until it is genuinely in the future.
            while (next <= asOfEpochSeconds) next += step

            while (next <= throughEpochSeconds) {
                out += ScheduledDividend(
                    symbol = position.asset.symbol,
                    exDateEpochSeconds = next,
                    perShare = seed.amountPerShare,
                    estimatedAmount = Money(
                        seed.amountPerShare.amount * position.quantity,
                        seed.amountPerShare.currencyCode,
                    ),
                )
                next += step
            }
        }
        return out.sortedBy { it.exDateEpochSeconds }
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
