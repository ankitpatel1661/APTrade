/// User-adjustable application preferences. Pure value type — persisted by an
/// infrastructure adapter behind the `SettingsStore` port. Defaults match the app's
/// out-of-the-box behavior so a fresh install needs no seeding.
public struct AppSettings: Equatable, Codable, Sendable {
    // Notifications
    public var priceAlerts: Bool
    public var orderFills: Bool
    public var marketOpenClose: Bool
    public var newsDigest: Bool
    public var emailNotifications: Bool
    public var earningsReports: Bool

    // Security & privacy
    public var biometricLogin: Bool
    public var requireAuthOnLaunch: Bool
    public var confirmTrades: Bool
    public var analyticsSharing: Bool

    // Appearance
    public var isDarkMode: Bool
    public var accent: AccentTheme

    // Language
    public var language: AppLanguage

    public init(
        priceAlerts: Bool = true,
        orderFills: Bool = true,
        marketOpenClose: Bool = false,
        newsDigest: Bool = true,
        emailNotifications: Bool = false,
        earningsReports: Bool = true,
        biometricLogin: Bool = true,
        requireAuthOnLaunch: Bool = true,
        confirmTrades: Bool = true,
        analyticsSharing: Bool = false,
        isDarkMode: Bool = true,
        accent: AccentTheme = .champagneGold,
        language: AppLanguage = .english
    ) {
        self.priceAlerts = priceAlerts
        self.orderFills = orderFills
        self.marketOpenClose = marketOpenClose
        self.newsDigest = newsDigest
        self.emailNotifications = emailNotifications
        self.earningsReports = earningsReports
        self.biometricLogin = biometricLogin
        self.requireAuthOnLaunch = requireAuthOnLaunch
        self.confirmTrades = confirmTrades
        self.analyticsSharing = analyticsSharing
        self.isDarkMode = isDarkMode
        self.accent = accent
        self.language = language
    }

    /// Lenient decode: any key absent from an older persisted payload falls back to its
    /// default, so adding a new preference never resets the user's existing choices.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings.default
        priceAlerts = try c.decodeIfPresent(Bool.self, forKey: .priceAlerts) ?? d.priceAlerts
        orderFills = try c.decodeIfPresent(Bool.self, forKey: .orderFills) ?? d.orderFills
        marketOpenClose = try c.decodeIfPresent(Bool.self, forKey: .marketOpenClose) ?? d.marketOpenClose
        newsDigest = try c.decodeIfPresent(Bool.self, forKey: .newsDigest) ?? d.newsDigest
        emailNotifications = try c.decodeIfPresent(Bool.self, forKey: .emailNotifications) ?? d.emailNotifications
        earningsReports = try c.decodeIfPresent(Bool.self, forKey: .earningsReports) ?? d.earningsReports
        biometricLogin = try c.decodeIfPresent(Bool.self, forKey: .biometricLogin) ?? d.biometricLogin
        requireAuthOnLaunch = try c.decodeIfPresent(Bool.self, forKey: .requireAuthOnLaunch) ?? d.requireAuthOnLaunch
        confirmTrades = try c.decodeIfPresent(Bool.self, forKey: .confirmTrades) ?? d.confirmTrades
        analyticsSharing = try c.decodeIfPresent(Bool.self, forKey: .analyticsSharing) ?? d.analyticsSharing
        isDarkMode = try c.decodeIfPresent(Bool.self, forKey: .isDarkMode) ?? d.isDarkMode
        accent = try c.decodeIfPresent(AccentTheme.self, forKey: .accent) ?? d.accent
        language = try c.decodeIfPresent(AppLanguage.self, forKey: .language) ?? d.language
    }

    public static let `default` = AppSettings()
}
