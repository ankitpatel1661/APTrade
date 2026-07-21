package com.aptrade.android.screener

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.ScreenComparison
import com.aptrade.shared.domain.ScreenCondition
import com.aptrade.shared.domain.ScreenerMetric
import java.text.DecimalFormatSymbols
import java.util.UUID

// MARK: - ScreenBuilderModel
//
// SANCTIONED LAYOUT DIFFERENCE from desktop: desktop's `ScreenBuilderDialog.kt` keeps this model
// class alongside its `ScreenBuilderDialog` composable in one file. Android splits the two —
// this model lives in its own file so it stays unit-testable apart from `ScreenBuilderSheet`
// (the Compose UI ships without unit tests per the standing waiver; this model carries the
// behavior instead). No behavioral difference from the desktop twin.

/**
 * Local editing state for the custom screen builder sheet: a name, an ordered list of draft
 * conditions, and the validation that gates Save. Deliberately store-free — it only shapes a
 * [CustomScreen] in memory; [ScreenerViewModel.saveScreen]/[ScreenerViewModel.deleteScreen] own
 * the actual persistence, and [ScreenBuilderSheet] wires this model to those calls.
 *
 * Compose port of `Sources/APTradeApp/ScreenBuilderSheet.swift`'s `ScreenBuilderModel` AS-BUILT,
 * transcribed byte-faithfully from desktop's `ScreenBuilderDialog.kt`'s `ScreenBuilderModel`
 * (M9.2 Task 8).
 *
 * DELIBERATE DIVERGENCE from the Swift twin's per-row `Binding` plumbing: [ConditionDraft]'s
 * fields are Compose `mutableStateOf`-backed `var`s on a reference-type class (not a Swift
 * `struct` copied in/out of an index/id-keyed `Binding`), so a picker's `onSelect` or a text
 * field's `onValueChange` can mutate the draft object directly (e.g. `draft.metric = it`) —
 * Compose's snapshot-state system tracks that write exactly like the Swift `@Observable` macro
 * tracks a struct-array element write, with none of `ScreenBuilderSheet`'s
 * `metricBinding(for:)`/`comparisonBinding(for:)`/`thresholdBinding(for:)` id-lookup machinery
 * needed. [conditions] itself is a `val` [androidx.compose.runtime.snapshots.SnapshotStateList]
 * (mutated in place via `add`/`removeAll`/`clear`), not a reassignable Swift `var
 * [ConditionDraft]` — tests that reset the Swift twin's array (`model.conditions = []`) instead
 * call `model.conditions.clear()` here.
 */
