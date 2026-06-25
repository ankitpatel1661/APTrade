public enum AssetKind: String, Codable, Sendable {
    case stock, etf, crypto
}

public struct Asset: Identifiable, Equatable, Hashable, Codable, Sendable {
    public let symbol: String
    public let name: String
    public let kind: AssetKind

    public var id: String { symbol }

    public init(symbol: String, name: String, kind: AssetKind) {
        self.symbol = symbol
        self.name = name
        self.kind = kind
    }
}
