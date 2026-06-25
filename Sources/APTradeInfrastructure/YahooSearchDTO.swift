import Foundation
import APTradeApplication
import APTradeDomain

struct YahooSearchResponse: Decodable {
    let quotes: [Item]?

    struct Item: Decodable {
        let symbol: String?
        let shortname: String?
        let longname: String?
        let quoteType: String?
    }
}

enum YahooSearchMapper {
    static func assets(from data: Data) throws -> [Asset] {
        let decoded: YahooSearchResponse
        do { decoded = try JSONDecoder().decode(YahooSearchResponse.self, from: data) }
        catch { throw AppError.decoding }
        return (decoded.quotes ?? []).compactMap(asset(from:))
    }

    private static func asset(from item: YahooSearchResponse.Item) -> Asset? {
        guard let symbol = item.symbol, let kind = kind(from: item.quoteType) else { return nil }
        let name = item.shortname ?? item.longname ?? symbol
        return Asset(symbol: symbol, name: name, kind: kind)
    }

    private static func kind(from type: String?) -> AssetKind? {
        switch type?.uppercased() {
        case "EQUITY": return .stock
        case "ETF": return .etf
        case "CRYPTOCURRENCY": return .crypto
        default: return nil   // INDEX, FUTURE, CURRENCY, OPTION, … unsupported
        }
    }
}
