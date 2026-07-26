package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * How a goal is tracking. Never fabricates an ETA it cannot support.
 *
 * All FIVE outcomes are distinct cases so a caller can tell "already met" from "unreachable" from
 * "too far out" from "no data at all" from a concrete ETA WITHOUT string-matching a rendered
 * sentence (carry-notes §3.5).
 */
sealed class GoalProjection {
    data object Reached : GoalProjection()
    data class Years(val value: Double) : GoalProjection()
    data object BeyondHorizon : GoalProjection()
    data object NotOnTrack : GoalProjection()
    data object InsufficientHistory : GoalProjection()
}

/** Progress and honest time-to-target math for portfolio goals. Pure. Transcribed from
 *  `Sources/APTradeDomain/GoalMath.swift` AS-BUILT, with one recorded divergence at
 *  [GoalMath.MINIMUM_HISTORY_DAYS]. */
object GoalMath {

    /**
     * Minimum days of ACCOUNT history before a growth rate is trustworthy.
     *
     * RECORDED DIVERGENCE FROM SWIFT (M11.2 kickoff decision 4a.2, carry-notes §1.2/§4a.2):
     * Swift's twin measures the span of the equity curve it is handed — but that curve is the
     * one-year PRICE window with a flat pre-inception cash point, so its span is ~365 days for
     * anyone holding any seasoned symbol and the floor is very nearly inert there. Kotlin
     * measures instead from the account's FIRST TRANSACTION (see
     * [Portfolio.inceptionEpochSeconds] — the one named derivation, shared with
     * `FetchPortfolioPerformance`'s sinceInception trim so the metric and this floor cannot
     * drift apart).
     *
     * Consequence, accepted deliberately: a genuinely new account reports insufficient history
     * regardless of how seasoned its holdings are. That is the honest reading, because the
     * projection extrapolates THE ACCOUNT'S growth rate; a user who transfers in a long-held
     * position still waits until the account itself has 180 days behind it.
     *
     * This is a Swift BACKPORT CANDIDATE, not a transcription slip.
     *
     * Why 180 and not 30: a 30-day window annualized by 365.25/days is a 12.175x extrapolation —
     * a portfolio up 5% in its first month reads as +80%/yr, sails under the +100% clamp, and
     * renders a confident multi-year ETA, contradicting this type's promise never to fabricate
     * an ETA it cannot support.
     */
    const val MINIMUM_HISTORY_DAYS = 180

    /** Projections longer than this report [GoalProjection.BeyondHorizon]. */
    const val HORIZON_YEARS = 30.0

    /** Portfolio-value growth clamp, lower bound.
     *
     *  INDEPENDENT of `DividendMath.MIN/MAX_DIVIDEND_GROWTH` (−0.20 … 0.25), which clamp a single
     *  symbol's dividend growth. The two pairs are easy to conflate and are deliberately kept
     *  separate; both are pinned by exact-equality tests (carry-notes §3.6). */
    val MIN_ANNUAL_GROWTH: BigDecimal = BigDecimal.parseString("-0.5")

    /** Portfolio-value growth clamp, upper bound. See [MIN_ANNUAL_GROWTH]. */
    val MAX_ANNUAL_GROWTH: BigDecimal = BigDecimal.parseString("1.0")

    private const val SECONDS_PER_DAY = 86_400.0

    /** Fraction of the target achieved. May exceed 1. A zero or negative target yields 0. The
     *  result is always finite and non-negative. */
    fun progress(current: Money, target: Money): Double {
        if (target.amount <= BigDecimal.ZERO) return 0.0
        val value = current.amount.divide(target.amount, MONEY_MATH).doubleValue(false)
        if (!value.isFinite()) return 0.0
        return max(value, 0.0)
    }

    /** Days between the account's inception and [asOfEpochSeconds]; `null` when the account has
     *  never traded. Feed it [Portfolio.inceptionEpochSeconds]'s result — never a locally
     *  re-derived minimum. */
    fun accountAgeDays(inceptionEpochSeconds: Long?, asOfEpochSeconds: Long): Double? {
        if (inceptionEpochSeconds == null) return null
        val days = (asOfEpochSeconds - inceptionEpochSeconds).toDouble() / SECONDS_PER_DAY
        return max(days, 0.0)
    }

