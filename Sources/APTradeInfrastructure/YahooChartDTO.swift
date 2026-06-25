import Foundation

struct YahooChartResponse: Decodable {
    let chart: Chart

    struct Chart: Decodable {
        let result: [ResultItem]?
        let error: YahooError?
    }
    struct YahooError: Decodable { let code: String?; let description: String? }

    struct ResultItem: Decodable {
        let meta: Meta
        let timestamp: [Int]?
        let indicators: Indicators
    }
    struct Meta: Decodable {
        let symbol: String
        let currency: String?
        let instrumentType: String?
        let regularMarketPrice: Double?
        let chartPreviousClose: Double?
        let longName: String?
        let shortName: String?
    }
    struct Indicators: Decodable { let quote: [QuoteBlock]? }
    struct QuoteBlock: Decodable {
        let open: [Double?]?
        let high: [Double?]?
        let low: [Double?]?
        let close: [Double?]?
    }
}
