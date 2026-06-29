import SwiftUI
#if os(macOS)
import AppKit
#endif

@main
struct APTradeApp: App {
    #if os(macOS)
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    #endif

    var body: some Scene {
        WindowGroup("APTrade Lite") {
            RootView()
        }
    }
}

#if os(macOS)
/// When run as a bare SwiftPM executable (no `.app` bundle), macOS launches the
/// process as a background agent with no Dock icon or foreground window. Promote
/// it to a regular foreground app and bring its window to the front.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        // As a bare SwiftPM executable (no .app bundle), the window can come up
        // without a fully-formed title bar — the traffic-light buttons are absent
        // until something else forces a relayout. Force the standard style mask
        // explicitly so close/minimize/zoom are present from the first frame.
        for window in NSApp.windows {
            window.styleMask.insert([.titled, .closable, .miniaturizable, .resizable])
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
#endif