class ScreenBuilderModel(
    existingScreen: CustomScreen? = null,
    /** The locale's decimal separator. Injected for testability; defaults to the JVM's active
     *  locale (Android's runtime is a JVM, so `java.text.DecimalFormatSymbols` is safe to use
     *  directly). Gates comma-normalization in [parsedThreshold]: only replace "," with "."
     *  when the locale's separator is ",". See that function's doc for the full rationale. */
    val decimalSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator,
) {

    /** One condition row's editable state. `thresholdText` is free-form user input —
     *  intentionally a `String`, not a `Double` — so a row can sit in an unparseable,
     *  mid-typing state (e.g. "-", "") without discarding what the user has typed so far;
     *  validation and parsing happen at [isValid]/[buildScreen], not on every keystroke. */
    class ConditionDraft(
        val id: String = UUID.randomUUID().toString(),
        metric: ScreenerMetric = ScreenerMetric.price,
        comparison: ScreenComparison = ScreenComparison.Above,
        thresholdText: String = "",
    ) {
        var metric: ScreenerMetric by mutableStateOf(metric)
        var comparison: ScreenComparison by mutableStateOf(comparison)
        var thresholdText: String by mutableStateOf(thresholdText)
    }

    var name: String by mutableStateOf("")

    /** Mutated in place (`add`/`removeAll`/`clear`) rather than reassigned — see this class's
     *  doc for why a `SnapshotStateList` replaces the Swift twin's reassignable array. */
    val conditions = mutableStateListOf<ConditionDraft>()

    /** The id a saved [CustomScreen] will carry: the original screen's id when editing, or a
     *  freshly generated one for a brand-new screen — decided once at construction so it stays
     *  stable across edits within one dialog session. */
    val screenId: String
    val isEditing: Boolean

    init {
        if (existingScreen != null) {
            screenId = existingScreen.id
            name = existingScreen.name
            conditions.addAll(
                existingScreen.conditions.map {
                    ConditionDraft(metric = it.metric, comparison = it.comparison, thresholdText = textFor(it.threshold))
                },
            )
            isEditing = true
        } else {
            screenId = UUID.randomUUID().toString()
            conditions.add(ConditionDraft())
            isEditing = false
        }
    }

    /** Non-empty trimmed name, at least one condition row, and every row's threshold a
     *  parseable number — the exact three rules Task 8's brief calls out. */
    val isValid: Boolean
        get() = trimmedName().isNotEmpty() && conditions.isNotEmpty() &&
            conditions.all { parsedThreshold(it.thresholdText) != null }

    fun addCondition() {
        conditions.add(ConditionDraft())
    }

    fun removeCondition(id: String) {
        conditions.removeAll { it.id == id }
    }

    /** The [CustomScreen] this draft represents, or `null` while [isValid] is false — callers
     *  (the sheet's Save button) should treat a `null` result as "can't save yet" rather than
     *  force-unwrap. */
    fun buildScreen(): CustomScreen? {
        if (!isValid) return null
        val built = conditions.mapNotNull { draft ->
            val threshold = parsedThreshold(draft.thresholdText) ?: return@mapNotNull null
            ScreenCondition(metric = draft.metric, comparison = draft.comparison, threshold = threshold)
        }
        return CustomScreen(id = screenId, name = trimmedName(), conditions = built)
    }

    /** Best-effort conditions for the sheet's LIVE match-count preview: rows with a
     *  still-being-typed (unparseable) threshold are skipped rather than blocking the whole
     *  preview — unlike [buildScreen], which requires every row valid before it will produce
     *  anything at all. A user midway through typing a second condition's threshold still sees
     *  a live count reflecting the rows that already parse. */
    val matchableConditions: List<ScreenCondition>
        get() = conditions.mapNotNull { draft ->
            val threshold = parsedThreshold(draft.thresholdText) ?: return@mapNotNull null
            ScreenCondition(metric = draft.metric, comparison = draft.comparison, threshold = threshold)
        }

    private fun trimmedName(): String = name.trim()

    /** Parses a threshold field's raw text as a `Double`. Normalizes a comma decimal separator
     *  to a dot BEFORE parsing ONLY when [decimalSeparator] is ',' (e.g. DE/IT/ES locales).
     *  Gating on the locale prevents silent corruption of US-style grouped numerals (e.g.
     *  "1,500" on an EN/dot locale now correctly fails to parse instead of becoming 1.5). This
     *  is a simple, predictable normalization (not a full locale-aware parse) — good enough for
     *  a plain decimal threshold, and it correctly still rejects genuine garbage like "1,5.2"
     *  (becomes "1.5.2", which fails to parse either way). */
    private fun parsedThreshold(text: String): Double? {
        val trimmed = text.trim()
        val normalized = if (decimalSeparator == ',') trimmed.replace(',', '.') else trimmed
        return normalized.toDoubleOrNull()
    }

    companion object {
        /** Pre-fill text for an existing condition's threshold — whole numbers render without
         *  a trailing ".0" (e.g. `30` not `30.0`), matching how a user would actually type it. */
        private fun textFor(threshold: Double): String =
            if (threshold == Math.floor(threshold) && !threshold.isInfinite()) threshold.toLong().toString() else threshold.toString()
    }
}
