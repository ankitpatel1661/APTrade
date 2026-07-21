import XCTest
import APTradeDomain

/// Fixture derivations use the exact algorithms in TechnicalIndicators.swift (Wilder RSI,
/// SMA-seeded EMA, population-variance Bollinger Bands). Each fixture's expected values are
/// derived by hand from the published formulas (shown in comments); reviewers should
/// recompute independently before trusting these numbers.
final class ScreenerMathTests: XCTestCase {

    // MARK: - Helpers

    private func makeCandle(_ offset: Int, close: Double, high: Double? = nil, low: Double? = nil, volume: Double = 0) -> Candle {
        let date = Date(timeIntervalSince1970: TimeInterval(offset) * 86_400)
        let closeMoney = Money(amount: Decimal(close))
        let highMoney = Money(amount: Decimal(high ?? close))
        let lowMoney = Money(amount: Decimal(low ?? close))
        return Candle(date: date, open: closeMoney, high: highMoney, low: lowMoney, close: closeMoney, volume: volume)
    }

    private func makeCandles(closes: [Double], highs: [Double]? = nil, lows: [Double]? = nil, volumes: [Double]? = nil) -> [Candle] {
        closes.indices.map { i in
            makeCandle(i, close: closes[i], high: highs?[i], low: lows?[i], volume: volumes?[i] ?? 0)
        }
    }

    // MARK: - (a) Small rising series → exact RSI / EMA / Bollinger / day-change

    /// closes = 100...119 (20 bars, strictly +1/day).
    ///
    /// RSI(14): every daily change is a gain (+1), so lossSum stays 0 for the whole warm-up
    /// and every later Wilder update. `rsiValue` special-cases avgLoss == 0 → 100. So
    /// rsi14 == 100 exactly, at every defined index — including the last (index 19).
    ///
    /// EMA(20): count == period (20), so the EMA is *only* its SMA seed (the iteration loop
    /// `for i in period..<count` is empty). seed = mean(100...119) = (100+119)/2 = 109.5.
    ///
    /// Bollinger(20): same reasoning — only the seed bar (index 19) is defined.
    ///   middle = mean(100...119) = 109.5
    ///   population variance of n consecutive integers = (n²−1)/12 = (400−1)/12 = 33.25
    ///   stddev = sqrt(33.25) = 5.766281297335398…
    ///   upper = 109.5 + 2×5.766281297335398 = 121.03256259467080
    ///   lower = 109.5 − 2×5.766281297335398 = 97.96743740532920
    ///   close = 119
    ///   %B = (119 − 97.9674374053292) / (121.0325625946708 − 97.9674374053292)
    ///      = 21.0325625946708 / 23.0651251893416 = 0.911877235523957
    ///   bandwidth = (121.0325625946708 − 97.9674374053292) / 109.5 = 0.210640412688051
    ///
    /// Day change: (119 − 118)/118 × 100 = 100/118 = 0.847457627118644…
    ///
    /// sma50/sma200/macd all need more bars than are present (20 < 50/200/34) → nil.
    func test_a_risingSeries_exactRSIEmaBollingerAndDayChange() {
        let closes = (0..<20).map { 100.0 + Double($0) }
        let candles = makeCandles(closes: closes)

        let row = ScreenerMath.snapshot(symbol: "RISE", name: "Rising Co", candles: candles)
        XCTAssertNotNil(row)
        guard let row else { return }

        XCTAssertEqual(row.close, 119, accuracy: 1e-9)
        XCTAssertEqual(row.rsi14!, 100, accuracy: 1e-9)
        XCTAssertEqual(row.ema20!, 109.5, accuracy: 1e-9)
        XCTAssertEqual(row.bollingerPercentB!, 0.911877235523957, accuracy: 1e-9)
        XCTAssertEqual(row.bollingerBandwidth!, 0.210640412688051, accuracy: 1e-9)
        XCTAssertEqual(row.dayChangePercent!, 100.0 / 118.0, accuracy: 1e-9)

        XCTAssertNil(row.sma50)
        XCTAssertNil(row.sma200)
        XCTAssertNil(row.macd)
        XCTAssertNil(row.macdSignal)
        XCTAssertNil(row.macdHistogram)
        XCTAssertNil(row.pctVsSma50)
        XCTAssertNil(row.pctVsSma200)
    }

    // MARK: - (b) Short history (5 bars) → most metrics nil/false, close + day-change present

