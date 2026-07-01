import Shared

@main
struct SkeletonHarness {
    static func main() async {
        let repository = YahooMarketDataRepository()
        let useCase = FetchMarketQuotes(repository: repository)
        do {
            let quotes = try await useCase.execute(symbols: ["AAPL", "MSFT", "BTC-USD"])
            print("APTrade KMP — live quotes from Yahoo via shared Kotlin core:")
            for quote in quotes {
                let arrow = quote.changePercent >= 0 ? "▲" : "▼"
                print("  \(quote.symbol)\t\(quote.price.formatted)\t\(arrow) \(quote.changePercent)%")
            }
        } catch {
            print("error: \(error)")
        }
    }
}
