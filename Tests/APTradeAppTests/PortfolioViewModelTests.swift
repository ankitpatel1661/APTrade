import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

private final class FixedRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 150), previousClose: Money(amount: 140))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

@MainActor
final class PortfolioViewModelTests: XCTestCase {
    func test_onAppear_loadsHoldingsAndValues() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let start = try! Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(2)), at: Money(amount: 100))
        let store = MemoryStore(start)
        let repo = FixedRepo()
        let vm = PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            resetPortfolio: ResetPortfolioUseCase(store: store)
        )
        await vm.onAppear()
        XCTAssertEqual(vm.holdings.count, 1)
        XCTAssertEqual(vm.valuation.holdingsValue, Money(amount: 300))   // 150*2
    }

    func test_reset_clearsHoldings() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let start = try! Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(1)), at: Money(amount: 100))
        let store = MemoryStore(start)
        let vm = PortfolioViewModel(
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: FixedRepo()),
            resetPortfolio: ResetPortfolioUseCase(store: store)
        )
        await vm.onAppear()
        vm.reset()
        XCTAssertTrue(vm.holdings.isEmpty)
        XCTAssertEqual(vm.portfolio.cash, Money(amount: 100_000))
    }
}
