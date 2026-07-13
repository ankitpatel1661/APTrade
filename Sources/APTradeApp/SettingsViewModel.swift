import Foundation
import APTradeApplication
import APTradeDomain

/// Holds the user's preferences and writes every change straight through to the
/// `SettingsStore` port. Views bind directly to `settings`; the `didSet` persists.
///
/// Also carries the Finnhub API key for the Account Settings entry field (iOS — the
/// sandboxed config path isn't user-reachable there). The key deliberately lives OUTSIDE
/// `AppSettings`: its home is config.json (the file `CompositionRoot`'s news factories and
/// the manual file-drop path both read), not the settings store — one source of truth. The
/// load/save closures are injected so this stays infrastructure-free and testable.
@MainActor
@Observable
final class SettingsViewModel {
    private let saveSettings: SaveSettingsUseCase
    private let persistFinnhubKey: (String) -> Void

    var settings: AppSettings {
        didSet { if settings != oldValue { saveSettings(settings) } }
    }

    /// The stored Finnhub key ("" while none is saved). The view keeps a draft against it;
    /// `saveFinnhubKey` is the only mutation path.
    private(set) var finnhubKey: String

    init(
        loadSettings: LoadSettingsUseCase,
        saveSettings: SaveSettingsUseCase,
        loadFinnhubKey: () -> String? = { nil },
        persistFinnhubKey: @escaping (String) -> Void = { _ in }
    ) {
        self.saveSettings = saveSettings
        self.persistFinnhubKey = persistFinnhubKey
        self.settings = loadSettings()
        self.finnhubKey = loadFinnhubKey() ?? ""
    }

    /// Trims and persists the key (blank clears it). The news factories re-read config.json
    /// each time a news view model is built, so the key applies the next time News loads.
    func saveFinnhubKey(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        finnhubKey = trimmed
        persistFinnhubKey(trimmed)
    }
}
