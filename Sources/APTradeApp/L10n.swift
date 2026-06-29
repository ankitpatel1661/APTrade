import APTradeDomain

/// Typed UI-string catalog. Each `Key`'s `String` raw value is the English source text,
/// so it doubles as a never-empty last-resort fallback. Translations for all four
/// languages live in `table`; the catalog-completeness test enforces full coverage.
/// Migration tasks add `Key` cases + `table` rows per view file.
enum L10n {
    enum Key: String, CaseIterable {
        // MARK: Navigation / core
        case watchlist = "Watchlist"
        case portfolio = "Portfolio"
        case news = "News"
        case language = "Language"

        // MARK: Settings / account
        case account = "Account"
        case profile = "Profile"
        case accountSettings = "Account Settings"
        case notifications = "Notifications"
        case appearance = "Appearance"
        case exportPortfolioData = "Export Portfolio Data"
        case securityAndPrivacy = "Security & Privacy"
        case helpAndSupport = "Help & Support"
        case aboutAPTrade = "About APTrade"
        case signOut = "Sign Out"
        case login = "Login"
        case cancel = "Cancel"
        case ok = "OK"
        case done = "Done"
        case exportFailed = "Export Failed"
        case exportFormatPrompt = "Choose a format to save your holdings, cost basis, and P&L."

        case name = "Name"
        case dateOfBirth = "Date of Birth"
        case email = "Email"

        case tradingMode = "Trading Mode"
        case startingBalance = "Starting Balance"
        case displayCurrency = "Display Currency"
        case defaultTab = "Default Tab"
        case biometricLogin = "Biometric Login"
        case simulatedPaperTrading = "Simulated · Paper Trading"
        case enabledTouchID = "Enabled — Touch ID"

        case pushNotifications = "Push Notifications"
        case priceAlerts = "Price Alerts"
        case priceAlertsSubtitle = "When a watchlist alert is triggered"
        case orderFills = "Order Fills"
        case orderFillsSubtitle = "Buy and sell confirmations"
        case marketOpenAndClose = "Market Open & Close"
        case marketOpenAndCloseSubtitle = "Daily session reminders"
        case dailyNewsDigest = "Daily News Digest"
        case dailyNewsDigestSubtitle = "Top stories for your holdings"
        case emailNotifications = "Email Notifications"
        case emailNotificationsSubtitle = "Send a copy to ankitpatel.svnit@gmail.com"

        case authentication = "Authentication"
        case biometricLoginSubtitle = "Unlock with Touch ID / Face ID"
        case requireAuthOnLaunch = "Require Auth on Launch"
        case requireAuthOnLaunchSubtitle = "Ask every time the app opens"
        case confirmTrades = "Confirm Trades"
        case confirmTradesSubtitle = "Re-authenticate before buy / sell"
        case privacy = "Privacy"
        case shareUsageAnalytics = "Share Usage Analytics"
        case shareUsageAnalyticsSubtitle = "Anonymous diagnostics to improve APTrade"
        case dataSection = "Data"
        case changePassword = "Change Password"
        case manageDevices = "Manage Devices"
        case clearLocalCache = "Clear Local Cache"

        case resources = "Resources"
        case faq = "Frequently Asked Questions"
        case userGuide = "User Guide"
        case keyboardShortcuts = "Keyboard Shortcuts"
        case contact = "Contact"
        case emailSupport = "Email Support"
        case reportAProblem = "Report a Problem"

        case version = "Version"
        case dataProviders = "Data Providers"
        case mode = "Mode"
        case taglineShort = "Premium investing for macOS"
        case termsOfService = "Terms of Service"
        case privacyPolicy = "Privacy Policy"
        case licenses = "Licenses"
        case copyrightDisclaimer = "© 2026 APTrade. Market data for informational purposes only."

        // MARK: Appearance
        case theme = "Theme"
        case accent = "Accent"
        case dark = "Dark"
        case darkSubtitle = "Default — gold on black"
        case light = "Light"
        case lightSubtitle = "Charcoal on warm white"
    }

