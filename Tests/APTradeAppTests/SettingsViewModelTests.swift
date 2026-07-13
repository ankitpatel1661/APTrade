import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

/// Pins the Finnhub key flow the iOS Account Settings field drives: the key lives in
/// config.json via the injected closures (NOT in `AppSettings`/the settings store — one
/// source of truth), is trimmed on save, and blank clears it.
@MainActor
final class SettingsViewModelTests: XCTestCase {

    private final class InMemorySettingsStore: SettingsStore, @unchecked Sendable {
        private var settings = AppSettings()
        func load() -> AppSettings { settings }
        func save(_ settings: AppSettings) { self.settings = settings }
    }

    private func makeVM(
        storedKey: String?,
        persisted: @escaping (String) -> Void = { _ in }
    ) -> SettingsViewModel {
        let store = InMemorySettingsStore()
        return SettingsViewModel(
            loadSettings: LoadSettingsUseCase(store: store),
            saveSettings: SaveSettingsUseCase(store: store),
            loadFinnhubKey: { storedKey },
            persistFinnhubKey: persisted
        )
    }

    func test_init_loadsStoredKey_andEmptyWhenAbsent() {
        XCTAssertEqual(makeVM(storedKey: "stored-key").finnhubKey, "stored-key")
        XCTAssertEqual(makeVM(storedKey: nil).finnhubKey, "")
    }

    func test_save_trimsAndPersistsThroughTheInjectedCloser() {
        var persistedValue: String?
        let vm = makeVM(storedKey: nil, persisted: { persistedValue = $0 })

        vm.saveFinnhubKey("  entered-key-123  ")

        XCTAssertEqual(vm.finnhubKey, "entered-key-123")
        XCTAssertEqual(persistedValue, "entered-key-123")
    }

    func test_savingBlank_clearsTheKey() {
        var persistedValue: String? = "sentinel"
        let vm = makeVM(storedKey: "old-key", persisted: { persistedValue = $0 })

        vm.saveFinnhubKey("   ")

        XCTAssertEqual(vm.finnhubKey, "")
        XCTAssertEqual(persistedValue, "")
    }
}
