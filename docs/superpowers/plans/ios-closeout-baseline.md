# iOS Closeout Baseline

**Date:** 2026-07-12

Baseline verification recording ground truth before iOS closeout tasks.

## Results

### iOS Build (Step 1)

- **Status:** ✅ BUILD SUCCEEDED
- **Command:** `xcodegen generate` + `xcodebuild build -project APTrade.xcodeproj -scheme APTradeiOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro'`
- **Result:** Project regenerates and builds successfully to iOS simulator

### macOS Test Suite (Step 2)

- **Status:** ✅ PASSED
- **Command:** `swift test`
- **Result:** Executed 200 tests, with 0 failures (0 unexpected)

### iOS Simulator Test Suite (Step 3)

- **Status:** ✅ TEST SUCCEEDED
- **Command:** `xcodebuild test -scheme APTradeLite-Package -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -skipPackagePluginValidation`
- **Result:** 200 tests total, 0 failures (across 4 test bundles)
- **Verification:** `xcrun xcresulttool get test-results summary --path <xcresult>` → `totalTestCount: 200, passedTests: 200, failedTests: 0`

> Note: xcodebuild prints one "All tests" summary block per test bundle, so a
> `tail` of the console output can mislead — it shows only the last bundle's
> count. Future baseline counts should be taken from `xcresulttool` against
> the .xcresult bundle, not from the console tail.

## Summary

- iOS builds: ✅ OK
- macOS test baseline: 200 tests (all pass)
- iOS test baseline: 200 tests (all pass, 4 bundles, per xcresulttool)
- Pre-closeout state is stable and ready for edits
