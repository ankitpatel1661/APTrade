import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class ScreenerStoresTests: XCTestCase {

    // MARK: - FileScreenerSnapshotStore

    func makeTempDirectory() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("aptrade-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func makeSnapshot() -> ScreenerSnapshot {
        let row = ScreenerSnapshotRow(
            symbol: "AAPL",
            name: "Apple Inc.",
            close: 150.0,
            dayChangePercent: 1.2,
            rsi14: 55.0,
            macd: 0.5,
            macdSignal: 0.3,
            macdHistogram: 0.2,
            sma50: 145.0,
            sma200: 140.0,
            ema20: 148.0,
            pctVsSma50: 3.4,
            pctVsSma200: 7.1,
            bollingerPercentB: 0.6,
            bollingerBandwidth: 0.1,
            week52High: 180.0,
            week52Low: 120.0,
            pctTo52wHigh: 16.7,
            pctTo52wLow: 25.0,
            relativeVolume: 1.1,
            macdCrossedUp: true,
            macdCrossedDown: false,
            goldenCross: true,
            deathCross: false
        )
        return ScreenerSnapshot(
            tradingDay: "2026-07-21",
            scannedAt: Date(timeIntervalSince1970: 1_753_100_000),
            rows: [row],
            failedSymbols: ["TSLA"]
        )
    }

    // (a) snapshot round-trip via temp directory
    func test_snapshotStore_roundTrips_viaTempDirectory() throws {
        let dir = try makeTempDirectory()
        let store = FileScreenerSnapshotStore(directory: dir)
        let snapshot = makeSnapshot()

        store.save(snapshot)
        let loaded = store.load()

        XCTAssertEqual(loaded, snapshot)
    }

    // (b) corrupt file -> nil, bytes untouched
    func test_snapshotStore_corruptFile_returnsNil_withoutOverwriting() throws {
        let dir = try makeTempDirectory()
        let store = FileScreenerSnapshotStore(directory: dir)
        store.save(makeSnapshot())

        let fileURL = dir.appendingPathComponent("screener-snapshot.json")
        let corruptBytes = try XCTUnwrap("not valid json at all".data(using: .utf8))
        try corruptBytes.write(to: fileURL, options: .atomic)

        let loaded = store.load()
        XCTAssertNil(loaded)

        let bytesAfterLoad = try Data(contentsOf: fileURL)
        XCTAssertEqual(bytesAfterLoad, corruptBytes)
    }

    // (c) missing file -> nil
    func test_snapshotStore_missingFile_returnsNil() throws {
        let dir = try makeTempDirectory()
        let store = FileScreenerSnapshotStore(directory: dir)

        XCTAssertNil(store.load())
    }

    // (e) hand-written legacy snapshot JSON missing a nullable metric field decodes
    func test_snapshotStore_legacyJSONMissingNullableField_decodes() throws {
        let dir = try makeTempDirectory()
        let fileURL = dir.appendingPathComponent("screener-snapshot.json")

        // Hand-written legacy payload: relativeVolume key entirely absent from the row.
        let legacyJSON = """
        {
            "tradingDay": "2026-07-20",
            "scannedAt": 1753000000,
            "rows": [
                {
                    "symbol": "MSFT",
                    "name": "Microsoft Corp.",
                    "close": 300.0,
                    "dayChangePercent": 0.5,
                    "rsi14": 60.0,
                    "macd": 0.1,
                    "macdSignal": 0.05,
                    "macdHistogram": 0.05,
                    "sma50": 295.0,
                    "sma200": 290.0,
                    "ema20": 298.0,
                    "pctVsSma50": 1.7,
                    "pctVsSma200": 3.4,
                    "bollingerPercentB": 0.5,
                    "bollingerBandwidth": 0.08,
                    "week52High": 320.0,
                    "week52Low": 250.0,
                    "pctTo52wHigh": 6.25,
                    "pctTo52wLow": 20.0,
                    "macdCrossedUp": false,
                    "macdCrossedDown": false,
                    "goldenCross": false,
                    "deathCross": false
                }
            ],
            "failedSymbols": []
        }
        """
        try XCTUnwrap(legacyJSON.data(using: .utf8)).write(to: fileURL, options: .atomic)

        let store = FileScreenerSnapshotStore(directory: dir)
        let loaded = try XCTUnwrap(store.load())

        XCTAssertEqual(loaded.rows.count, 1)
        XCTAssertNil(loaded.rows[0].relativeVolume)
        XCTAssertEqual(loaded.rows[0].symbol, "MSFT")
    }

    // MARK: - UserDefaultsScreenStore

    func makeDefaults() throws -> UserDefaults {
        let suite = "test.\(UUID().uuidString)"
        return try XCTUnwrap(UserDefaults(suiteName: suite))
    }

    func makeScreen(id: String = "screen-1", name: String = "My Screen") -> CustomScreen {
        CustomScreen(
            id: id,
            name: name,
            conditions: [ScreenCondition(metric: .rsi14, comparison: .below, threshold: 30)]
        )
    }

    // (d) screens round-trip + corrupt -> []
    func test_screenStore_roundTrips() throws {
        let defaults = try makeDefaults()
        let store = UserDefaultsScreenStore(defaults: defaults)
        XCTAssertEqual(store.load(), [])

        let screen = makeScreen()
        store.save([screen])
        let loaded = store.load()

        XCTAssertEqual(loaded.count, 1)
        XCTAssertEqual(loaded[0].id, screen.id)
        XCTAssertEqual(loaded[0].name, screen.name)
    }

    func test_screenStore_corruptData_returnsEmpty_withoutOverwriting() throws {
        let defaults = try makeDefaults()
        let store = UserDefaultsScreenStore(defaults: defaults)
        store.save([makeScreen()])

        let corruptedBytes = try XCTUnwrap("not valid json at all".data(using: .utf8))
        defaults.set(corruptedBytes, forKey: "screens")

        let loaded = store.load()
        XCTAssertEqual(loaded, [])
        XCTAssertEqual(defaults.data(forKey: "screens"), corruptedBytes)
    }
}