    /// closes = 100,101,102,103,104 (5 bars). Every long-window indicator (RSI-14 needs
    /// count > 14, EMA/Bollinger-20 need count >= 20, SMA-50/200 need count >= 50/200,
    /// MACD needs count >= 26 for the slow EMA) degrades to nil independently — none of
    /// them share state, so a 5-bar history simply starves all of them at once.
    ///
    /// close = 104 (last bar).
    /// dayChangePercent = (104 − 103)/103 × 100 = 100/103 = 0.9708737864077669…
    func test_b_shortHistory_degradesGracefullyToNilAndFalse() {
        let closes = (0..<5).map { 100.0 + Double($0) }
        let candles = makeCandles(closes: closes)

        let row = ScreenerMath.snapshot(symbol: "SHRT", name: "Short Co", candles: candles)
        XCTAssertNotNil(row)
        guard let row else { return }

        XCTAssertEqual(row.close, 104, accuracy: 1e-9)
        XCTAssertEqual(row.dayChangePercent!, 100.0 / 103.0, accuracy: 1e-9)

        XCTAssertNil(row.rsi14)
        XCTAssertNil(row.ema20)
        XCTAssertNil(row.sma50)
        XCTAssertNil(row.sma200)
        XCTAssertNil(row.macd)
        XCTAssertNil(row.macdSignal)
        XCTAssertNil(row.macdHistogram)
        XCTAssertNil(row.bollingerPercentB)
        XCTAssertNil(row.bollingerBandwidth)
        XCTAssertNil(row.pctVsSma50)
        XCTAssertNil(row.pctVsSma200)

        XCTAssertFalse(row.macdCrossedUp)
        XCTAssertFalse(row.macdCrossedDown)
        XCTAssertFalse(row.goldenCross)
        XCTAssertFalse(row.deathCross)
    }

    // MARK: - (c) macdCrossedUp: flips ≤0→>0 on the final bar; control stays positive → false

    /// Construction: closes decline by exactly 1.0/day for 40 days (100 → 60). A long
    /// constant-slope run drives the MACD histogram to its steady state, where the fast/slow
    /// EMAs' difference (the MACD line) is itself constant, so the signal EMA (which is just
    /// an EMA *of* the MACD line) converges onto that same constant — histogram → 0 exactly
    /// (verified by replaying the exact sma/ema/macd recurrences from TechnicalIndicators.swift:
    /// histogram is bit-exact 0.0 for several days before the final decline bar).
    ///
    /// One rally day (+2.0) then flips the histogram positive:
    ///   histogram[n-2] (last decline bar, close 60)  = 0.0
    ///   histogram[n-1] (rally bar, close 62)         = 0.19145299145299077
    /// → 0.0 ≤ 0 and 0.19145299145299077 > 0 ⇒ macdCrossedUp == true.
    ///
    /// Control: continue the same rally for 3 days instead of 1 (100→60→62→64→66). By the
    /// third rally day the histogram has been positive for 2 consecutive bars already:
    ///   histogram[n-2] = 0.4924323666203918 (> 0, so the "≤0 yesterday" condition fails)
    ///   histogram[n-1] = 0.8451605680578727
    /// → macdCrossedUp == false (already above zero, no new cross).
    func test_c_macdCrossedUp_flipsTrueOnFinalBar_falseWhenAlreadyPositive() {
        var declineToFlip = [100.0]
        for _ in 0..<40 { declineToFlip.append(declineToFlip.last! - 1.0) }
        declineToFlip.append(declineToFlip.last! + 2.0) // single rally day → the flip bar

        let flipRow = ScreenerMath.snapshot(symbol: "FLIP", name: "Flip Co", candles: makeCandles(closes: declineToFlip))
        XCTAssertNotNil(flipRow)
        XCTAssertTrue(flipRow?.macdCrossedUp ?? false)
        XCTAssertFalse(flipRow?.macdCrossedDown ?? true)

        var declineThenSustainedRally = [100.0]
        for _ in 0..<40 { declineThenSustainedRally.append(declineThenSustainedRally.last! - 1.0) }
        for _ in 0..<3 { declineThenSustainedRally.append(declineThenSustainedRally.last! + 2.0) }

        let controlRow = ScreenerMath.snapshot(symbol: "CTRL", name: "Control Co", candles: makeCandles(closes: declineThenSustainedRally))
        XCTAssertNotNil(controlRow)
        XCTAssertFalse(controlRow?.macdCrossedUp ?? true)
    }

    // MARK: - (d) goldenCross: sma50 crosses above sma200 on the final bar; control already above → false

    /// Construction: closes decline by 0.5/day for 220 days from 200 (200 → 90), then rally
    /// by 3.0/day. Verified by replaying TechnicalIndicators.sma exactly (period 50 and 200):
    /// after 38 rally days (259 bars total) sma50 crosses above sma200 for the first time on
    /// the final bar:
    ///   sma50[n-2]  = 132.96,  sma200[n-2]  = 133.5525   (50 ≤ 200 yesterday)
    ///   sma50[n-1]  = 135.12,  sma200[n-1]  = 133.7175   (50 >  200 today)
    /// → goldenCross == true.
    ///
    /// Control: one further rally day (39 total, 260 bars) — sma50 was already above sma200
    /// on the prior bar, so there's no new cross:
    ///   sma50[n-2] = 135.12 > sma200[n-2] = 133.7175 (already above → "≤" fails)
    /// → goldenCross == false.
    func test_d_goldenCross_flipsTrueOnFinalBar_falseWhenAlreadyAbove() {
        func decliningThenRallying(rallyDays: Int) -> [Double] {
            var closes = [200.0]
            for _ in 0..<220 { closes.append(closes.last! - 0.5) }
            for _ in 0..<rallyDays { closes.append(closes.last! + 3.0) }
            return closes
        }

        let flipCloses = decliningThenRallying(rallyDays: 38)
        let flipRow = ScreenerMath.snapshot(symbol: "GOLD", name: "Golden Co", candles: makeCandles(closes: flipCloses))
        XCTAssertNotNil(flipRow)
        XCTAssertTrue(flipRow?.goldenCross ?? false)
        XCTAssertFalse(flipRow?.deathCross ?? true)

        let controlCloses = decliningThenRallying(rallyDays: 39)
        let controlRow = ScreenerMath.snapshot(symbol: "GCTL", name: "Golden Control Co", candles: makeCandles(closes: controlCloses))
        XCTAssertNotNil(controlRow)
        XCTAssertFalse(controlRow?.goldenCross ?? true)
    }

