import SwiftUI

struct RootView: View {
    enum Tab: String, CaseIterable { case watchlist = "Watchlist", portfolio = "Portfolio" }
    @State private var tab: Tab = .watchlist
    @Namespace private var pill

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            VStack(spacing: 0) {
                switcher
                    .padding(.horizontal, 24)
                    .padding(.top, 14)
                    .padding(.bottom, 8)
                Group {
                    switch tab {
                    case .watchlist: WatchlistView()
                    case .portfolio: PortfolioView()
                    }
                }
            }
        }
        .frame(minWidth: 560, minHeight: 680)
        .preferredColorScheme(.dark)
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
        .frame(maxWidth: .infinity, alignment: .center)
    }
}
