import SwiftUI
#if os(macOS)
import AppKit
#endif
import UniformTypeIdentifiers
import APTradeApplication
import APTradeDomain

public struct RootView: View {
    public init() {}

    enum Tab: String, CaseIterable {
        case home = "Home", markets = "Markets", portfolio = "Portfolio", invest = "Invest"
    }
    private enum PanelRoute {
        case menu, profile, accountSettings, notifications, appearance, security, help, about, language
    }

    @State private var tab: Tab = .home
    @State private var showAccountPanel = false
    @State private var panelRoute: PanelRoute = .menu
    @State private var isLoggedIn = true
    @State private var settingsVM = CompositionRoot.makeSettingsViewModel()
    /// Draft text for the iOS Finnhub key field; synced from `settingsVM.finnhubKey` when
    /// the Account Settings page appears, compared against it to enable Save.
    @State private var finnhubKeyDraft = ""
    @State private var scheduler = CompositionRoot.makeMarketActivityCoordinator()
    @Namespace private var pill

    @State private var exportPortfolio = CompositionRoot.makeExportPortfolioUseCase()
    @State private var showExportDialog = false
    @State private var isExporting = false
    @State private var exportError: String?

    #if os(iOS)
    @State private var iosExportDocument: PortfolioExportDocument?
    @State private var showFileExporter = false
    #endif

    @State private var showPalette = false
    @State private var paletteVM = CompositionRoot.makeCommandPaletteViewModel()
    @State private var paletteAsset: Asset?

    public var body: some View {
        #if os(iOS)
        iosBody
        #else
        macBody
        #endif
    }

