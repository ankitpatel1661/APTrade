import Foundation

/// A single news item, normalized from whatever the news source returns. Pure value type.
public struct NewsArticle: Identifiable, Equatable, Codable, Sendable {
    public let id: String
    public let headline: String
    public let summary: String
    public let source: String
    public let url: URL
    public let imageURL: URL?
    public let publishedAt: Date
    public let category: String?
    public let relatedSymbol: String?

    public init(id: String, headline: String, summary: String, source: String,
                url: URL, imageURL: URL?, publishedAt: Date,
                category: String?, relatedSymbol: String?) {
        self.id = id
        self.headline = headline
        self.summary = summary
        self.source = source
        self.url = url
        self.imageURL = imageURL
        self.publishedAt = publishedAt
        self.category = category
        self.relatedSymbol = relatedSymbol
    }
}
