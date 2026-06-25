import Foundation

/// One OHLC bar: open, high, low, and close for a single period. Used for candlestick
/// rendering; the close alone drives line/area charts and indicators.
public struct Candle: Equatable, Sendable {
    public let date: Date
    public let open: Money
    public let high: Money
    public let low: Money
    public let close: Money

    public init(date: Date, open: Money, high: Money, low: Money, close: Money) {
        self.date = date
        self.open = open
        self.high = high
        self.low = low
        self.close = close
    }

    /// True when the bar closed at or above its open (an "up" candle).
    public var isUp: Bool { close.amount >= open.amount }

    /// The closing price as a `PricePoint`, for line/area charts and indicators.
    public var pricePoint: PricePoint { PricePoint(date: date, close: close) }
}
