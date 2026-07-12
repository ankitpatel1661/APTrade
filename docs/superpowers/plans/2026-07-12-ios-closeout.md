# iOS Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the iPhone app to shippable feature parity with macOS — real portfolio export, touch-driven chart crosshair, and a verified build — reusing the existing Swift core and the already-built iOS UI.

**Architecture:** The `APTradeiOS` Xcode target already renders the shared SwiftUI presentation via `RootView.iosBody` (TabView + NavigationStack). This plan touches only the three unfinished spots: the export **save** step (still an iOS stub), the chart **crosshair** (hover-only), and a full **verification** pass over the already-written iOS layout branches. macOS behavior must stay byte-for-byte unchanged.

**Tech Stack:** Swift, SwiftUI, Swift Charts, SwiftPM packages consumed by an XcodeGen-generated project. Tests: XCTest via `swift test` (macOS) and `xcodebuild` (iOS simulator).

## Global Constraints

- **macOS must not change behavior.** Every edit is `#if os(iOS)`-gated or a pure refactor with identical macOS output. The `PortfolioExportRenderer` macOS P&L colors stay calibrated-RGB; iOS stays sRGB — do not "unify" them.
- **Device target:** iPhone only, portrait, iOS 17.0 (`project.yml` is authoritative — do not widen to iPad/landscape).
- **macOS test command:** `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test` (plain `swift test` fails — CLT has no XCTest).
- **iOS test/build command:** `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild ... -scheme APTradeLite-Package -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -skipPackagePluginValidation`. **Gotcha:** the generated `APTrade.xcodeproj` shadows the package and hides the `APTradeLite-Package` scheme — move it aside to run package tests, then restore/regenerate.
- **Domain layer stays pure:** no UI/UniformTypeIdentifiers/AppKit/UIKit imports in `APTradeDomain`.
- Commit after every task with a conventional-commit message.

---

### Task 1: Baseline verify (no code change)

Establish ground truth before edits: the iOS project builds and both test suites' current counts are known.

**Files:**
- None modified. This task only runs commands and records results.

**Interfaces:**
- Consumes: nothing.
- Produces: a recorded baseline (iOS build OK; macOS test count; iOS test count) referenced by Task 5.

- [ ] **Step 1: Regenerate the iOS project and build it**

```bash
cd "/Users/ap/Desktop/Work_25_02/Claude/Software engineering/Trading app"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodegen generate
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild build \
  -project APTrade.xcodeproj -scheme APTradeiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

Expected: `** BUILD SUCCEEDED **`. If it fails, stop and report — the pre-existing iOS UI does not compile and that must be fixed before this plan proceeds.

- [ ] **Step 2: Run the macOS test suite (baseline)**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test 2>&1 | tail -20
```

Expected: all tests pass. Record the total count (e.g. "Executed N tests").

- [ ] **Step 3: Run the iOS simulator test suite (baseline)**

```bash
mv APTrade.xcodeproj /tmp/APTrade.xcodeproj.parked
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test \
  -scheme APTradeLite-Package \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -skipPackagePluginValidation 2>&1 | tail -20
mv /tmp/APTrade.xcodeproj.parked APTrade.xcodeproj
```

Expected: `** TEST SUCCEEDED **`. Record the count. (Restore step runs even if you must re-run; if interrupted, `xcodegen generate` recreates the project.)

- [ ] **Step 4: Commit the baseline note**

Create `docs/superpowers/plans/ios-closeout-baseline.md` with the three recorded results (iOS build status, macOS test count, iOS test count, date), then:

```bash
git add docs/superpowers/plans/ios-closeout-baseline.md
git commit -m "test(ios): record pre-closeout build + test baseline"
```

---

### Task 2: Extract `PortfolioExportNaming` helper (TDD)

The date-stamped filename is currently inlined in `RootView` (`exportFileStem`, lines 222-228). Both macOS and iOS need it. Extract a pure, testable helper in the Domain layer and repoint macOS at it. No behavior change.

**Files:**
- Create: `Sources/APTradeDomain/PortfolioExportNaming.swift`
- Create: `Tests/APTradeDomainTests/PortfolioExportNamingTests.swift`
- Modify: `Sources/APTradeApp/RootView.swift` (replace `exportFileStem` usage at line 206; remove the private `exportFileStem` at lines 222-228)

