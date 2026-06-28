import SwiftUI
import AppKit
import UniformTypeIdentifiers
import APTradeApplication
import APTradeDomain

struct RootView: View {
    enum Tab: String, CaseIterable { case watchlist = "Watchlist", portfolio = "Portfolio" }
    private enum PanelRoute {
        case menu, profile, accountSettings, notifications, appearance, security, help, about
    }

    @State private var tab: Tab = .watchlist
    @State private var showAccountPanel = false
    @State private var panelRoute: PanelRoute = .menu
    @State private var isLoggedIn = true
    @State private var settingsVM = CompositionRoot.makeSettingsViewModel()
    @State private var scheduler = CompositionRoot.makeMarketActivityCoordinator()
    @Namespace private var pill

    @State private var exportPortfolio = CompositionRoot.makeExportPortfolioUseCase()
    @State private var showExportDialog = false
    @State private var isExporting = false
    @State private var exportError: String?

    @State private var showPalette = false
    @State private var paletteVM = CompositionRoot.makeCommandPaletteViewModel()
    @State private var paletteAsset: Asset?

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .trailing) {
                Theme.background.ignoresSafeArea()
                Button("") { showPalette = true }
                    .keyboardShortcut("k", modifiers: .command)
                    .frame(width: 0, height: 0)
                    .opacity(0)
                VStack(spacing: 0) {
                    ZStack {
                        if let appWordmarkImage = BrandImage.wordmark(accent: ThemeManager.shared.accent, isDark: ThemeManager.shared.isDark) {
                            Image(nsImage: appWordmarkImage)
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
                    Group {
                        switch tab {
                        case .watchlist: WatchlistView(switcher: AnyView(switcher))
                        case .portfolio: PortfolioView(switcher: AnyView(switcher))
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
        .confirmationDialog("Export Portfolio Data", isPresented: $showExportDialog,
                            titleVisibility: .visible) {
            ForEach(PortfolioExportFormat.allCases, id: \.self) { format in
                Button(format.displayName) { beginExport(format) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Choose a format to save your holdings, cost basis, and P&L.")
        }
        .alert("Export Failed", isPresented: Binding(
            get: { exportError != nil },
            set: { if !$0 { exportError = nil } }
        )) {
            Button("OK", role: .cancel) { exportError = nil }
        } message: {
            Text(exportError ?? "")
        }
        .sheet(item: $paletteAsset) { asset in
            NavigationStack {
                AssetDetailView(asset: asset)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Done") { paletteAsset = nil }
                        }
                    }
            }
        }
    }

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
        let panel = NSSavePanel()
        panel.title = "Export Portfolio Data"
        panel.nameFieldStringValue = "\(Self.exportFileStem).\(format.fileExtension)"
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
    }

    /// Date-stamped base filename, e.g. `APTrade-Portfolio-2026-06-25`.
    private static var exportFileStem: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return "APTrade-Portfolio-\(formatter.string(from: Date()))"
    }

    private func close() {
        showAccountPanel = false
        panelRoute = .menu
    }

    private func handlePaletteSelection(_ result: PaletteResult) {
        switch result {
        case .navigate(_, _, let destination):
            switch destination {
            case .watchlist: tab = .watchlist
            case .portfolio: tab = .portfolio
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
        }
    }

    private var accountMenuPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Account")
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
                accountRow(icon: "person.crop.circle", title: "Profile") { panelRoute = .profile }
                accountRow(icon: "gearshape", title: "Account Settings") { panelRoute = .accountSettings }
                accountRow(icon: "bell", title: "Notifications") { panelRoute = .notifications }
                accountRow(icon: "paintpalette", title: "Appearance") { panelRoute = .appearance }
            }
            .padding(.top, 10)

            Divider().overlay(Theme.hairline).padding(.vertical, 10)

            VStack(alignment: .leading, spacing: 2) {
                accountRow(icon: "lock.shield", title: "Security & Privacy") { panelRoute = .security }
                accountRow(icon: "square.and.arrow.up", title: "Export Portfolio Data") {
                    close()
                    showExportDialog = true
                }
                accountRow(icon: "questionmark.circle", title: "Help & Support") { panelRoute = .help }
                accountRow(icon: "info.circle", title: "About APTrade") { panelRoute = .about }
            }

            Spacer()

            Divider().overlay(Theme.hairline)
            if isLoggedIn {
                accountRow(icon: "rectangle.portrait.and.arrow.right", title: "Sign Out", destructive: true) {
                    isLoggedIn = false
                    close()
                }
                .padding(.bottom, 12)
            } else {
                accountRow(icon: "person.crop.circle.badge.checkmark", title: "Login") {
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
            subpageHeader(title: "Profile")

            Divider().overlay(Theme.hairline)

            VStack(alignment: .leading, spacing: 18) {
                detailField(label: "Name", value: "Ankit Patel")
                detailField(label: "Date of Birth", value: "January 1, 1995")
                detailField(label: "Email", value: "ankitpatel.svnit@gmail.com")
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)

            Spacer()
        }
        .padding(.top, 4)
    }

    private var accountSettingsPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: "Account Settings")

            Divider().overlay(Theme.hairline)

            VStack(alignment: .leading, spacing: 18) {
                detailField(label: "Trading Mode", value: "Simulated · Paper Trading")
                detailField(label: "Starting Balance", value: "$100,000.00")
                detailField(label: "Display Currency", value: "USD ($)")
                detailField(label: "Default Tab", value: "Watchlist")
                detailField(label: "Biometric Login", value: "Enabled — Touch ID")
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)

            Spacer()
        }
        .padding(.top, 4)
    }

    private var notificationsPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: "Notifications")
            Divider().overlay(Theme.hairline)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    sectionLabel("Push Notifications")
                    toggleRow(icon: "bell.badge", title: "Price Alerts",
                              subtitle: "When a watchlist alert is triggered", isOn: $settingsVM.settings.priceAlerts)
                    toggleRow(icon: "checkmark.seal", title: "Order Fills",
                              subtitle: "Buy and sell confirmations", isOn: $settingsVM.settings.orderFills)
                    toggleRow(icon: "clock", title: "Market Open & Close",
                              subtitle: "Daily session reminders", isOn: $settingsVM.settings.marketOpenClose)
                    toggleRow(icon: "newspaper", title: "Daily News Digest",
                              subtitle: "Top stories for your holdings", isOn: $settingsVM.settings.newsDigest)

                    sectionLabel("Email")
                    toggleRow(icon: "envelope", title: "Email Notifications",
                              subtitle: "Send a copy to ankitpatel.svnit@gmail.com", isOn: $settingsVM.settings.emailNotifications)
                }
                .padding(.top, 6)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 4)
    }

    private var appearancePage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: "Appearance")
            Divider().overlay(Theme.hairline)
            VStack(alignment: .leading, spacing: 0) {
                sectionLabel("Theme")
                themeOptionRow(title: "Dark", subtitle: "Default — gold on black", isSelected: ThemeManager.shared.isDark) {
                    if !ThemeManager.shared.isDark { withAnimation(.easeInOut(duration: 0.25)) { ThemeManager.shared.toggle() } }
                }
                themeOptionRow(title: "Light", subtitle: "Charcoal on warm white", isSelected: !ThemeManager.shared.isDark) {
                    if ThemeManager.shared.isDark { withAnimation(.easeInOut(duration: 0.25)) { ThemeManager.shared.toggle() } }
                }

                sectionLabel("Accent")
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

    private var securityPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: "Security & Privacy")
            Divider().overlay(Theme.hairline)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    sectionLabel("Authentication")
                    toggleRow(icon: "faceid", title: "Biometric Login",
                              subtitle: "Unlock with Touch ID / Face ID", isOn: $settingsVM.settings.biometricLogin)
                    toggleRow(icon: "lock.rotation", title: "Require Auth on Launch",
                              subtitle: "Ask every time the app opens", isOn: $settingsVM.settings.requireAuthOnLaunch)
                    toggleRow(icon: "hand.raised", title: "Confirm Trades",
                              subtitle: "Re-authenticate before buy / sell", isOn: $settingsVM.settings.confirmTrades)

                    sectionLabel("Privacy")
                    toggleRow(icon: "chart.bar.doc.horizontal", title: "Share Usage Analytics",
                              subtitle: "Anonymous diagnostics to improve APTrade", isOn: $settingsVM.settings.analyticsSharing)

                    sectionLabel("Data")
                    linkRow(icon: "key", title: "Change Password")
                    linkRow(icon: "iphone.and.arrow.forward", title: "Manage Devices", value: "2 active")
                    linkRow(icon: "trash", title: "Clear Local Cache")
                }
                .padding(.top, 6)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 4)
    }

    private var helpPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: "Help & Support")
            Divider().overlay(Theme.hairline)
            VStack(alignment: .leading, spacing: 0) {
                sectionLabel("Resources")
                linkRow(icon: "questionmark.circle", title: "Frequently Asked Questions")
                linkRow(icon: "book", title: "User Guide")
                linkRow(icon: "keyboard", title: "Keyboard Shortcuts")

                sectionLabel("Contact")
                linkRow(icon: "envelope", title: "Email Support", value: "support@aptrade.app")
                linkRow(icon: "exclamationmark.bubble", title: "Report a Problem")
            }
            .padding(.top, 6)
            Spacer()
        }
        .padding(.top, 4)
    }

    private var aboutPage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: "About APTrade")
            Divider().overlay(Theme.hairline)
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 14) {
                    if let logo = BrandImage.logo(accent: ThemeManager.shared.accent, isDark: ThemeManager.shared.isDark) {
                        Image(nsImage: logo).resizable().scaledToFit().frame(width: 44, height: 44)
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        BrandMark(size: 18, showsMark: false)
                        Text("Premium investing for macOS")
                            .font(.system(size: 12)).foregroundStyle(Theme.textSecondary)
                    }
                }
                .padding(.bottom, 2)
                detailField(label: "Version", value: "1.0.0 (100)")
                detailField(label: "Data Providers", value: "Yahoo Finance · CoinGecko")
                detailField(label: "Mode", value: "Simulated · Paper Trading")
            }
            .padding(.horizontal, 20).padding(.top, 20)

            VStack(alignment: .leading, spacing: 0) {
                linkRow(icon: "doc.text", title: "Terms of Service")
                linkRow(icon: "hand.raised", title: "Privacy Policy")
                linkRow(icon: "checkmark.shield", title: "Licenses")
            }
            .padding(.top, 14)

            Spacer()
            Text("© 2026 APTrade. Market data for informational purposes only.")
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

    private func themeOptionRow(title: String, subtitle: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: title == "Dark" ? "moon.fill" : "sun.max.fill")
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

    private var switcher: some View {
        HStack(spacing: 4) {
            ForEach(Tab.allCases, id: \.self) { item in
                let selected = tab == item
                Button {
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) { tab = item }
                } label: {
                    Text(item.rawValue)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
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
