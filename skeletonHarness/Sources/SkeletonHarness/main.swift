import Shared

@main
struct SkeletonHarness {
    static func main() async {
        let useCase = FetchMarketQuotes(repository: StubQuoteRepository())
        do {
            let quotes = try await useCase.execute(symbols: ["AAPL", "MSFT", "BTC"])
            print("APTrade KMP walking skeleton — quotes from shared Kotlin core:")
            // `for case let ... as Quote` tolerates whichever element typing
            // Kotlin/Native exposes (typed `[Quote]` or `[Any]`).
            for case let quote as Quote in quotes {
                let arrow = quote.changePercent >= 0 ? "▲" : "▼"
                print("  \(quote.symbol)\t\(quote.price.formatted)\t\(arrow) \(quote.changePercent)%")
            }
        } catch {
            print("error: \(error)")
        }
    }
}