    /**
     * Annualized growth of the equity curve, clamped to [MIN_ANNUAL_GROWTH] … [MAX_ANNUAL_GROWTH].
     * `null` when the account is younger than [MINIMUM_HISTORY_DAYS], when the priced CURVE ITSELF
     * spans fewer than [MINIMUM_HISTORY_DAYS], or when the curve is otherwise too degenerate to
     * measure (fewer than two points, or a non-positive endpoint).
     *
     * BOTH GATES ARE REQUIRED (carry-notes §4a.2, wording-correction addendum, 2026-07-25) — this
     * is not redundancy. Account age is orthogonal to curve span: `performanceSeries`'s
     * all-priced-symbols gate truncates the WHOLE curve to the newest-history symbol's first
     * candle, so an account that is 400 days old can still hand this function a curve spanning
     * only 30 days (e.g. it just bought a recently-listed symbol). Without the span gate, a
     * portfolio up 5% over that 30-day sliver annualizes to +81.1%/yr — under the +100% clamp —
     * and renders a confident "1.2 yrs" ETA off ten data points, exactly the fabrication
     * [MINIMUM_HISTORY_DAYS]'s KDoc says this type promises never to produce. An earlier revision
     * of this function read the "measure account age INSTEAD of price-history span" ruling as a
     * replacement rather than an addition and dropped the span floor to 1 day; that was wrong and
     * is fixed here. Swift already gates on curve span; Kotlin adds the age gate on top of it,
     * not in place of it.
     *
     * FRACTIONAL EXPONENTIATION (carry-notes §4): ionspin's `BigDecimal.pow` takes Int/Long only,
     * so the annualization routes through Double, as the Swift twin does.
     */
    fun annualGrowthRate(curve: List<PortfolioPerformancePoint>, accountAgeDays: Double?): BigDecimal? {
        if (accountAgeDays == null || accountAgeDays < MINIMUM_HISTORY_DAYS.toDouble()) return null
        val sorted = curve.sortedBy { it.epochSeconds }
        val first = sorted.firstOrNull() ?: return null
        val last = sorted.lastOrNull() ?: return null
        val spanDays = (last.epochSeconds - first.epochSeconds).toDouble() / SECONDS_PER_DAY
        if (spanDays < MINIMUM_HISTORY_DAYS.toDouble()) return null
        if (first.value.amount <= BigDecimal.ZERO || last.value.amount <= BigDecimal.ZERO) return null

        val ratio = last.value.amount.divide(first.value.amount, MONEY_MATH).doubleValue(false)
        if (ratio <= 0.0) return null
        val rate = ratio.pow(365.25 / spanDays) - 1.0
        if (!rate.isFinite()) return null

        val asDecimal = BigDecimal.fromDouble(rate)
        return when {
            asDecimal < MIN_ANNUAL_GROWTH -> MIN_ANNUAL_GROWTH
            asDecimal > MAX_ANNUAL_GROWTH -> MAX_ANNUAL_GROWTH
            else -> asDecimal
        }
    }

    /** Degenerate-input guard shared by [valueProjection]/[incomeProjection]: a non-positive
     *  target reads as 0% progress (mirroring [progress]) and is reported as
     *  [GoalProjection.NotOnTrack] rather than a misleading [GoalProjection.Reached]; a `current`
     *  already at or past `target` reports [GoalProjection.Reached]. `null` means neither shortcut
     *  applies and the caller must consult its own projection data. */
    private fun degenerateOrReached(current: Money, target: Money): GoalProjection? = when {
        target.amount <= BigDecimal.ZERO -> GoalProjection.NotOnTrack
        current.amount >= target.amount -> GoalProjection.Reached
        else -> null
    }

    /** When the portfolio's value reaches [target] at its historical growth rate. */
    fun valueProjection(
        current: Money,
        target: Money,
        curve: List<PortfolioPerformancePoint>,
        accountAgeDays: Double?,
    ): GoalProjection {
        degenerateOrReached(current, target)?.let { return it }
        val rate = annualGrowthRate(curve, accountAgeDays) ?: return GoalProjection.InsufficientHistory
        return yearsToTarget(current.amount, target.amount, rate)
    }