**Interfaces:**
- Consumes: `PortfolioExportFormat` (from `APTradeDomain`; cases `.pdf/.excel/.word`, `fileExtension` → `pdf/xlsx/docx`).
- Produces:
  - `enum PortfolioExportNaming` with
    - `static func fileStem(on date: Date) -> String` → `"APTrade-Portfolio-yyyy-MM-dd"`
    - `static func filename(for format: PortfolioExportFormat, on date: Date) -> String` → `"<stem>.<ext>"`

- [ ] **Step 1: Write the failing test**

Create `Tests/APTradeDomainTests/PortfolioExportNamingTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class PortfolioExportNamingTests: XCTestCase {
    /// 2026-07-12 12:00:00 UTC as a fixed reference instant.
    private let fixed = Date(timeIntervalSince1970: 1_752_321_600)

    func test_fileStem_isDateStampedWithPosixFormat() {
        XCTAssertEqual(PortfolioExportNaming.fileStem(on: fixed), "APTrade-Portfolio-2026-07-12")
    }

    func test_filename_appendsFormatExtension() {
        XCTAssertEqual(PortfolioExportNaming.filename(for: .pdf, on: fixed), "APTrade-Portfolio-2026-07-12.pdf")
        XCTAssertEqual(PortfolioExportNaming.filename(for: .excel, on: fixed), "APTrade-Portfolio-2026-07-12.xlsx")
        XCTAssertEqual(PortfolioExportNaming.filename(for: .word, on: fixed), "APTrade-Portfolio-2026-07-12.docx")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PortfolioExportNamingTests 2>&1 | tail -15
```

Expected: FAIL — `cannot find 'PortfolioExportNaming' in scope`.

- [ ] **Step 3: Write the implementation**

Create `Sources/APTradeDomain/PortfolioExportNaming.swift`:

```swift
import Foundation

/// Builds the date-stamped filename for an exported portfolio statement. Pure and
/// platform-agnostic so macOS (`NSSavePanel`) and iOS (`.fileExporter`) share one source
/// of truth for the name. The stem uses a POSIX locale so it is stable across regions.
public enum PortfolioExportNaming {
    public static func fileStem(on date: Date = Date()) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd"
        return "APTrade-Portfolio-\(formatter.string(from: date))"
    }

    public static func filename(for format: PortfolioExportFormat, on date: Date = Date()) -> String {
        "\(fileStem(on: date)).\(format.fileExtension)"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter PortfolioExportNamingTests 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 5: Repoint macOS at the helper**

In `Sources/APTradeApp/RootView.swift`, change the save-panel name line (currently line 206):

```swift
        panel.nameFieldStringValue = PortfolioExportNaming.filename(for: format, on: Date())
```

Then delete the now-unused private helper (lines 222-228):

```swift
    /// Date-stamped base filename, e.g. `APTrade-Portfolio-2026-06-25`.
    private static var exportFileStem: String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return "APTrade-Portfolio-\(formatter.string(from: Date()))"
    }
```

Note: the old inline stem used the device timezone; the helper pins UTC. This only affects the *suggested* filename near a midnight boundary and is harmless. `APTradeDomain` is already imported in `RootView.swift`.

- [ ] **Step 6: Verify the full macOS suite still passes**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test 2>&1 | tail -15
```

Expected: PASS at the Task 1 baseline count + 2 new tests.

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeDomain/PortfolioExportNaming.swift \
        Tests/APTradeDomainTests/PortfolioExportNamingTests.swift \
        Sources/APTradeApp/RootView.swift
git commit -m "refactor(export): extract PortfolioExportNaming helper; repoint macOS save panel"
```

---

### Task 3: iOS portfolio export via `.fileExporter`

Replace the iOS stub in `presentSavePanel` with a real save flow. On iOS, stash the produced `Data` + resolved `UTType` in state and present a SwiftUI `.fileExporter`.

**Files:**
- Modify: `Sources/APTradeApp/RootView.swift`
  - add iOS-gated state + a `FileDocument` type
  - fill the iOS branch of `presentSavePanel` (lines 217-219)
  - attach `.fileExporter` to `iosBody`

**Interfaces:**
- Consumes: `PortfolioExportNaming.filename(for:on:)` (Task 2); `PortfolioExportFormat.fileExtension`; existing `exportError` state; existing `beginExport` → `presentSavePanel(for:format:)` flow.
- Produces: no new public API (self-contained view state).

- [ ] **Step 1: Add iOS export state and a document type**

In `Sources/APTradeApp/RootView.swift`, immediately after the existing export state block (after line 28, `@State private var exportError: String?`), add:

```swift
    #if os(iOS)
    @State private var iosExportDocument: PortfolioExportDocument?
    @State private var showFileExporter = false
    #endif
