/// The app's interface language. Pure value type — the actual translated strings live in
/// the presentation `L10n` catalog (the way `AccentTheme` is domain but its color ramp is
/// presentation). Only the endonym `displayName` is carried here, for the picker.
public enum AppLanguage: String, CaseIterable, Codable, Sendable {
    case english = "en"
    case german  = "de"
    case italian = "it"
    case spanish = "es"

    /// The language's own name, shown in the picker regardless of the active language.
    public var displayName: String {
        switch self {
        case .english: return "English"
        case .german:  return "Deutsch"
        case .italian: return "Italiano"
        case .spanish: return "Español"
        }
    }
}
