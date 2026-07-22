import SwiftUI

/// The Invest tab host (M10.1 IA restructure): a pill-row section switcher over the two
/// views `PortfolioView` used to host as its fifth/sixth sections — Plans and Income —
/// moved here unchanged (their own view models, own lifecycles, own internal navigation).
/// Pill idiom copied from `PortfolioView`'s section switcher (matchedGeometryEffect
/// capsule). Wraps its own `NavigationStack` since `PlansSection`/`IncomeSection` push
/// detail screens via `navigationDestination`, exactly as `PortfolioView` provided before.
struct InvestView: View {
    enum Section: String, CaseIterable {
        case plans, income

        @MainActor
        var title: String {
            switch self {
            case .plans:  return tr(.plansSection)
            case .income: return tr(.incomeSection)
            }
        }
    }

    var onOpenSearch: (() -> Void)? = nil
    var onOpenAccount: (() -> Void)? = nil
    /// Cross-tab navigation request (from Home, via `RootView`): when set, jumps straight
    /// to that section and clears itself. Additive/optional — existing call sites that omit
    /// it behave exactly as before.
    var externalSection: Binding<Section?>? = nil
    /// M10.1 Task 8: DRIP re-homed from Account Settings to the Income section's header
    /// card. Bound to the SAME `settingsVM.settings.dripEnabled` field `RootView` owns —
    /// threaded down here rather than duplicating a `SettingsViewModel` instance, exactly
    /// as `onOpenSearch`/`onOpenAccount` thread `RootView`'s closures.
    var dripEnabled: Binding<Bool>
    @State private var section: Section = .plans
    @Namespace private var sectionPill

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    sectionPicker
                        .padding(.horizontal, 24)
                        .padding(.top, 20)
                        .padding(.bottom, 8)
                    Divider().overlay(Theme.hairline)
                    content
                }
            }
            #if os(iOS)
            .iosTopChrome(onSearch: { onOpenSearch?() }, onAccount: { onOpenAccount?() })
            .navigationBarTitleDisplayMode(.inline)
            #endif
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 640)
        #endif
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
        // `initial: true`: TabView builds tabs lazily, so a section request set in the same
        // transaction as the tab switch is already the baseline by the time onChange installs —
        // without firing on first mount, that request lands on the default section and is
        // never cleared, wedging same-section requests for the rest of the session.
        .onChange(of: externalSection?.wrappedValue, initial: true) { _, requested in
            guard let requested else { return }
            withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { section = requested }
            externalSection?.wrappedValue = nil
        }
    }

    // MARK: - Section switcher

    private var sectionPicker: some View {
        #if os(iOS)
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) { sectionButtons }
        }
        #else
        HStack(spacing: 6) {
            sectionButtons
            Spacer()
        }
        #endif
    }

    @ViewBuilder
    private var sectionButtons: some View {
        ForEach(Section.allCases, id: \.self) { item in
            let selected = section == item
            Button {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { section = item }
            } label: {
                Text(item.title)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
                    .fixedSize()
                    .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
                    .padding(.horizontal, 14).padding(.vertical, 6)
                    .background {
                        if selected {
                            Capsule().fill(Theme.surfaceHi)
                                .overlay(Capsule().stroke(Theme.gold.opacity(0.40), lineWidth: 1))
                                .matchedGeometryEffect(id: "section", in: sectionPill)
                        }
                    }
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private var content: some View {
        InvestView.sectionView(section, dripEnabled: dripEnabled)
    }

    /// The section → view mapping, hoisted (Task 6) so the macOS sidebar can construct
    /// IDENTICAL section content to this host's own `content` without duplicating the
    /// switch — both sections are self-contained (own their own view models), except Income
    /// which additionally needs the `dripEnabled` binding threaded through (Task 8).
    @MainActor
    @ViewBuilder
    static func sectionView(_ section: Section, dripEnabled: Binding<Bool>) -> some View {
        switch section {
        case .plans:  PlansSection()
        case .income: IncomeSection(dripEnabled: dripEnabled)
        }
    }
}
