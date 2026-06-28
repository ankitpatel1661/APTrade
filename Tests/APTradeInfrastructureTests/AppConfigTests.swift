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
}
