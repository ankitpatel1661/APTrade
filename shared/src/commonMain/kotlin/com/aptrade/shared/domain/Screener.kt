package com.aptrade.shared.domain

/**
 * Transcribed from `Sources/APTradeDomain/Screener.swift` (the shipped M9.1 Swift/macOS
 * reference) — semantics must not drift, including the STRICT (`<`/`>`) boundary behavior
 * throughout: a value exactly at a threshold matches neither `above` nor `below`.
 *
 * `CustomScreen` is kept a PLAIN (non-`@Serializable`) data class here, following the same
 * house precedent `ScreenerMath.kt` documents for `ScreenerSnapshotRow`/`ScreenerSnapshot`:
 * persistence DTOs are deferred to the store that needs them (Task 4 of this milestone — a
 * `CustomScreenDto` living next to, not on, this domain type).
 */

/** The metrics a screen can condition on — the custom builder's picker plus every value
 *  [ScreenCondition] compares against a threshold. Case names are deliberately camelCase,
 *  matching the Swift `ScreenerMetric` raw values exactly (rather than this file's usual
 *  PascalCase enum convention), since Task 4's persistence DTO will serialize these names
 *  and must stay wire-compatible with the Swift/macOS side. */
enum class ScreenerMetric {
    price, dayChangePercent, rsi14, bollingerPercentB, bollingerBandwidth,
    pctTo52wHigh, pctTo52wLow, relativeVolume, pctVsSma50, pctVsSma200;

    /** The row's value for this metric (price -> close). Null-propagating: a metric backed
     *  by a null field on the row yields null, never a crash. */
    fun value(row: ScreenerSnapshotRow): Double? = when (this) {
        price -> row.close
        dayChangePercent -> row.dayChangePercent
        rsi14 -> row.rsi14
        bollingerPercentB -> row.bollingerPercentB
        bollingerBandwidth -> row.bollingerBandwidth
        pctTo52wHigh -> row.pctTo52wHigh
        pctTo52wLow -> row.pctTo52wLow
        relativeVolume -> row.relativeVolume
        pctVsSma50 -> row.pctVsSma50
        pctVsSma200 -> row.pctVsSma200
    }
}

/** Direction of a threshold comparison. Both sides are STRICT — a value exactly at the
 *  threshold matches neither. Case names are PascalCase; the Swift twin uses lowerCamelCase
 *  ("above"/"below"), and Task 4's persistence DTO must map explicitly, never `.name`. */
enum class ScreenComparison { Above, Below }

/** One metric/comparison/threshold predicate. [matches] is total: a row whose metric is
 *  null (insufficient history) never matches — it is excluded, not crashed on. */
data class ScreenCondition(
    val metric: ScreenerMetric,
    val comparison: ScreenComparison,
    val threshold: Double,
) {
    /** Null metric -> false. Otherwise strict `<`/`>` against [threshold]. */
    fun matches(row: ScreenerSnapshotRow): Boolean {
        val value = metric.value(row) ?: return false
        return when (comparison) {
            ScreenComparison.Above -> value > threshold
            ScreenComparison.Below -> value < threshold
        }
    }
}

/** A user-saved, AND-combined set of conditions. Persisted (unlike presets, which are
 *  code) — kept plain here; see this file's header doc for the DTO precedent. */
data class CustomScreen(
    val id: String,
    val name: String,
    /** AND-combined. Empty conditions match nothing — an unbuilt screen should never
     *  appear to match the whole universe. */
    val conditions: List<ScreenCondition>,
)

/** The 9 curated signal screens. Presets are code, not storage — identified by case.
 *  Case names are PascalCase (RsiOversold, etc.); the Swift twin's raw values are
 *  lowerCamelCase ("rsiOversold", etc.). Task 4's persistence DTO must use EXPLICIT
 *  string mapping, never `.name`, to stay wire-compatible with the Swift/macOS side. */
enum class PresetScreen {
    RsiOversold, RsiOverbought, MacdBullishCross, MacdBearishCross,
    GoldenCross, DeathCross, BollingerSqueeze, Near52wHigh, Near52wLow;

    /** Null-backed numeric metrics never match (no data, no signal). Boolean-flag presets
     *  read the row's precomputed cross flags directly. */
    fun matches(row: ScreenerSnapshotRow): Boolean {
        return when (this) {
            RsiOversold -> (row.rsi14 ?: return false) < 30
            RsiOverbought -> (row.rsi14 ?: return false) > 70
            MacdBullishCross -> row.macdCrossedUp
            MacdBearishCross -> row.macdCrossedDown
            GoldenCross -> row.goldenCross
            DeathCross -> row.deathCross
            BollingerSqueeze -> (row.bollingerBandwidth ?: return false) < 0.05
            Near52wHigh -> (row.pctTo52wHigh ?: return false) < 3
            Near52wLow -> (row.pctTo52wLow ?: return false) < 3
        }
    }
}

/** The active screen the UI runs — preset or custom — with one evaluation door. */
sealed class ScreenSelection {
    data class Preset(val preset: PresetScreen) : ScreenSelection()
    data class Custom(val screen: CustomScreen) : ScreenSelection()

    /** Runs this selection's predicate over [rows], preserving row order. */
    fun evaluate(rows: List<ScreenerSnapshotRow>): List<ScreenerSnapshotRow> = when (this) {
        is Preset -> rows.filter { preset.matches(it) }
        is Custom -> {
            if (screen.conditions.isEmpty()) {
                emptyList()
            } else {
                rows.filter { row -> screen.conditions.all { it.matches(row) } }
            }
        }
    }
}
