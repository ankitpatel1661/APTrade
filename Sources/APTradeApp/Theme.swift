import SwiftUI
import APTradeDomain

/// "Gold on black": the APTrade identity — a warm near-black ground with a champagne-gold
/// gradient for the brand, chrome, and every interactive accent, plus silver for the
/// secondary wordmark. Price direction stays green/red: color-coding gains and losses is
/// the one signal a trading app must never spend on brand, so semantics win there.
enum Theme {
    static let bgTop = Color(red: 0.047, green: 0.043, blue: 0.035)    // #0C0B09
    static let bgBottom = Color(red: 0.020, green: 0.020, blue: 0.016) // #050504
    static let surface = Color(red: 0.086, green: 0.078, blue: 0.059)   // #16140F  cards / track
    static let surfaceHi = Color(red: 0.129, green: 0.114, blue: 0.082) // #211D15  selected / raised
    static let hairline = Color.white.opacity(0.07)

    static let textPrimary = Color(red: 0.957, green: 0.945, blue: 0.918)   // #F4F1EA warm white
    static let textSecondary = Color(red: 0.612, green: 0.588, blue: 0.541) // #9C968A
    static let textTertiary = Color(red: 0.380, green: 0.361, blue: 0.318)  // #615C51

    // Brand golds, sampled from the logo's bottom-to-top gradient.
    static let goldDeep = Color(red: 0.663, green: 0.467, blue: 0.165)  // #A9772A
    static let gold = Color(red: 0.831, green: 0.663, blue: 0.306)      // #D4A94E
    static let goldLight = Color(red: 0.949, green: 0.867, blue: 0.627) // #F2DDA0 champagne
    static let silver = Color(red: 0.847, green: 0.835, blue: 0.808)    // #D8D5CE

    /// Single-color accent for borders, focus, and live indicators.
    static var accent: Color { gold }

    /// The logo's diagonal gold gradient, for the wordmark and primary actions.
    static var goldGradient: LinearGradient {
        LinearGradient(colors: [goldDeep, gold, goldLight],
                       startPoint: .bottomLeading, endPoint: .topTrailing)
    }

    // Price direction — semantic, deliberately outside the brand palette.
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
