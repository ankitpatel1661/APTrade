import SwiftUI

#if os(iOS)
import UIKit

/// Tints the UIKit tab bar and navigation bar with the brand gold. SwiftUI's `.tint`
/// does not reliably reach bar-button items / tab selection, so set the appearance
/// proxies once at launch — otherwise they fall back to the system blue accent.
@MainActor
public func applyAPTradeBarAppearance() {
    let gold = UIColor(Theme.gold)
    UITabBar.appearance().tintColor = gold
    UINavigationBar.appearance().tintColor = gold
}

/// iOS navigation-bar chrome shared by every tab: brand mark on the left,
/// search + account buttons on the right. Applied INSIDE each tab's NavigationStack.
struct IOSTopChrome: ViewModifier {
    let onSearch: () -> Void
    let onAccount: () -> Void

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    // The recolored "AP Trade" wordmark — same artwork the macOS top bar
                    // uses, which tints gold/charcoal correctly for light and dark mode.
                    // (The text BrandMark rendered a dark logo tile + low-contrast "Trade"
                    // in the bar, hence the unreadable mark.)
                    if let wordmark = BrandImage.wordmark(accent: ThemeManager.shared.accent,
                                                          isDark: ThemeManager.shared.isDark) {
                        Image(platformImage: wordmark)
                            .renderingMode(.original)
                            .resizable()
                            .scaledToFit()
                            .frame(height: 26)
                            .accessibilityLabel("APTrade")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onSearch) {
                        Image(systemName: "magnifyingglass")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onAccount) {
                        Image(systemName: "ellipsis")
                    }
                }
            }
            // Toolbar bar-button items follow the bar's tint, not foregroundStyle on the
            // label — set the tint so the search/account icons render gold, not system blue.
            .tint(Theme.gold)
            .toolbarBackground(Theme.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
    }
}

extension View {
    func iosTopChrome(onSearch: @escaping () -> Void, onAccount: @escaping () -> Void) -> some View {
        modifier(IOSTopChrome(onSearch: onSearch, onAccount: onAccount))
    }
}
#endif
