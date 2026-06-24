import SwiftUI
import AppKit

@main
struct APTradeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup("APTrade Lite") {
            RootView()
        }
    }
}

/// When run as a bare SwiftPM executable (no `.app` bundle), macOS launches the
/// process as a background agent with no Dock icon or foreground window. Promote
/// it to a regular foreground app and bring its window to the front.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