```

At file scope, below the `RootView` struct's closing brace (after line 791), add the document type. (`UniformTypeIdentifiers` is already imported at the top of the file — do not re-import.)

```swift
#if os(iOS)
/// Wraps already-rendered export bytes so SwiftUI's `.fileExporter` can write them.
/// Export is one-way (write only); the reader init is required by `FileDocument` but unused.
struct PortfolioExportDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }

    let data: Data
    let contentType: UTType
    let filename: String

    init(data: Data, contentType: UTType, filename: String) {
        self.data = data
        self.contentType = contentType
        self.filename = filename
    }

    init(configuration: ReadConfiguration) throws {
        throw CocoaError(.fileReadUnsupportedScheme)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
#endif
```

- [ ] **Step 2: Fill the iOS branch of `presentSavePanel`**

Replace the iOS stub (lines 217-219):

```swift
        #else
        // iOS Phase 0 stub — full export via .fileExporter is Phase 1.
        #endif
```

with:

```swift
        #else
        let contentType = UTType(filenameExtension: format.fileExtension) ?? .data
        iosExportDocument = PortfolioExportDocument(
            data: data,
            contentType: contentType,
            filename: PortfolioExportNaming.filename(for: format, on: Date())
        )
        showFileExporter = true
        #endif
```

- [ ] **Step 3: Attach the exporter to `iosBody`**

In `iosBody`, add a modifier after the export failure `.alert` (after line 79, before the `.sheet(item: $paletteAsset)` at line 80):

```swift
        .fileExporter(
            isPresented: $showFileExporter,
            document: iosExportDocument,
            contentType: iosExportDocument?.contentType ?? .data,
            defaultFilename: iosExportDocument?.filename
        ) { result in
            if case .failure(let error) = result { exportError = error.localizedDescription }
            iosExportDocument = nil
        }
```

- [ ] **Step 4: Build for iOS to verify it compiles**

```bash
cd "/Users/ap/Desktop/Work_25_02/Claude/Software engineering/Trading app"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodegen generate
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild build \
  -project APTrade.xcodeproj -scheme APTradeiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' 2>&1 | tail -15
```

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 5: Verify macOS build/tests unaffected**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build 2>&1 | tail -5
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test 2>&1 | tail -8
```

Expected: build succeeds; tests pass at the Task 2 count.

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeApp/RootView.swift
git commit -m "feat(ios): portfolio export via .fileExporter"
```

---

### Task 4: Touch-driven chart crosshair

Both chart overlays scrub via `.onContinuousHover`, which never fires on touch. Add a `DragGesture` path on iOS while leaving hover on macOS. The existing `updateHover(at:…)` helpers already map a point to the nearest data point, so the gesture just forwards `value.location`.

**Files:**
- Modify: `Sources/APTradeApp/AssetDetailView.swift` (overlay at lines 328-339)
- Modify: `Sources/APTradeApp/ExpandableValueChart.swift` (overlay at lines 192-198)

**Interfaces:**
- Consumes: `AssetDetailView.updateHover(at:proxy:geometry:)` and its `hoverPoint` state; `ExpandableValueChart.updateHover(at:proxy:geo:)` and its `hoverIndex` state. (Both already exist — do not rename.)
- Produces: nothing new.

- [ ] **Step 1: AssetDetailView — gate hover, add drag**

In `Sources/APTradeApp/AssetDetailView.swift`, replace the overlay hit-area (lines 329-339):

```swift
                        Rectangle()
                            .fill(Color.clear)
                            .contentShape(Rectangle())
                            .onContinuousHover { phase in
                                switch phase {
                                case .active(let location):
                                    updateHover(at: location, proxy: proxy, geometry: geometry)
                                case .ended:
                                    hoverPoint = nil
                                }
                            }
```

with:

```swift
                        Rectangle()
                            .fill(Color.clear)
                            .contentShape(Rectangle())
                            #if os(iOS)
                            .gesture(
                                DragGesture(minimumDistance: 0)
                                    .onChanged { value in
                                        updateHover(at: value.location, proxy: proxy, geometry: geometry)
                                    }
                                    .onEnded { _ in hoverPoint = nil }
                            )
                            #else
                            .onContinuousHover { phase in
                                switch phase {
                                case .active(let location):
                                    updateHover(at: location, proxy: proxy, geometry: geometry)
                                case .ended:
                                    hoverPoint = nil
                                }
                            }
                            #endif
```

- [ ] **Step 2: ExpandableValueChart — gate hover, add drag**

In `Sources/APTradeApp/ExpandableValueChart.swift`, replace the overlay hit-area (lines 192-198):

```swift
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .onContinuousHover { phase in
                            switch phase {
                            case .active(let location): updateHover(at: location, proxy: proxy, geo: geo)
                            case .ended: hoverIndex = nil
                            }
                        }
```

with:

```swift
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        #if os(iOS)
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { value in updateHover(at: value.location, proxy: proxy, geo: geo) }
                                .onEnded { _ in hoverIndex = nil }
                        )
                        #else
                        .onContinuousHover { phase in
                            switch phase {
                            case .active(let location): updateHover(at: location, proxy: proxy, geo: geo)
                            case .ended: hoverIndex = nil
                            }
                        }
                        #endif
