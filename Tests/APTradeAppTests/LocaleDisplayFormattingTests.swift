import XCTest
@testable import APTradeApp
import APTradeDomain

/// Guards the locale defect Task 1 found empirically: `Double.formatted(.number…)` with no
/// explicit `.locale(...)` follows `Locale.autoupdatingCurrent` — the device region — not
/// `en_US`. This machine (and its simulator) is `en_DE`: English language, German region,
/// which prints a comma decimal separator for that unpinned API where every neighbouring
/// pinned number in the app shows a period.
///
/// ## Why this file leans on the host's real locale rather than faking one
///
/// The trap: any test that never runs under a non-US locale passes on ANY implementation —
/// pinned or not — the moment the decimal separator happens to be a period already, and is
/// therefore exercising nothing. The correct fix is to force a non-US locale from inside the
/// test, run, and confirm.
///
/// Tried and rejected: `Locale.autoupdatingCurrent`/`Locale.current` on Apple platforms read
/// the `AppleLocale`/`AppleLanguages` keys, and setting those via the process's *argument*
/// domain at launch (Xcode's own "-AppleLocale de_DE" scheme argument, or a fresh
/// `swiftc`-compiled binary that pokes `UserDefaults.standard.set("de_DE", forKey:
/// "AppleLocale")` as its very first Foundation call) genuinely moves
/// `Locale.autoupdatingCurrent`, confirmed with a throwaway standalone binary. But inside the
/// `swift test` process specifically, the same in-process `UserDefaults` poke does NOT move
/// `Locale.autoupdatingCurrent` — by the time our test method runs, XCTest and its
/// dependencies have already resolved and cached the process's locale, and re-setting the
/// `AppleLocale` default afterward does not invalidate that cache. Measured directly:
/// `test_hostLocaleIsGenuinelyNonUS` below still reports `en_DE`/comma even after that poke.
/// The legacy `xctest` CLI (`xcrun xctest <bundle>`) also can't help: it accepts exactly one
/// positional argument (the bundle path) and errors out on any trailing `-AppleLocale de_DE`
/// rather than passing it through to the hosted process.
///
/// So: no reliable way was found to force a non-US locale onto an already-running
/// `swift test` process. Instead, this file exploits the fact that the *actual* host is
/// already non-US (`en_DE`) and makes that fact an explicit, checked precondition
/// (`test_hostLocaleIsGenuinelyNonUS`) rather than a silent assumption. If this suite is ever
/// run on a genuinely US-locale CI host, that canary test fails loudly — telling the reader
/// this file cannot validate the fix there — instead of the other tests quietly passing for
/// the wrong reason. The RED/GREEN transcripts in the Task 3 report were captured on this
/// same non-US host, with the canary green in both runs.
@MainActor
final class LocaleDisplayFormattingTests: XCTestCase {

    /// Precondition canary — see the class comment. Reproduces Task 1's empirical RED
    /// transcript verbatim: an unpinned `Double.formatted(.number…)` prints a comma here.
    /// If this ever fails, every other test in this file is running on a US-locale host and
    /// proves nothing about the locale fix.
    func test_hostLocaleIsGenuinelyNonUS() {
        let unpinned = 12.3.formatted(.number.precision(.fractionLength(2)))
        XCTAssertEqual(unpinned, "12,30",
                       "this host is not the non-US (en_DE) locale these tests require — "
                       + "got \(unpinned) instead; the assertions below cannot be trusted here")
    }

    // MARK: - ExpandableValueChart.swift — `expandedValueChangeText`
    // Task 3 brief: two unlocalized sub-expressions on one line (an amount and a percentage).

    /// Money-style change line (the "$12.30 (+0.42%)" headline).
    func test_expandedValueChangeText_money_rendersAPeriodOnThisNonUSHost() {
        let text = expandedValueChangeText(changeAmount: 12.3, changePercent: 0.42, style: .money)
        XCTAssertEqual(text, "+$12.30 (+0.42%)")
    }

    /// Losses take the same unpinned-formatter path as gains; pin both.
    func test_expandedValueChangeText_money_negativeRendersAPeriodOnThisNonUSHost() {
        let text = expandedValueChangeText(changeAmount: -8.5, changePercent: -1.25, style: .money)
        XCTAssertEqual(text, "−$8.50 (−1.25%)")
    }

    /// Percentage-points change line (the watchlist's average-day-change style).
    func test_expandedValueChangeText_percentagePoints_rendersAPeriodOnThisNonUSHost() {
        let text = expandedValueChangeText(changeAmount: 3.21, changePercent: 0, style: .percentagePoints)
        XCTAssertEqual(text, "+3.21%")
    }

    // MARK: - WatchlistView.swift — `watchlistAverageDayChangeText` (both `#if os` branches)

    /// One function backs both the iOS (line 38) and macOS (line 113) closures pre-fix, so
    /// one test covers both platform branches.
    func test_watchlistAverageDayChangeText_rendersAPeriodOnThisNonUSHost() {
        XCTAssertEqual(watchlistAverageDayChangeText(3.21), "+3.21%")
        XCTAssertEqual(watchlistAverageDayChangeText(-1.25), "-1.25%")
        XCTAssertEqual(watchlistAverageDayChangeText(0), "0.00%")
    }
}
