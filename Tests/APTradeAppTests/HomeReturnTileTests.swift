import XCTest
@testable import APTradeApp
import APTradeDomain

/// Pins the Home hero "Total Return" tile's rendered string, and pins it AGAINST the
/// Performance tab's metric grid.
///
/// The defect these tests exist to reject: `PerformanceMetrics.totalReturnFraction` is a
/// FRACTION (`last / first − 1`, so `0.12` means 12%), and Home's old `signedPercent1dp`
/// formatted it with no `× 100`. A 12% gain rendered as "+0.1%" on the Home tab while the
/// Performance tab — which always had the `× 100` — rendered "12.00%" for the identical
/// report at the identical moment. Two screens of one app, disagreeing by exactly 100×.
///
/// Why every assertion here is a FULL rendered string: the Kotlin twin of this surface was
/// "covered" by `assertTrue(metrics.totalReturn.endsWith("%"))`, which "0.12%" and "12.00%"
/// both satisfy. A suffix assertion cannot fail for ANY scaling error, and that is precisely
/// how this shipped. Nothing in this file asserts a prefix, a suffix, or a substring.
@MainActor
final class HomeReturnTileTests: XCTestCase {

    // MARK: - Scale

    /// Rejects the shipped implementation: formatting the fraction without scaling it to
    /// percentage points. Under that bug this reads "+0.12%" instead of "+12.00%".
    func test_homeTile_scalesTheFractionToPercentagePoints() {
        XCTAssertEqual(homeTotalReturnText(fraction: 0.12), "+12.00%")
    }

    /// Losses and flat portfolios take the same path — a sign-only or magnitude-only fix
    /// (e.g. one that special-cases gains) does not pass this.
    func test_homeTile_rendersLossesAndFlatAtTheSameScale() {
        XCTAssertEqual(homeTotalReturnText(fraction: -0.05), "-5.00%")
        XCTAssertEqual(homeTotalReturnText(fraction: 0), "0.00%")
    }

    /// A whole-number multiple is where a missing `× 100` is easiest to mistake for a
    /// rounding artefact, so pin one: a portfolio that doubled reads "+100.00%", never "+1%".
    func test_homeTile_aDoubledPortfolioReadsAsOneHundredPercent() {
        XCTAssertEqual(homeTotalReturnText(fraction: 1.0), "+100.00%")
    }

    // MARK: - Cross-screen agreement (the test that would have caught this)

    /// THE regression test. Home and Performance are handed the same fraction and must
    /// render the same NUMBER. Before this fix the two sides of this assertion were
    /// "+0.1%" and "12.00%".
    ///
    /// The only difference the two surfaces are permitted is Home's explicit leading "+" on
    /// a gain (it inherits `Percentage.formatted`, matching the day-change pill beside it);
    /// the digits must match exactly. Both full strings are asserted first, so this cannot
    /// pass by both sides being wrong in the same way.
    ///
    /// ⚠️ Known residual, deliberately NOT asserted here because it is out of this task's
    /// scope: above 1000 percentage points the two surfaces still differ cosmetically —
    /// `Percentage.formatted` (Home) groups thousands, `String(format:)` (Performance) does
    /// not, so a 12.3456 fraction reads "+1,234.56%" vs "1234.56%". The digits and the scale
    /// agree; only the grouping separator does not. Carried as a follow-up, not silently
    /// unnoticed — every fraction below is chosen under that threshold on purpose.
    func test_homeTile_andPerformanceGrid_renderTheSameNumber() {
        for fraction in [0.12, -0.05, 0.0, 1.0, 0.0725] {
            let home = homeTotalReturnText(fraction: fraction)
            let performance = PerformanceSection.percent(fraction)
            XCTAssertEqual(String(home.drop(while: { $0 == "+" })), performance,
                           "Home and Performance disagree for fraction \(fraction): "
                           + "\(home) vs \(performance)")
        }
    }

    /// Spelled out once at a literal, so the loop above cannot pass vacuously if
    /// `homeTotalReturnText` and `PerformanceSection.percent` ever collapse into each other.
    func test_homeTile_andPerformanceGrid_bothRenderTwelvePercentForATwelvePercentGain() {
        XCTAssertEqual(homeTotalReturnText(fraction: 0.12), "+12.00%")
        XCTAssertEqual(PerformanceSection.percent(0.12), "12.00%")
    }

    // MARK: - Formatter parity with the neighbouring day-change pill

    /// The tile and the `ChangePill` sit side by side in the same hero row, and the pill
    /// renders `Percentage.formatted`. Pinning the tile to that exact formatter is what
    /// removes BOTH the old 1-decimal-vs-2-decimal mismatch and the old device-locale
    /// decimal separator (`signedPercent1dp` used `.formatted(.number…)`, which prints
    /// "+0,1%" under a German locale; `Percentage.formatted` pins `en_US`).
    func test_homeTile_usesTheSamePinnedFormatterAsTheDayChangePill() {
        XCTAssertEqual(homeTotalReturnText(fraction: 0.12), Percentage(value: 12).formatted)
        XCTAssertEqual(homeTotalReturnText(fraction: -0.05), Percentage(value: -5).formatted)
    }

    /// Two decimal places, always — not the one the old helper used, and not a variable
    /// count. `0.0725` is chosen because a 1dp formatter rounds it to "+7.3%" and so cannot
    /// be mistaken for a passing 2dp render.
    func test_homeTile_alwaysRendersTwoDecimalPlaces() {
        XCTAssertEqual(homeTotalReturnText(fraction: 0.0725), "+7.25%")
    }
}
