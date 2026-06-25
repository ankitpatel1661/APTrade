import Foundation

public struct PricePoint: Equatable, Codable, Sendable {
    public let date: Date
    public let close: Money

    public init(date: Date, close: Money) {
        self.date = date
        self.close = close
    }
}
