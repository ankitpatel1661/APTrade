package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M11.2 Task 5. Semantics transcribed from `Sources/APTradeDomain/DividendMath.swift` AS-BUILT
 * (post-fix-wave), including the two invariants the Swift wave nearly lost:
 *  - forecast year 1 carries NO growth (carry-notes §3.2), and
 *  - DRIP reinvests at the QUOTED price, falling back to cost basis only per-symbol
 *    (carry-notes §1.1 — reinvesting at cost basis overstated year 30 by ~66%).
 */
class DividendForecastTest {
    private val day = 86_400L
    private fun usd(s: String): Money = Money.usd(s)
    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

    private fun asset(symbol: String) = Asset(symbol, symbol, AssetKind.Stock)
    private fun position(symbol: String, shares: String, cost: String) =
        Position(asset(symbol), qty(shares), usd(cost), usd("0"))

    private fun event(symbol: String, atEpochSeconds: Long, amount: String) =
        DividendEvent(symbol, atEpochSeconds, usd(amount))

    /** A fixed "now" so every window is deterministic: 2026-07-01T00:00:00Z. */
    private val now = 1_782_000_000L

    /** Four quarterly $0.25 payments over the trailing year → $1.00/share/yr, flat history. */
    private fun flatQuarterly(symbol: String): List<DividendEvent> = listOf(
        event(symbol, now - 300 * day, "0.25"),
        event(symbol, now - 209 * day, "0.25"),
        event(symbol, now - 118 * day, "0.25"),
        event(symbol, now - 27 * day, "0.25"),
    )

    /**
     * Twelve quarterly $0.25 payments over three years, phase-matched to [flatQuarterly]
     * (offsets 300/209/118/27 days, then the same pattern 364 days further back, twice more) so
     * [DividendMath.trailingAnnualPerShare] reads exactly FOUR payments ($1.00/share/yr) at
     * `now`. Used by the income/DRIP tests below, which depend on `trailingAnnualPerShare`'s
     * exact value — that function is untouched, pre-existing, out-of-scope code, so those tests
     * still need this off-phase fixture even after Task 5 review Finding 1's fix to
     * [DividendMath.dividendGrowthRate] below. See [flatQuarterlyThreeYearsExactCadenceAliased]
     * for the OTHER phase (newest payment exactly at `now`), which `trailingAnnualPerShare`
     * reads as FIVE payments — a `trailingAnnualPerShare` boundary artifact, not real growth,
     * and out of scope to fix here.
     */
    private fun flatQuarterlyThreeYears(symbol: String): List<DividendEvent> =
        listOf(1028, 937, 846, 755, 664, 573, 482, 391, 300, 209, 118, 27)
            .map { offset -> event(symbol, now - offset * day, "0.25") }

    /**
     * The exact-cadence-aliased phase: naive 91-day multiples with the NEWEST payment landing
     * EXACTLY at `now`. Four 91-day gaps span only 364 days (< 365), so under the PRE-Finding-1
     * `dividendGrowthRate` (which sampled [DividendMath.trailingAnnualPerShare] at two instants)
     * this read as ~13.7% spurious "growth" for a payer that is, per-payment, flat $0.25/quarter
     * — the exact defect Task 5 review Finding 1 requires fixed. Kept as its own fixture
     * (deliberately NOT swapped for [flatQuarterlyThreeYears]) specifically so a regression back
     * to instant-sampling would redden [aFlatThreeYearHistoryHasZeroGrowthEvenWithExactCadenceAliasing].
     */
    private fun flatQuarterlyThreeYearsExactCadenceAliased(symbol: String): List<DividendEvent> =
        (0 until 12).map { i -> event(symbol, now - (11 - i) * 91 * day, "0.25") }

    // MARK: dividendGrowthRate

    @Test
    fun growthRateIsZeroWithFewerThanTwoEvents() {
        assertEquals(BigDecimal.ZERO, DividendMath.dividendGrowthRate(emptyList(), now))
        assertEquals(BigDecimal.ZERO, DividendMath.dividendGrowthRate(listOf(event("A", now, "1")), now))
    }

    @Test
    fun growthRateIsZeroWhenTheWindowSpansUnderTwoYears() {
        assertEquals(BigDecimal.ZERO, DividendMath.dividendGrowthRate(flatQuarterly("A"), now))
    }

    @Test
    fun aFlatThreeYearHistoryHasZeroGrowth() {
        val events = flatQuarterlyThreeYears("A")
        assertEquals(0.0, DividendMath.dividendGrowthRate(events, now).doubleValue(false), 1e-9)
    }

