package com.aptrade.shared.application

import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.DividendMath
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Position
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.domain.Transaction
import com.aptrade.shared.infrastructure.formatUtcDate
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CancellationException

/**
 * One projected future payout, reduced to the three facts [HomeIncomeSummary] (and the
 * `AppGraph.buildHomeIncomeSummary` callers that build it, desktop + Android) need. Stays on
 * the epoch-seconds ex-date convention — the SAME convention [DividendMath]/[DividendEvent]
 * already use — so the day-type conversion to [kotlinx.datetime.LocalDate] for
 * [HomeUpcomingDividend] happens exactly once, at the AppGraph wiring seam, per that class's
 * own KDoc (never here).
 */
data class NextDividendProjection(
    val symbol: String,
    val exDateEpochSeconds: Long,
    val estimatedAmount: Money,
)

/**
 * Pure income-summary orchestration, HOISTED (M10.3 Task 1) from what was, until this task,
 * duplicated three ways: desktop `AppGraph.buildHomeIncomeSummary` (a private top-level
 * function) and privately-named-identically `receivedYTD`/`buildUpcoming`-shaped logic inside
 * both desktop's and Android's `IncomeViewModel`. All three computed the SAME two things —
 * "which Dividend transactions fall in the current UTC calendar year" and "the single
 * soonest-upcoming, non-crypto dividend projection strictly after `asOf`" — over the SAME
 * underlying pieces ([Transaction]/[Position] plus [DividendMath.nextProjected]). Two reviews
 * (M10.2 final review, M10.3 plan) traced this as "provably equivalent today but unguarded" —
 * three copies that could silently drift the next time either changed. This object is the ONE
 * implementation both platforms' `AppGraph.buildHomeIncomeSummary` (desktop) and Android's Home
 * wiring (Task 1) now call.
 *
 * Neither function here touches [IncomeViewModel]'s OWN state shape (desktop's `UpcomingRow`/
 * `SummaryCards`, e.g.) — those VMs keep computing their full tab UI locally; only the two
 * pieces Home's [HomeIncomeSummary] needs are hoisted, matching the M10.2 plan's Global
 * Constraint 2 ("wrap the existing pipeline, never re-derive dividend math a second time").
 */
object IncomeSummaryMath {

    /**
     * Sum of `Dividend`-side transaction cash whose UTC calendar year matches
     * [asOfEpochSeconds]'s. Reuses [formatUtcDate] (shared/infrastructure/UtcDate.kt) for the
     * UTC epoch-seconds -> civil-year conversion — the ALREADY-EXPOSED shared civil-date
     * helper — rather than adding yet another private Hinnant-algorithm copy to this
     * codebase's existing pile (`DividendMath.kt`, `MarketCalendar.kt`, `PieSchedule.kt`, both
     * platforms' `IncomeViewModel.kt`, desktop `AppGraph.kt` before this hoist).
     */
    fun receivedYTD(transactions: List<Transaction>, asOfEpochSeconds: Long): Money {
        val currentYear = utcYear(asOfEpochSeconds)
        var total = Money(BigDecimal.ZERO, "USD")
        for (txn in transactions) {
            if (txn.side != TradeSide.Dividend) continue
            if (utcYear(txn.epochSeconds) != currentYear) continue
            total += Money(txn.price.amount * txn.quantity, txn.price.currencyCode)
        }
        return total
    }

    /**
     * The single soonest-upcoming dividend across [positions]' NON-CRYPTO holdings, or `null`
     * when none project forward. Wraps [DividendMath.nextProjected] per holding, exactly as
     * desktop `AppGraph.buildHomeIncomeSummary`/both platforms' `IncomeViewModel.buildUpcoming`
     * did: a STALE projection (`exDateEpochSeconds <= asOfEpochSeconds`) is discarded, and on a
     * tie the FIRST holding encountered wins (only replaced on a strict `<`, matching
     * [DividendMath.nextProjected]'s own `maxByOrNull` first-on-tie doc) rather than the last.
     *
     * [dividendEventsFetcher] is called once per non-crypto symbol with
     * `asOfEpochSeconds - lookbackSeconds` as the events-window start. A single symbol's
     * [QuoteError] is caught HERE and degrades that symbol only (empty events, so it
     * contributes no projection) — the SAME per-symbol isolation
     * `IncomeViewModel.load()`/`buildHomeIncomeSummary` applied — never the others, and
     * [CancellationException] is always rethrown first, never swallowed.
     */
    suspend fun nextUpcomingDividend(
        positions: List<Position>,
        dividendEventsFetcher: suspend (symbol: String, sinceEpochSeconds: Long) -> List<DividendEvent>,
        asOfEpochSeconds: Long,
        lookbackSeconds: Long,
    ): NextDividendProjection? {
        val nonCryptoPositions = positions.filter { it.asset.kind != AssetKind.Crypto }
        var best: NextDividendProjection? = null
        for (position in nonCryptoPositions) {
            val symbol = position.asset.symbol
            val events = try {
                dividendEventsFetcher(symbol, asOfEpochSeconds - lookbackSeconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: QuoteError) {
                emptyList()
            }
            val projected = DividendMath.nextProjected(events) ?: continue
            if (projected.exDateEpochSeconds <= asOfEpochSeconds) continue
            if (best == null || projected.exDateEpochSeconds < best.exDateEpochSeconds) {
                best = NextDividendProjection(
                    symbol = symbol,
                    exDateEpochSeconds = projected.exDateEpochSeconds,
                    estimatedAmount = Money(
                        projected.amountPerShare.amount * position.quantity,
                        projected.amountPerShare.currencyCode,
                    ),
                )
            }
        }
        return best
    }

    private fun utcYear(epochSeconds: Long): Long = formatUtcDate(epochSeconds).substring(0, 4).toLong()
}
