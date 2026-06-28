import XCTest
@testable import APTradeDomain

final class NewsTests: XCTestCase {
    private func sample() -> NewsArticle {
        NewsArticle(
            id: "42", headline: "Apple unveils thing", summary: "A summary.",
            source: "Reuters", url: URL(string: "https://example.com/a")!,
            imageURL: URL(string: "https://example.com/i.png"), publishedAt: Date(timeIntervalSince1970: 1_700_000_000),
            category: "general", relatedSymbol: "AAPL")
    }

    func test_newsArticle_codableRoundTrip() throws {
        let original = sample()
        let data = try JSONEncoder().encode(original)
        let restored = try JSONDecoder().decode(NewsArticle.self, from: data)
        XCTAssertEqual(restored, original)
    }

    func test_newsArticle_id_isStable() {
        XCTAssertEqual(sample().id, "42")
    }

    func test_newsCategory_displayNames() {
        XCTAssertEqual(NewsCategory.general.displayName, "General")
        XCTAssertEqual(NewsCategory.crypto.displayName, "Crypto")
        XCTAssertEqual(NewsCategory.merger.displayName, "Merger")
    }

    func test_newsCategory_allCases() {
        XCTAssertEqual(NewsCategory.allCases, [.general, .crypto, .merger])
    }
}
