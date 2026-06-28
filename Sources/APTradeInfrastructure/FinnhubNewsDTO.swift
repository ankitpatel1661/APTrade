import Foundation
import APTradeApplication
import APTradeDomain

/// One element of Finnhub's `/news` and `/company-news` JSON arrays. All fields optional —
/// the mapper validates and drops anything unusable.
struct FinnhubArticleDTO: Decodable {
    let category: String?
    let datetime: Double?     // Unix seconds
    let headline: String?
    let id: Int?
    let image: String?
    let related: String?
    let source: String?
    let summary: String?
    let url: String?
}

enum FinnhubNewsMapper {
    static func articles(from data: Data) throws -> [NewsArticle] {
        let decoded: [FinnhubArticleDTO]
        do { decoded = try JSONDecoder().decode([FinnhubArticleDTO].self, from: data) }
        catch { throw AppError.decoding }
        return decoded.compactMap(article(from:))
    }

    /// Skips any article without a non-empty headline or a parseable URL.
    private static func article(from dto: FinnhubArticleDTO) -> NewsArticle? {
        guard let headline = dto.headline, !headline.isEmpty,
              let urlString = dto.url, let url = URL(string: urlString) else { return nil }
        let nonEmpty: (String?) -> String? = { value in
            guard let value, !value.isEmpty else { return nil }
            return value
        }
        return NewsArticle(
            id: dto.id.map(String.init) ?? urlString,
            headline: headline,
            summary: dto.summary ?? "",
            source: dto.source ?? "",
            url: url,
            imageURL: nonEmpty(dto.image).flatMap { URL(string: $0) },
            publishedAt: Date(timeIntervalSince1970: dto.datetime ?? 0),
            category: dto.category,
            relatedSymbol: nonEmpty(dto.related)
        )
    }
}
