import XCTest
import APTradeDomain

/// Covers `ScreenerMetric.value(in:)`, `ScreenCondition.matches`, the 9 `PresetScreen`
/// predicates, `CustomScreen` AND-evaluation, and `ScreenSelection`'s single evaluation
/// door. Comparisons throughout are STRICT (< / >) — a value exactly at a threshold
/// never matches; several fixtures pin that boundary deliberately.
final class ScreenerTests: XCTestCase {

    // MARK: - Helpers

    /// A row with every optional metric nil and every flag false by default —
    /// individual fields overridden per test so each assertion only varies what it needs.
    private func makeRow(
        symbol: String = "TEST",
        name: String = "Test Co",
        close: Double = 100,
        dayChangePercent: Double? = nil,
        rsi14: Double? = nil,
        macd: Double? = nil,
        macdSignal: Double? = nil,
        macdHistogram: Double? = nil,
        sma50: Double? = nil,
        sma200: Double? = nil,
        ema20: Double? = nil,
        pctVsSma50: Double? = nil,
        pctVsSma200: Double? = nil,
        bollingerPercentB: Double? = nil,
        bollingerBandwidth: Double? = nil,
        week52High: Double? = nil,
        week52Low: Double? = nil,
        pctTo52wHigh: Double? = nil,
        pctTo52wLow: Double? = nil,
        relativeVolume: Double? = nil,
        macdCrossedUp: Bool = false,
        macdCrossedDown: Bool = false,
        goldenCross: Bool = false,
        deathCross: Bool = false
    ) -> ScreenerSnapshotRow {
        ScreenerSnapshotRow(
            symbol: symbol,
            name: name,
            close: close,
            dayChangePercent: dayChangePercent,
            rsi14: rsi14,
            macd: macd,
            macdSignal: macdSignal,
            macdHistogram: macdHistogram,
            sma50: sma50,
            sma200: sma200,
            ema20: ema20,
            pctVsSma50: pctVsSma50,
            pctVsSma200: pctVsSma200,
            bollingerPercentB: bollingerPercentB,
            bollingerBandwidth: bollingerBandwidth,
            week52High: week52High,
            week52Low: week52Low,
            pctTo52wHigh: pctTo52wHigh,
            pctTo52wLow: pctTo52wLow,
            relativeVolume: relativeVolume,
            macdCrossedUp: macdCrossedUp,
            macdCrossedDown: macdCrossedDown,
            goldenCross: goldenCross,
            deathCross: deathCross
        )
    }

    // MARK: - (a) 9 presets — matching row + boundary near-miss (strict comparisons)

    func test_a_rsiOversold_matchesBelow30_notAtExactly30() {
        let matching = makeRow(rsi14: 29)
        let boundary = makeRow(rsi14: 30)
        XCTAssertTrue(PresetScreen.rsiOversold.matches(matching))
        XCTAssertFalse(PresetScreen.rsiOversold.matches(boundary))
    }

    func test_a_rsiOverbought_matchesAbove70_notAtExactly70() {
        let matching = makeRow(rsi14: 71)
        let boundary = makeRow(rsi14: 70)
        XCTAssertTrue(PresetScreen.rsiOverbought.matches(matching))
        XCTAssertFalse(PresetScreen.rsiOverbought.matches(boundary))
    }

    func test_a_macdBullishCross_matchesFlagTrue_notWhenFalse() {
        let matching = makeRow(macdCrossedUp: true)
        let nearMiss = makeRow(macdCrossedUp: false)
        XCTAssertTrue(PresetScreen.macdBullishCross.matches(matching))
        XCTAssertFalse(PresetScreen.macdBullishCross.matches(nearMiss))
    }

    func test_a_macdBearishCross_matchesFlagTrue_notWhenFalse() {
        let matching = makeRow(macdCrossedDown: true)
        let nearMiss = makeRow(macdCrossedDown: false)
        XCTAssertTrue(PresetScreen.macdBearishCross.matches(matching))
        XCTAssertFalse(PresetScreen.macdBearishCross.matches(nearMiss))
    }

    func test_a_goldenCross_matchesFlagTrue_notWhenFalse() {
        let matching = makeRow(goldenCross: true)
        let nearMiss = makeRow(goldenCross: false)
        XCTAssertTrue(PresetScreen.goldenCross.matches(matching))
        XCTAssertFalse(PresetScreen.goldenCross.matches(nearMiss))
    }

    func test_a_deathCross_matchesFlagTrue_notWhenFalse() {
        let matching = makeRow(deathCross: true)
        let nearMiss = makeRow(deathCross: false)
        XCTAssertTrue(PresetScreen.deathCross.matches(matching))
        XCTAssertFalse(PresetScreen.deathCross.matches(nearMiss))
    }

    func test_a_bollingerSqueeze_matchesBelow0_05_notAtExactly0_05() {
        let matching = makeRow(bollingerBandwidth: 0.04)
        let boundary = makeRow(bollingerBandwidth: 0.05)
        XCTAssertTrue(PresetScreen.bollingerSqueeze.matches(matching))
        XCTAssertFalse(PresetScreen.bollingerSqueeze.matches(boundary))
    }

    func test_a_near52wHigh_matchesBelow3_notAtExactly3() {
        let matching = makeRow(pctTo52wHigh: 2)
        let boundary = makeRow(pctTo52wHigh: 3)
        XCTAssertTrue(PresetScreen.near52wHigh.matches(matching))
        XCTAssertFalse(PresetScreen.near52wHigh.matches(boundary))
    }