    #if os(iOS)
    private var iosBody: some View {
        TabView(selection: $tab) {
            // Task 5 builds the real Home dashboard (hero + quick stats + Today feed).
            // Placeholder keeps the tab wired and navigable in the meantime.
            Theme.background.ignoresSafeArea()
                .tabItem { Label(tr(.homeTab), systemImage: "house.fill") }
                .tag(Tab.home)
            MarketsView(onOpenSearch: { showPalette = true },
                        onOpenAccount: { showAccountPanel = true })
                .tabItem { Label(tr(.marketsTab), systemImage: "chart.line.uptrend.xyaxis") }
                .tag(Tab.markets)
            PortfolioView(onOpenSearch: { showPalette = true },
                          onOpenAccount: { showAccountPanel = true })
                .tabItem { Label(tr(.portfolio), systemImage: "chart.pie") }
                .tag(Tab.portfolio)
            InvestView(onOpenSearch: { showPalette = true },
                       onOpenAccount: { showAccountPanel = true })
                .tabItem { Label(tr(.investTab), systemImage: "basket.fill") }
                .tag(Tab.invest)
        }
        .tint(Theme.gold)
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
        .task { await scheduler.run() }
        .sheet(isPresented: $showAccountPanel, onDismiss: { panelRoute = .menu }) {
            NavigationStack { accountPanel.background(Theme.surface.ignoresSafeArea()) }
                .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
        }
        .sheet(isPresented: $showPalette, onDismiss: { paletteVM.reset() }) {
            CommandPaletteView(viewModel: paletteVM,
                               onSelect: { handlePaletteSelection($0) },
                               onClose: { closePalette() })
                .presentationBackground(Theme.background)
        }
        .confirmationDialog(tr(.exportPortfolioData), isPresented: $showExportDialog, titleVisibility: .visible) {
            ForEach(PortfolioExportFormat.allCases, id: \.self) { format in
                Button(format.displayName) { beginExport(format) }
            }
            Button(tr(.cancel), role: .cancel) {}
        } message: { Text(tr(.exportFormatPrompt)) }
        .alert(tr(.exportFailed), isPresented: Binding(
            get: { exportError != nil }, set: { if !$0 { exportError = nil } })) {
            Button(tr(.ok), role: .cancel) { exportError = nil }
        } message: { Text(exportError ?? "") }
        .fileExporter(
            isPresented: $showFileExporter,
            document: iosExportDocument,
            contentType: iosExportDocument?.contentType ?? .data,
            defaultFilename: iosExportDocument?.filename
        ) { result in
            if case .failure(let error) = result,
               (error as NSError).code != NSUserCancelledError {
                exportError = error.localizedDescription
            }
            iosExportDocument = nil
        }
        .sheet(item: $paletteAsset) { asset in
            NavigationStack {
                AssetDetailView(asset: asset)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button(tr(.done)) { paletteAsset = nil } } }
            }
        }
    }
    #endif

    #if os(macOS)
    private var macBody: some View {
        GeometryReader { geo in
            ZStack(alignment: .trailing) {
                Theme.background.ignoresSafeArea()
                Button("") { showPalette = true }
                    .keyboardShortcut("k", modifiers: .command)
                    .frame(width: 0, height: 0)
                    .opacity(0)
                    .accessibilityHidden(true)
                VStack(spacing: 0) {
                    ZStack {
                        if let appWordmarkImage = BrandImage.wordmark(accent: ThemeManager.shared.accent, isDark: ThemeManager.shared.isDark) {
                            Image(platformImage: appWordmarkImage)
                                .resizable()
                                .scaledToFit()
                                .frame(height: 108)
                                .frame(maxWidth: .infinity, alignment: .center)
                        }
                        HStack(spacing: 10) {
                            paletteButton
                            themeToggleButton
                            accountMenuButton
                        }
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 18)
                    .padding(.bottom, 4)
                    // ONE fixed, centered switcher for all five tabs — hoisted out of the
                    // per-view headers (each embedded it beside different-width siblings,
                    // so its x-position shifted per tab; user-reported). Views receive no
                    // switcher and render their headers without it, exactly like iOS.
                    switcher
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.top, 8)
                        .padding(.bottom, 4)
                    // Task 6 replaces this shell with the sidebar + master-detail layout;
                    // this is a minimal mechanical mapping onto the new four-destination
                    // Tab enum so macOS keeps compiling in the meantime. Markets/Invest
                    // don't have their real macOS section hosts yet — Watchlist stands in
                    // for Markets (its first section) and Home/Invest render an empty
                    // placeholder. No polish; macOS UAT happens after Task 6.
                    Group {
                        switch tab {
                        case .home: EmptyView()
                        case .markets: WatchlistView()
                        case .portfolio: PortfolioView()
                        case .invest: EmptyView()
                        }
                    }
                }
                .allowsHitTesting(!showAccountPanel)

                if showAccountPanel {
                    Color.black.opacity(0.45)
                        .ignoresSafeArea()
                        .transition(.opacity)
                        .onTapGesture { close() }

                    accountPanel
                        .frame(width: max(geo.size.width * 0.25, 260))
                        .frame(maxHeight: .infinity)
                        .background(Theme.surface)
                        .overlay(Rectangle().frame(width: 1).foregroundStyle(Theme.hairline), alignment: .leading)
                        .ignoresSafeArea()
                        .transition(.move(edge: .trailing))
                }

                if showPalette {
                    CommandPaletteView(
                        viewModel: paletteVM,
                        onSelect: { handlePaletteSelection($0) },
                        onClose: { closePalette() }
                    )
                }
            }
            .animation(.spring(response: 0.34, dampingFraction: 0.88), value: showAccountPanel)
        }
        .frame(minWidth: 560, minHeight: 680)
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
        .task { await scheduler.run() }
        .confirmationDialog(tr(.exportPortfolioData), isPresented: $showExportDialog,
                            titleVisibility: .visible) {
            ForEach(PortfolioExportFormat.allCases, id: \.self) { format in
                Button(format.displayName) { beginExport(format) }
            }
            Button(tr(.cancel), role: .cancel) {}
        } message: {
            Text(tr(.exportFormatPrompt))
        }
        .alert(tr(.exportFailed), isPresented: Binding(
            get: { exportError != nil },
            set: { if !$0 { exportError = nil } }
        )) {
            Button(tr(.ok), role: .cancel) { exportError = nil }
        } message: {
            Text(exportError ?? "")
        }
        .sheet(item: $paletteAsset) { asset in
            NavigationStack {
                AssetDetailView(asset: asset)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(tr(.done)) { paletteAsset = nil }
                        }
                    }
            }
        }
    }
    #endif

    /// Renders the current portfolio to `format` off the main run loop, then presents a
    /// save panel so the user can write it anywhere on their Mac.
    private func beginExport(_ format: PortfolioExportFormat) {
        guard !isExporting else { return }
        isExporting = true
        Task {
            defer { isExporting = false }
            do {
                let data = try await exportPortfolio(format: format)
                presentSavePanel(for: data, format: format)
            } catch {
                exportError = error.localizedDescription
            }
        }
    }

    private func presentSavePanel(for data: Data, format: PortfolioExportFormat) {
        #if os(macOS)
        let panel = NSSavePanel()
        panel.title = tr(.exportPortfolioData)
        panel.nameFieldStringValue = PortfolioExportNaming.filename(for: format, on: Date())
        if let contentType = UTType(filenameExtension: format.fileExtension) {
            panel.allowedContentTypes = [contentType]
        }
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            exportError = error.localizedDescription
        }
        #else
        let contentType = UTType(filenameExtension: format.fileExtension) ?? .data
        iosExportDocument = PortfolioExportDocument(
            data: data,
            contentType: contentType,
            filename: PortfolioExportNaming.filename(for: format, on: Date())
        )
        showFileExporter = true
        #endif
    }

    private func close() {
        showAccountPanel = false
        panelRoute = .menu
    }

    private func handlePaletteSelection(_ result: PaletteResult) {
        switch result {
        case .navigate(_, _, let destination):
            switch destination {
            case .home: tab = .home
            case .markets: tab = .markets
            case .portfolio: tab = .portfolio
            case .invest: tab = .invest
            }
        case .asset(let asset):
            paletteAsset = asset
        }
        closePalette()
    }

    private func closePalette() {
        showPalette = false
        paletteVM.reset()
    }

    private var paletteButton: some View {
        Button {
            showPalette = true
        } label: {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.gold)
                .frame(width: 28, height: 28)
                .background(Theme.surface, in: Circle())
                .overlay(Circle().stroke(Theme.gold.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var themeToggleButton: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.25)) { ThemeManager.shared.toggle() }
        } label: {
            Image(systemName: ThemeManager.shared.isDark ? "moon.fill" : "sun.max.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.gold)
                .frame(width: 28, height: 28)
                .background(Theme.surface, in: Circle())
                .overlay(Circle().stroke(Theme.gold.opacity(0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    private var accountMenuButton: some View {
        Button {
            showAccountPanel.toggle()
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(showAccountPanel ? Theme.bgBottom : Theme.gold)
                .frame(width: 28, height: 28)
                .background(showAccountPanel ? AnyShapeStyle(Theme.goldGradient) : AnyShapeStyle(Theme.surface), in: Circle())
                .overlay(Circle().stroke(Theme.gold.opacity(showAccountPanel ? 0 : 0.4), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var accountPanel: some View {
        switch panelRoute {
        case .menu: accountMenuPage
        case .profile: profilePage
        case .accountSettings: accountSettingsPage
        case .notifications: notificationsPage
        case .appearance: appearancePage
        case .security: securityPage
        case .help: helpPage
        case .about: aboutPage
        case .language: languagePage
        }
    }

    private var accountMenuPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(tr(.account))
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                Button { close() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(width: 24, height: 24)
                        .background(Theme.surfaceHi, in: Circle())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            .padding(.top, 44)
            .padding(.bottom, 14)

            Divider().overlay(Theme.hairline)

            VStack(alignment: .leading, spacing: 2) {
                accountRow(icon: "person.crop.circle", title: tr(.profile)) { panelRoute = .profile }
                accountRow(icon: "gearshape", title: tr(.accountSettings)) { panelRoute = .accountSettings }
                accountRow(icon: "bell", title: tr(.notifications)) { panelRoute = .notifications }
                accountRow(icon: "paintpalette", title: tr(.appearance)) { panelRoute = .appearance }
                accountRow(icon: "globe", title: tr(.language)) { panelRoute = .language }
            }
            .padding(.top, 10)

            Divider().overlay(Theme.hairline).padding(.vertical, 10)

            VStack(alignment: .leading, spacing: 2) {
                accountRow(icon: "lock.shield", title: tr(.securityAndPrivacy)) { panelRoute = .security }
                accountRow(icon: "square.and.arrow.up", title: tr(.exportPortfolioData)) {
                    close()
                    showExportDialog = true
                }
                accountRow(icon: "questionmark.circle", title: tr(.helpAndSupport)) { panelRoute = .help }
                accountRow(icon: "info.circle", title: tr(.aboutAPTrade)) { panelRoute = .about }
            }

            Spacer()

            Divider().overlay(Theme.hairline)
            if isLoggedIn {
                accountRow(icon: "rectangle.portrait.and.arrow.right", title: tr(.signOut), destructive: true) {
                    isLoggedIn = false
                    close()
                }
                .padding(.bottom, 12)
            } else {
                accountRow(icon: "person.crop.circle.badge.checkmark", title: tr(.login)) {
                    isLoggedIn = true
                    close()
                }
                .padding(.bottom, 12)
            }
        }
        .padding(.top, 4)
    }

    private var profilePage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: tr(.profile))

            Divider().overlay(Theme.hairline)

            VStack(alignment: .leading, spacing: 18) {
                detailField(label: tr(.name), value: "Ankit Patel")
                detailField(label: tr(.dateOfBirth), value: "January 1, 1995")
                detailField(label: tr(.email), value: "ankitpatel.svnit@gmail.com")
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)

            Spacer()
        }
        .padding(.top, 4)
    }

    private var accountSettingsPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: tr(.accountSettings))

            Divider().overlay(Theme.hairline)

            VStack(alignment: .leading, spacing: 18) {
                detailField(label: tr(.tradingMode), value: tr(.simulatedPaperTrading))
                detailField(label: tr(.startingBalance), value: "$100,000.00")
                detailField(label: tr(.displayCurrency), value: "USD ($)")
                detailField(label: tr(.defaultTab), value: tr(.watchlist))
                detailField(label: tr(.biometricLogin), value: tr(.enabledTouchID))
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)

            toggleRow(icon: "arrow.triangle.2.circlepath", title: tr(.settingsDrip),
                      subtitle: tr(.settingsDripFooter), isOn: $settingsVM.settings.dripEnabled)

            #if os(iOS)
            // Finnhub key entry — iOS only: the sandboxed config.json isn't user-reachable,
            // so the key is entered here instead of the macOS file-drop flow. Saving writes
            // the same config.json the news factories re-read per News visit.
            sectionLabel(tr(.news))
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    TextField(tr(.finnhubApiKeyField), text: $finnhubKeyDraft)
                        .textFieldStyle(.plain)
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textPrimary)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    if finnhubKeyDraft.trimmingCharacters(in: .whitespacesAndNewlines) != settingsVM.finnhubKey {
                        Button(tr(.saveAction)) {
                            settingsVM.saveFinnhubKey(finnhubKeyDraft)
                            finnhubKeyDraft = settingsVM.finnhubKey
                        }
                        .buttonStyle(.plain)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Theme.bgBottom)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 6)
                        .background(Theme.goldGradient, in: Capsule())
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 11)
                .background(Theme.surface, in: Capsule())

                Text(tr(.finnhubKeyAppliesNote))
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textTertiary)
            }
            .padding(.horizontal, 20)
            .onAppear { finnhubKeyDraft = settingsVM.finnhubKey }
            #endif

            Spacer()
        }
        .padding(.top, 4)
    }

    private var notificationsPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: tr(.notifications))
            Divider().overlay(Theme.hairline)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    sectionLabel(tr(.pushNotifications))
                    toggleRow(icon: "bell.badge", title: tr(.priceAlerts),
                              subtitle: tr(.priceAlertsSubtitle), isOn: $settingsVM.settings.priceAlerts)
                    toggleRow(icon: "checkmark.seal", title: tr(.orderFills),
                              subtitle: tr(.orderFillsSubtitle), isOn: $settingsVM.settings.orderFills)
                    toggleRow(icon: "clock", title: tr(.marketOpenAndClose),
                              subtitle: tr(.marketOpenAndCloseSubtitle), isOn: $settingsVM.settings.marketOpenClose)
                    toggleRow(icon: "chart.bar.doc.horizontal", title: tr(.earningsReportsToggle),
                              subtitle: tr(.earningsReportsSubtitle), isOn: $settingsVM.settings.earningsReports)
                    toggleRow(icon: "chart.pie.fill", title: tr(.pieContributionsToggle),
                              subtitle: tr(.pieContributionsSubtitle), isOn: $settingsVM.settings.pieContributions)
                    toggleRow(icon: "banknote", title: tr(.settingsDividendNotif),
                              subtitle: tr(.settingsDividendNotifSubtitle), isOn: $settingsVM.settings.dividendNotifications)
                    toggleRow(icon: "newspaper", title: tr(.dailyNewsDigest),
                              subtitle: tr(.dailyNewsDigestSubtitle), isOn: $settingsVM.settings.newsDigest)

                    sectionLabel(tr(.email))
                    toggleRow(icon: "envelope", title: tr(.emailNotifications),
                              subtitle: tr(.emailNotificationsSubtitle), isOn: $settingsVM.settings.emailNotifications)
                }
                .padding(.top, 6)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 4)
    }

    private var appearancePage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: tr(.appearance))
            Divider().overlay(Theme.hairline)
            VStack(alignment: .leading, spacing: 0) {
                sectionLabel(tr(.theme))
                themeOptionRow(icon: "moon.fill", title: tr(.dark), subtitle: tr(.darkSubtitle), isSelected: ThemeManager.shared.isDark) {
                    if !ThemeManager.shared.isDark { withAnimation(.easeInOut(duration: 0.25)) { ThemeManager.shared.toggle() } }
                }
                themeOptionRow(icon: "sun.max.fill", title: tr(.light), subtitle: tr(.lightSubtitle), isSelected: !ThemeManager.shared.isDark) {
                    if ThemeManager.shared.isDark { withAnimation(.easeInOut(duration: 0.25)) { ThemeManager.shared.toggle() } }
                }

                sectionLabel(tr(.accent))
                ForEach(AccentTheme.allCases, id: \.self) { accent in
                    accentRow(accent)
                }
            }
            .padding(.top, 6)
            Spacer()
        }
        .padding(.top, 4)
    }

    private func accentRow(_ accent: AccentTheme) -> some View {
        let ramp = Theme.accentRamp(accent)
        let selected = ThemeManager.shared.accent == accent
        return Button {
            withAnimation(.easeInOut(duration: 0.25)) { ThemeManager.shared.accent = accent }
        } label: {
            HStack(spacing: 12) {
                Circle()
                    .fill(LinearGradient(colors: [ramp.deep, ramp.mid, ramp.light],
                                         startPoint: .bottomLeading, endPoint: .topTrailing))
                    .frame(width: 26, height: 26)
                    .overlay(Circle().stroke(Theme.hairline, lineWidth: 1))
                    .overlay {
                        if selected {
                            Circle().stroke(Theme.textPrimary, lineWidth: 1.5).padding(-3)
                        }
                    }
                VStack(alignment: .leading, spacing: 2) {
                    Text(accent.displayName).font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.textPrimary)
                    Text(accent.tagline).font(.system(size: 11)).foregroundStyle(Theme.textTertiary)
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 16)).foregroundStyle(Theme.gold)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var languagePage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: tr(.language))
            Divider().overlay(Theme.hairline)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(AppLanguage.allCases, id: \.self) { language in
                    languageRow(language)
                }
            }
            .padding(.top, 6)
            Spacer()
        }
        .padding(.top, 4)
    }

    private func languageRow(_ language: AppLanguage) -> some View {
        let selected = LocalizationManager.shared.language == language
        return Button {
            withAnimation(.easeInOut(duration: 0.25)) {
                LocalizationManager.shared.language = language
            }
        } label: {
            HStack(spacing: 12) {
                Text(language.displayName)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 16)).foregroundStyle(Theme.gold)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var securityPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: tr(.securityAndPrivacy))
            Divider().overlay(Theme.hairline)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    sectionLabel(tr(.authentication))
                    toggleRow(icon: "faceid", title: tr(.biometricLogin),
                              subtitle: tr(.biometricLoginSubtitle), isOn: $settingsVM.settings.biometricLogin)
                    toggleRow(icon: "lock.rotation", title: tr(.requireAuthOnLaunch),
                              subtitle: tr(.requireAuthOnLaunchSubtitle), isOn: $settingsVM.settings.requireAuthOnLaunch)
                    toggleRow(icon: "hand.raised", title: tr(.confirmTrades),
                              subtitle: tr(.confirmTradesSubtitle), isOn: $settingsVM.settings.confirmTrades)

                    sectionLabel(tr(.privacy))
                    toggleRow(icon: "chart.bar.doc.horizontal", title: tr(.shareUsageAnalytics),
                              subtitle: tr(.shareUsageAnalyticsSubtitle), isOn: $settingsVM.settings.analyticsSharing)

                    sectionLabel(tr(.dataSection))
                    linkRow(icon: "key", title: tr(.changePassword))
                    linkRow(icon: "iphone.and.arrow.forward", title: tr(.manageDevices), value: "2 active")
                    linkRow(icon: "trash", title: tr(.clearLocalCache))
                }
                .padding(.top, 6)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 4)
    }

    private var helpPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: tr(.helpAndSupport))
            Divider().overlay(Theme.hairline)
            VStack(alignment: .leading, spacing: 0) {
                sectionLabel(tr(.resources))
                linkRow(icon: "questionmark.circle", title: tr(.faq))
                linkRow(icon: "book", title: tr(.userGuide))
                linkRow(icon: "keyboard", title: tr(.keyboardShortcuts))

                sectionLabel(tr(.contact))
                linkRow(icon: "envelope", title: tr(.emailSupport), value: "support@aptrade.app")
                linkRow(icon: "exclamationmark.bubble", title: tr(.reportAProblem))
            }
            .padding(.top, 6)
            Spacer()
        }
        .padding(.top, 4)
    }

    private var aboutPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: tr(.aboutAPTrade))
            Divider().overlay(Theme.hairline)
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 14) {
                    if let logo = BrandImage.logo(accent: ThemeManager.shared.accent, isDark: ThemeManager.shared.isDark) {
                        Image(platformImage: logo).resizable().scaledToFit().frame(width: 44, height: 44)
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        BrandMark(size: 18, showsMark: false)
                        Text(tr(.taglineShort))
                            .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                    }
                }
                .padding(.bottom, 2)
                detailField(label: tr(.version), value: "1.0.0 (100)")
                detailField(label: tr(.dataProviders), value: "Yahoo Finance · CoinGecko")
                detailField(label: tr(.mode), value: tr(.simulatedPaperTrading))
            }
            .padding(.horizontal, 20).padding(.top, 20)

            VStack(alignment: .leading, spacing: 0) {
                linkRow(icon: "doc.text", title: tr(.termsOfService))
                linkRow(icon: "hand.raised", title: tr(.privacyPolicy))
                linkRow(icon: "checkmark.shield", title: tr(.licenses))
            }
            .padding(.top, 14)

            Spacer()
            Text(tr(.copyrightDisclaimer))
                .font(.system(size: 10)).foregroundStyle(Theme.textTertiary)
                .padding(.horizontal, 20).padding(.bottom, 14)
        }
        .padding(.top, 4)
    }

    // MARK: Settings row primitives

    private func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .bold)).tracking(1.2)
            .foregroundStyle(Theme.textTertiary)
            .padding(.horizontal, 20)
            .padding(.top, 18).padding(.bottom, 6)
    }

    private func toggleRow(icon: String, title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Theme.gold).frame(width: 20)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.textPrimary)
                Text(subtitle).font(.system(size: 11)).foregroundStyle(Theme.textTertiary)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(Theme.gold)
                .controlSize(.small)
        }
        .padding(.horizontal, 20).padding(.vertical, 10)
    }

    private func themeOptionRow(icon: String, title: String, subtitle: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.gold).frame(width: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.textPrimary)
                    Text(subtitle).font(.system(size: 11)).foregroundStyle(Theme.textTertiary)
                }
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 16)).foregroundStyle(Theme.gold)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func linkRow(icon: String, title: String, value: String? = nil) -> some View {
        Button { close() } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.gold).frame(width: 20)
                Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.textPrimary)
                Spacer()
                if let value {
                    Text(value).font(.system(size: 12).monospacedDigit()).foregroundStyle(Theme.textTertiary)
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold)).foregroundStyle(Theme.textTertiary)
            }
            .padding(.horizontal, 20).padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func subpageHeader(title: String) -> some View {
        HStack(spacing: 12) {
            Button { panelRoute = .menu } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 24, height: 24)
                    .background(Theme.surfaceHi, in: Circle())
            }
            .buttonStyle(.plain)
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Theme.textPrimary)
            Spacer()
            Button { close() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 24, height: 24)
                    .background(Theme.surfaceHi, in: Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        // Matches the menu page's header inset so the title sits at the same height as
        // "Account" and clears the window's top edge rather than touching it.
        .padding(.top, 44)
        .padding(.bottom, 14)
    }

    private func detailField(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .bold))
                .tracking(1.2)
                .foregroundStyle(Theme.textTertiary)
            Text(value)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Theme.textPrimary)
        }
    }

    private func accountRow(icon: String, title: String, destructive: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(destructive ? Theme.down : Theme.gold)
                    .frame(width: 20)
                Text(title)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(destructive ? Theme.down : Theme.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(Color.clear)
    }

    private func tabTitle(_ tab: Tab) -> String {
        switch tab {
        case .home:      return tr(.homeTab)
        case .markets:   return tr(.marketsTab)
        case .portfolio: return tr(.portfolio)
        case .invest:    return tr(.investTab)
        }
    }

    private var switcher: some View {
        HStack(spacing: 4) {
            ForEach(Tab.allCases, id: \.self) { item in
                let selected = tab == item
                Button {
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) { tab = item }
                } label: {
                    Text(tabTitle(item))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 8)
                        .background {
                            if selected {
                                Capsule().fill(Theme.surfaceHi)
                                    .overlay(Capsule().stroke(Theme.gold.opacity(0.40), lineWidth: 1))
                                    .matchedGeometryEffect(id: "tab", in: pill)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .background(Theme.surface, in: Capsule())
        .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
    }
}

#if os(iOS)
/// Wraps already-rendered export bytes so SwiftUI's `.fileExporter` can write them.
/// Export is one-way (write only); the reader init is required by `FileDocument` but unused.
struct PortfolioExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }

    let data: Data
    let contentType: UTType
    let filename: String

    init(data: Data, contentType: UTType, filename: String) {
        self.data = data
        self.contentType = contentType
        self.filename = filename
    }

    init(configuration: ReadConfiguration) throws {
        throw CocoaError(.fileReadUnsupportedScheme)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
#endif
