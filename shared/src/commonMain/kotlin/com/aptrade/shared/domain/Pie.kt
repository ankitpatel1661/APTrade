package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.random.Random

/**
 * A portfolio allocation strategy: divide cash or holdings proportionally across a set of
 * assets (slices) on a recurring schedule. Transcribed from `Sources/APTradeDomain/Pie.swift`
 * (the shipped M7.1 Swift/macOS reference) — semantics must not drift.
 */

/** How often a contribution is made to a [Pie]. */
enum class PieCadence { Weekly, Biweekly, Monthly }

/**
 * A scheduled recurring contribution to a [Pie].
 *
 * [anchorDay] is the schedule's ORIGINAL first due day, fixed for the schedule's entire
 * lifetime (set once, e.g. by the pie-creation wizard, to the initial [nextDueDay], and
 * never advanced afterward). Cadence step generation (`PieSchedule.dueDays`/`nextDueDay`)
 * must always step from THIS anchor, never from the moving [nextDueDay] cursor below —
 * re-anchoring on the cursor causes permanent monthly drift once a month-length clamp is
 * applied (e.g. a schedule anchored on the 31st: cursor Jan 31 -> clamped to Feb 28 ->
 * re-anchor on Feb 28 -> next step Mar 28 forever, instead of the correct Mar 31). Stepping
 * from the fixed original anchor reproduces the correct Jan 31 -> Feb 28 -> Mar 31 sequence
 * indefinitely.
 *
 * Swift's `Codable` decode falls back to `anchorDay = nextDueDay` when the `anchorDay` key
 * is missing (no persisted schedule predates the field in practice, but decoding
 * pre-existing data should still produce a usable schedule rather than fail outright).
 * Kotlin has no decoder yet for this type — `FilePieStore` (Task 6) is where that
 * absent-key fallback gets a real DTO and a byte-level test; this file only carries the
 * invariant the fallback exists to protect.
 */
data class ContributionSchedule(
    val amount: Money,
    val cadence: PieCadence,
    val anchorDay: String,
    /** The next due day still to be executed. Advances as contributions execute; always
     *  a genuine [anchorDay]-cadence step (never independently derived). */
    val nextDueDay: String,
)

/**
 * One slice of a [Pie]: an asset allocation target.
 *
 * [targetWeightPP] is the target weight in percent points (0..100, summing to exactly 100
 * across a Pie's slices). Kotlin's shared domain has no `Percentage` value object (unlike
 * the Swift domain's `Percentage`, used for `PieSlice.targetWeight` there) — mirroring
 * [Position.quantity]'s use of a raw `BigDecimal` in place of Swift's `Quantity`, this is a
 * deliberate divergence rather than introducing a VO for a single call site. Recorded here
 * so the omission reads as a decision, not an oversight.
 */
data class PieSlice(
    val symbol: String,
    val assetKind: AssetKind,
    val targetWeightPP: BigDecimal,
)

/** A ledger entry tracking the current quantity of an asset held in a [Pie]. */
data class PieLedgerEntry(
    val symbol: String,
    val quantity: BigDecimal,
)

/** The kind of activity recorded in a [Pie]'s activity log. */
enum class PieActivityKind { Contribution, Rebalance, MissedInsufficientCash, ManualAdjustment }

/** Unique-enough activity-entry id without a platform UUID dependency. 128 bits of
 *  randomness — collision-negligible, process-stable. Mirrors [generateTradeId]/
 *  [generateAlertId]'s precedent. */
fun generatePieActivityId(): String =
    "pie-activity-${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"

/** Unique-enough pie id without a platform UUID dependency. 128 bits of randomness —
 *  collision-negligible, process-stable. Mirrors [generateTradeId]/[generateAlertId]'s
 *  precedent. */
fun generatePieId(): String =
    "pie-${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}"

/** A log entry recording an event that occurred on a [Pie] (contribution, rebalance, etc.). */
data class PieActivityEntry(
    val id: String = generatePieActivityId(),
    val kind: PieActivityKind,
    val day: String,
    val amount: Money?,
)

