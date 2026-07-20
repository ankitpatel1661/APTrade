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

        // MARK: Watchlist
        case avgDayChangeTitle = "Avg. Day Change"
        case avgDayChange = "avg. day change"
        case advancingFormat = "%d advancing"
        case decliningFormat = "%d declining"
        case noStocksYet = "No stocks yet"
        case noETFsYet = "No ETFs yet"
        case noCryptoYet = "No crypto yet"
        case addSymbolHint = "Add a symbol below to start tracking it here."
        case searchTickerPlaceholder = "Search ticker symbol or name — Apple, VOO, SOL"
        case add = "Add"
        case setPriceAlert = "Set Price Alert"
        case removeFromWatchlist = "Remove from Watchlist"
        case activeAlertsFormat = "%d active alert(s)"
        case setAPriceAlert = "Set a price alert"
        case removeFromWatchlistHelp = "Remove from watchlist"
        case stocksLabel = "Stocks"
        case etfsLabel = "ETFs"
        case cryptoLabel = "Crypto"
        case stockChip = "STOCK"
        case etfChip = "ETF"
        case cryptoChip = "CRYPTO"

        // MARK: Portfolio
        case holdingsSection = "Holdings"
        case allocationSection = "Allocation"
        case activitySection = "Activity"
        case performanceSection = "Performance"
        case totalValue = "TOTAL VALUE"
        case dayPnL = "Day P&L"
        case unrealizedPnL = "Unrealized P&L"
        case cashLabel = "Cash"
        case realizedPnL = "Realized P&L"
        case tradesLabel = "TRADES"
        case simulatedPaperTradingFooter = "Simulated · paper trading"
        case resetPortfolio = "Reset portfolio"
        case reset = "Reset"
        case resetPortfolioConfirm = "Reset portfolio to $100,000 cash and clear all holdings?"
        case portfolioUnrealizedPnLChartTitle = "Portfolio · Unrealized P&L"
        case byHolding = "BY HOLDING"
        case holdingsLabel = "HOLDINGS"
        case noTransactionsYet = "No transactions yet."
        case noHoldingsYet = "No holdings yet"
        case noHoldingsHint = "Open an asset and tap Buy to start a simulated position."
        case buyChip = "BUY"
        case sellChip = "SELL"

        // MARK: Performance metrics
        case totalReturn = "Total Return"
        case annualizedReturn = "Annualized"
        case volatility = "Volatility"
        case maxDrawdown = "Max Drawdown"
        case sharpe = "Sharpe"
        case beta = "Beta"
        case alpha = "Alpha"
        case benchmark = "Benchmark"
        case benchmarkUnavailable = "Benchmark unavailable"
        case diversification = "Diversification"
        case effectiveHoldingsFormat = "%.1f effective holdings"
        case notEnoughHistoryYet = "Not enough history yet"
        case addHoldingsForAnalytics = "Add holdings to see performance analytics."
        case concentrationWarningFormat = "%@ is %@ of holdings"

        // MARK: Chart
        case expandChart = "Expand chart"

        // MARK: News
        case newsGeneral = "General"
        case newsMerger = "Merger"
        case saved = "Saved"
        case filterHeadlinesPlaceholder = "Filter headlines"
        case noHeadlinesRightNow = "No headlines right now"
        case noSavedArticles = "No saved articles"
        case refresh = "Refresh"
        case connectNewsSource = "Connect a news source"
        case finnhubKeyInstructions = "Add a Finnhub API key to ~/.config/aptrade/config.json (field \"finnhubAPIKey\") and relaunch."
        case finnhubKeyInstructionsIOS = "Add a Finnhub API key under Account → Account Settings."
        // In-app Finnhub key entry (Account → Account Settings, iOS) — the sandboxed config
        // path isn't user-reachable, so iOS gets a field instead of file-drop instructions.
        case finnhubApiKeyField = "Finnhub API key"
        case saveAction = "Save"
        case finnhubKeyAppliesNote = "Applies the next time News loads."

        // MARK: Asset detail
        case couldntLoadChart = "Couldn't load chart"
        case chartStyleArea = "Area"
        case chartStyleCandles = "Candles"
        case indicatorSMA = "SMA 20"
        case indicatorEMA = "EMA 12"
        case indicatorVWAP = "VWAP"
        case indicatorBollinger = "BB 20"
        case indicatorRSI = "RSI 14"
        case indicatorMACD = "MACD"
        case highLowFormat = "H %@ · L %@"
        case rsiPeriodFormat = "RSI %d"
        case macdParamsLabel = "MACD 12·26·9"
        case signalLegend = "Signal"
        case keyStats = "KEY STATS"
        case yourPosition = "YOUR POSITION"
        case statLast = "Last"
        case statPreviousClose = "Previous close"
        case statDayChange = "Day change"
        case statDayChangePercent = "Day change %"
        case statSymbol = "Symbol"
        case statType = "Type"
        case statShares = "Shares"
        case statAverageCost = "Average cost"
        case statMarketValue = "Market value"
        case assetKindStock = "Stock"
        case buy = "Buy"
        case sell = "Sell"

        // MARK: Trade
        case confirmBuy = "Confirm Buy"
        case confirmSell = "Confirm Sell"
        case confirmBuyTitleFormat = "Buy %@ %@?"
        case confirmSellTitleFormat = "Sell %@ %@?"
        case estimatedCost = "Estimated cost"
        case estimatedProceeds = "Estimated proceeds"
        case confirmEstimateFormat = "%@: %@"
        case marketPrice = "Market price"
        case quantityLabel = "QUANTITY"
        case maxButton = "Max"
        case availableCashFormat = "Available cash %@"
        case sharesOwnedFormat = "Shares owned %@"

        // MARK: Alerts
        case priceAboveKind = "Price above"
        case priceBelowKind = "Price below"
        case percentMoveKind = "% move"
        case currentPriceFormat = "Current price: %@"
        case targetPriceLabel = "Target price ($)"
        case dailyMoveLabel = "Daily move (%)"
        case addAlert = "Add Alert"
        case priceAboveSummaryFormat = "Price above %@"
        case priceBelowSummaryFormat = "Price below %@"
        case percentMoveSummaryFormat = "Moves %@%% in a day"

        // MARK: Command palette
        case noMatches = "No matches"
        case searchAssetsPlaceholder = "Search assets or jump to a tab…"
        case goToWatchlist = "Go to Watchlist"
        case goToPortfolio = "Go to Portfolio"

        // MARK: Brand chrome
        case liveBadge = "LIVE"
        case livePricesAccessibility = "Live prices"

        // MARK: Trade errors
        case couldntPlaceOrder = "Couldn't place the order. Try again."
        case notEnoughCash = "Not enough cash for this order."
        case notEnoughShares = "You don't own that many shares."
        case invalidQuantityError = "Enter a quantity greater than zero."

        // MARK: Watchlist errors
        case couldntFindSymbolFormat = "Couldn't find \"%@\""

        // MARK: Market activity notifications
        case digestNoSymbols = "Add symbols to your watchlist to get a daily digest."
        case digestUpdating = "Your watchlist is being updated. Check back shortly."
        case digestMoversFormat = "Today's movers — %@"

        // MARK: Calendar (market holidays + earnings)
        case calendarTab = "Calendar"
        case marketClosedBannerFmt = "Market closed — %@"
        case closesEarlyBannerFmt = "Closes 1:00 PM — %@"
        case holidayNewYears = "New Year's Day"
        case holidayMLK = "Martin Luther King Jr. Day"
        case holidayWashington = "Washington's Birthday"
        case holidayGoodFriday = "Good Friday"
        case holidayMemorial = "Memorial Day"
        case holidayJuneteenth = "Juneteenth"
        case holidayIndependence = "Independence Day"
        case holidayLabor = "Labor Day"
        case holidayThanksgiving = "Thanksgiving Day"
        case holidayChristmas = "Christmas Day"
        case sessionBeforeOpen = "Before open"
        case sessionAfterClose = "After close"
        case sessionDuringMarket = "During market"
        case nextEarnings = "Next earnings"
        case earningsReportsToggle = "Earnings reports"
        case earningsReportsSubtitle = "When a company you hold or watch reports today"
        case earningsTodayTitle = "Earnings today"
        case earningsTodayBodyFmt = "%1$@ reports today · %2$@"
        case noUpcomingEarnings = "No earnings in the next two weeks"

        // MARK: Plans (investment pies)
        case pieInsufficientCash = "Not enough cash for this contribution."
        case nextContributionFormat = "Next: %@"
        case pieInvalidAmount = "Enter an amount greater than zero."

        // MARK: Plans UI (Task 14)
        case plansSection = "Plans"
        case plansEmptyTitle = "No investment pies yet"
        case plansEmptyHint = "Create a pie to auto-invest across a target allocation."
        case createPlan = "Create Pie"
        case editPlan = "Edit Pie"
        case deletePlan = "Delete Pie"
        case deletePlanConfirm = "Delete this pie? This won't sell any holdings."
        case sliceWeights = "Slice Weights"
        case equalSplit = "Equal Split"
        case weightSumLabel = "Total weight"
        case scheduleSection = "Contribution Schedule"
        case cadenceWeekly = "Weekly"
        case cadenceBiweekly = "Biweekly"
        case cadenceMonthly = "Monthly"
        case nextContribution = "Next contribution"
        case contributeNow = "Contribute Now"
        case rebalanceNow = "Rebalance Now"
        case rebalancePreviewTitle = "Rebalance Preview"
        case driftLabel = "Drift"
        case backtestTitle = "Backtest"
        case backtestInvested = "Invested"
        case backtestValue = "Value"
        case backtestLumpSum = "Lump sum"
        case backtestInsufficient = "Not enough price history to run this backtest."
        case manualAdjustmentNote = "Adjusted after a manual sale outside this pie."
        case missedContribution = "Contribution skipped — insufficient cash"
        case pieContributionsToggle = "Plan Contributions"
        case notifPieExecutedTitle = "Contribution Complete"
        case notifPieExecutedBody = "%@ contribution has been executed"
        case notifPieSkippedTitle = "Contribution Skipped"
        case notifPieSkippedBody = "%@ was skipped due to insufficient cash"

        case pieNameLabel = "Pie Name"
        case pieNamePlaceholder = "e.g. Core Growth"
        case searchAssetsToAddPlaceholder = "Search assets to add…"
        case targetWeightLabel = "Target"
        case actualWeightLabel = "Actual"
        case recurringContributionToggle = "Recurring contribution"
        case contributionAmountLabel = "Amount"
        case cadenceLabel = "Cadence"
        case stepSlicesTitle = "Slices"
        case stepScheduleTitle = "Schedule"
        case next = "Next"
        case back = "Back"
        case runBacktest = "Run Backtest"
        case confirmRebalanceTitle = "Confirm Rebalance"
        case confirmRebalanceMessageFormat = "Place %d orders to rebalance this pie?"
        case rebalanceOrdersEmpty = "Your pie is already balanced."
        case noSlicesYetHint = "Search above to add the first slice."
        case contributeSheetTitleFormat = "Contribute to %@"
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

        // MARK: Watchlist
        .avgDayChangeTitle: [.english: "Avg. Day Change", .german: "Ø Tagesveränderung",
                             .italian: "Variazione media giornaliera", .spanish: "Cambio diario promedio"],
        .avgDayChange: [.english: "avg. day change", .german: "Ø Tagesveränderung",
                        .italian: "variazione media giornaliera", .spanish: "cambio diario promedio"],
        .advancingFormat: [.english: "%d advancing", .german: "%d im Plus",
                           .italian: "%d in rialzo", .spanish: "%d en alza"],
        .decliningFormat: [.english: "%d declining", .german: "%d im Minus",
                           .italian: "%d in calo", .spanish: "%d en baja"],
        .noStocksYet: [.english: "No stocks yet", .german: "Noch keine Aktien",
                       .italian: "Ancora nessuna azione", .spanish: "Aún no hay acciones"],
        .noETFsYet: [.english: "No ETFs yet", .german: "Noch keine ETFs",
                     .italian: "Ancora nessun ETF", .spanish: "Aún no hay ETFs"],
        .noCryptoYet: [.english: "No crypto yet", .german: "Noch keine Kryptowährungen",
                       .italian: "Ancora nessuna criptovaluta", .spanish: "Aún no hay criptomonedas"],
        .addSymbolHint: [.english: "Add a symbol below to start tracking it here.",
                         .german: "Fügen Sie unten ein Symbol hinzu, um es hier zu verfolgen.",
                         .italian: "Aggiungi un simbolo qui sotto per iniziare a monitorarlo.",
                         .spanish: "Agrega un símbolo abajo para empezar a seguirlo aquí."],
        .searchTickerPlaceholder: [.english: "Search ticker symbol or name — Apple, VOO, SOL",
                                   .german: "Tickersymbol oder Name suchen — Apple, VOO, SOL",
                                   .italian: "Cerca simbolo o nome — Apple, VOO, SOL",
                                   .spanish: "Buscar símbolo o nombre — Apple, VOO, SOL"],
        .add: [.english: "Add", .german: "Hinzufügen",
               .italian: "Aggiungi", .spanish: "Añadir"],
        .setPriceAlert: [.english: "Set Price Alert", .german: "Preisalarm festlegen",
                         .italian: "Imposta avviso sui prezzi", .spanish: "Establecer alerta de precio"],
        .removeFromWatchlist: [.english: "Remove from Watchlist", .german: "Aus Beobachtungsliste entfernen",
                               .italian: "Rimuovi dalla lista di controllo", .spanish: "Quitar de la lista de seguimiento"],
        .activeAlertsFormat: [.english: "%d active alert(s)", .german: "%d aktive(r) Alarm(e)",
                              .italian: "%d avviso/i attivo/i", .spanish: "%d alerta(s) activa(s)"],
        .setAPriceAlert: [.english: "Set a price alert", .german: "Preisalarm festlegen",
                          .italian: "Imposta un avviso sui prezzi", .spanish: "Establecer una alerta de precio"],
        .removeFromWatchlistHelp: [.english: "Remove from watchlist", .german: "Aus der Beobachtungsliste entfernen",
                                   .italian: "Rimuovi dalla lista di controllo", .spanish: "Quitar de la lista de seguimiento"],
        .stocksLabel: [.english: "Stocks", .german: "Aktien",
                       .italian: "Azioni", .spanish: "Acciones"],
        .etfsLabel: [.english: "ETFs", .german: "ETFs",
                     .italian: "ETF", .spanish: "ETFs"],
        .cryptoLabel: [.english: "Crypto", .german: "Krypto",
                       .italian: "Cripto", .spanish: "Cripto"],
        .stockChip: [.english: "STOCK", .german: "AKTIE",
                     .italian: "AZIONE", .spanish: "ACCIÓN"],
        .etfChip: [.english: "ETF", .german: "ETF",
                   .italian: "ETF", .spanish: "ETF"],
        .cryptoChip: [.english: "CRYPTO", .german: "KRYPTO",
                      .italian: "CRIPTO", .spanish: "CRIPTO"],

        // MARK: Portfolio
        .holdingsSection: [.english: "Holdings", .german: "Bestände",
                           .italian: "Posizioni", .spanish: "Posiciones"],
        .allocationSection: [.english: "Allocation", .german: "Aufteilung",
                             .italian: "Allocazione", .spanish: "Asignación"],
        .activitySection: [.english: "Activity", .german: "Aktivität",
                           .italian: "Attività", .spanish: "Actividad"],
        .performanceSection: [.english: "Performance", .german: "Wertentwicklung",
                              .italian: "Rendimento", .spanish: "Rendimiento"],
        .totalValue: [.english: "TOTAL VALUE", .german: "GESAMTWERT",
                      .italian: "VALORE TOTALE", .spanish: "VALOR TOTAL"],
        .dayPnL: [.english: "Day P&L", .german: "Tages-G/V",
                  .italian: "P&L giornaliero", .spanish: "P&L del día"],
        .unrealizedPnL: [.english: "Unrealized P&L", .german: "Unrealisierter G/V",
                         .italian: "P&L non realizzato", .spanish: "P&L no realizado"],
        .cashLabel: [.english: "Cash", .german: "Bargeld",
                     .italian: "Liquidità", .spanish: "Efectivo"],
        .realizedPnL: [.english: "Realized P&L", .german: "Realisierter G/V",
                       .italian: "P&L realizzato", .spanish: "P&L realizado"],
        .tradesLabel: [.english: "TRADES", .german: "TRADES",
                       .italian: "OPERAZIONI", .spanish: "OPERACIONES"],
        .simulatedPaperTradingFooter: [.english: "Simulated · paper trading",
                                       .german: "Simuliert · Paper Trading",
                                       .italian: "Simulato · Paper Trading",
                                       .spanish: "Simulado · Paper Trading"],
        .resetPortfolio: [.english: "Reset portfolio", .german: "Portfolio zurücksetzen",
                          .italian: "Reimposta portafoglio", .spanish: "Restablecer cartera"],
        .reset: [.english: "Reset", .german: "Zurücksetzen",
                 .italian: "Reimposta", .spanish: "Restablecer"],
        .resetPortfolioConfirm: [.english: "Reset portfolio to $100,000 cash and clear all holdings?",
                                 .german: "Portfolio auf 100.000 $ Bargeld zurücksetzen und alle Bestände löschen?",
                                 .italian: "Reimpostare il portafoglio a $100.000 in liquidità e azzerare tutte le posizioni?",
                                 .spanish: "¿Restablecer la cartera a $100,000 en efectivo y eliminar todas las posiciones?"],
        .portfolioUnrealizedPnLChartTitle: [.english: "Portfolio · Unrealized P&L",
                                            .german: "Portfolio · Unrealisierter G/V",
                                            .italian: "Portafoglio · P&L non realizzato",
                                            .spanish: "Cartera · P&L no realizado"],
        .byHolding: [.english: "BY HOLDING", .german: "NACH BESTAND",
                     .italian: "PER POSIZIONE", .spanish: "POR POSICIÓN"],
        .holdingsLabel: [.english: "HOLDINGS", .german: "BESTÄNDE",
                         .italian: "POSIZIONI", .spanish: "POSICIONES"],
        .noTransactionsYet: [.english: "No transactions yet.", .german: "Noch keine Transaktionen.",
                             .italian: "Ancora nessuna transazione.", .spanish: "Aún no hay transacciones."],
        .noHoldingsYet: [.english: "No holdings yet", .german: "Noch keine Bestände",
                         .italian: "Ancora nessuna posizione", .spanish: "Aún no hay posiciones"],
        .noHoldingsHint: [.english: "Open an asset and tap Buy to start a simulated position.",
                          .german: "Öffnen Sie einen Vermögenswert und tippen Sie auf Kaufen, um eine simulierte Position zu starten.",
                          .italian: "Apri un asset e tocca Acquista per avviare una posizione simulata.",
                          .spanish: "Abre un activo y toca Comprar para iniciar una posición simulada."],
        .buyChip: [.english: "BUY", .german: "KAUF",
                   .italian: "ACQUISTO", .spanish: "COMPRA"],
        .sellChip: [.english: "SELL", .german: "VERKAUF",
                    .italian: "VENDITA", .spanish: "VENTA"],

        // MARK: Performance metrics
        .totalReturn: [.english: "Total Return", .german: "Gesamtrendite",
                       .italian: "Rendimento totale", .spanish: "Rentabilidad total"],
        .annualizedReturn: [.english: "Annualized", .german: "Annualisiert",
                            .italian: "Annualizzato", .spanish: "Anualizado"],
        .volatility: [.english: "Volatility", .german: "Volatilität",
                      .italian: "Volatilità", .spanish: "Volatilidad"],
        .maxDrawdown: [.english: "Max Drawdown", .german: "Max. Drawdown",
                       .italian: "Drawdown massimo", .spanish: "Drawdown máximo"],
        .sharpe: [.english: "Sharpe", .german: "Sharpe",
                  .italian: "Sharpe", .spanish: "Sharpe"],
        .beta: [.english: "Beta", .german: "Beta",
                .italian: "Beta", .spanish: "Beta"],
        .alpha: [.english: "Alpha", .german: "Alpha",
                 .italian: "Alpha", .spanish: "Alfa"],
        .benchmark: [.english: "Benchmark", .german: "Benchmark",
                     .italian: "Benchmark", .spanish: "Referencia"],
        .benchmarkUnavailable: [.english: "Benchmark unavailable", .german: "Benchmark nicht verfügbar",
                                .italian: "Benchmark non disponibile", .spanish: "Referencia no disponible"],
        .diversification: [.english: "Diversification", .german: "Diversifikation",
                           .italian: "Diversificazione", .spanish: "Diversificación"],
        .effectiveHoldingsFormat: [.english: "%.1f effective holdings",
                                   .german: "%.1f effektive Bestände",
                                   .italian: "%.1f posizioni effettive",
                                   .spanish: "%.1f posiciones efectivas"],
        .notEnoughHistoryYet: [.english: "Not enough history yet", .german: "Noch nicht genug Verlauf",
                               .italian: "Cronologia ancora insufficiente", .spanish: "Aún no hay suficiente historial"],
        .addHoldingsForAnalytics: [.english: "Add holdings to see performance analytics.",
                                   .german: "Fügen Sie Bestände hinzu, um Performance-Analysen zu sehen.",
                                   .italian: "Aggiungi posizioni per visualizzare le analisi di rendimento.",
                                   .spanish: "Agrega posiciones para ver los análisis de rendimiento."],
        .concentrationWarningFormat: [.english: "%@ is %@ of holdings",
                                      .german: "%@ macht %@ der Bestände aus",
                                      .italian: "%@ rappresenta il %@ delle posizioni",
                                      .spanish: "%@ representa el %@ de las posiciones"],

        // MARK: Chart
        .expandChart: [.english: "Expand chart", .german: "Diagramm erweitern",
                       .italian: "Espandi grafico", .spanish: "Expandir gráfico"],

        // MARK: News
        .newsGeneral: [.english: "General", .german: "Allgemein",
                       .italian: "Generali", .spanish: "Generales"],
        .newsMerger: [.english: "Merger", .german: "Fusionen",
                      .italian: "Fusioni", .spanish: "Fusiones"],
        .saved: [.english: "Saved", .german: "Gespeichert",
                 .italian: "Salvati", .spanish: "Guardados"],
        .filterHeadlinesPlaceholder: [.english: "Filter headlines", .german: "Schlagzeilen filtern",
                                      .italian: "Filtra titoli", .spanish: "Filtrar titulares"],
        .noHeadlinesRightNow: [.english: "No headlines right now", .german: "Derzeit keine Schlagzeilen",
                               .italian: "Nessun titolo al momento", .spanish: "No hay titulares por ahora"],
        .noSavedArticles: [.english: "No saved articles", .german: "Keine gespeicherten Artikel",
                           .italian: "Nessun articolo salvato", .spanish: "No hay artículos guardados"],
        .refresh: [.english: "Refresh", .german: "Aktualisieren",
                   .italian: "Aggiorna", .spanish: "Actualizar"],
        .connectNewsSource: [.english: "Connect a news source", .german: "Nachrichtenquelle verbinden",
                             .italian: "Collega una fonte di notizie", .spanish: "Conecta una fuente de noticias"],
        .finnhubKeyInstructions: [.english: "Add a Finnhub API key to ~/.config/aptrade/config.json (field \"finnhubAPIKey\") and relaunch.",
                                  .german: "Fügen Sie einen Finnhub-API-Schlüssel in ~/.config/aptrade/config.json (Feld \"finnhubAPIKey\") hinzu und starten Sie die App neu.",
                                  .italian: "Aggiungi una chiave API Finnhub in ~/.config/aptrade/config.json (campo \"finnhubAPIKey\") e riavvia.",
                                  .spanish: "Agrega una clave de API de Finnhub en ~/.config/aptrade/config.json (campo \"finnhubAPIKey\") y vuelve a iniciar la app."],
        .finnhubKeyInstructionsIOS: [.english: "Add a Finnhub API key under Account → Account Settings.",
                                     .german: "Fügen Sie einen Finnhub-API-Schlüssel unter Konto → Kontoeinstellungen hinzu.",
                                     .italian: "Aggiungi una chiave API Finnhub in Account → Impostazioni account.",
                                     .spanish: "Agrega una clave de API de Finnhub en Cuenta → Ajustes de cuenta."],
        .finnhubApiKeyField: [.english: "Finnhub API key", .german: "Finnhub-API-Schlüssel",
                              .italian: "Chiave API Finnhub", .spanish: "Clave de API de Finnhub"],
        .saveAction: [.english: "Save", .german: "Speichern",
                      .italian: "Salva", .spanish: "Guardar"],
        .finnhubKeyAppliesNote: [.english: "Applies the next time News loads.",
                                 .german: "Gilt beim nächsten Laden der News.",
                                 .italian: "Si applica al prossimo caricamento delle notizie.",
                                 .spanish: "Se aplica la próxima vez que se carguen las noticias."],

        // MARK: Asset detail
        .couldntLoadChart: [.english: "Couldn't load chart", .german: "Diagramm konnte nicht geladen werden",
                            .italian: "Impossibile caricare il grafico", .spanish: "No se pudo cargar el gráfico"],
        .chartStyleArea: [.english: "Area", .german: "Fläche",
                          .italian: "Area", .spanish: "Área"],
        .chartStyleCandles: [.english: "Candles", .german: "Kerzen",
                             .italian: "Candele", .spanish: "Velas"],
        .indicatorSMA: [.english: "SMA 20", .german: "SMA 20",
                        .italian: "SMA 20", .spanish: "SMA 20"],
        .indicatorEMA: [.english: "EMA 12", .german: "EMA 12",
                        .italian: "EMA 12", .spanish: "EMA 12"],
        .indicatorVWAP: [.english: "VWAP", .german: "VWAP",
                         .italian: "VWAP", .spanish: "VWAP"],
        .indicatorBollinger: [.english: "BB 20", .german: "BB 20",
                              .italian: "BB 20", .spanish: "BB 20"],
        .indicatorRSI: [.english: "RSI 14", .german: "RSI 14",
                        .italian: "RSI 14", .spanish: "RSI 14"],
        .indicatorMACD: [.english: "MACD", .german: "MACD",
                         .italian: "MACD", .spanish: "MACD"],
        .highLowFormat: [.english: "H %@ · L %@", .german: "H %@ · T %@",
                         .italian: "M %@ · m %@", .spanish: "M %@ · m %@"],
        .rsiPeriodFormat: [.english: "RSI %d", .german: "RSI %d",
                           .italian: "RSI %d", .spanish: "RSI %d"],
        .macdParamsLabel: [.english: "MACD 12·26·9", .german: "MACD 12·26·9",
                           .italian: "MACD 12·26·9", .spanish: "MACD 12·26·9"],
        .signalLegend: [.english: "Signal", .german: "Signal",
                        .italian: "Segnale", .spanish: "Señal"],
        .keyStats: [.english: "KEY STATS", .german: "KENNZAHLEN",
                    .italian: "DATI CHIAVE", .spanish: "DATOS CLAVE"],
        .yourPosition: [.english: "YOUR POSITION", .german: "IHRE POSITION",
                        .italian: "LA TUA POSIZIONE", .spanish: "TU POSICIÓN"],
        .statLast: [.english: "Last", .german: "Letzter",
                    .italian: "Ultimo", .spanish: "Último"],
        .statPreviousClose: [.english: "Previous close", .german: "Vorheriger Schluss",
                             .italian: "Chiusura precedente", .spanish: "Cierre anterior"],
        .statDayChange: [.english: "Day change", .german: "Tagesveränderung",
                         .italian: "Variazione giornaliera", .spanish: "Cambio diario"],
        .statDayChangePercent: [.english: "Day change %", .german: "Tagesveränderung %",
                                .italian: "Variazione giornaliera %", .spanish: "Cambio diario %"],
        .statSymbol: [.english: "Symbol", .german: "Symbol",
                      .italian: "Simbolo", .spanish: "Símbolo"],
        .statType: [.english: "Type", .german: "Typ",
                    .italian: "Tipo", .spanish: "Tipo"],
        .statShares: [.english: "Shares", .german: "Anteile",
                      .italian: "Quote", .spanish: "Acciones"],
        .statAverageCost: [.english: "Average cost", .german: "Durchschnittlicher Einstand",
                           .italian: "Costo medio", .spanish: "Costo promedio"],
        .statMarketValue: [.english: "Market value", .german: "Marktwert",
                           .italian: "Valore di mercato", .spanish: "Valor de mercado"],
        .assetKindStock: [.english: "Stock", .german: "Aktie",
                          .italian: "Azione", .spanish: "Acción"],
        .buy: [.english: "Buy", .german: "Kaufen",
               .italian: "Compra", .spanish: "Comprar"],
        .sell: [.english: "Sell", .german: "Verkaufen",
                .italian: "Vendi", .spanish: "Vender"],

        // MARK: Trade
        .confirmBuy: [.english: "Confirm Buy", .german: "Kauf bestätigen",
                      .italian: "Conferma acquisto", .spanish: "Confirmar compra"],
        .confirmSell: [.english: "Confirm Sell", .german: "Verkauf bestätigen",
                       .italian: "Conferma vendita", .spanish: "Confirmar venta"],
        .confirmBuyTitleFormat: [.english: "Buy %@ %@?", .german: "%@ %@ kaufen?",
                                 .italian: "Acquistare %@ %@?", .spanish: "¿Comprar %@ %@?"],
        .confirmSellTitleFormat: [.english: "Sell %@ %@?", .german: "%@ %@ verkaufen?",
                                  .italian: "Vendere %@ %@?", .spanish: "¿Vender %@ %@?"],
        .estimatedCost: [.english: "Estimated cost", .german: "Geschätzte Kosten",
                         .italian: "Costo stimato", .spanish: "Costo estimado"],
        .estimatedProceeds: [.english: "Estimated proceeds", .german: "Geschätzter Erlös",
                             .italian: "Ricavo stimato", .spanish: "Ingreso estimado"],
        .confirmEstimateFormat: [.english: "%@: %@", .german: "%@: %@",
                                 .italian: "%@: %@", .spanish: "%@: %@"],
        .marketPrice: [.english: "Market price", .german: "Marktpreis",
                       .italian: "Prezzo di mercato", .spanish: "Precio de mercado"],
        .quantityLabel: [.english: "QUANTITY", .german: "ANZAHL",
                         .italian: "QUANTITÀ", .spanish: "CANTIDAD"],
        .maxButton: [.english: "Max", .german: "Max.",
                     .italian: "Max", .spanish: "Máx."],
        .availableCashFormat: [.english: "Available cash %@", .german: "Verfügbares Guthaben %@",
                               .italian: "Liquidità disponibile %@", .spanish: "Efectivo disponible %@"],
        .sharesOwnedFormat: [.english: "Shares owned %@", .german: "Gehaltene Anteile %@",
                             .italian: "Quote possedute %@", .spanish: "Acciones en posesión %@"],

        // MARK: Alerts
        .priceAboveKind: [.english: "Price above", .german: "Preis über",
                          .italian: "Prezzo sopra", .spanish: "Precio por encima"],
        .priceBelowKind: [.english: "Price below", .german: "Preis unter",
                          .italian: "Prezzo sotto", .spanish: "Precio por debajo"],
        .percentMoveKind: [.english: "% move", .german: "% Veränderung",
                           .italian: "% variazione", .spanish: "% variación"],
        .currentPriceFormat: [.english: "Current price: %@", .german: "Aktueller Preis: %@",
                              .italian: "Prezzo attuale: %@", .spanish: "Precio actual: %@"],
        .targetPriceLabel: [.english: "Target price ($)", .german: "Zielpreis ($)",
                            .italian: "Prezzo obiettivo ($)", .spanish: "Precio objetivo ($)"],
        .dailyMoveLabel: [.english: "Daily move (%)", .german: "Tägliche Veränderung (%)",
                          .italian: "Variazione giornaliera (%)", .spanish: "Variación diaria (%)"],
        .addAlert: [.english: "Add Alert", .german: "Alarm hinzufügen",
                    .italian: "Aggiungi avviso", .spanish: "Añadir alerta"],
        .priceAboveSummaryFormat: [.english: "Price above %@", .german: "Preis über %@",
                                   .italian: "Prezzo sopra %@", .spanish: "Precio por encima de %@"],
        .priceBelowSummaryFormat: [.english: "Price below %@", .german: "Preis unter %@",
                                   .italian: "Prezzo sotto %@", .spanish: "Precio por debajo de %@"],
        .percentMoveSummaryFormat: [.english: "Moves %@%% in a day", .german: "Bewegt sich %@%% an einem Tag",
                                    .italian: "Si muove del %@%% in un giorno", .spanish: "Se mueve %@%% en un día"],

        // MARK: Command palette
        .noMatches: [.english: "No matches", .german: "Keine Treffer",
                     .italian: "Nessun risultato", .spanish: "Sin coincidencias"],
        .searchAssetsPlaceholder: [.english: "Search assets or jump to a tab…",
                                   .german: "Werte suchen oder zu einem Tab wechseln …",
                                   .italian: "Cerca asset o vai a una scheda…",
                                   .spanish: "Buscar activos o ir a una pestaña…"],
        .goToWatchlist: [.english: "Go to Watchlist", .german: "Zur Beobachtungsliste",
                         .italian: "Vai alla lista di controllo", .spanish: "Ir a la lista de seguimiento"],
        .goToPortfolio: [.english: "Go to Portfolio", .german: "Zum Portfolio",
                         .italian: "Vai al portafoglio", .spanish: "Ir a la cartera"],

        // MARK: Brand chrome
        .liveBadge: [.english: "LIVE", .german: "LIVE",
                     .italian: "LIVE", .spanish: "EN VIVO"],
        .livePricesAccessibility: [.english: "Live prices", .german: "Live-Kurse",
                                   .italian: "Prezzi in tempo reale", .spanish: "Precios en vivo"],

        // MARK: Trade errors
        .couldntPlaceOrder: [.english: "Couldn't place the order. Try again.",
                             .german: "Die Order konnte nicht aufgegeben werden. Bitte erneut versuchen.",
                             .italian: "Impossibile eseguire l'ordine. Riprova.",
                             .spanish: "No se pudo realizar la orden. Inténtalo de nuevo."],
        .notEnoughCash: [.english: "Not enough cash for this order.",
                         .german: "Nicht genügend Guthaben für diese Order.",
                         .italian: "Liquidità insufficiente per questo ordine.",
                         .spanish: "No hay suficiente efectivo para esta orden."],
        .notEnoughShares: [.english: "You don't own that many shares.",
                           .german: "Sie besitzen nicht so viele Anteile.",
                           .italian: "Non possiedi così tante quote.",
                           .spanish: "No posees esa cantidad de acciones."],
        .invalidQuantityError: [.english: "Enter a quantity greater than zero.",
                                .german: "Geben Sie eine Anzahl größer als null ein.",
                                .italian: "Inserisci una quantità maggiore di zero.",
                                .spanish: "Ingresa una cantidad mayor que cero."],

        // MARK: Watchlist errors
        .couldntFindSymbolFormat: [.english: "Couldn't find \"%@\"",
                                   .german: "„%@“ wurde nicht gefunden",
                                   .italian: "Impossibile trovare \"%@\"",
                                   .spanish: "No se pudo encontrar \"%@\""],

        // MARK: Market activity notifications
        .digestNoSymbols: [.english: "Add symbols to your watchlist to get a daily digest.",
                           .german: "Fügen Sie Symbole zu Ihrer Beobachtungsliste hinzu, um eine tägliche Übersicht zu erhalten.",
                           .italian: "Aggiungi simboli alla tua lista di controllo per ricevere un riepilogo giornaliero.",
                           .spanish: "Agrega símbolos a tu lista de seguimiento para recibir un resumen diario."],
        .digestUpdating: [.english: "Your watchlist is being updated. Check back shortly.",
                          .german: "Ihre Beobachtungsliste wird aktualisiert. Schauen Sie in Kürze wieder nach.",
                          .italian: "La tua lista di controllo è in fase di aggiornamento. Controlla di nuovo a breve.",
                          .spanish: "Tu lista de seguimiento se está actualizando. Vuelve a consultarla pronto."],
        .digestMoversFormat: [.english: "Today's movers — %@", .german: "Heutige Bewegungen — %@",
                              .italian: "I movimenti di oggi — %@", .spanish: "Los movimientos de hoy — %@"],

        // MARK: Calendar (market holidays + earnings)
        .calendarTab: [.english: "Calendar", .german: "Kalender",
                       .italian: "Calendario", .spanish: "Calendario"],
        .marketClosedBannerFmt: [.english: "Market closed — %@", .german: "Markt geschlossen — %@",
                                 .italian: "Mercato chiuso — %@", .spanish: "Mercado cerrado — %@"],
        .closesEarlyBannerFmt: [.english: "Closes 1:00 PM — %@", .german: "Schließt 13:00 Uhr — %@",
                                .italian: "Chiude alle 13:00 — %@", .spanish: "Cierra a las 13:00 — %@"],
        .holidayNewYears: [.english: "New Year's Day", .german: "Neujahr",
                           .italian: "Capodanno", .spanish: "Año Nuevo"],
        .holidayMLK: [.english: "Martin Luther King Jr. Day", .german: "Martin-Luther-King-Tag",
                      .italian: "Giorno di Martin Luther King", .spanish: "Día de Martin Luther King"],
        .holidayWashington: [.english: "Washington's Birthday", .german: "Washingtons Geburtstag",
                             .italian: "Compleanno di Washington", .spanish: "Natalicio de Washington"],
        .holidayGoodFriday: [.english: "Good Friday", .german: "Karfreitag",
                             .italian: "Venerdì Santo", .spanish: "Viernes Santo"],
        .holidayMemorial: [.english: "Memorial Day", .german: "Memorial Day",
                           .italian: "Memorial Day", .spanish: "Memorial Day"],
        .holidayJuneteenth: [.english: "Juneteenth", .german: "Juneteenth",
                             .italian: "Juneteenth", .spanish: "Juneteenth"],
        .holidayIndependence: [.english: "Independence Day", .german: "Unabhängigkeitstag",
                               .italian: "Giorno dell'Indipendenza", .spanish: "Día de la Independencia"],
        .holidayLabor: [.english: "Labor Day", .german: "Labor Day",
                        .italian: "Labor Day", .spanish: "Labor Day"],
        .holidayThanksgiving: [.english: "Thanksgiving Day", .german: "Thanksgiving",
                               .italian: "Giorno del Ringraziamento", .spanish: "Día de Acción de Gracias"],
        .holidayChristmas: [.english: "Christmas Day", .german: "Weihnachten",
                            .italian: "Natale", .spanish: "Navidad"],
        .sessionBeforeOpen: [.english: "Before open", .german: "Vor Handelsbeginn",
                             .italian: "Prima dell'apertura", .spanish: "Antes de la apertura"],
        .sessionAfterClose: [.english: "After close", .german: "Nach Handelsschluss",
                             .italian: "Dopo la chiusura", .spanish: "Tras el cierre"],
        .sessionDuringMarket: [.english: "During market", .german: "Während des Handels",
                               .italian: "Durante la seduta", .spanish: "Durante la sesión"],
        .nextEarnings: [.english: "Next earnings", .german: "Nächster Quartalsbericht",
                        .italian: "Prossima trimestrale", .spanish: "Próximo informe trimestral"],
        .earningsReportsToggle: [.english: "Earnings reports", .german: "Quartalsberichte",
                                 .italian: "Trimestrali", .spanish: "Informes trimestrales"],
        .earningsReportsSubtitle: [.english: "When a company you hold or watch reports today",
                                   .german: "Wenn ein gehaltenes oder beobachtetes Unternehmen heute berichtet",
                                   .italian: "Quando una società che possiedi o segui pubblica i risultati oggi",
                                   .spanish: "Cuando una empresa que posees o sigues presenta resultados hoy"],
        .earningsTodayTitle: [.english: "Earnings today", .german: "Quartalszahlen heute",
                              .italian: "Trimestrali oggi", .spanish: "Resultados hoy"],
        .earningsTodayBodyFmt: [.english: "%1$@ reports today · %2$@",
                                .german: "%1$@ berichtet heute · %2$@",
                                .italian: "%1$@ pubblica i risultati oggi · %2$@",
                                .spanish: "%1$@ presenta resultados hoy · %2$@"],
        .noUpcomingEarnings: [.english: "No earnings in the next two weeks",
                              .german: "Keine Quartalsberichte in den nächsten zwei Wochen",
                              .italian: "Nessuna trimestrale nelle prossime due settimane",
                              .spanish: "Sin informes trimestrales en las próximas dos semanas"],

        // MARK: Plans (investment pies)
        .pieInsufficientCash: [.english: "Not enough cash for this contribution.",
                               .german: "Nicht genügend Guthaben für diesen Beitrag.",
                               .italian: "Liquidità insufficiente per questo contributo.",
                               .spanish: "No hay suficiente efectivo para esta aportación."],
        .nextContributionFormat: [.english: "Next: %@", .german: "Nächste: %@",
                                  .italian: "Prossimo: %@", .spanish: "Próximo: %@"],
        .pieInvalidAmount: [.english: "Enter an amount greater than zero.",
                            .german: "Geben Sie einen Betrag größer als null ein.",
                            .italian: "Inserisci un importo maggiore di zero.",
                            .spanish: "Ingresa un importe mayor que cero."],

        // MARK: Plans UI (Task 14) — DE/IT/ES provisional, pending Task 15 native review.
        .plansSection: [.english: "Plans", .german: "Pläne",
                        .italian: "Piani", .spanish: "Planes"],
        .plansEmptyTitle: [.english: "No investment pies yet", .german: "Noch keine Investment-Pies",
                           .italian: "Nessuna pie di investimento", .spanish: "Aún no hay pies de inversión"],
        .plansEmptyHint: [.english: "Create a pie to auto-invest across a target allocation.",
                          .german: "Erstelle einen Pie, um automatisch nach einer Zielallokation zu investieren.",
                          .italian: "Crea una pie per investire automaticamente secondo un'allocazione target.",
                          .spanish: "Crea un pie para invertir automáticamente según una asignación objetivo."],
        .createPlan: [.english: "Create Pie", .german: "Pie erstellen",
                     .italian: "Crea pie", .spanish: "Crear pie"],
        .editPlan: [.english: "Edit Pie", .german: "Pie bearbeiten",
                   .italian: "Modifica pie", .spanish: "Editar pie"],
        .deletePlan: [.english: "Delete Pie", .german: "Pie löschen",
                     .italian: "Elimina pie", .spanish: "Eliminar pie"],
        .deletePlanConfirm: [.english: "Delete this pie? This won't sell any holdings.",
                             .german: "Diesen Pie löschen? Bestände werden dadurch nicht verkauft.",
                             .italian: "Eliminare questa pie? Le posizioni non verranno vendute.",
                             .spanish: "¿Eliminar este pie? Esto no venderá ninguna posición."],
        .sliceWeights: [.english: "Slice Weights", .german: "Slice-Gewichte",
                        .italian: "Pesi delle fette", .spanish: "Pesos de los segmentos"],
        .equalSplit: [.english: "Equal Split", .german: "Gleichmäßig aufteilen",
                     .italian: "Suddividi equamente", .spanish: "Repartir por igual"],
        .weightSumLabel: [.english: "Total weight", .german: "Gesamtgewicht",
                          .italian: "Peso totale", .spanish: "Peso total"],
        .scheduleSection: [.english: "Contribution Schedule", .german: "Beitragsplan",
                           .italian: "Piano di contribuzione", .spanish: "Calendario de aportaciones"],
        .cadenceWeekly: [.english: "Weekly", .german: "Wöchentlich",
                         .italian: "Settimanale", .spanish: "Semanal"],
        .cadenceBiweekly: [.english: "Biweekly", .german: "Zweiwöchentlich",
                           .italian: "Ogni due settimane", .spanish: "Quincenal"],
        .cadenceMonthly: [.english: "Monthly", .german: "Monatlich",
                          .italian: "Mensile", .spanish: "Mensual"],
        .nextContribution: [.english: "Next contribution", .german: "Nächster Beitrag",
                            .italian: "Prossimo contributo", .spanish: "Próxima aportación"],
        .contributeNow: [.english: "Contribute Now", .german: "Jetzt einzahlen",
                         .italian: "Contribuisci ora", .spanish: "Aportar ahora"],
        .rebalanceNow: [.english: "Rebalance Now", .german: "Jetzt rebalancieren",
                        .italian: "Ribilancia ora", .spanish: "Reequilibrar ahora"],
        .rebalancePreviewTitle: [.english: "Rebalance Preview", .german: "Rebalancing-Vorschau",
                                 .italian: "Anteprima ribilanciamento", .spanish: "Vista previa del reequilibrio"],
        .driftLabel: [.english: "Drift", .german: "Abweichung",
                     .italian: "Scostamento", .spanish: "Desviación"],
        .backtestTitle: [.english: "Backtest", .german: "Backtest",
                         .italian: "Backtest", .spanish: "Backtest"],
        .backtestInvested: [.english: "Invested", .german: "Investiert",
                            .italian: "Investito", .spanish: "Invertido"],
        .backtestValue: [.english: "Value", .german: "Wert",
                         .italian: "Valore", .spanish: "Valor"],
        .backtestLumpSum: [.english: "Lump sum", .german: "Einmalanlage",
                           .italian: "Investimento unico", .spanish: "Pago único"],
        .backtestInsufficient: [.english: "Not enough price history to run this backtest.",
                                .german: "Nicht genügend Kursdaten für diesen Backtest.",
                                .italian: "Storico dei prezzi insufficiente per questo backtest.",
                                .spanish: "No hay suficiente historial de precios para este backtest."],
        .manualAdjustmentNote: [.english: "Adjusted after a manual sale outside this pie.",
                                .german: "Angepasst nach einem manuellen Verkauf außerhalb dieses Pies.",
                                .italian: "Rettificato dopo una vendita manuale al di fuori di questa pie.",
                                .spanish: "Ajustado tras una venta manual fuera de este pie."],
        .missedContribution: [.english: "Contribution skipped — insufficient cash",
                              .german: "Beitrag übersprungen — unzureichendes Guthaben",
                              .italian: "Contributo saltato — liquidità insufficiente",
                              .spanish: "Aportación omitida — efectivo insuficiente"],
        .pieContributionsToggle: [.english: "Plan Contributions", .german: "Plan-Beiträge",
                                  .italian: "Contributi del piano", .spanish: "Aportaciones del plan"],
        .notifPieExecutedTitle: [.english: "Contribution Complete", .german: "Beitrag abgeschlossen",
                                 .italian: "Contributo completato", .spanish: "Aportación completada"],
        .notifPieExecutedBody: [.english: "%@ contribution has been executed",
                                .german: "%@ Beitrag wurde ausgeführt",
                                .italian: "Il contributo di %@ è stato eseguito",
                                .spanish: "La aportación de %@ se ha ejecutado"],
        .notifPieSkippedTitle: [.english: "Contribution Skipped", .german: "Beitrag übersprungen",
                                .italian: "Contributo saltato", .spanish: "Aportación omitida"],
        .notifPieSkippedBody: [.english: "%@ was skipped due to insufficient cash",
                               .german: "%@ wurde aufgrund unzureichenden Guthabens übersprungen",
                               .italian: "%@ è stato saltato a causa di liquidità insufficiente",
                               .spanish: "%@ se omitió debido a efectivo insuficiente"],
        .pieNameLabel: [.english: "Pie Name", .german: "Pie-Name",
                        .italian: "Nome della pie", .spanish: "Nombre del pie"],
        .pieNamePlaceholder: [.english: "e.g. Core Growth", .german: "z. B. Core Growth",
                              .italian: "es. Core Growth", .spanish: "p. ej. Core Growth"],
        .searchAssetsToAddPlaceholder: [.english: "Search assets to add…", .german: "Werte zum Hinzufügen suchen…",
                                        .italian: "Cerca asset da aggiungere…", .spanish: "Buscar activos para añadir…"],
        .targetWeightLabel: [.english: "Target", .german: "Ziel",
                             .italian: "Target", .spanish: "Objetivo"],
        .actualWeightLabel: [.english: "Actual", .german: "Ist",
                             .italian: "Attuale", .spanish: "Actual"],
        .recurringContributionToggle: [.english: "Recurring contribution", .german: "Wiederkehrender Beitrag",
                                       .italian: "Contributo ricorrente", .spanish: "Aportación recurrente"],
        .contributionAmountLabel: [.english: "Amount", .german: "Betrag",
                                   .italian: "Importo", .spanish: "Importe"],
        .cadenceLabel: [.english: "Cadence", .german: "Rhythmus",
                        .italian: "Frequenza", .spanish: "Frecuencia"],
        .stepSlicesTitle: [.english: "Slices", .german: "Slices",
                           .italian: "Fette", .spanish: "Segmentos"],
        .stepScheduleTitle: [.english: "Schedule", .german: "Zeitplan",
                             .italian: "Pianificazione", .spanish: "Calendario"],
        .next: [.english: "Next", .german: "Weiter",
               .italian: "Avanti", .spanish: "Siguiente"],
        .back: [.english: "Back", .german: "Zurück",
               .italian: "Indietro", .spanish: "Atrás"],
        .runBacktest: [.english: "Run Backtest", .german: "Backtest starten",
                       .italian: "Avvia backtest", .spanish: "Ejecutar backtest"],
        .confirmRebalanceTitle: [.english: "Confirm Rebalance", .german: "Rebalancing bestätigen",
                                 .italian: "Conferma ribilanciamento", .spanish: "Confirmar reequilibrio"],
        .confirmRebalanceMessageFormat: [.english: "Place %d orders to rebalance this pie?",
                                         .german: "%d Aufträge zur Rebalancierung dieses Pies aufgeben?",
                                         .italian: "Inviare %d ordini per ribilanciare questa pie?",
                                         .spanish: "¿Colocar %d órdenes para reequilibrar este pie?"],
        .rebalanceOrdersEmpty: [.english: "Your pie is already balanced.",
                                .german: "Dieser Pie ist bereits ausbalanciert.",
                                .italian: "Questa pie è già bilanciata.",
                                .spanish: "Este pie ya está equilibrado."],
        .noSlicesYetHint: [.english: "Search above to add the first slice.",
                           .german: "Oben suchen, um den ersten Slice hinzuzufügen.",
                           .italian: "Cerca sopra per aggiungere la prima fetta.",
                           .spanish: "Busca arriba para añadir el primer segmento."],
        .contributeSheetTitleFormat: [.english: "Contribute to %@", .german: "Einzahlen in %@",
                                      .italian: "Contribuisci a %@", .spanish: "Aportar a %@"],
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
