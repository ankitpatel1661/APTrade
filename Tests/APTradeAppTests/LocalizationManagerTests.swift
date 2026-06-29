import XCTest
@testable import APTradeApp
@testable import APTradeInfrastructure
import APTradeDomain

@MainActor
final class LocalizationManagerTests: XCTestCase {
    // LocalizationManager.shared persists through CompositionRoot.settingsStore (UserDefaults.standard).
    // Save/restore that key around each test so we don't leak state. The manager itself is a
    // process-wide singleton, so its in-memory `language` must also be restored — otherwise it
    // leaks into later test suites (e.g. anything reading `tr(_:)`/`Key` resolution) regardless
    // of what's persisted to the store.
    private var saved: AppSettings!
    override func setUp() { saved = CompositionRoot.settingsStore.load() }
    override func tearDown() {
        CompositionRoot.settingsStore.save(saved)
        LocalizationManager.shared.language = saved.language
    }

    func test_settingLanguage_persistsThroughStore() {
        LocalizationManager.shared.language = .italian
        XCTAssertEqual(CompositionRoot.settingsStore.load().language, .italian)
    }

    func test_switchingLanguage_doesNotClobberThemeOrAccent() {
        var s = CompositionRoot.settingsStore.load()
        s.isDarkMode = false
        s.accent = .roseGold
        CompositionRoot.settingsStore.save(s)

        LocalizationManager.shared.language = .german

        let after = CompositionRoot.settingsStore.load()
        XCTAssertEqual(after.language, .german)
        XCTAssertFalse(after.isDarkMode)        // untouched
        XCTAssertEqual(after.accent, .roseGold) // untouched
    }
}
