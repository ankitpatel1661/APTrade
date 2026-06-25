import Foundation
import APTradeApplication
import APTradeDomain

/// Holds the user's preferences and writes every change straight through to the
/// `SettingsStore` port. Views bind directly to `settings`; the `didSet` persists.
@MainActor
@Observable
final class SettingsViewModel {
    private let saveSettings: SaveSettingsUseCase

    var settings: AppSettings {
        didSet { if settings != oldValue { saveSettings(settings) } }
    }

    init(loadSettings: LoadSettingsUseCase, saveSettings: SaveSettingsUseCase) {
        self.saveSettings = saveSettings
        self.settings = loadSettings()
    }
}
