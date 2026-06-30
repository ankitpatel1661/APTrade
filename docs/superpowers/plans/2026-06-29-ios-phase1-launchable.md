# iOS Phase 1 — Launchable iPhone Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Produce a real iOS `.app` that boots and renders APTrade in the iPhone simulator, with the macOS app still working and the 190 tests green on both platforms.

**Architecture:** Convert the `APTradeApp` executable target into a library (only `RootView` becomes public), move the macOS `@main`+`AppDelegate` into a new thin `APTradeMac` executable, and add an `APTradeiOS` application target via a committed XcodeGen `project.yml` whose tiny `@main` imports the library and renders `RootView()`.

**Tech Stack:** Swift 6, SwiftUI, SwiftPM, XcodeGen, `xcodebuild`, `xcrun simctl`. Reference spec: `docs/superpowers/specs/2026-06-29-ios-phase1-launchable-design.md`.

## Global Constraints

- **macOS stays green:** after every task, `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test` = **190/190**, and `swift build` produces a runnable `APTradeMac` binary.
- **iOS package stays green:** `DEVELOPER_DIR=… xcodebuild build -scheme APTradeLite-Package -destination 'generic/platform=iOS Simulator' -skipPackagePluginValidation` = BUILD SUCCEEDED throughout (the gated `APTradeMac` entry keeps the iOS package build clean).
- **Toolchain:** all `swift`/`xcodebuild`/`xcrun` commands use `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`. Xcode 26.5; iOS 26.5 simulator runtime installed; sim device **iPhone 17 Pro**.
- **Only `RootView` becomes public.** No other view/type's access level or behavior changes. The `@main` relocates; nothing else in the views changes.
- **iOS app facts (verbatim):** bundle id `com.aptrade.ios`; deployment target `17.0`; iPhone-only (`TARGETED_DEVICE_FAMILY = 1`); display name `APTrade`; portrait.
- **VCS:** commit `project.yml`; the generated `APTrade.xcodeproj` is already covered by `.gitignore` (`*.xcodeproj`); iOS build output goes under `.build/ios-derived` (already ignored via `.build/`). No `.gitignore` change.
- This phase has **no new unit tests** (structural/tooling). Deliverables are: macOS 190/190, iOS package BUILD SUCCEEDED + 190/190 on sim, and the iOS app launching + rendering (screenshot). UI visual-quality judgment is the user's; the implementer/controller confirms launch + captures the screenshot.

---

### Task 1: Restructure — `APTradeApp`→library, new `APTradeMac` exec, `RootView` public

**Files:**
- Modify: `Package.swift`
- Modify: `Sources/APTradeApp/RootView.swift` (make `RootView` public)
- Create: `Sources/APTradeMac/APTradeMacApp.swift` (moved app entry, gated)
- Delete: `Sources/APTradeApp/APTradeApp.swift`

**Interfaces:**
- Produces: `public struct RootView: View { public init() }`; SwiftPM library target `APTradeApp`; SwiftPM executable target `APTradeMac` (macOS app). The Mac run binary becomes `APTradeMac`.

- [ ] **Step 1: Make `RootView` public**

In `Sources/APTradeApp/RootView.swift`:
- Line 9: change `struct RootView: View {` → `public struct RootView: View {`
- Immediately after the opening brace (before the first stored property), add a no-arg public initializer:
  ```swift
  public struct RootView: View {
      public init() {}
  ```
- Line ~32: change `var body: some View {` → `public var body: some View {`

Leave every nested type and `@State private`/`internal` member exactly as-is (private/internal members of a public type are valid).

- [ ] **Step 2: Move the app entry into `APTradeMac`**

Create `Sources/APTradeMac/APTradeMacApp.swift` with the relocated, gated entry (the macOS `App`+`AppDelegate` are moved verbatim from the old `APTradeApp.swift`; the iOS branch is a trivial entry so the package still links for iOS):

```swift
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
```

Then delete the old entry: `git rm Sources/APTradeApp/APTradeApp.swift`.

- [ ] **Step 3: Update `Package.swift`**

Change the `APTradeApp` target from `.executableTarget` to `.target`, and add the `APTradeMac` executable after it:

```swift
        .target(
            name: "APTradeApp",
            dependencies: ["APTradeInfrastructure", "APTradeApplication", "APTradeDomain"],
            resources: [.process("Resources")]
        ),
        .executableTarget(
            name: "APTradeMac",
            dependencies: ["APTradeApp"]
        ),
```

(Leave the three lower library targets and all four test targets unchanged — `APTradeAppTests` still depends on `APTradeApp`, now a library.)

- [ ] **Step 4: Verify macOS (build + test + runnable binary)**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build`
Expected: build succeeds; `"$(swift build --show-bin-path)/APTradeMac"` exists (the Mac app binary).
Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: **190/190** (the test target imports the `APTradeApp` library unchanged).

- [ ] **Step 5: Verify the package still compiles for iOS**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild build -scheme APTradeLite-Package -destination 'generic/platform=iOS Simulator' -skipPackagePluginValidation 2>&1 | tail -15`
Expected: `** BUILD SUCCEEDED **` (the gated `APTradeMac` entry + the `APTradeApp` library both compile for iOS).

- [ ] **Step 6: Commit**

```bash
git add Package.swift Sources/APTradeApp/RootView.swift Sources/APTradeMac/APTradeMacApp.swift
git rm Sources/APTradeApp/APTradeApp.swift
git commit -m "ios(phase1): APTradeApp→library; thin APTradeMac exec; public RootView"
```

---

### Task 2: iOS application target via XcodeGen

**Files:**
- Create: `Sources/APTradeiOS/APTradeiOSApp.swift` (iOS `@main`; NOT a SwiftPM target)
- Create: `project.yml` (XcodeGen spec, committed)

**Interfaces:**
- Consumes: `public RootView` + the `APTradeApp` library product (Task 1).
- Produces: `APTrade.xcodeproj` (generated) with an `APTradeiOS` application target that builds an installable iOS `.app`.

- [ ] **Step 1: Create the iOS entry point**

Create `Sources/APTradeiOS/APTradeiOSApp.swift`:

```swift
import SwiftUI
import APTradeApp

@main
struct APTradeiOSApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
```

- [ ] **Step 2: Create the XcodeGen project spec**

Create `project.yml` at the repo root:

```yaml
name: APTrade
options:
  bundleIdPrefix: com.aptrade
packages:
  APTradeLite:
    path: .
targets:
  APTradeiOS:
    type: application
    platform: iOS
    deploymentTarget: "17.0"
    sources:
      - Sources/APTradeiOS
    dependencies:
      - package: APTradeLite
        product: APTradeApp
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.aptrade.ios
        TARGETED_DEVICE_FAMILY: 1
        GENERATE_INFOPLIST_FILE: YES
        INFOPLIST_KEY_CFBundleDisplayName: APTrade
        INFOPLIST_KEY_UILaunchScreen_Generation: YES
        INFOPLIST_KEY_UISupportedInterfaceOrientations: UIInterfaceOrientationPortrait
```

(`GENERATE_INFOPLIST_FILE` + the `INFOPLIST_KEY_*` settings let Xcode synthesize the Info.plist and a default launch screen — no hand-written plist. The local SwiftPM package is referenced as `APTradeLite`, and the app links its `APTradeApp` library product.)

- [ ] **Step 3: Install XcodeGen and generate the project**

Run: `brew install xcodegen`
Then: `xcodegen generate`
Expected: `Created project at .../APTrade.xcodeproj`. Confirm `git status` shows `project.yml` and `Sources/APTradeiOS/` as new, and that `APTrade.xcodeproj` is ignored (`git check-ignore APTrade.xcodeproj` prints the path).

- [ ] **Step 4: Build the iOS app for the simulator**

Run:
```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild \
  -project APTrade.xcodeproj -scheme APTradeiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath .build/ios-derived build 2>&1 | tail -20
```
Expected: `** BUILD SUCCEEDED **`. The app is at `.build/ios-derived/Build/Products/Debug-iphonesimulator/APTradeiOS.app`.
If the scheme isn't found, run `xcodegen generate` again and `xcodebuild -project APTrade.xcodeproj -list` to confirm the `APTradeiOS` scheme exists.

- [ ] **Step 5: Re-confirm macOS still green (no regression from the new files)**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: **190/190** (the new `Sources/APTradeiOS` dir is not a SwiftPM target, so `swift test` is unaffected — this confirms it).