    /**
     * When projected annual income reaches [target], read off the forecast curve.
     *
     * A forecast that never grows reports [GoalProjection.NotOnTrack] rather than a fake ETA. A
     * forecast carrying NO positive income at all — a brand-new portfolio holding nothing, whose
     * forecast is `HORIZON_YEARS` zero years — reports [GoalProjection.InsufficientHistory],
     * checked BEFORE the not-on-track fallthrough (carry-notes §2.4, BINDING). Nothing is off
     * track; there is simply no data, and the symmetric value-goal card says exactly that in the
     * identical situation.
     *
     * [forecast] must always be exactly [HORIZON_YEARS] long, independent of whatever horizon the
     * chart beside it is displaying (carry-notes §3.3) — a truncated forecast makes an
     * unreachable goal indistinguishable from one reached in year 31: the uncrossed-but-growing
     * fallthrough below reports [GoalProjection.BeyondHorizon] for ANY such forecast regardless of
     * its length, so a caller that hands in a short forecast silently narrows its own horizon.
     *
     * DELIBERATE DIVERGENCE FROM SWIFT, BACKPORT CANDIDATE (carry-notes §4a.3): Swift's twin
     * returns `.years(crossing.yearOffset)` unbounded, so a crossing at year 35 renders a concrete
     * "35 yrs" while [valueProjection] would report [GoalProjection.BeyondHorizon] for the
     * identical span — the two symmetric cards would disagree, and the "> 30 yrs" rule would be
     * satisfied only by caller discipline nothing enforces. Kotlin clamps the crossing itself to
     * [HORIZON_YEARS] so the boundary is provably identical in both projections.
     */
    fun incomeProjection(current: Money, target: Money, forecast: List<ForecastYear>): GoalProjection {
        degenerateOrReached(current, target)?.let { return it }
        val last = forecast.lastOrNull() ?: return GoalProjection.InsufficientHistory
        val crossing = forecast.firstOrNull { it.income.amount >= target.amount }
        if (crossing != null) {
            return if (crossing.yearOffset.toDouble() > HORIZON_YEARS) {
                GoalProjection.BeyondHorizon
            } else {
                GoalProjection.Years(crossing.yearOffset.toDouble())
            }
        }
        if (forecast.none { it.income.amount > BigDecimal.ZERO }) return GoalProjection.InsufficientHistory
        return if (last.income.amount > current.amount) {
            GoalProjection.BeyondHorizon
        } else {
            GoalProjection.NotOnTrack
        }
    }

    /** Solves `current * (1 + rate)^t >= target`, honestly. Internal-by-convention (the two
     *  projections above are the intended entry points); public so the horizon constant can be
     *  pinned directly by test. */
    fun yearsToTarget(current: BigDecimal, target: BigDecimal, annualRate: BigDecimal): GoalProjection {
        if (current <= BigDecimal.ZERO) return GoalProjection.NotOnTrack
        val rate = annualRate.doubleValue(false)
        if (rate <= 0.0) return GoalProjection.NotOnTrack
        val ratio = target.divide(current, MONEY_MATH).doubleValue(false)
        if (ratio <= 0.0) return GoalProjection.NotOnTrack
        val years = ln(ratio) / ln(1.0 + rate)
        if (!years.isFinite() || years <= 0.0) return GoalProjection.NotOnTrack
        return if (years > HORIZON_YEARS) GoalProjection.BeyondHorizon else GoalProjection.Years(years)
    }
}

/**
 * The value-goal card's "current value" when no priced equity curve is available.
 *
 * Cash plus every position's OWN cost basis — never a fabricated zero (carry-notes §2.3, BINDING).
 * The equity curve comes back empty in TWO distinct situations, and only one of them is exotic:
 *  1. genuinely all-cash (`positions.isEmpty()` — `FetchPortfolioPerformance.execute` returns
 *     `emptyList()` immediately), which is durable, and
 *  2. positions exist but their history fetch failed or was too thin — every offline, rate-limited
 *     or upstream-error session lands here, and that is common.
 *
 * A hardcoded `0` would show a user holding real positions "$0 / $500,000 · 0%": a specific, wrong
 * dollar figure for their own portfolio. This collapses to exactly `cash` when there are no
 * positions, preserving the all-cash reading precisely.
 */
fun Portfolio.goalCurrentValueFloor(): Money =
    positions.fold(cash) { acc, position -> acc + position.marketValue(at = position.averageCost) }