    /** Task 5 review Finding 1: a genuinely flat payer must read exactly 0.0 regardless of
     *  ex-date phase. This is the aliased phase — see [flatQuarterlyThreeYearsExactCadenceAliased]
     *  for why it used to read as ~13.7% spurious growth under the pre-fix algorithm. Together
     *  with [aFlatThreeYearHistoryHasZeroGrowth] (the off-phase fixture), both phases read flat. */
    @Test
    fun aFlatThreeYearHistoryHasZeroGrowthEvenWithExactCadenceAliasing() {
        val events = flatQuarterlyThreeYearsExactCadenceAliased("A")
        assertEquals(0.0, DividendMath.dividendGrowthRate(events, now).doubleValue(false), 1e-9)
    }

    @Test
    fun aDoublingOverTwoYearsAnnualizesAndClampsToTheUpperBound() {
        // Early quarterly $0.25/payment -> late quarterly $0.50/payment (per-payment average
        // doubles). Raw annualized rate is ~43.37% (2.0^(1/1.9243) - 1, spanYears ~1.9243 from a
        // ~2.9243-year first-to-last span) — comfortably past MAX_DIVIDEND_GROWTH, so this is a
        // clamp-engagement test, not a literal "doubles in two years" (~41%) scenario.
        val early = (0 until 4).map { i -> event("A", now - (1095 - i * 91) * day, "0.25") }
        val late = (0 until 4).map { i -> event("A", now - (300 - i * 91) * day, "0.50") }
        val rate = DividendMath.dividendGrowthRate(early + late, now)
        assertEquals(DividendMath.MAX_DIVIDEND_GROWTH, rate)
    }

    @Test
    fun aCollapsingHistoryClampsToTheLowerBound() {
        // Early quarterly $1.00/payment -> late quarterly $0.05/payment (per-payment average
        // falls to 1/20th). Raw annualized rate is roughly -79% — far past MIN_DIVIDEND_GROWTH,
        // so this is a clamp-engagement test, not a literal decay-rate scenario.
        val early = (0 until 4).map { i -> event("A", now - (1095 - i * 91) * day, "1.00") }
        val late = (0 until 4).map { i -> event("A", now - (300 - i * 91) * day, "0.05") }
        assertEquals(DividendMath.MIN_DIVIDEND_GROWTH, DividendMath.dividendGrowthRate(early + late, now))
    }

    /**
     * Task 5 review Finding 2: every other growth-rate assertion in this file resolves to
     * exactly 0.0, MIN, or MAX, so the single most delicate line in the function — the
     * annualization exponent — was completely uncovered (replacing `pow(1.0 / spanYears)` with
     * `pow(spanYears)`, or `spanYears = totalSpanYears` instead of `totalSpanYears - 1.0`, left
     * all 18 tests green). This fixture engineers an EXACT 3.0-year first-to-last span (so
     * `spanYears = totalSpanYears - 1.0 = 2.0` exactly) and an exact $0.36/$0.25 = 1.44
     * early/late per-payment ratio, so `1.44^(1/2) - 1 = 0.20` exactly (up to Double rounding) —
     * comfortably inside both clamps, so a wrong exponent fails loudly instead of silently
     * clamping.
     */
    @Test
    fun dividendGrowthRateAnnualizesAMidRangeRatioPrecisely() {
        val yearSeconds = (365.25 * 86_400.0).toLong()
        val newestOffset = 27 * day
        val oldestOffset = newestOffset + 3 * yearSeconds
        val early = (0 until 4).map { i -> event("A", now - (oldestOffset - i * 91 * day), "0.25") }
        val late = (0 until 4).map { i -> event("A", now - (newestOffset + (3 - i) * 91 * day), "0.36") }
        val rate = DividendMath.dividendGrowthRate(early + late, now)
        assertEquals(0.20, rate.doubleValue(false), 1e-6)
    }

    /** Carry-notes §3.6: the per-symbol clamp is −0.20 … 0.25 and is INDEPENDENT of GoalMath's
     *  −0.50 … 1.00 portfolio clamp. Pinned by exact equality so the two cannot be conflated. */
    @Test
    fun perSymbolClampBoundsAreExact() {
        assertEquals(BigDecimal.parseString("-0.20"), DividendMath.MIN_DIVIDEND_GROWTH)
        assertEquals(BigDecimal.parseString("0.25"), DividendMath.MAX_DIVIDEND_GROWTH)
    }

    // MARK: incomeForecast

    @Test
    fun forecastOfZeroOrFewerYearsIsEmpty() {
        assertTrue(
            DividendMath.incomeForecast(
                positions = listOf(position("A", "100", "50")),
                pricesBySymbol = mapOf("A" to usd("150")),
                eventsBySymbol = mapOf("A" to flatQuarterly("A")),
                years = 0,
                dripEnabled = false,
                asOfEpochSeconds = now,
            ).isEmpty(),
        )
    }

