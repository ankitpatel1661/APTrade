import XCTest
import APTradeDomain

final class TechnicalIndicatorsTests: XCTestCase {
    func test_sma_warmupThenAverages() {
        let sma = TechnicalIndicators.sma([1, 2, 3, 4, 5], period: 3)
        XCTAssertNil(sma[0])
        XCTAssertNil(sma[1])
        XCTAssertEqual(sma[2]!, 2, accuracy: 1e-9)   // (1+2+3)/3
        XCTAssertEqual(sma[3]!, 3, accuracy: 1e-9)   // (2+3+4)/3
        XCTAssertEqual(sma[4]!, 4, accuracy: 1e-9)   // (3+4+5)/3
    }

    func test_ema_seedsWithSMAThenSmooths() {
        let ema = TechnicalIndicators.ema([1, 2, 3, 4, 5], period: 3)
        XCTAssertNil(ema[1])
        XCTAssertEqual(ema[2]!, 2, accuracy: 1e-9)   // seed = SMA(1,2,3)
        // k = 2/4 = 0.5; ema[3] = (4-2)*0.5 + 2 = 3
        XCTAssertEqual(ema[3]!, 3, accuracy: 1e-9)
        // ema[4] = (5-3)*0.5 + 3 = 4
        XCTAssertEqual(ema[4]!, 4, accuracy: 1e-9)
    }

    func test_rsi_allGains_is100() {
        let rsi = TechnicalIndicators.rsi([1, 2, 3, 4, 5, 6], period: 3)
        XCTAssertNil(rsi[2])
        XCTAssertEqual(rsi[3]!, 100, accuracy: 1e-9)  // no losses → RSI 100
    }

    func test_rsi_knownSeriesInRange() {
        let closes = [44.0, 44.5, 43.8, 44.2, 45.0, 44.7, 45.5, 46.0, 45.2, 46.5]
        let rsi = TechnicalIndicators.rsi(closes, period: 5)
        let defined = rsi.compactMap { $0 }
        XCTAssertFalse(defined.isEmpty)
        for value in defined { XCTAssertTrue(value >= 0 && value <= 100, "RSI \(value) out of range") }
    }

    func test_shortSeries_returnsAllNil() {
        XCTAssertEqual(TechnicalIndicators.sma([1, 2], period: 5).compactMap { $0 }.count, 0)
        XCTAssertEqual(TechnicalIndicators.rsi([1, 2], period: 14).compactMap { $0 }.count, 0)
    }
}
