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
}