```

- [ ] **Step 3: Build for iOS**

```bash
cd "/Users/ap/Desktop/Work_25_02/Claude/Software engineering/Trading app"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild build \
  -project APTrade.xcodeproj -scheme APTradeiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' 2>&1 | tail -15
```

Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 4: Verify macOS build unaffected**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build 2>&1 | tail -5
```

Expected: build succeeds (macOS hover branch unchanged).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeApp/AssetDetailView.swift Sources/APTradeApp/ExpandableValueChart.swift
git commit -m "feat(ios): touch-drag crosshair on detail and value charts"
```

---

### Task 5: Full verification pass

Ship-readiness gate. Confirm both test suites are green and visually walk every iPhone screen — the pre-existing iOS layout branches have never been verified on a running simulator.

**Files:**
- None modified (bug fixes discovered here are folded back into the relevant task's file and committed separately).

**Interfaces:**
- Consumes: the Task 1 baseline counts.
- Produces: a go/no-go verdict.

- [ ] **Step 1: Run the macOS suite**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test 2>&1 | tail -12
```

Expected: PASS at (Task 1 macOS count + 2).

- [ ] **Step 2: Run the iOS simulator suite**

```bash
cd "/Users/ap/Desktop/Work_25_02/Claude/Software engineering/Trading app"
mv APTrade.xcodeproj /tmp/APTrade.xcodeproj.parked
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test \
  -scheme APTradeLite-Package \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -skipPackagePluginValidation 2>&1 | tail -12
mv /tmp/APTrade.xcodeproj.parked APTrade.xcodeproj
```

Expected: `** TEST SUCCEEDED **`.

- [ ] **Step 3: Launch the app on the simulator for visual UAT**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild build \
  -project APTrade.xcodeproj -scheme APTradeiOS \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
xcrun simctl boot "iPhone 17 Pro" 2>/dev/null; open -a Simulator
# install + launch the built .app (path from the build output's TARGET_BUILD_DIR):
# xcrun simctl install booted "<TARGET_BUILD_DIR>/APTrade.app"
# xcrun simctl launch booted com.aptrade.ios
```

- [ ] **Step 4: Visual UAT checklist (user-performed)**

Automated computer-use cannot target this dev build, so the user drives the simulator. Walk each screen in portrait and confirm no horizontal clipping, no unreachable controls, no broken sheet:

- [ ] Watchlist — rows, live prices, daily %, search + account entry points
- [ ] Portfolio — holdings; Performance sub-tab; allocation
- [ ] AssetDetail — hero, chart (drag the crosshair — Task 4), stat grids, position panel, news
- [ ] News — list, article open
- [ ] Account sheet — every subpage (Profile, Account Settings, Notifications, Appearance, Language, Security, Help, About)
- [ ] Trade sheet and PriceAlert sheet — present, fields reachable, dismiss
- [ ] Command palette — opens via tap, result navigates
- [ ] Export — pick a format, `.fileExporter` appears, file saves (Task 3)

- [ ] **Step 5: Fix any defects found, then re-run Steps 1-3**

For each defect, edit the owning file (`#if os(iOS)` only), commit with a `fix(ios): …` message, and re-verify. Repeat until the checklist is clean.

- [ ] **Step 6: Final confirmation**

State the outcome plainly: iOS build succeeds, both suites green (with counts), and the UAT checklist passes. The iPhone app is shippable; the next workstream is Android → Windows parity (separate plan).
