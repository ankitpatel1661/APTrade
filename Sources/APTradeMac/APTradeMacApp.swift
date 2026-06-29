import SwiftUI
import APTradeApp
#if os(macOS)
import AppKit

@main
struct APTradeMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup("APTrade Lite") {
            RootView()
        }
    }
}

/// (moved from APTradeApp.swift) When run as a bare SwiftPM executable (no `.app`
/// bundle), macOS launches the process as a background agent with no foreground
/// window. Promote it to a regular foreground app and bring its window to the front.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        for window in NSApp.windows {
            window.styleMask.insert([.titled, .closable, .miniaturizable, .resizable])
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
#else
// iOS never launches APTradeMac (the XcodeGen APTradeiOS target is the iOS app);
// trivial entry so this executable target still links for the iOS package build.
@main
struct APTradeMacApp {
    static func main() {}
}
#endif
