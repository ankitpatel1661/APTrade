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
}
