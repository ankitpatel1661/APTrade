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
        Quote(symbol: symbol, price: Money(amount: 200), previousClose: Money(amount: 200))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

@MainActor
final class TradeViewModelTests: XCTestCase {
    private let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)

    private func make(_ store: MemoryStore) -> TradeViewModel {
        let repo = FixedRepo()
        return TradeViewModel(
            asset: aapl,
            buy: BuyAssetUseCase(repository: repo, store: store),
            sell: SellAssetUseCase(repository: repo, store: store),
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo)
        )
    }

    func test_buy_succeedsAndCompletes() async {
        let store = MemoryStore(.starting())
        let vm = make(store)
        await vm.load()
        vm.side = .buy
        vm.quantityText = "2"
        await vm.submit()
        XCTAssertTrue(vm.didComplete)
        XCTAssertNil(vm.errorMessage)
        XCTAssertEqual(store.portfolio.position(for: "AAPL")?.quantity, Quantity(Decimal(2)))
    }

    func test_buy_insufficientFunds_setsError() async {
        let store = MemoryStore(.starting(cash: Money(amount: 10)))
        let vm = make(store)
        await vm.load()
        vm.side = .buy
        vm.quantityText = "2"   // 2 * 200 = 400 > 10
        await vm.submit()
        XCTAssertFalse(vm.didComplete)
        XCTAssertEqual(vm.errorMessage, "Not enough cash for this order.")
    }

    func test_setMax_onSell_usesSharesOwned() async {
        let start = try! Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(3)), at: Money(amount: 100))
        let store = MemoryStore(start)
        let vm = make(store)
        await vm.load()
        vm.side = .sell
        vm.setMax()
        XCTAssertEqual(vm.quantityText, "3")
    }
}
