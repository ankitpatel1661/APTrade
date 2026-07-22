import SwiftUI

/// The Markets tab host (M10.1 IA restructure): a visible search field that opens the
/// existing command palette, then a pill-row section switcher over the four views that
/// used to be separate top-level tabs — Watchlist, Screener, Calendar, News — hosted here
/// completely unchanged (same view models, same lifecycles, same `onOpenSearch`/
/// `onOpenAccount` closures they always took). Pill idiom copied from `PortfolioView`'s
/// section switcher (matchedGeometryEffect capsule).
struct MarketsView: View {
    enum Section: String, CaseIterable {
        case watchlist, screener, calendar, news

        @MainActor
        var title: String {
            switch self {
            case .watchlist: return tr(.watchlist)
            case .screener:  return tr(.screenerTab)
            case .calendar:  return tr(.calendarTab)
            case .news:      return tr(.news)
            }
        }
    }

    var onOpenSearch: (() -> Void)? = nil
    var onOpenAccount: (() -> Void)? = nil
    /// Cross-tab navigation request (from Home, via `RootView`): when set, jumps straight
    /// to that section and clears itself. Additive/optional — existing call sites that omit
    /// it behave exactly as before.
    var externalSection: Binding<Section?>? = nil
    @State private var section: Section = .watchlist
    @Namespace private var sectionPill

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(spacing: 0) {
                searchField
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
                    .padding(.bottom, 8)
                sectionPicker
                    .padding(.horizontal, 24)
                    .padding(.bottom, 8)
                Divider().overlay(Theme.hairline)
                content
            }
        }
        .preferredColorScheme(ThemeManager.shared.isDark ? .dark : .light)
        .onChange(of: externalSection?.wrappedValue) { _, requested in
            guard let requested else { return }
            withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { section = requested }
            externalSection?.wrappedValue = nil
        }
    }

    /// Styled as a text field but is really a button — tapping opens the existing
    /// command palette (search + jump-to-tab), it does not host its own search UI.
    private var searchField: some View {
        Button { onOpenSearch?() } label: {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 13, weight: .semibold))
                Text(tr(.searchAssetsPlaceholder))
                    .font(.system(size: 13))
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .foregroundStyle(Theme.textTertiary)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Theme.surface, in: Capsule())
            .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
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
        MarketsView.sectionView(section, onOpenSearch: onOpenSearch, onOpenAccount: onOpenAccount)
    }

    /// The section → view mapping, hoisted (Task 6) so the macOS sidebar can construct
    /// IDENTICAL section content to this host's own `content` without duplicating the
    /// switch — each destination view is self-contained (owns its own view model), so no
    /// shared state needs to cross the call boundary beyond the two navigation closures.
    @MainActor
    @ViewBuilder
    static func sectionView(_ section: Section, onOpenSearch: (() -> Void)?, onOpenAccount: (() -> Void)?) -> some View {
        switch section {
        case .watchlist:
            WatchlistView(onOpenSearch: onOpenSearch, onOpenAccount: onOpenAccount)
        case .screener:
            ScreenerView(onOpenSearch: onOpenSearch, onOpenAccount: onOpenAccount)
        case .calendar:
            CalendarView(onOpenSearch: onOpenSearch, onOpenAccount: onOpenAccount)
        case .news:
            NewsView(onOpenSearch: onOpenSearch, onOpenAccount: onOpenAccount)
        }
    }
}
