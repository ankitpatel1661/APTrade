import XCTest
@testable import APTradeApp
import APTradeDomain

/// Pins the bundled name map against the shared ticker set — both are generated from the
/// same constituents snapshot (and from the Windows desktop's twin map) and must be
/// refreshed together; a drifted refresh would silently drop names from Calendar rows.
final class SP500NamesTests: XCTestCase {

    func test_everyIndexSymbolHasAName() {
        let missing = SP500Symbols.set.subtracting(sp500Names.keys)
        XCTAssertTrue(missing.isEmpty, "index symbols without a name: \(missing)")
        XCTAssertEqual(sp500Names.count, SP500Symbols.set.count, "name map carries extra symbols")
    }

    func test_namesAreNonBlankAndSpotChecksHold() {
        XCTAssertFalse(sp500Names.values.contains(where: \.isEmpty))
        XCTAssertEqual(sp500Names["AAPL"], "Apple Inc.")
        XCTAssertEqual(sp500Names["BNY"], "BNY Mellon")
        XCTAssertEqual(sp500Names["BRK.B"], "Berkshire Hathaway")
    }
}
