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

    #if os(macOS)
    /// The macOS sidebar's selection (Task 6): Home is a single item, the other three
    /// destinations carry their own section directly — the sidebar item IS the section
    /// picker, there is no separate pill row underneath it (unlike iOS, where `Tab`
    /// switches a whole host view that owns its own pill switcher).
    enum SidebarDestination: Hashable {
        case home
        case markets(MarketsView.Section)
        case portfolio(PortfolioView.Section)
        case invest(InvestView.Section)
    }
    #endif

    @State private var tab: Tab = .home
    @State private var showAccountPanel = false
    @State private var panelRoute: PanelRoute = .menu
    @State private var isLoggedIn = true
    @State private var settingsVM = CompositionRoot.makeSettingsViewModel()
    /// Draft text for the iOS Finnhub key field; synced from `settingsVM.finnhubKey` when
    /// the Account Settings page appears, compared against it to enable Save.
    @State private var finnhubKeyDraft = ""
    @State private var scheduler = CompositionRoot.makeMarketActivityCoordinator()

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

    /// Cross-tab navigation requests from Home (Task 5): set + cleared by `MarketsView`/
    /// `InvestView`'s own `onChange`, via `handleHomeNavigation(_:)` below. iOS only —
    /// macOS routes Home navigation straight onto `sidebarSelection` (no request/clear
    /// dance needed since the sidebar owns section selection directly).
    @State private var marketsSectionRequest: MarketsView.Section?
    @State private var investSectionRequest: InvestView.Section?
    @State private var portfolioSectionRequest: PortfolioView.Section?

    #if os(macOS)
    /// The sidebar's current destination (Task 6). Persists per launch only, like `tab`.
    @State private var sidebarSelection: SidebarDestination = .home
    /// The macOS shell owns its OWN `PortfolioViewModel`/`PerformanceViewModel` instances
    /// (separate from any `PortfolioView` elsewhere) so the Portfolio destination can
    /// render `PortfolioSectionContent` directly without instantiating the full
    /// `PortfolioView` host (which would bring back its own pill switcher, redundant with
    /// the sidebar). See `macDestinationContent`.
    @State private var portfolioViewModel = CompositionRoot.makePortfolioViewModel()
    @State private var portfolioPerformanceVM = CompositionRoot.makePerformanceViewModel()
    @State private var portfolioSelectedAsset: Asset?
    #endif

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
            HomeView(onNavigate: { handleHomeNavigation($0) })
                .tabItem { Label(tr(.homeTab), systemImage: "house.fill") }
                .tag(Tab.home)
            MarketsView(onOpenSearch: { showPalette = true },
                        onOpenAccount: { showAccountPanel = true },
                        externalSection: $marketsSectionRequest)
                .tabItem { Label(tr(.marketsTab), systemImage: "chart.line.uptrend.xyaxis") }
                .tag(Tab.markets)
            PortfolioView(onOpenSearch: { showPalette = true },
                          onOpenAccount: { showAccountPanel = true },
                          onExport: { showExportDialog = true },
                          externalSection: $portfolioSectionRequest)
                .tabItem { Label(tr(.portfolio), systemImage: "chart.pie") }
                .tag(Tab.portfolio)
            InvestView(onOpenSearch: { showPalette = true },
                       onOpenAccount: { showAccountPanel = true },
                       externalSection: $investSectionRequest,
                       dripEnabled: $settingsVM.settings.dripEnabled)
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
                HStack(spacing: 0) {
                    sidebar
                    macDestinationContent
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
        // Sidebar 208 + Screener's list column 520 + 1pt hairline + ~390pt legible
        // detail pane = 1120 floor (bumped from 860; that width left the pane-hosted
        // detail an unsatisfiable ~131pt at the Screener's list width).
        .frame(minWidth: 1120, minHeight: 680)
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
        // M10.1 UAT U5 (general guard, verified against the SAME bug class as the Invest
        // fix above): `portfolioSelectedAsset` is hoisted here on `RootView`, not local to
        // `PortfolioSectionContent`, so it survives even when the Portfolio destination's
        // `NavigationStack` IS torn down (e.g. leaving Portfolio for Home, then returning) —
        // a fresh NavigationStack would immediately re-push the stale asset on mount purely
        // because the bound item was still non-nil. Clearing it on ANY sidebar destination
        // change (not just within Portfolio) closes both that case and the within-Portfolio
        // case (e.g. Holdings → Allocation, same switch case/NavigationStack instance):
        // setting the bound item to nil is itself what pops `.navigationDestination(item:)`,
        // no `.id()`/stack teardown required here.
        .onChange(of: sidebarSelection) { _, _ in portfolioSelectedAsset = nil }
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

    // MARK: - Sidebar (Task 6)

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            BrandMark(size: 14)
                .padding(.horizontal, 14)
                .padding(.top, 18)
                .padding(.bottom, 18)

            sidebarItem(icon: "house.fill", title: tr(.homeTab), selected: sidebarSelection == .home) {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { sidebarSelection = .home }
            }
            .padding(.horizontal, 10)

            groupLabel(tr(.marketsTab))
            ForEach(MarketsView.Section.allCases, id: \.self) { section in
                sidebarItem(icon: icon(for: section), title: section.title,
                            selected: sidebarSelection == .markets(section)) {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { sidebarSelection = .markets(section) }
                }
                .padding(.horizontal, 10)
            }

            groupLabel(tr(.portfolio))
            ForEach(PortfolioView.Section.allCases, id: \.self) { section in
                sidebarItem(icon: icon(for: section), title: section.title,
                            selected: sidebarSelection == .portfolio(section)) {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { sidebarSelection = .portfolio(section) }
                }
                .padding(.horizontal, 10)
            }

            groupLabel(tr(.investTab))
            ForEach(InvestView.Section.allCases, id: \.self) { section in
                sidebarItem(icon: icon(for: section), title: section.title,
                            selected: sidebarSelection == .invest(section)) {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { sidebarSelection = .invest(section) }
                }
                .padding(.horizontal, 10)
            }

            Spacer(minLength: 12)

            Divider().overlay(Theme.hairline).padding(.horizontal, 10).padding(.bottom, 6)

            sidebarFooterRow(icon: "magnifyingglass", title: tr(.sidebarSearch), trailing: "⌘K") {
                showPalette = true
            }
            .padding(.horizontal, 10)

            HStack(spacing: 8) {
                sidebarFooterRow(icon: "gearshape", title: tr(.sidebarSettings), trailing: nil) {
                    showAccountPanel = true
                }
                themeToggleButton
            }
            .padding(.horizontal, 10)
            .padding(.bottom, 14)
        }
        .frame(width: 208)
        .frame(maxHeight: .infinity)
        .background(Theme.surface.opacity(0.55))
        .overlay(Rectangle().frame(width: 1).foregroundStyle(Theme.hairline), alignment: .trailing)
    }

    /// One sidebar row — Home or a section item. Selected = `Theme.surfaceHi` fill with a
    /// gold inset stroke ring (~0.35 opacity), gold-tinted glyph; mirrors the mockup's
    /// `.sitem`/`.sitem.on` exactly, in Theme tokens.
    private func sidebarItem(icon: String, title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 9) {
                Image(systemName: icon)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(selected ? Theme.gold : Theme.textSecondary)
                    .frame(width: 16)
                Text(title)
                    .font(.system(size: 13, weight: selected ? .semibold : .medium))
                    .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background {
                if selected {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(Theme.surfaceHi)
                        .overlay(
                            RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .stroke(Theme.gold.opacity(0.35), lineWidth: 1)
                        )
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Group headers — MARKETS / PORTFOLIO / INVEST — mirrors `sectionLabel`'s micro-caps
    /// tertiary idiom (used by the account panel), at the sidebar's narrower inset.
    private func groupLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.system(size: 9.5, weight: .bold))
            .tracking(1.3)
            .foregroundStyle(Theme.textTertiary)
            .padding(.horizontal, 10)
            .padding(.top, 14)
            .padding(.bottom, 5)
    }

    /// Search / Settings footer rows — same row shape as `sidebarItem` but never "selected"
    /// (they open overlays, not destinations) and can show a trailing hint (⌘K).
    private func sidebarFooterRow(icon: String, title: String, trailing: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 9) {
                Image(systemName: icon)
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 16)
                Text(title)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.textSecondary)
                Spacer(minLength: 0)
                if let trailing {
                    Text(trailing)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Theme.textTertiary)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func icon(for section: MarketsView.Section) -> String {
        switch section {
        case .watchlist: return "list.bullet"
        case .screener: return "scope"
        case .calendar: return "calendar"
        case .news: return "newspaper"
        }
    }

    private func icon(for section: PortfolioView.Section) -> String {
        switch section {
        case .holdings: return "briefcase.fill"
        case .allocation: return "chart.pie.fill"
        case .activity: return "clock.arrow.circlepath"
        case .performance: return "waveform.path.ecg"
        }
    }

    private func icon(for section: InvestView.Section) -> String {
        switch section {
        case .plans: return "square.grid.2x2.fill"
        case .income: return "banknote.fill"
        }
    }

    // MARK: - Destination content (Task 6)

    /// Reusing the Task-4 hosts (`MarketsView`/`PortfolioView`/`InvestView`) wholesale —
    /// driving their `externalSection`/section state from the sidebar — was evaluated
    /// first, since it would reuse everything through one code path. Rejected: each host
    /// renders its OWN pill-row section switcher, which the sidebar already replaces, so
    /// reusing them wholesale would show a redundant picker under every destination —
    /// contradicting the mockup, where the sidebar IS the only section nav on desktop.
    /// Instead, each host exposes the hoisted section-rendering it already uses internally
    /// (`MarketsView.sectionView`, `InvestView.sectionView`, `PortfolioSectionContent`) and
    /// this switch constructs the SAME content directly. Markets/Invest section views are
    /// self-contained (each owns its own view model) so no shell-level state is needed for
    /// them; Portfolio needs its own `PortfolioViewModel`/`PerformanceViewModel` instances,
    /// owned above as `portfolioViewModel`/`portfolioPerformanceVM` — mirroring how
    /// `PortfolioView` owns its own copies at host level for the iOS pill host.
    @ViewBuilder
    private var macDestinationContent: some View {
        switch sidebarSelection {
        case .home:
            HomeViewMac(onNavigate: { handleHomeNavigation($0) })
        case .markets(let section):
            MarketsView.sectionView(section, onOpenSearch: { showPalette = true }, onOpenAccount: { showAccountPanel = true })
        case .portfolio(let section):
            NavigationStack {
                VStack(spacing: 0) {
                    // T6-review carried acceptance item: the sidebar destination originally
                    // rendered ONLY `PortfolioSectionContent`, dropping the summary header
                    // (total value, day/unrealized P&L, cash, the expandable P&L chart, reset)
                    // that `PortfolioView` shows above its own section picker on iOS. Restored
                    // here using the SAME hoisted header (`PortfolioSummaryHeader`, Task 7) —
                    // no section picker beside it, since the sidebar itself is that picker.
                    PortfolioSummaryHeader(viewModel: portfolioViewModel,
                                           onExport: { showExportDialog = true })
                    Divider().overlay(Theme.hairline)
                    PortfolioSectionContent(section: section, viewModel: portfolioViewModel,
                                            performanceVM: portfolioPerformanceVM,
                                            selectedAsset: $portfolioSelectedAsset)
                }
                .navigationDestination(item: $portfolioSelectedAsset) { asset in
                    AssetDetailView(asset: asset)
                }
            }
            .task {
                await portfolioViewModel.onAppear()
                await portfolioViewModel.runLiveUpdates()
            }
            .onAppear { portfolioViewModel.reload() }
        case .invest(let section):
            // `.id(section)` (M10.1 UAT U5): without it, switching Invest sections (e.g.
            // Plans → Income) keeps this SAME `NavigationStack` instance alive — the
            // `switch`'s `.invest` case doesn't change, only `section`'s associated value
            // does, so SwiftUI never tears the branch down. `PlansSection` can push a pie
            // detail via its own `.navigationDestination(item:)`; that push is registered
            // on this NavigationStack, which then keeps showing it on top even after the
            // switch's child content swaps from `PlansSection` to `IncomeSection` — Income
            // is only reachable after popping back manually (the reported bug). Keying the
            // stack to `section` forces a fresh `NavigationStack` (and hence an empty push
            // history) on every section change, so a pushed pie detail can never survive a
            // sidebar section switch.
            NavigationStack {
                InvestView.sectionView(section, dripEnabled: $settingsVM.settings.dripEnabled)
            }
            .id(section)
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
        // M10.1 UAT U7: the palette's static "Go to Home/Markets/Portfolio/Invest" rows
        // (and the `PaletteDestination`/`tab`-or-`sidebarSelection` routing they drove) are
        // gone — redundant with the sidebar (macOS) / tab bar (iOS). Symbol selection is
        // the only result kind left.
        switch result {
        case .asset(let asset):
            paletteAsset = asset
        }
        closePalette()
    }

    private func closePalette() {
        showPalette = false
        paletteVM.reset()
    }

    /// Routes a Home row/card tap onto the right destination + (where applicable) nested
    /// section — the single place both platforms' Home bodies delegate to. On iOS, only
    /// `RootView` owns `tab` and the per-tab section-request bindings; on macOS (Task 6),
    /// only `RootView` owns `sidebarSelection`, which the sidebar itself also writes, so
    /// Home navigation sets it directly — no request/clear dance needed since there's no
    /// separate host view to notify.
    private func handleHomeNavigation(_ destination: HomeDestination) {
        #if os(macOS)
        switch destination {
        case .marketsWatchlist: sidebarSelection = .markets(.watchlist)
        case .marketsScreener:  sidebarSelection = .markets(.screener)
        case .marketsCalendar:  sidebarSelection = .markets(.calendar)
        case .marketsNews:      sidebarSelection = .markets(.news)
        case .investIncome:     sidebarSelection = .invest(.income)
        // Hero click -> Portfolio Performance (not Holdings) since the Home hero chart IS
        // the Portfolio tab's Performance section, just fed by the same state — clicking it
        // lands on the exact same chart, in context. Settled cross-platform behavior; see
        // desktop `HomePane.kt`'s `HeroSection` (Constraint 4) and shared `HomeFeed.kt` — this
        // was a Swift/Kotlin divergence (Swift used to land on Holdings) that this backport
        // resolves.
        case .portfolio:        sidebarSelection = .portfolio(.performance)
        }
        #else
        switch destination {
        case .marketsWatchlist:
            tab = .markets
            marketsSectionRequest = .watchlist
        case .marketsScreener:
            tab = .markets
            marketsSectionRequest = .screener
        case .marketsCalendar:
            tab = .markets
            marketsSectionRequest = .calendar
        case .marketsNews:
            tab = .markets
            marketsSectionRequest = .news
        case .investIncome:
            tab = .invest
            investSectionRequest = .income
        // Hero click -> Portfolio Performance (not Holdings) — same cross-platform rationale
        // as the macOS branch above (now resolved to match Kotlin's settled behavior).
        case .portfolio:
            tab = .portfolio
            portfolioSectionRequest = .performance
        }
        #endif
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

            // Settings honesty pass (M10.1 Task 8): app-level settings first (Appearance,
            // Language, Notifications — things that change how the app itself looks/talks),
            // then identity/account settings (Profile, Account Settings, Security &
            // Privacy), then Help/About. Export moved to Portfolio · Holdings and DRIP
            // moved to Invest · Income (see `PortfolioSummaryHeader.exportButton` and
            // `IncomeSection.dripCard`) — this panel no longer hosts either.
            VStack(alignment: .leading, spacing: 2) {
                accountRow(icon: "paintpalette", title: tr(.appearance)) { panelRoute = .appearance }
                accountRow(icon: "globe", title: tr(.language)) { panelRoute = .language }
                accountRow(icon: "bell", title: tr(.notifications)) { panelRoute = .notifications }
            }
            .padding(.top, 10)

            Divider().overlay(Theme.hairline).padding(.vertical, 10)

            VStack(alignment: .leading, spacing: 2) {
                accountRow(icon: "person.crop.circle", title: tr(.profile)) { panelRoute = .profile }
                accountRow(icon: "gearshape", title: tr(.accountSettings)) { panelRoute = .accountSettings }
                accountRow(icon: "lock.shield", title: tr(.securityAndPrivacy)) { panelRoute = .security }
            }
            .padding(.top, 2)

            Divider().overlay(Theme.hairline).padding(.vertical, 10)

            VStack(alignment: .leading, spacing: 2) {
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
                detailField(label: tr(.defaultTab), value: tr(.homeTab))
                detailField(label: tr(.biometricLogin), value: tr(.enabledTouchID))
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)

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