    // MARK: - (e) 52-week distance percentages, exact

    /// 5 candles; week52High/Low are the max/min of *all* highs/lows in the provided series
    /// (the caller is expected to hand in ~1y of daily candles; ScreenerMath just reduces
    /// over the whole thing).
    ///   highs = [150, 200, 160, 170, 155] → week52High = 200
    ///   lows  = [120, 130, 100, 140, 145] → week52Low  = 100
    ///   close (last bar) = 150
    ///   pctTo52wHigh = (200 − 150)/200 × 100 = 25.0
    ///   pctTo52wLow  = (150 − 100)/100 × 100 = 50.0
    func test_e_52WeekDistancePercentages_exact() {
        let closes = [140.0, 180.0, 110.0, 155.0, 150.0]
        let highs  = [150.0, 200.0, 160.0, 170.0, 155.0]
        let lows   = [120.0, 130.0, 100.0, 140.0, 145.0]
        let candles = makeCandles(closes: closes, highs: highs, lows: lows)

        let row = ScreenerMath.snapshot(symbol: "WK52", name: "52 Week Co", candles: candles)
        XCTAssertNotNil(row)
        guard let row else { return }

        XCTAssertEqual(row.week52High!, 200, accuracy: 1e-9)
        XCTAssertEqual(row.week52Low!, 100, accuracy: 1e-9)
        XCTAssertEqual(row.pctTo52wHigh!, 25.0, accuracy: 1e-9)
        XCTAssertEqual(row.pctTo52wLow!, 50.0, accuracy: 1e-9)
    }

    // MARK: - (f) relativeVolume = today ÷ mean(last 20), exact; nil when volumes all 0

    /// 20 candles: the first 19 days trade 100 shares, today trades 200.
    ///   mean(last 20) = (19×100 + 200)/20 = 2100/20 = 105
    ///   relativeVolume = 200/105 = 40/21 = 1.9047619047619047…
    func test_f_relativeVolume_exactAndNilWhenVolumesAllZero() {
        let closes = Array(repeating: 50.0, count: 20)
        var volumes = Array(repeating: 100.0, count: 19)
        volumes.append(200.0)

        let row = ScreenerMath.snapshot(symbol: "VOL", name: "Volume Co", candles: makeCandles(closes: closes, volumes: volumes))
        XCTAssertNotNil(row)
        XCTAssertEqual(row?.relativeVolume ?? -1, 40.0 / 21.0, accuracy: 1e-9)

        let zeroVolumeCandles = makeCandles(closes: closes, volumes: Array(repeating: 0.0, count: 20))
        guard let zeroRow = ScreenerMath.snapshot(symbol: "ZERO", name: "Zero Volume Co", candles: zeroVolumeCandles) else {
            return XCTFail("expected a row")
        }
        XCTAssertNil(zeroRow.relativeVolume)
    }

    // MARK: - (g) Empty candles → nil

    func test_g_emptyCandles_returnsNil() {
        let row = ScreenerMath.snapshot(symbol: "NONE", name: "No Data Co", candles: [])
        XCTAssertNil(row)
    }

    // MARK: - Bonus: Codable round-trip (not one of the 7 lettered scenarios, cheap safety net)

    func test_codableRoundTrip_rowAndSnapshot() throws {
        let candles = makeCandles(closes: (0..<20).map { 100.0 + Double($0) })
        guard let row = ScreenerMath.snapshot(symbol: "RT", name: "Round Trip Co", candles: candles) else {
            return XCTFail("expected a row")
        }
        let snapshot = ScreenerSnapshot(tradingDay: "2026-07-20", scannedAt: Date(), rows: [row], failedSymbols: ["BAD"])

        let encoder = JSONEncoder()
        let decoder = JSONDecoder()
        let decodedRow = try decoder.decode(ScreenerSnapshotRow.self, from: encoder.encode(row))
        XCTAssertEqual(decodedRow, row)
        let decodedSnapshot = try decoder.decode(ScreenerSnapshot.self, from: encoder.encode(snapshot))
        XCTAssertEqual(decodedSnapshot, snapshot)
    }
}
