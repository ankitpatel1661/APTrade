import Foundation
import APTradeApplication
import APTradeDomain

enum YahooMapper {
    private static func decimal(_ value: Double) -> Decimal {
        Decimal(string: String(value)) ?? Decimal(value)
    }

    private static func firstResult(from data: Data) throws -> YahooChartResponse.ResultItem {
        let decoded: YahooChartResponse
        do { decoded = try JSONDecoder().decode(YahooChartResponse.self, from: data) }
        catch { throw AppError.decoding }
        guard let item = decoded.chart.result?.first else { throw AppError.notFound }
        return item
    }

    static func quote(from data: Data) throws -> Quote {
        let item = try firstResult(from: data)
        guard let price = item.meta.regularMarketPrice,
              let prev = item.meta.chartPreviousClose else { throw AppError.decoding }
        let currency = item.meta.currency ?? "USD"
        return Quote(
            symbol: item.meta.symbol,
            price: Money(amount: decimal(price), currencyCode: currency),
            previousClose: Money(amount: decimal(prev), currencyCode: currency)
        )
    }

    static func asset(from data: Data) throws -> Asset {
        let meta = try firstResult(from: data).meta
        let name = meta.longName ?? meta.shortName ?? meta.symbol
        return Asset(symbol: meta.symbol, name: name, kind: kind(of: meta))
    }

    private static func kind(of meta: YahooChartResponse.Meta) -> AssetKind {
        switch meta.instrumentType?.uppercased() {
        case "ETF": return .etf
        case "CRYPTOCURRENCY": return .crypto
        case "EQUITY": return .stock
        default:
            return meta.symbol.uppercased().hasSuffix("-USD") ? .crypto : .stock
        }
    }

    static func history(from data: Data) throws -> [PricePoint] {
        let item = try firstResult(from: data)
        guard let stamps = item.timestamp,
              let closes = item.indicators.quote?.first?.close else { return [] }
        let currency = item.meta.currency ?? "USD"
        var points: [PricePoint] = []
        for (i, stamp) in stamps.enumerated() where i < closes.count {
            guard let close = closes[i] else { continue }
            points.append(PricePoint(
                date: Date(timeIntervalSince1970: TimeInterval(stamp)),
                close: Money(amount: decimal(close), currencyCode: currency)
            ))
        }
        return points
    }

    static func candles(from data: Data) throws -> [Candle] {
        let item = try firstResult(from: data)
        guard let stamps = item.timestamp,
              let block = item.indicators.quote?.first,
              let closes = block.close else { return [] }
        let currency = item.meta.currency ?? "USD"
        func money(_ value: Double) -> Money { Money(amount: decimal(value), currencyCode: currency) }

        var candles: [Candle] = []
        for (i, stamp) in stamps.enumerated() where i < closes.count {
            guard let close = closes[i] else { continue }
            // Fall back to close for any missing OHLC field so the bar still renders.
            let open = block.open?[safe: i].flatMap { $0 } ?? close
            let high = block.high?[safe: i].flatMap { $0 } ?? max(open, close)
            let low = block.low?[safe: i].flatMap { $0 } ?? min(open, close)
            let volume = block.volume?[safe: i].flatMap { $0 } ?? 0
            candles.append(Candle(
                date: Date(timeIntervalSince1970: TimeInterval(stamp)),
                open: money(open), high: money(high), low: money(low), close: money(close),
                volume: volume
            ))
        }
        return candles
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