    func test_a_near52wLow_matchesBelow3_notAtExactly3() {
        let matching = makeRow(pctTo52wLow: 2)
        let boundary = makeRow(pctTo52wLow: 3)
        XCTAssertTrue(PresetScreen.near52wLow.matches(matching))
        XCTAssertFalse(PresetScreen.near52wLow.matches(boundary))
    }

    /// Nil-metric presets never crash and never match — same "no data, no match" rule
    /// as `ScreenCondition`.
    func test_a_presetsOverNumericMetrics_nilMetric_neverMatchesNeverCrashes() {
        let allNil = makeRow()
        XCTAssertFalse(PresetScreen.rsiOversold.matches(allNil))
        XCTAssertFalse(PresetScreen.rsiOverbought.matches(allNil))
        XCTAssertFalse(PresetScreen.bollingerSqueeze.matches(allNil))
        XCTAssertFalse(PresetScreen.near52wHigh.matches(allNil))
        XCTAssertFalse(PresetScreen.near52wLow.matches(allNil))
    }

    // MARK: - (b) Condition with nil metric → row excluded

    func test_b_conditionOnNilMetric_excludesRow() {
        let row = makeRow(rsi14: nil)
        let condition = ScreenCondition(metric: .rsi14, comparison: .below, threshold: 50)
        XCTAssertFalse(condition.matches(row))
    }

    func test_b_conditionOnPresentMetric_stillEvaluatesStrictly() {
        let row = makeRow(rsi14: 50)
        let above = ScreenCondition(metric: .rsi14, comparison: .above, threshold: 50)
        let below = ScreenCondition(metric: .rsi14, comparison: .below, threshold: 50)
        XCTAssertFalse(above.matches(row), "50 is not strictly above 50")
        XCTAssertFalse(below.matches(row), "50 is not strictly below 50")
    }

    // MARK: - (c) AND semantics — row passing only one of two conditions is excluded

    func test_c_andSemantics_rowPassingOnlyOneOfTwoConditions_isExcluded() {
        let row = makeRow(dayChangePercent: -1, rsi14: 25)
        let passesFirst = ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30)
        let failsSecond = ScreenCondition(metric: .dayChangePercent, comparison: .above, threshold: 5)
        let screen = CustomScreen(id: "s1", name: "Partial", conditions: [passesFirst, failsSecond])

        XCTAssertTrue(passesFirst.matches(row))
        XCTAssertFalse(failsSecond.matches(row))
        XCTAssertEqual(ScreenSelection.custom(screen).evaluate([row]), [])
    }

    func test_c_andSemantics_rowPassingBothConditions_isIncluded() {
        let row = makeRow(dayChangePercent: 6, rsi14: 25)
        let first = ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30)
        let second = ScreenCondition(metric: .dayChangePercent, comparison: .above, threshold: 5)
        let screen = CustomScreen(id: "s1", name: "Both", conditions: [first, second])

        XCTAssertEqual(ScreenSelection.custom(screen).evaluate([row]), [row])
    }

    // MARK: - (d) Empty custom conditions → no matches

    func test_d_emptyConditions_matchesNothing() {
        let row = makeRow(close: 9999, dayChangePercent: 100, rsi14: 1)
        let screen = CustomScreen(id: "empty", name: "Empty", conditions: [])
        XCTAssertEqual(ScreenSelection.custom(screen).evaluate([row]), [])
    }

    // MARK: - (e) Codable round-trip + legacy JSON tolerance

    func test_e_customScreen_codableRoundTrip() throws {
        let screen = CustomScreen(
            id: "abc123",
            name: "My Screen",
            conditions: [
                ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30),
                ScreenCondition(metric: .price, comparison: .above, threshold: 10)
            ]
        )
        let data = try JSONEncoder().encode(screen)
        let decoded = try JSONDecoder().decode(CustomScreen.self, from: data)
        XCTAssertEqual(decoded, screen)
    }

    /// Hand-written legacy JSON — exactly the fields the type defines today, none of any
    /// hypothetical future addition — still decodes cleanly.
    func test_e_legacyCustomScreenJSON_withoutFutureField_decodes() throws {
        let legacy = """
        {
            "id": "legacy-1",
            "name": "Legacy Screen",
            "conditions": [
                { "metric": "rsi14", "comparison": "below", "threshold": 30 }
            ]
        }
        """.data(using: .utf8)!

        let decoded = try JSONDecoder().decode(CustomScreen.self, from: legacy)
        XCTAssertEqual(decoded.id, "legacy-1")
        XCTAssertEqual(decoded.name, "Legacy Screen")
        XCTAssertEqual(decoded.conditions, [
            ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30)
        ])
    }

    // MARK: - ScreenerMetric.value(in:) — one door per case, nil-propagating

    func test_metricValue_priceMapsToClose() {
        let row = makeRow(close: 42.5)
        XCTAssertEqual(ScreenerMetric.price.value(in: row), 42.5)
    }

    func test_metricValue_allCasesPropagateNilIndependently() {
        let row = makeRow()
        for metric in ScreenerMetric.allCases where metric != .price {
            XCTAssertNil(metric.value(in: row), "\(metric) should be nil when the row field is nil")
        }
    }

    // MARK: - ScreenSelection.evaluate — preset door

    func test_screenSelection_presetDoor_filtersRows() {
        let oversold = makeRow(symbol: "OVER", rsi14: 20)
        let neutral = makeRow(symbol: "NEUT", rsi14: 50)
        let results = ScreenSelection.preset(.rsiOversold).evaluate([oversold, neutral])
        XCTAssertEqual(results.map(\.symbol), ["OVER"])
    }
}
