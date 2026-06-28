import XCTest
@testable import APTradeInfrastructure
import APTradeDomain

final class TimeframeMappingTests: XCTestCase {
    func test_mappings() {
        XCTAssertEqual(Timeframe.oneDay.yahooRange, "5d")
        XCTAssertEqual(Timeframe.oneDay.yahooInterval, "5m")
        XCTAssertEqual(Timeframe.oneWeek.yahooRange, "1mo")
        XCTAssertEqual(Timeframe.oneMonth.yahooInterval, "1d")
        XCTAssertEqual(Timeframe.oneYear.yahooRange, "1y")
    }

    func test_windowDurations() {
        XCTAssertEqual(Timeframe.oneDay.windowDuration, 24 * 3600)
        XCTAssertEqual(Timeframe.oneWeek.windowDuration, 7 * 24 * 3600)
    }

    func test_clampToWindow_anchorsToNewestBar_notNow() {
        // Five bars spaced 2 days apart, the newest ~10 days in the PAST (a stale weekend).
        let newest = Date().addingTimeInterval(-10 * 24 * 3600)
        let dates = (0..<5).map { newest.addingTimeInterval(Double(-$0) * 2 * 24 * 3600) }

        // A now-anchored window would drop everything (all bars are >24h old) — this must not.
        let oneDay = YahooMarketDataRepository.clampToWindow(dates, timeframe: .oneDay) { $0 }
        XCTAssertEqual(oneDay, [newest], "1D keeps only the most recent bar (next is 2 days back)")

        // 1W = 7 days back from newest → newest, −2d, −4d, −6d (−8d falls outside).
        let oneWeek = YahooMarketDataRepository.clampToWindow(dates, timeframe: .oneWeek) { $0 }
        XCTAssertEqual(oneWeek.count, 4, "1W keeps bars within 7 days of the newest")
    }

    func test_clampToWindow_emptyInput() {
        XCTAssertTrue(YahooMarketDataRepository.clampToWindow([Date](), timeframe: .oneDay) { $0 }.isEmpty)
    }
}
