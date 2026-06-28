import XCTest
@testable import APTradeInfrastructure
import APTradeApplication
import APTradeDomain

final class FinnhubNewsMapperTests: XCTestCase {
    // Two valid articles + one malformed (missing headline) — mirrors the real Finnhub schema.
    private let json = """
    [
      {"category":"company","datetime":1700000000,"headline":"Apple rises","id":111,
       "image":"https://e.com/i.png","related":"AAPL","source":"Reuters",
       "summary":"Shares up.","url":"https://e.com/apple"},
      {"category":"general","datetime":1700000500,"headline":"Markets steady","id":222,
       "image":"","related":"","source":"Bloomberg","summary":"","url":"https://e.com/markets"},
      {"category":"general","datetime":1700000900,"headline":"","id":333,
       "image":"","related":"","source":"NoHeadline","summary":"x","url":"https://e.com/skip"}
    ]
    """.data(using: .utf8)!

    func test_articles_decodesAndMapsFields() throws {
        let articles = try FinnhubNewsMapper.articles(from: json)
        XCTAssertEqual(articles.count, 2, "the headline-less article is skipped")
        let first = articles[0]
        XCTAssertEqual(first.id, "111")
        XCTAssertEqual(first.headline, "Apple rises")
        XCTAssertEqual(first.summary, "Shares up.")
        XCTAssertEqual(first.source, "Reuters")
        XCTAssertEqual(first.url, URL(string: "https://e.com/apple"))
        XCTAssertEqual(first.imageURL, URL(string: "https://e.com/i.png"))
        XCTAssertEqual(first.publishedAt, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(first.relatedSymbol, "AAPL")
    }

    func test_articles_emptyImageAndRelated_becomeNil() throws {
        let articles = try FinnhubNewsMapper.articles(from: json)
        XCTAssertNil(articles[1].imageURL)
        XCTAssertNil(articles[1].relatedSymbol)
    }

    func test_articles_malformedJSON_throwsDecoding() {
        let bad = "not json".data(using: .utf8)!
        XCTAssertThrowsError(try FinnhubNewsMapper.articles(from: bad)) { error in
            XCTAssertEqual(error as? AppError, .decoding)
        }
    }

    func test_finnhubValue_mapping() {
        XCTAssertEqual(NewsCategory.general.finnhubValue, "general")
        XCTAssertEqual(NewsCategory.crypto.finnhubValue, "crypto")
        XCTAssertEqual(NewsCategory.merger.finnhubValue, "merger")
    }
}
