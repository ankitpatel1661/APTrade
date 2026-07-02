/// A market-news category. Conceptual — the news-source query mapping lives in
/// infrastructure.
public enum NewsCategory: String, CaseIterable, Sendable {
    case general, crypto, merger

    public var displayName: String {
        switch self {
        case .general: return "General"
        case .crypto: return "Crypto"
        case .merger: return "Merger"
        }
    }
}