- [ ] **Step 6: Commit**

```bash
git add project.yml Sources/APTradeiOS/APTradeiOSApp.swift
git commit -m "ios(phase1): XcodeGen iOS app target (APTradeiOS) building an installable .app"
```

---

### Task 3: Launch in the simulator + verify it renders (the proof) + full regression

**Files:** none (verification + a short report).

- [ ] **Step 1: Boot the simulator**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun simctl boot "iPhone 17 Pro" 2>/dev/null; echo booted`
(Ignore "Unable to boot device in current state: Booted" — that just means it's already up.) Optionally `open -a Simulator` so the window is visible to the user.

- [ ] **Step 2: Install + launch the app**

```bash
APP=.build/ios-derived/Build/Products/Debug-iphonesimulator/APTradeiOS.app
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun simctl install booted "$APP"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun simctl launch booted com.aptrade.ios
```
Expected: `install` returns silently; `launch` prints `com.aptrade.ios: <pid>` (a real PID = launched, didn't crash on launch).

- [ ] **Step 3: Screenshot + confirm it rendered**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcrun simctl io booted screenshot /tmp/aptrade-ios.png && echo saved`
Then read `/tmp/aptrade-ios.png` and confirm APTrade UI is on screen (the watchlist/tab chrome — cramped/Mac-shaped is expected and fine). Confirm it's not a blank/crash screen. Note whether the brand logo/wordmark images rendered (validates `Bundle.module` resource embedding); if they're blank but the rest renders, record it as a finding — it's not a launch blocker.

- [ ] **Step 4: Full cross-platform regression**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test` → **190/190** (macOS).
Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test -scheme APTradeLite-Package -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -skipPackagePluginValidation 2>&1 | tail -6` → `** TEST SUCCEEDED **` (iOS package, 190/190).

- [ ] **Step 5: Report**

Write a short proof note (launch PID, screenshot path + what's visible, both regression results, the brand-image finding if any) to the task report file. No commit needed (verification only); if `open -a Simulator` or any helper left artifacts, ensure `git status` is clean.

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task |
|---|---|
| `APTradeApp` executable → library; `RootView` public | Task 1 |
| Move `@main`+`AppDelegate` to thin `APTradeMac` exec (gated iOS entry) | Task 1 |
| macOS run via `APTradeMac`; Mac workflow preserved | Task 1 (Step 4) |
| `APTradeiOS` app target via committed `project.yml` (XcodeGen) | Task 2 |
| iOS entry: `@main` `WindowGroup { RootView() }` importing the library | Task 2 (Step 1) |
| bundle id / deployment target / iPhone-only / display name / portrait | Task 2 (Step 2, verbatim) |
| Build the iOS `.app` for the sim | Task 2 (Step 4) |
| Launch + render in iPhone 17 Pro sim + screenshot | Task 3 (Steps 1–3) |
| macOS 190/190 + iOS package 190/190 throughout | Tasks 1 (4–5), 2 (5), 3 (4) |
| `Bundle.module` resources keep working (verified on iOS) | Task 3 (Step 3) |
| Commit `project.yml`; `.xcodeproj` ignored; build under `.build/` | Task 2 (Steps 3, 6); Global Constraints |

No gaps. Out-of-scope items (responsive layout, `.fileExporter`, iOS news key, macOS Xcode target) are correctly excluded.

**2. Placeholder scan:** All code blocks are complete (Package.swift edits, the full `APTradeMacApp.swift`/`APTradeiOSApp.swift`/`project.yml`, exact RootView edits). Commands are exact with expected output. No "TBD"/"similar to"/vague steps.

**3. Type consistency:** `RootView` (public, `init()`) defined in Task 1 is consumed identically by `APTradeMacApp` (Task 1) and `APTradeiOSApp` (Task 2). The `APTradeApp` library product name and `APTradeMac`/`APTradeiOS` target names are consistent across `Package.swift`, `project.yml`, and the build/launch commands. Bundle id `com.aptrade.ios` matches between `project.yml` (Step 2) and `simctl launch` (Task 3 Step 2). Build-product path `.build/ios-derived/...APTradeiOS.app` matches between Task 2 Step 4 and Task 3 Step 2.
