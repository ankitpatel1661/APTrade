import SwiftUI

#if os(iOS)
/// iOS navigation-bar chrome shared by every tab: brand mark on the left,
/// search + account buttons on the right. Applied INSIDE each tab's NavigationStack.
struct IOSTopChrome: ViewModifier {
    let onSearch: () -> Void
    let onAccount: () -> Void

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    BrandMark(size: 17, showsMark: true)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onSearch) {
                        Image(systemName: "magnifyingglass").foregroundStyle(Theme.gold)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: onAccount) {
                        Image(systemName: "ellipsis").foregroundStyle(Theme.gold)
                    }
                }
            }
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
