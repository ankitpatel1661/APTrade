import XCTest
@testable import APTradeInfrastructure

final class AppConfigTests: XCTestCase {
    private func tempFile(_ contents: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString + ".json")
        try contents.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    func test_validKey_isReturned() throws {
        let url = try tempFile(#"{ "finnhubAPIKey": "abc123" }"#)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertEqual(AppConfig.finnhubAPIKey(path: url), "abc123")
    }

    func test_absentFile_returnsNil() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("does-not-exist.json")
        XCTAssertNil(AppConfig.finnhubAPIKey(path: url))
    }

    func test_malformedJSON_returnsNil() throws {
        let url = try tempFile("not json")
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertNil(AppConfig.finnhubAPIKey(path: url))
    }

    func test_emptyOrWhitespaceKey_returnsNil() throws {
        let url = try tempFile(#"{ "finnhubAPIKey": "   " }"#)
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertNil(AppConfig.finnhubAPIKey(path: url))
    }

    // MARK: - saveFinnhubAPIKey (the in-app settings-field write path)

    func test_savedKey_roundTripsThroughTheReadPath() {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString + ".json")
        defer { try? FileManager.default.removeItem(at: url) }

        XCTAssertTrue(AppConfig.saveFinnhubAPIKey("  round-trip-key  ", path: url))
        XCTAssertEqual(AppConfig.finnhubAPIKey(path: url), "round-trip-key")
    }

    func test_save_createsIntermediateDirectories() {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent(".config/aptrade/config.json")
        defer { try? FileManager.default.removeItem(at: url) }

        XCTAssertTrue(AppConfig.saveFinnhubAPIKey("fresh-key", path: url))
        XCTAssertEqual(AppConfig.finnhubAPIKey(path: url), "fresh-key")
    }

    func test_save_preservesUnknownFields() throws {
        let url = try tempFile(#"{ "finnhubAPIKey": "old-key", "someOtherField": "kept" }"#)
        defer { try? FileManager.default.removeItem(at: url) }

        XCTAssertTrue(AppConfig.saveFinnhubAPIKey("new-key", path: url))

        XCTAssertEqual(AppConfig.finnhubAPIKey(path: url), "new-key")
        let text = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(text.contains("someOtherField"), "unknown field dropped: \(text)")
        XCTAssertTrue(text.contains("kept"), "unknown field's value dropped: \(text)")
    }

    func test_savingBlank_removesTheKey() throws {
        let url = try tempFile(#"{ "finnhubAPIKey": "old-key" }"#)
        defer { try? FileManager.default.removeItem(at: url) }

        XCTAssertTrue(AppConfig.saveFinnhubAPIKey("   ", path: url))
        XCTAssertNil(AppConfig.finnhubAPIKey(path: url))
    }
}
