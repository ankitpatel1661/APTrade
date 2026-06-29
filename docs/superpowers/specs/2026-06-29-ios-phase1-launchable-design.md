# iOS Phase 1 — Launchable iPhone Build (design)

**Date:** 2026-06-29
**Status:** Approved (design), pending spec review → plan.
**Goal:** Produce a real iOS `.app` that **boots and renders APTrade in the iPhone simulator**, while the macOS app keeps working and the 190 tests stay green. Phone-shaped layout, export, and the iOS news-key story are explicitly out of scope (later phases).

Builds on Phase 0 (`docs/ios-port-readiness.md`), which proved the package compiles for iOS and all 190 tests pass on the simulator.

## Why a structural change is required

An iOS app needs a real `.app` bundle; a SwiftPM **executable** target can't be launched on iOS, and an Xcode app target can only consume a **library** product (not an executable). The brand-image resources (`AppLogo.png`/`AppWordmark.png`) are loaded via `Bundle.module`, which only exists for a SwiftPM target — so the presentation must remain a SwiftPM **library** (not be copied into an app target). Therefore: presentation becomes a library; thin app entry points consume it.

## Architecture

```
SwiftPM package (single source of truth for code + tests)
  APTradeDomain / APTradeApplication / APTradeInfrastructure   (libraries, unchanged)
  APTradeApp            (was .executableTarget → now .target/library; keeps Resources)
  APTradeMac            (NEW thin .executableTarget: macOS @main + AppDelegate)
  *Tests                (unchanged; APTradeAppTests still @testable import APTradeApp)

XcodeGen project (wraps the package into launchable app bundles)
  project.yml  →  APTrade.xcodeproj
  APTradeiOS   (iOS application target: thin @main importing APTradeApp → RootView)
  Sources/APTradeiOS/APTradeiOSApp.swift   (iOS entry; NOT a SwiftPM target)
```

The macOS app stays a SwiftPM executable (`APTradeMac`) so the existing `swift build` launch workflow is preserved — only the binary name changes (`APTradeApp` → `APTradeMac`). The iOS app is built via the XcodeGen project.

## Components

### 1. `APTradeApp`: executable → library
- `Package.swift`: change `.executableTarget(name: "APTradeApp", …)` to `.target(name: "APTradeApp", …)`, keeping `resources: [.process("Resources")]`.
- Move the `@main App` + macOS `AppDelegate` **out** of this target (libraries can't have `@main`) into `APTradeMac` (below). Delete `Sources/APTradeApp/APTradeApp.swift` after moving its content.
- Make exactly one symbol public — `RootView`:
  ```swift
  public struct RootView: View {
      public init() {}
      public var body: some View { … }   // unchanged body
  }
  ```
  Every other view/type stays `internal` (RootView composes them within the module; `CompositionRoot`, `ThemeManager`, etc. remain internal). `RootView`'s stored properties are `@State private` with defaults, so a no-arg `public init()` is sufficient.
- `Bundle.module` resource loading in `DesignKit` is unchanged — resources stay in this library and are embedded into each consuming app.

### 2. `APTradeMac`: new thin macOS executable
- `Package.swift`: add `.executableTarget(name: "APTradeMac", dependencies: ["APTradeApp"])`.
- `Sources/APTradeMac/APTradeMacApp.swift` holds the macOS entry (moved from the old `APTradeApp.swift`), with a cross-platform-safe `@main` so the **package still compiles for iOS** (the iOS package build/test includes every target):
  ```swift
  import SwiftUI
  import APTradeApp
  #if os(macOS)
  import AppKit
  @main struct APTradeMacApp: App {
      @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
      var body: some Scene { WindowGroup("APTrade Lite") { RootView() } }
  }
  final class AppDelegate: NSObject, NSApplicationDelegate { /* …moved, unchanged… */ }
  #else
  // iOS never launches APTradeMac (the XcodeGen APTradeiOS target is the iOS app);
  // provide a trivial entry so this executable target still links for the iOS package build.
  @main struct APTradeMacApp { static func main() {} }
  #endif
  ```
- macOS run command becomes: `"$(swift build --show-bin-path)/APTradeMac"`.

### 3. `APTradeiOS`: iOS app target (XcodeGen)
- `brew install xcodegen`.
- `Sources/APTradeiOS/APTradeiOSApp.swift` (NOT a SwiftPM target — only the XcodeGen app target compiles it):
  ```swift
  import SwiftUI
  import APTradeApp
  @main struct APTradeiOSApp: App {
      var body: some Scene { WindowGroup { RootView() } }
  }
  ```
- `project.yml` (committed; regenerable) defines `APTrade.xcodeproj`:
  - `packages: APTradeLite: { path: . }`
  - target `APTradeiOS`: `type: application`, `platform: iOS`, `deploymentTarget: "17.0"`, `sources: [Sources/APTradeiOS]`, `dependencies: [{ package: APTradeLite, product: APTradeApp }]`, a generated Info.plist (CFBundleDisplayName "APTrade", a default storyboard-less launch screen, portrait), and a bundle id (e.g. `com.aptrade.ios`).
- `APTrade.xcodeproj` is generated; commit `project.yml` and gitignore the generated `.xcodeproj` (regenerate with `xcodegen generate`).

## Build / run / verify

- **Generate:** `brew install xcodegen` (once) → `xcodegen generate`.
- **Build iOS app:** `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project APTrade.xcodeproj -scheme APTradeiOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build` → BUILD SUCCEEDED.
- **Install + launch:** boot the sim (`xcrun simctl boot "iPhone 17 Pro"`), `xcrun simctl install booted <built APTradeiOS.app>`, `xcrun simctl launch booted com.aptrade.ios`.
- **Confirm it rendered:** `xcrun simctl io booted screenshot /tmp/aptrade-ios.png` and inspect — the watchlist/tab UI should appear (cramped, Mac-shaped). Brand images (logo/wordmark) should load (validates `Bundle.module` embedding). Controller confirms launch (process up, no crash, screenshot); the visual quality judgment is the user's.

## Guardrails / non-regression

- `DEVELOPER_DIR=… swift test` stays **190/190** on macOS after every change.
- macOS app still builds and launches (now via `APTradeMac`).
- The package still compiles + tests on the iOS simulator via the existing scheme (`xcodebuild test -scheme APTradeLite -destination 'iOS Simulator,…'` = 190/190) — the gated `APTradeMac` entry keeps the iOS package build clean.
- No behavior change to any existing view (only `RootView`'s access level changes, and the `@main` relocates).

## Out of scope (later phases)
Responsive iPhone layout (Phase 2: `TabView`/`NavigationStack`, per-screen density), `.fileExporter` export, the iOS Finnhub-key mechanism, touch chart crosshair, sheet detents, app icon/launch-screen polish, a macOS Xcode app target (Mac stays a SwiftPM executable).

## Risks & mitigations
- **XcodeGen embedding of the SwiftPM library's resource bundle on iOS** — if `Bundle.module` images don't load in the app, the brand art is blank but the app still boots; verified via screenshot, fixable by adjusting the resource reference. Not a launch blocker.
- **`APTradeMac` gated entry** — keeps the iOS package build green; if any other package target later needs gating for iOS it surfaces as a compile error (same iterative discovery as Phase 0).
- **Generated `.xcodeproj` churn** — mitigated by committing only `project.yml` and regenerating.
