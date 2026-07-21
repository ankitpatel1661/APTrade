import XCTest
@testable import APTradeApp
import APTradeDomain

/// Unit tests for `ScreenBuilderModel` — the custom screen builder sheet's local editing
/// state (Task 8). Kept in its own file (mirroring `PieWizardViewModelTests.swift`
/// alongside `PieWizardViewModel`) since it's a distinct type from `ScreenerViewModel`,
/// which already has its own test file for the tab-level save/delete/matchCount surface.
@MainActor
final class ScreenBuilderModelTests: XCTestCase {

    // MARK: - Init

    func test_init_newScreen_startsWithOneEmptyConditionRow_andIsNotEditing() {
        let model = ScreenBuilderModel()

        XCTAssertEqual(model.name, "")
        XCTAssertEqual(model.conditions.count, 1)
        XCTAssertEqual(model.conditions[0].thresholdText, "")
        XCTAssertFalse(model.isEditing)
    }

    func test_init_existingScreen_prefillsNameAndConditions_andIsEditing() {
        let existing = CustomScreen(
            id: "s1", name: "My Screen",
            conditions: [ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30)]
        )

        let model = ScreenBuilderModel(existingScreen: existing)

        XCTAssertEqual(model.name, "My Screen")
        XCTAssertEqual(model.screenId, "s1")
        XCTAssertTrue(model.isEditing)
        XCTAssertEqual(model.conditions.count, 1)
        XCTAssertEqual(model.conditions[0].metric, .rsi14)
        XCTAssertEqual(model.conditions[0].comparison, .below)
        XCTAssertEqual(Double(model.conditions[0].thresholdText), 30)
    }

    func test_init_newScreen_generatesAUUIDScreenId() {
        let a = ScreenBuilderModel()
        let b = ScreenBuilderModel()

        XCTAssertNotEqual(a.screenId, b.screenId)
        XCTAssertNotNil(UUID(uuidString: a.screenId))
    }

    // MARK: - Validation: name

    func test_isValid_falseWhenNameIsEmpty() {
        let model = ScreenBuilderModel()
        model.name = ""
        model.conditions[0].thresholdText = "30"

        XCTAssertFalse(model.isValid)
    }

    func test_isValid_falseWhenNameIsOnlyWhitespace() {
        let model = ScreenBuilderModel()
        model.name = "   "
        model.conditions[0].thresholdText = "30"

        XCTAssertFalse(model.isValid)
    }

    // MARK: - Validation: conditions

    func test_isValid_falseWhenNoConditions() {
        let model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions = []

        XCTAssertFalse(model.isValid)
    }

    func test_isValid_falseWhenAnyThresholdIsNotANumber() {
        let model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions[0].thresholdText = "not a number"

        XCTAssertFalse(model.isValid)
    }

    func test_isValid_falseWhenOneOfMultipleThresholdsIsInvalid() {
        let model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions[0].thresholdText = "30"
        model.addCondition()
        model.conditions[1].thresholdText = "abc"

        XCTAssertFalse(model.isValid)
    }

    func test_isValid_trueWhenNameNonEmpty_conditionPresent_thresholdNumeric() {
        let model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions[0].thresholdText = "30"

        XCTAssertTrue(model.isValid)
    }

    // MARK: - addCondition / removeCondition

    func test_addCondition_appendsANewEmptyDraft() {
        let model = ScreenBuilderModel()

        model.addCondition()

        XCTAssertEqual(model.conditions.count, 2)
        XCTAssertEqual(model.conditions[1].thresholdText, "")
    }

    func test_removeCondition_removesOnlyTheMatchingId() {
        let model = ScreenBuilderModel()
        model.addCondition()
        let keepId = model.conditions[0].id
        let removeId = model.conditions[1].id

        model.removeCondition(id: removeId)

        XCTAssertEqual(model.conditions.map(\.id), [keepId])
    }

    // MARK: - buildScreen

    func test_buildScreen_returnsNilWhenInvalid() {
        let model = ScreenBuilderModel()
        model.name = ""

        XCTAssertNil(model.buildScreen())
    }

    func test_buildScreen_trimsNameAndParsesConditions() {
        let model = ScreenBuilderModel()
        model.name = "  My Screen  "
        model.conditions[0].metric = .rsi14
        model.conditions[0].comparison = .below
        model.conditions[0].thresholdText = "30"

        let screen = model.buildScreen()

        XCTAssertEqual(screen?.name, "My Screen")
        XCTAssertEqual(screen?.conditions, [ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30)])
    }

    func test_buildScreen_newScreen_usesGeneratedScreenId() {
        let model = ScreenBuilderModel()
        model.name = "Screen"
        model.conditions[0].thresholdText = "30"

        XCTAssertEqual(model.buildScreen()?.id, model.screenId)
    }

    func test_buildScreen_editingExistingScreen_preservesOriginalId() {
        let existing = CustomScreen(id: "s1", name: "Original",
                                     conditions: [ScreenCondition(metric: .price, comparison: .above, threshold: 1)])
        let model = ScreenBuilderModel(existingScreen: existing)
        model.name = "Renamed"

        XCTAssertEqual(model.buildScreen()?.id, "s1")
        XCTAssertEqual(model.buildScreen()?.name, "Renamed")
    }

    // MARK: - matchableConditions (live preview while a row is mid-edit)

    func test_matchableConditions_skipsRowsWithUnparseableThresholds() {
        let model = ScreenBuilderModel()
        model.conditions[0].metric = .rsi14
        model.conditions[0].comparison = .below
        model.conditions[0].thresholdText = "30"
        model.addCondition()
        model.conditions[1].thresholdText = "" // still being typed

        XCTAssertEqual(model.matchableConditions, [ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30)])
    }
}