    static let table: [Key: [AppLanguage: String]] = [
        .watchlist: [.english: "Watchlist", .german: "Beobachtungsliste",
                     .italian: "Lista di controllo", .spanish: "Lista de seguimiento"],
        .portfolio: [.english: "Portfolio", .german: "Portfolio",
                     .italian: "Portafoglio", .spanish: "Cartera"],
        .news:      [.english: "News", .german: "Nachrichten",
                     .italian: "Notizie", .spanish: "Noticias"],
        .language:  [.english: "Language", .german: "Sprache",
                     .italian: "Lingua", .spanish: "Idioma"],

        // MARK: Settings / account
        .account: [.english: "Account", .german: "Konto",
                   .italian: "Account", .spanish: "Cuenta"],
        .profile: [.english: "Profile", .german: "Profil",
                   .italian: "Profilo", .spanish: "Perfil"],
        .accountSettings: [.english: "Account Settings", .german: "Kontoeinstellungen",
                           .italian: "Impostazioni account", .spanish: "Ajustes de cuenta"],
        .notifications: [.english: "Notifications", .german: "Benachrichtigungen",
                         .italian: "Notifiche", .spanish: "Notificaciones"],
        .appearance: [.english: "Appearance", .german: "Darstellung",
                     .italian: "Aspetto", .spanish: "Apariencia"],
        .exportPortfolioData: [.english: "Export Portfolio Data", .german: "Portfoliodaten exportieren",
                               .italian: "Esporta dati del portafoglio", .spanish: "Exportar datos de la cartera"],
        .securityAndPrivacy: [.english: "Security & Privacy", .german: "Sicherheit & Datenschutz",
                              .italian: "Sicurezza e privacy", .spanish: "Seguridad y privacidad"],
        .helpAndSupport: [.english: "Help & Support", .german: "Hilfe & Support",
                          .italian: "Assistenza e supporto", .spanish: "Ayuda y soporte"],
        .aboutAPTrade: [.english: "About APTrade", .german: "Über APTrade",
                        .italian: "Informazioni su APTrade", .spanish: "Acerca de APTrade"],
        .signOut: [.english: "Sign Out", .german: "Abmelden",
                   .italian: "Esci", .spanish: "Cerrar sesión"],
        .login: [.english: "Login", .german: "Anmelden",
                 .italian: "Accedi", .spanish: "Iniciar sesión"],
        .cancel: [.english: "Cancel", .german: "Abbrechen",
                  .italian: "Annulla", .spanish: "Cancelar"],
        .ok: [.english: "OK", .german: "OK",
              .italian: "OK", .spanish: "Aceptar"],
        .done: [.english: "Done", .german: "Fertig",
                .italian: "Fine", .spanish: "Listo"],
        .exportFailed: [.english: "Export Failed", .german: "Export fehlgeschlagen",
                        .italian: "Esportazione non riuscita", .spanish: "Error al exportar"],
        .exportFormatPrompt: [.english: "Choose a format to save your holdings, cost basis, and P&L.",
                              .german: "Wählen Sie ein Format, um Ihre Bestände, Kostenbasis und Gewinn/Verlust zu speichern.",
                              .italian: "Scegli un formato per salvare le tue posizioni, il costo base e il profitto/perdita.",
                              .spanish: "Elige un formato para guardar tus posiciones, el costo base y las ganancias/pérdidas."],

        .name: [.english: "Name", .german: "Name",
                .italian: "Nome", .spanish: "Nombre"],
        .dateOfBirth: [.english: "Date of Birth", .german: "Geburtsdatum",
                       .italian: "Data di nascita", .spanish: "Fecha de nacimiento"],
        .email: [.english: "Email", .german: "E-Mail",
                 .italian: "Email", .spanish: "Correo electrónico"],

        .tradingMode: [.english: "Trading Mode", .german: "Handelsmodus",
                       .italian: "Modalità di trading", .spanish: "Modo de operación"],
        .startingBalance: [.english: "Starting Balance", .german: "Startguthaben",
                           .italian: "Saldo iniziale", .spanish: "Saldo inicial"],
        .displayCurrency: [.english: "Display Currency", .german: "Anzeigewährung",
                           .italian: "Valuta visualizzata", .spanish: "Moneda mostrada"],
        .defaultTab: [.english: "Default Tab", .german: "Standard-Tab",
                      .italian: "Scheda predefinita", .spanish: "Pestaña predeterminada"],
        .biometricLogin: [.english: "Biometric Login", .german: "Biometrische Anmeldung",
                          .italian: "Accesso biometrico", .spanish: "Inicio de sesión biométrico"],
        .simulatedPaperTrading: [.english: "Simulated · Paper Trading", .german: "Simuliert · Paper Trading",
                                 .italian: "Simulato · Paper Trading", .spanish: "Simulado · Paper Trading"],
        .enabledTouchID: [.english: "Enabled — Touch ID", .german: "Aktiviert — Touch ID",
                          .italian: "Attivato — Touch ID", .spanish: "Activado — Touch ID"],

        .pushNotifications: [.english: "Push Notifications", .german: "Push-Benachrichtigungen",
                             .italian: "Notifiche push", .spanish: "Notificaciones push"],
        .priceAlerts: [.english: "Price Alerts", .german: "Preisalarme",
                       .italian: "Avvisi sui prezzi", .spanish: "Alertas de precio"],
        .priceAlertsSubtitle: [.english: "When a watchlist alert is triggered",
                               .german: "Wenn ein Beobachtungslisten-Alarm ausgelöst wird",
                               .italian: "Quando viene attivato un avviso della lista di controllo",
                               .spanish: "Cuando se activa una alerta de la lista de seguimiento"],
        .orderFills: [.english: "Order Fills", .german: "Orderausführungen",
                      .italian: "Esecuzioni ordini", .spanish: "Ejecuciones de órdenes"],
        .orderFillsSubtitle: [.english: "Buy and sell confirmations", .german: "Kauf- und Verkaufsbestätigungen",
                              .italian: "Conferme di acquisto e vendita", .spanish: "Confirmaciones de compra y venta"],
        .marketOpenAndClose: [.english: "Market Open & Close", .german: "Marktöffnung & -schluss",
                              .italian: "Apertura e chiusura del mercato", .spanish: "Apertura y cierre del mercado"],
        .marketOpenAndCloseSubtitle: [.english: "Daily session reminders", .german: "Tägliche Sitzungserinnerungen",
                                      .italian: "Promemoria giornalieri di sessione", .spanish: "Recordatorios diarios de sesión"],
        .dailyNewsDigest: [.english: "Daily News Digest", .german: "Tägliche Nachrichtenübersicht",
                           .italian: "Riepilogo notizie giornaliero", .spanish: "Resumen diario de noticias"],
        .dailyNewsDigestSubtitle: [.english: "Top stories for your holdings", .german: "Top-Meldungen zu Ihren Beständen",
                                   .italian: "Notizie principali sulle tue posizioni", .spanish: "Noticias destacadas de tus posiciones"],
        .emailNotifications: [.english: "Email Notifications", .german: "E-Mail-Benachrichtigungen",
                              .italian: "Notifiche email", .spanish: "Notificaciones por correo"],
        .emailNotificationsSubtitle: [.english: "Send a copy to ankitpatel.svnit@gmail.com",
                                      .german: "Eine Kopie an ankitpatel.svnit@gmail.com senden",
                                      .italian: "Invia una copia a ankitpatel.svnit@gmail.com",
                                      .spanish: "Enviar una copia a ankitpatel.svnit@gmail.com"],

        .authentication: [.english: "Authentication", .german: "Authentifizierung",
                          .italian: "Autenticazione", .spanish: "Autenticación"],
        .biometricLoginSubtitle: [.english: "Unlock with Touch ID / Face ID", .german: "Entsperren mit Touch ID / Face ID",
                                  .italian: "Sblocca con Touch ID / Face ID", .spanish: "Desbloquear con Touch ID / Face ID"],
        .requireAuthOnLaunch: [.english: "Require Auth on Launch", .german: "Authentifizierung beim Start erforderlich",
                               .italian: "Richiedi autenticazione all'avvio", .spanish: "Requerir autenticación al iniciar"],
        .requireAuthOnLaunchSubtitle: [.english: "Ask every time the app opens", .german: "Bei jedem App-Start nachfragen",
                                       .italian: "Richiedi ogni volta che l'app si apre", .spanish: "Preguntar cada vez que se abre la app"],
        .confirmTrades: [.english: "Confirm Trades", .german: "Trades bestätigen",
                         .italian: "Conferma operazioni", .spanish: "Confirmar operaciones"],
        .confirmTradesSubtitle: [.english: "Re-authenticate before buy / sell", .german: "Vor Kauf/Verkauf erneut authentifizieren",
                                 .italian: "Autenticati di nuovo prima di acquistare/vendere", .spanish: "Volver a autenticarse antes de comprar/vender"],
        .privacy: [.english: "Privacy", .german: "Datenschutz",
                   .italian: "Privacy", .spanish: "Privacidad"],
        .shareUsageAnalytics: [.english: "Share Usage Analytics", .german: "Nutzungsanalysen teilen",
                               .italian: "Condividi analisi di utilizzo", .spanish: "Compartir análisis de uso"],
        .shareUsageAnalyticsSubtitle: [.english: "Anonymous diagnostics to improve APTrade",
                                       .german: "Anonyme Diagnosedaten zur Verbesserung von APTrade",
                                       .italian: "Diagnostica anonima per migliorare APTrade",
                                       .spanish: "Diagnóstico anónimo para mejorar APTrade"],
        .dataSection: [.english: "Data", .german: "Daten",
                       .italian: "Dati", .spanish: "Datos"],
        .changePassword: [.english: "Change Password", .german: "Passwort ändern",
                          .italian: "Cambia password", .spanish: "Cambiar contraseña"],
        .manageDevices: [.english: "Manage Devices", .german: "Geräte verwalten",
                         .italian: "Gestisci dispositivi", .spanish: "Administrar dispositivos"],
        .clearLocalCache: [.english: "Clear Local Cache", .german: "Lokalen Cache leeren",
                           .italian: "Svuota cache locale", .spanish: "Borrar caché local"],

        .resources: [.english: "Resources", .german: "Ressourcen",
                     .italian: "Risorse", .spanish: "Recursos"],
        .faq: [.english: "Frequently Asked Questions", .german: "Häufig gestellte Fragen",
               .italian: "Domande frequenti", .spanish: "Preguntas frecuentes"],
        .userGuide: [.english: "User Guide", .german: "Benutzerhandbuch",
                     .italian: "Guida utente", .spanish: "Guía del usuario"],
        .keyboardShortcuts: [.english: "Keyboard Shortcuts", .german: "Tastaturkürzel",
                             .italian: "Scorciatoie da tastiera", .spanish: "Atajos de teclado"],
        .contact: [.english: "Contact", .german: "Kontakt",
                   .italian: "Contatti", .spanish: "Contacto"],
        .emailSupport: [.english: "Email Support", .german: "E-Mail-Support",
                        .italian: "Assistenza via email", .spanish: "Soporte por correo electrónico"],
        .reportAProblem: [.english: "Report a Problem", .german: "Problem melden",
                          .italian: "Segnala un problema", .spanish: "Informar un problema"],

        .version: [.english: "Version", .german: "Version",
                   .italian: "Versione", .spanish: "Versión"],
        .dataProviders: [.english: "Data Providers", .german: "Datenanbieter",
                         .italian: "Fornitori di dati", .spanish: "Proveedores de datos"],
        .mode: [.english: "Mode", .german: "Modus",
                .italian: "Modalità", .spanish: "Modo"],
        .taglineShort: [.english: "Premium investing for macOS", .german: "Premium-Investieren für macOS",
                        .italian: "Investimenti premium per macOS", .spanish: "Inversión premium para macOS"],
        .termsOfService: [.english: "Terms of Service", .german: "Nutzungsbedingungen",
                          .italian: "Termini di servizio", .spanish: "Términos del servicio"],
        .privacyPolicy: [.english: "Privacy Policy", .german: "Datenschutzrichtlinie",
                         .italian: "Informativa sulla privacy", .spanish: "Política de privacidad"],
        .licenses: [.english: "Licenses", .german: "Lizenzen",
                    .italian: "Licenze", .spanish: "Licencias"],
        .copyrightDisclaimer: [.english: "© 2026 APTrade. Market data for informational purposes only.",
                               .german: "© 2026 APTrade. Marktdaten dienen nur zu Informationszwecken.",
                               .italian: "© 2026 APTrade. I dati di mercato sono solo a scopo informativo.",
                               .spanish: "© 2026 APTrade. Los datos de mercado son solo para fines informativos."],

        // MARK: Appearance
        .theme: [.english: "Theme", .german: "Design",
                 .italian: "Tema", .spanish: "Tema"],
        .accent: [.english: "Accent", .german: "Akzent",
                  .italian: "Colore d'accento", .spanish: "Color de acento"],
        .dark: [.english: "Dark", .german: "Dunkel",
                .italian: "Scuro", .spanish: "Oscuro"],
        .darkSubtitle: [.english: "Default — gold on black", .german: "Standard — Gold auf Schwarz",
                        .italian: "Predefinito — oro su nero", .spanish: "Predeterminado — dorado sobre negro"],
        .light: [.english: "Light", .german: "Hell",
                 .italian: "Chiaro", .spanish: "Claro"],
        .lightSubtitle: [.english: "Charcoal on warm white", .german: "Anthrazit auf Warmweiß",
                         .italian: "Antracite su bianco caldo", .spanish: "Carbón sobre blanco cálido"],
    ]
}

/// Resolves `key` against the active language. Falls back to English, then the key's
/// English raw value — both unreachable while the completeness test passes.
@MainActor
func tr(_ key: L10n.Key) -> String {
    let lang = LocalizationManager.shared.language
    return L10n.table[key]?[lang]
        ?? L10n.table[key]?[.english]
        ?? key.rawValue
}
