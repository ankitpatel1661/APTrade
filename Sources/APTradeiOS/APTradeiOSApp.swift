import SwiftUI
import APTradeApp

@main
struct APTradeiOSApp: App {
    init() {
        applyAPTradeBarAppearance()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