    /** Carry-notes §3.2: yearOffset 1 is the trailing twelve months, growth applied ZERO times. */
    @Test
    fun yearOneIsTheTrailingRateWithNoGrowthApplied() {
        val events = flatQuarterlyThreeYears("A")
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = mapOf("A" to usd("150")),
            eventsBySymbol = mapOf("A" to events),
            years = 3,
            dripEnabled = false,
            asOfEpochSeconds = now,
        )
        assertEquals(1, forecast.first().yearOffset)
        assertEquals(usd("100"), forecast.first().income)   // 100 shares x $1.00 trailing
    }

    /** Carry-notes §3.1: year 1 must equal `projectedAnnualIncome` EXACTLY, or the goal card's
     *  progress % and ETA disagree with the forecast chart rendered beside them. */
    @Test
    fun yearOneEqualsProjectedAnnualIncome() {
        val positions = listOf(position("A", "100", "50"), position("B", "40", "20"))
        val events = mapOf("A" to flatQuarterly("A"), "B" to flatQuarterly("B"))
        val forecast = DividendMath.incomeForecast(
            positions = positions,
            pricesBySymbol = mapOf("A" to usd("150"), "B" to usd("30")),
            eventsBySymbol = events,
            years = 5,
            dripEnabled = true,
            asOfEpochSeconds = now,
        )
        val current = DividendMath.projectedAnnualIncome(positions, events, now)
        assertEquals(current.amount.doubleValue(false), forecast.first().income.amount.doubleValue(false), 1e-9)
    }

    @Test
    fun holdingsWithNoDividendHistoryContributeNothing() {
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50"), position("Z", "100", "10")),
            pricesBySymbol = mapOf("A" to usd("150"), "Z" to usd("12")),
            eventsBySymbol = mapOf("A" to flatQuarterly("A")),
            years = 1,
            dripEnabled = false,
            asOfEpochSeconds = now,
        )
        assertEquals(usd("100"), forecast.single().income)
    }

    @Test
    fun withoutDripAFlatPayerProducesAFlatCurve() {
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = mapOf("A" to usd("150")),
            eventsBySymbol = mapOf("A" to flatQuarterlyThreeYears("A")),
            years = 5,
            dripEnabled = false,
            asOfEpochSeconds = now,
        )
        assertEquals(listOf(1, 2, 3, 4, 5), forecast.map { it.yearOffset })
        for (year in forecast) assertEquals(100.0, year.income.amount.doubleValue(false), 1e-9)
    }

    /** THE §1.1 REGRESSION. A holding bought at $50 now trading at $150 must reinvest at $150.
     *  Reinvesting at the $50 cost basis buys 3x the shares per year and overstates the curve. */
    @Test
    fun dripReinvestsAtTheQuotedPriceNotCostBasis() {
        val events = flatQuarterlyThreeYears("A")
        fun run(price: Money) = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = mapOf("A" to price),
            eventsBySymbol = mapOf("A" to events),
            years = 2,
            dripEnabled = true,
            asOfEpochSeconds = now,
        )
        // Year 1 income $100 buys 100/150 shares at the quote -> year 2 = 100.6667 x $1.00.
        assertEquals(100.0 + 100.0 / 150.0, run(usd("150")).last().income.amount.doubleValue(false), 1e-6)
        // At cost basis it would be 100 + 100/50 = 102 — the overstatement this test forbids.
        assertTrue(run(usd("150")).last().income.amount < run(usd("50")).last().income.amount)
    }

    @Test
    fun aSymbolMissingFromPricesBySymbolFallsBackToItsOwnCostBasis() {
        val events = flatQuarterlyThreeYears("A")
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = emptyMap(),
            eventsBySymbol = mapOf("A" to events),
            years = 2,
            dripEnabled = true,
            asOfEpochSeconds = now,
        )
        assertEquals(102.0, forecast.last().income.amount.doubleValue(false), 1e-6)
    }

    @Test
    fun growthCompoundsFromYearTwoOnward() {
        // 25%-clamped grower: year1 = trailing, year2 = trailing x 1.25.
        val early = (0 until 4).map { i -> event("A", now - (1095 - i * 91) * day, "0.25") }
        val late = (0 until 4).map { i -> event("A", now - (300 - i * 91) * day, "0.50") }
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = mapOf("A" to usd("150")),
            eventsBySymbol = mapOf("A" to (early + late)),
            years = 2,
            dripEnabled = false,
            asOfEpochSeconds = now,
        )
        assertEquals(200.0, forecast.first().income.amount.doubleValue(false), 1e-9)
        assertEquals(250.0, forecast.last().income.amount.doubleValue(false), 1e-9)
    }

    /**
     * Task 5 review Finding 3: every DRIP test above has growth 0, and every growth test has
     * DRIP off — the interaction (the reinvestment price growing at the SAME clamped dividend
     * growth rate) is the load-bearing modelling choice Tasks 6 and 11 both inherit, and had
     * zero coverage. 100 shares, $150 quote, $1.00/share trailing (four $0.25 quarterly
     * payments — [flatQuarterly]'s exact phase), early quarterly $0.125/payment so the
     * early/late ratio (same shape as [growthCompoundsFromYearTwoOnward]) again clamps to
     * MAX_DIVIDEND_GROWTH regardless of the exact raw value. Five-year series independently
     * re-derived (Python, same recurrence as [DividendMath.incomeForecast]):
     *  - with DRIP:    100, 125.833333, 158.340278, 199.244907, 250.716...
     *  - without DRIP: 100, 125,        156.25,     195.3125,   244.140625
     */
    @Test
    fun dripWithNonZeroGrowthCompoundsSharesAndRateTogetherAcrossFiveYears() {
        val early = (0 until 4).map { i -> event("A", now - (1095 - i * 91) * day, "0.125") }
        val late = (0 until 4).map { i -> event("A", now - (300 - i * 91) * day, "0.25") }
        val events = mapOf("A" to (early + late))

        fun run(drip: Boolean) = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = mapOf("A" to usd("150")),
            eventsBySymbol = events,
            years = 5,
            dripEnabled = drip,
            asOfEpochSeconds = now,
        ).map { it.income.amount.doubleValue(false) }

        val growth = DividendMath.dividendGrowthRate(early + late, now)
        assertEquals(DividendMath.MAX_DIVIDEND_GROWTH, growth)

        val expectedWithDrip = listOf(100.0, 125.833333, 158.340278, 199.244907, 250.716436)
        val expectedWithoutDrip = listOf(100.0, 125.0, 156.25, 195.3125, 244.140625)

        run(true).zip(expectedWithDrip).forEach { (actual, expected) -> assertEquals(expected, actual, 1e-3) }
        run(false).zip(expectedWithoutDrip).forEach { (actual, expected) -> assertEquals(expected, actual, 1e-9) }
    }

    // MARK: projectedSchedule

    @Test
    fun scheduleRollsCadenceForwardWithinTheWindowAscending() {
        val rows = DividendMath.projectedSchedule(
            positions = listOf(position("A", "100", "50")),
            eventsBySymbol = mapOf("A" to flatQuarterly("A")),
            throughEpochSeconds = now + 365 * day,
            asOfEpochSeconds = now,
        )
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.exDateEpochSeconds > now && it.exDateEpochSeconds <= now + 365 * day })
        assertEquals(rows.map { it.exDateEpochSeconds }.sorted(), rows.map { it.exDateEpochSeconds })
        assertEquals(usd("25"), rows.first().estimatedAmount)   // 100 shares x $0.25
    }

    @Test
    fun aStaleProjectionIsRolledForwardUntilItIsGenuinelyInTheFuture() {
        // Last payment two years ago: nextProjected lands in the past and must be rolled.
        val stale = listOf(
            event("A", now - 820 * day, "0.25"),
            event("A", now - 729 * day, "0.25"),
        )
        val rows = DividendMath.projectedSchedule(
            positions = listOf(position("A", "100", "50")),
            eventsBySymbol = mapOf("A" to stale),
            throughEpochSeconds = now + 365 * day,
            asOfEpochSeconds = now,
        )
        assertTrue(rows.all { it.exDateEpochSeconds > now })
    }

    @Test
    fun aHoldingWithNoInferableCadenceContributesNoRows() {
        val rows = DividendMath.projectedSchedule(
            positions = listOf(position("A", "100", "50")),
            eventsBySymbol = mapOf("A" to listOf(event("A", now - 30 * day, "0.25"))),
            throughEpochSeconds = now + 365 * day,
            asOfEpochSeconds = now,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun everyScheduledRowCarriesItsPerShareRateSoTheUiCanLabelItAnEstimate() {
        val rows = DividendMath.projectedSchedule(
            positions = listOf(position("A", "100", "50")),
            eventsBySymbol = mapOf("A" to flatQuarterly("A")),
            throughEpochSeconds = now + 200 * day,
            asOfEpochSeconds = now,
        )
        assertTrue(rows.all { it.perShare == usd("0.25") })
    }
}
