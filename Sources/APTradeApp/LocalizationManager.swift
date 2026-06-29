import SwiftUI
import APTradeDomain

/// Drives the app's interface language for `tr(_:)`. Mirrors `ThemeManager`: Observation
/// tracks `language`, so any view whose body calls `tr(...)` re-renders when it changes,
/// with no environment plumbing. Persists through the shared settings store.
@MainActor
@Observable
final class LocalizationManager {
    static let shared = LocalizationManager()

    var language: AppLanguage {
        didSet { if language != oldValue { persist() } }
    }

    private init() {
        language = CompositionRoot.settingsStore.load().language
    }

    /// Fresh load-modify-save so only `language` is written — `ThemeManager` owns
    /// `isDarkMode`/`accent` in the same store and must not be clobbered.
    private func persist() {
        var settings = CompositionRoot.settingsStore.load()
        settings.language = language
        CompositionRoot.settingsStore.save(settings)
    }
}
