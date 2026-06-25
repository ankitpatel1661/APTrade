import SwiftUI
import APTradeDomain

extension Color {
    /// Builds a SwiftUI `Color` from the domain's framework-free `AccentRGB` triple.
    init(_ rgb: AccentRGB) {
        self.init(red: rgb.red, green: rgb.green, blue: rgb.blue)
    }
}

/// Drives light/dark mode for `Theme`. Swift's Observation framework tracks property
/// access automatically — any view whose body reads a `Theme` color (which reads
/// `ThemeManager.shared.isDark`) re-renders when the mode flips, with no environment
/// plumbing required.
@MainActor
@Observable
final class ThemeManager {
    static let shared = ThemeManager()

    var isDark: Bool {
        didSet { if isDark != oldValue { persist() } }
    }

    var accent: AccentTheme {
        didSet { if accent != oldValue { persist() } }
    }

    private init() {
        let settings = CompositionRoot.settingsStore.load()
        isDark = settings.isDarkMode
        accent = settings.accent
    }

    func toggle() {
        isDark.toggle()
    }

    /// Writes the appearance choices back through the shared settings store, so theme
    /// and accent live alongside every other preference rather than in their own keys.
    private func persist() {
        var settings = CompositionRoot.settingsStore.load()
        settings.isDarkMode = isDark
        settings.accent = accent
        CompositionRoot.settingsStore.save(settings)
    }
}

/// "Gold on black" (or champagne-on-ivory in light mode): the APTrade identity — a warm
/// ground with a champagne-gold gradient for the brand, chrome, and every interactive
/// accent, plus silver for the secondary wordmark. Price direction stays green/red:
/// color-coding gains and losses is the one signal a trading app must never spend on
/// brand, so semantics win there and never change between modes.
@MainActor
enum Theme {
    private static var isDark: Bool { ThemeManager.shared.isDark }

    static var bgTop: Color {
        isDark ? Color(red: 0.047, green: 0.043, blue: 0.035)   // #0C0B09
              : Color(red: 0.973, green: 0.965, blue: 0.949)    // #F8F6F2
    }
    static var bgBottom: Color {
        isDark ? Color(red: 0.020, green: 0.020, blue: 0.016)   // #050504
              : Color(red: 0.945, green: 0.933, blue: 0.906)    // #F1EEE7
    }
    static var surface: Color {
        isDark ? Color(red: 0.086, green: 0.078, blue: 0.059)   // #16140F
              : Color(red: 0.918, green: 0.902, blue: 0.871)    // #EAE6DE
    }
    static var surfaceHi: Color {
        isDark ? Color(red: 0.129, green: 0.114, blue: 0.082)   // #211D15
              : Color(red: 0.875, green: 0.851, blue: 0.804)    // #DFD9CD
    }
    static var hairline: Color {
        isDark ? Color.white.opacity(0.07) : Color.black.opacity(0.09)
    }

    static var textPrimary: Color {
        isDark ? Color(red: 0.957, green: 0.945, blue: 0.918)   // #F4F1EA warm white
              : Color(red: 0.118, green: 0.110, blue: 0.094)    // #1E1C18 near-black
    }
    static var textSecondary: Color {
        isDark ? Color(red: 0.612, green: 0.588, blue: 0.541)   // #9C968A
              : Color(red: 0.376, green: 0.353, blue: 0.310)    // #605A4F
    }
    static var textTertiary: Color {
        isDark ? Color(red: 0.380, green: 0.361, blue: 0.318)   // #615C51
              : Color(red: 0.557, green: 0.529, blue: 0.475)    // #8E8779
    }

    /// The selected accent's three-stop ramp (deep → mid → light), built from the domain's
    /// `AccentTheme.ramp` palette. Champagne gold is the brand default; the alternates are
    /// premium metallic/jewel tones. Identical across light/dark — the accent is brand
    /// color, not a mode signal.
    static func accentRamp(_ accent: AccentTheme) -> (deep: Color, mid: Color, light: Color) {
        let ramp = accent.ramp
        return (Color(ramp.deep), Color(ramp.mid), Color(ramp.light))
    }

    private static var ramp: (deep: Color, mid: Color, light: Color) {
        accentRamp(ThemeManager.shared.accent)
    }

    // Brand accent ramp — derived from the user's chosen accent, unchanged across modes.
    // (Named "gold" for the default identity; the names persist across accents.)
    static var goldDeep: Color { ramp.deep }
    static var gold: Color { ramp.mid }
    static var goldLight: Color { ramp.light }
    static var silver: Color {
        isDark ? Color(red: 0.847, green: 0.835, blue: 0.808)   // #D8D5CE
              : Color(red: 0.337, green: 0.318, blue: 0.282)    // #564F47
    }

    /// Single-color accent for borders, focus, and live indicators.
    static var accent: Color { gold }

    /// The logo's diagonal gold gradient, for the wordmark and primary actions.
    static var goldGradient: LinearGradient {
        LinearGradient(colors: [goldDeep, gold, goldLight],
                       startPoint: .bottomLeading, endPoint: .topTrailing)
    }

    // Price direction — semantic, deliberately outside the brand palette, identical in both modes.
    static let up = Color(red: 0.275, green: 0.788, blue: 0.541)   // #46C98A
    static let down = Color(red: 0.878, green: 0.416, blue: 0.369) // #E06A5E

    static func changeColor(_ percent: Percentage?) -> Color {
        guard let percent else { return textSecondary }
        if percent.isPositive { return up }
        if percent.isNegative { return down }
        return textSecondary
    }

    static var background: LinearGradient {
        LinearGradient(colors: [bgTop, bgBottom], startPoint: .top, endPoint: .bottom)
    }
}
