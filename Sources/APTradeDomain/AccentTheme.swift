/// The selectable brand accent. Gold remains the default identity; the alternates are
/// all metallic/jewel tones chosen to read as premium and to stay clear of the
/// green/red used for price direction, which must never be spent on branding.
public enum AccentTheme: String, Codable, CaseIterable, Sendable {
    case champagneGold
    case roseGold
    case sapphire
    case amethyst
    case platinum

    public var displayName: String {
        switch self {
        case .champagneGold: return "Champagne Gold"
        case .roseGold: return "Rose Gold"
        case .sapphire: return "Sapphire"
        case .amethyst: return "Amethyst"
        case .platinum: return "Platinum"
        }
    }

    public var tagline: String {
        switch self {
        case .champagneGold: return "Default — gold on black"
        case .roseGold: return "Warm copper blush"
        case .sapphire: return "Deep cobalt blue"
        case .amethyst: return "Regal violet"
        case .platinum: return "Cool brushed silver"
        }
    }

    /// The accent's three-stop ramp (deep → mid → light) as sRGB components in 0...1.
    /// This is the single source of truth for the accent's color: the presentation layer
    /// builds SwiftUI `Color`s from it, and the brand-image recolorer maps the logo's gold
    /// pixels onto it so the wordmark follows the chosen accent. Pure data — no framework.
    public var ramp: (deep: AccentRGB, mid: AccentRGB, light: AccentRGB) {
        switch self {
        case .champagneGold:
            return (AccentRGB(0.663, 0.467, 0.165),   // #A9772A
                    AccentRGB(0.831, 0.663, 0.306),   // #D4A94E
                    AccentRGB(0.949, 0.867, 0.627))   // #F2DDA0
        case .roseGold:
            return (AccentRGB(0.557, 0.290, 0.235),   // #8E4A3C
                    AccentRGB(0.804, 0.518, 0.435),   // #CD846F
                    AccentRGB(0.929, 0.769, 0.706))   // #EDC4B4
        case .sapphire:
            return (AccentRGB(0.110, 0.247, 0.451),   // #1C3F73
                    AccentRGB(0.255, 0.498, 0.831),   // #417FD4
                    AccentRGB(0.612, 0.761, 0.945))   // #9CC2F1
        case .amethyst:
            return (AccentRGB(0.318, 0.176, 0.471),   // #512D78
                    AccentRGB(0.541, 0.357, 0.788),   // #8A5BC9
                    AccentRGB(0.776, 0.659, 0.929))   // #C6A8ED
        case .platinum:
            return (AccentRGB(0.392, 0.420, 0.471),   // #646B78
                    AccentRGB(0.639, 0.667, 0.714),   // #A3AAB6
                    AccentRGB(0.855, 0.875, 0.906))   // #DADFE7
        }
    }
}

/// A plain sRGB color triple (each channel 0...1), framework-free so the domain can own
/// the accent palette. The presentation layer maps this onto its own color type.
public struct AccentRGB: Sendable, Equatable {
    public let red: Double
    public let green: Double
    public let blue: Double

    public init(_ red: Double, _ green: Double, _ blue: Double) {
        self.red = red
        self.green = green
        self.blue = blue
    }
}