/** Validation errors when constructing a [Pie] via [Pie.create]. */
sealed class PieError(message: String) : Exception(message) {
    object EmptySlices : PieError("Pie must have at least one slice")
    object DuplicateSymbols : PieError("Pie slices must have unique symbols")
    object InvalidWeights : PieError("Pie slice weights must sum to exactly 100")
}

private val ONE_HUNDRED_PERCENT_POINTS: BigDecimal = BigDecimal.parseString("100")

/**
 * A portfolio allocation strategy: divide cash or holdings proportionally across a set of
 * assets ([slices]) on a recurring [schedule]. Each Pie tracks its holdings in a [ledger]
 * and records [activity] (contributions, rebalances, etc.) for auditability.
 *
 * The primary constructor is private — [Pie.create] is the only construction path, and it
 * validates: slices non-empty, symbols unique, and weights sum to exactly 100. This mirrors
 * the Swift original, whose throwing `init` (including the one `Codable` decode routes
 * through) is likewise Pie's only entry point.
 *
 * **[ledger] is NOT validated against [slices].** A ledger entry MAY reference a symbol
 * that isn't (or is no longer) one of [slices] — this is a deliberately permitted,
 * transient state, not an invariant violation: `ReconcilePieLedgers`' clamp walk and
 * decoding legacy/pre-edit data both rebuild a Pie with its [slices] passed through
 * unchanged while only [ledger]/[activity] are touched, and a slice removed via the
 * pie-edit wizard intentionally leaves the ledger momentarily out of sync with [slices]
 * until the wizard's own save-time filter (`PieWizardViewModel.save()`) drops the orphaned
 * entries. Consumers must not assume every [ledger] entry has a matching slice — look up
 * [slices] explicitly (e.g. via [quantityOf] for a KNOWN slice symbol) rather than assuming
 * [ledger]'s symbol set is a subset of [slices]'.
 */
@ConsistentCopyVisibility
data class Pie private constructor(
    val id: String,
    val name: String,
    val slices: List<PieSlice>,
    val schedule: ContributionSchedule?,
    val createdDay: String,
    val ledger: List<PieLedgerEntry>,
    val activity: List<PieActivityEntry>,
) {
    /** The quantity of [symbol] held in this Pie's ledger, or `BigDecimal.ZERO` if the
     *  symbol is not in the ledger. */
    fun quantityOf(symbol: String): BigDecimal =
        ledger.firstOrNull { it.symbol == symbol }?.quantity ?: BigDecimal.ZERO

    companion object {
        /**
         * Create a [Pie] with validation.
         *
         * @param id UUID-shaped string identifier (generated if omitted).
         * @param name Display name for this Pie.
         * @param slices Asset allocation targets.
         * @param schedule Optional recurring contribution schedule.
         * @param createdDay Creation date as `yyyy-MM-dd`.
         * @param ledger Current holdings (defaults to empty).
         * @param activity Activity log entries (defaults to empty).
         *
         * @throws PieError.EmptySlices if [slices] is empty.
         * @throws PieError.DuplicateSymbols if any symbol appears more than once in [slices].
         * @throws PieError.InvalidWeights if [slices]' weights do not sum to exactly 100.
         */
        fun create(
            id: String = generatePieId(),
            name: String,
            slices: List<PieSlice>,
            schedule: ContributionSchedule?,
            createdDay: String,
            ledger: List<PieLedgerEntry> = emptyList(),
            activity: List<PieActivityEntry> = emptyList(),
        ): Pie {
            if (slices.isEmpty()) throw PieError.EmptySlices

            val symbolSet = slices.map { it.symbol }.toSet()
            if (symbolSet.size != slices.size) throw PieError.DuplicateSymbols

            val weightSum = slices.fold(BigDecimal.ZERO) { acc, slice -> acc + slice.targetWeightPP }
            if (weightSum.compareTo(ONE_HUNDRED_PERCENT_POINTS) != 0) throw PieError.InvalidWeights

            return Pie(id, name, slices, schedule, createdDay, ledger, activity)
        }
    }
}
