package com.aptrade.desktop.screener

import com.aptrade.shared.domain.CustomScreen
import com.aptrade.shared.domain.ScreenCondition
import com.aptrade.shared.domain.ScreenComparison
import com.aptrade.shared.domain.ScreenerMetric
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ScreenBuilderModel] — the custom screen builder dialog's local editing
 * state (M9.2 Task 8). Transcribed name-for-name from
 * `Tests/APTradeAppTests/ScreenBuilderModelTests.swift` (the shipped Swift/macOS reference),
 * all 22 tests. Kept in its own file mirroring `PieWizardViewModelTest.kt` alongside
 * `PieWizardViewModel` — a distinct type from `ScreenerViewModelTest.kt`'s tab-level
 * save/delete/matchCount surface.
 *
 * `decimalSeparator` is passed explicitly wherever a test's intent depends on it, rather than
 * relying on the test JVM's default locale (the Swift twin relies on `Locale.current` under
 * Xcode's en_US test runner; pinning the separator here keeps these tests deterministic
 * regardless of the machine/CI locale running Gradle).
 */
class ScreenBuilderModelTest {

    // MARK: - Init

    @Test
    fun test_init_newScreen_startsWithOneEmptyConditionRow_andIsNotEditing() {
        val model = ScreenBuilderModel()

        assertEquals("", model.name)
        assertEquals(1, model.conditions.size)
        assertEquals("", model.conditions[0].thresholdText)
        assertFalse(model.isEditing)
    }

    @Test
    fun test_init_existingScreen_prefillsNameAndConditions_andIsEditing() {
        val existing = CustomScreen(
            id = "s1",
            name = "My Screen",
            conditions = listOf(ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Below, threshold = 30.0)),
        )

        val model = ScreenBuilderModel(existingScreen = existing)

