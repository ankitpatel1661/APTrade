import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class PriceRepo: MarketDataRepository, @unchecked Sendable {
    var price: Money
    init(price: Money) { self.price = price }
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: price, previousClose: price)
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

final class PortfolioUseCasesTests: XCTestCase {
    private let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s)!) }

    func test_buy_persistsUpdatedPortfolio() async throws {
        let store = MemoryStore(.starting())
        let sut = BuyAssetUseCase(repository: PriceRepo(price: usd("100")), store: store)
        let result = try await sut(asset: aapl, quantity: Quantity(Decimal(2)))
        XCTAssertEqual(result.position(for: "AAPL")?.quantity, Quantity(Decimal(2)))
        XCTAssertEqual(store.portfolio.cash, usd("99800"))
    }

    func test_buy_propagatesTradeError() async {
        let store = MemoryStore(.starting())
        let sut = BuyAssetUseCase(repository: PriceRepo(price: usd("100000")), store: store)
        do {
            _ = try await sut(asset: aapl, quantity: Quantity(Decimal(2)))
            XCTFail("expected throw")
        } catch {
            XCTAssertEqual(error as? TradeError, .insufficientFunds)
        }
    }

    func test_sell_persistsUpdatedPortfolio() async throws {
        let start = try Portfolio.starting().buying(aapl, quantity: Quantity(Decimal(2)), at: usd("100"))
        let store = MemoryStore(start)
        let sut = SellAssetUseCase(repository: PriceRepo(price: usd("150")), store: store)
        let result = try await sut(symbol: "AAPL", quantity: Quantity(Decimal(2)))
        XCTAssertNil(result.position(for: "AAPL"))
    }

    func test_reset_restoresStartingCash() {
        let store = MemoryStore(.starting(cash: usd("5")))
        let result = ResetPortfolioUseCase(store: store)()
        XCTAssertEqual(result.cash, usd("100000"))
        XCTAssertEqual(store.portfolio.cash, usd("100000"))
    }
}