        assertEquals("My Screen", model.name)
        assertEquals("s1", model.screenId)
        assertTrue(model.isEditing)
        assertEquals(1, model.conditions.size)
        assertEquals(ScreenerMetric.rsi14, model.conditions[0].metric)
        assertEquals(ScreenComparison.Below, model.conditions[0].comparison)
        assertEquals(30.0, model.conditions[0].thresholdText.toDouble())
    }

    @Test
    fun test_init_newScreen_generatesAUUIDScreenId() {
        val a = ScreenBuilderModel()
        val b = ScreenBuilderModel()

        assertNotEquals(a.screenId, b.screenId)
        assertNotNull(UUID.fromString(a.screenId))
    }

    // MARK: - Validation: name

    @Test
    fun test_isValid_falseWhenNameIsEmpty() {
        val model = ScreenBuilderModel()
        model.name = ""
        model.conditions[0].thresholdText = "30"

        assertFalse(model.isValid)
    }

    @Test
    fun test_isValid_falseWhenNameIsOnlyWhitespace() {
        val model = ScreenBuilderModel()
        model.name = "   "
        model.conditions[0].thresholdText = "30"

        assertFalse(model.isValid)
    }

    // MARK: - Validation: conditions

    @Test
    fun test_isValid_falseWhenNoConditions() {
        val model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions.clear()

        assertFalse(model.isValid)
    }

    @Test
    fun test_isValid_falseWhenAnyThresholdIsNotANumber() {
        val model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions[0].thresholdText = "not a number"

        assertFalse(model.isValid)
    }

    @Test
    fun test_isValid_falseWhenOneOfMultipleThresholdsIsInvalid() {
        val model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions[0].thresholdText = "30"
        model.addCondition()
        model.conditions[1].thresholdText = "abc"

        assertFalse(model.isValid)
    }

    @Test
    fun test_isValid_trueWhenNameNonEmpty_conditionPresent_thresholdNumeric() {
        val model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions[0].thresholdText = "30"

        assertTrue(model.isValid)
    }

    // MARK: - Locale decimal parsing (comma decimal separator, e.g. DE/IT/ES)

    @Test
    fun test_isValid_true_thresholdWithCommaDecimalSeparator() {
        val model = ScreenBuilderModel(decimalSeparator = ',')
        model.name = "Screen"
        model.conditions[0].thresholdText = "1,5"

        assertTrue(model.isValid)
    }

    @Test
    fun test_isValid_true_thresholdWithDotDecimalSeparator_stillWorks() {
        val model = ScreenBuilderModel(decimalSeparator = '.')
        model.name = "Screen"
        model.conditions[0].thresholdText = "1.5"

        assertTrue(model.isValid)
    }

    @Test
    fun test_isValid_false_thresholdWithBothSeparators_isGarbage() {
        val model = ScreenBuilderModel(decimalSeparator = '.')
        model.name = "Screen"
        model.conditions[0].thresholdText = "1,5.2"

        assertFalse(model.isValid)
    }

    @Test
    fun test_buildScreen_commaDecimalSeparator_parsesAsExpectedDouble() {
        val model = ScreenBuilderModel(decimalSeparator = ',')
        model.name = "Screen"
        model.conditions[0].metric = ScreenerMetric.rsi14
        model.conditions[0].comparison = ScreenComparison.Below
        model.conditions[0].thresholdText = "1,5"

        assertEquals(1.5, model.buildScreen()?.conditions?.first()?.threshold)
    }

    @Test
    fun test_isValid_false_dotDecimalLocale_USGroupedNumeral_1500_isInvalid() {
        // EN locale uses "." as decimal separator; "1,500" is a US-style grouped numeral,
        // not a decimal. With locale-gating, it should NOT be normalized to "1.500" -> 1.5,
        // but instead fail to parse. This test ensures the fix prevents silent corruption of
        // grouped numerals.
        val model = ScreenBuilderModel(decimalSeparator = '.')
        model.name = "Screen"
        model.conditions[0].thresholdText = "1,500"

        assertFalse(model.isValid)
        assertNull(model.buildScreen())
    }

    @Test
    fun test_isValid_true_commaDecimalLocale_1_5_stillParses() {
        // Sanity check: comma-locale users typing "1,5" (a true decimal 1.5) should still
        // parse correctly with the gated normalization.
        val model = ScreenBuilderModel(decimalSeparator = ',')
        model.name = "Screen"
        model.conditions[0].thresholdText = "1,5"

        assertTrue(model.isValid)
        assertEquals(1.5, model.buildScreen()?.conditions?.first()?.threshold)
    }

    // MARK: - addCondition / removeCondition

    @Test
    fun test_addCondition_appendsANewEmptyDraft() {
        val model = ScreenBuilderModel()

        model.addCondition()

        assertEquals(2, model.conditions.size)
        assertEquals("", model.conditions[1].thresholdText)
    }

    @Test
    fun test_removeCondition_removesOnlyTheMatchingId() {
        val model = ScreenBuilderModel()
        model.addCondition()
        val keepId = model.conditions[0].id
        val removeId = model.conditions[1].id

        model.removeCondition(removeId)

        assertEquals(listOf(keepId), model.conditions.map { it.id })
    }

    // MARK: - buildScreen

    @Test
    fun test_buildScreen_returnsNilWhenInvalid() {
        val model = ScreenBuilderModel()
        model.name = ""

        assertNull(model.buildScreen())
    }

    @Test
    fun test_buildScreen_trimsNameAndParsesConditions() {
        val model = ScreenBuilderModel()
        model.name = "  My Screen  "
        model.conditions[0].metric = ScreenerMetric.rsi14
        model.conditions[0].comparison = ScreenComparison.Below
        model.conditions[0].thresholdText = "30"

        val screen = model.buildScreen()

        assertEquals("My Screen", screen?.name)
        assertEquals(
            listOf(ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Below, threshold = 30.0)),
            screen?.conditions,
        )
    }

    @Test
    fun test_buildScreen_newScreen_usesGeneratedScreenId() {
        val model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions[0].thresholdText = "30"

        assertEquals(model.screenId, model.buildScreen()?.id)
    }

    @Test
    fun test_buildScreen_editingExistingScreen_preservesOriginalId() {
        val existing = CustomScreen(
            id = "s1",
            name = "Original",
            conditions = listOf(ScreenCondition(metric = ScreenerMetric.price, comparison = ScreenComparison.Above, threshold = 1.0)),
        )
        val model = ScreenBuilderModel(existingScreen = existing)
        model.name = "Renamed"

        assertEquals("s1", model.buildScreen()?.id)
        assertEquals("Renamed", model.buildScreen()?.name)
    }

    // MARK: - matchableConditions (live preview while a row is mid-edit)

    @Test
    fun test_matchableConditions_skipsRowsWithUnparseableThresholds() {
        val model = ScreenBuilderModel()
        model.conditions[0].metric = ScreenerMetric.rsi14
        model.conditions[0].comparison = ScreenComparison.Below
        model.conditions[0].thresholdText = "30"
        model.addCondition()
        model.conditions[1].thresholdText = "" // still being typed

        assertEquals(
            listOf(ScreenCondition(metric = ScreenerMetric.rsi14, comparison = ScreenComparison.Below, threshold = 30.0)),
            model.matchableConditions,
        )
    }
}
