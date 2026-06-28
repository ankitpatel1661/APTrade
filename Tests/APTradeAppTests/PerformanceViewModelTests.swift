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

private final class RisingRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 110), previousClose: Money(amount: 100))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] {
        (0..<10).map { i in
            PricePoint(date: Date(timeIntervalSince1970: TimeInterval(i) * 86_400),
                       close: Money(amount: Decimal(100 + i)))
        }
    }
}

@MainActor
final class PerformanceViewModelTests: XCTestCase {
    private func vm(_ portfolio: Portfolio) -> PerformanceViewModel {
        PerformanceViewModel(compute: ComputePerformanceMetricsUseCase(
            repository: RisingRepo(), store: MemoryStore(portfolio)))
    }

    func test_load_withHoldings_entersLoaded() async {
        let aapl = Asset(symbol: "AAPL", name: "Apple", kind: .stock)
        let portfolio = try! Portfolio.starting()
            .buying(aapl, quantity: Quantity(Decimal(10)), at: Money(amount: 100),
                    on: Date(timeIntervalSince1970: 0))
        let model = vm(portfolio)
        await model.load()
        guard case .loaded(let report) = model.state else { return XCTFail("expected .loaded") }
        XCTAssertFalse(report.isEmpty)
    }

    func test_load_emptyPortfolio_entersEmpty() async {
        let model = vm(.starting())
        await model.load()
        XCTAssertEqual(model.state, .empty)
    }

    func test_defaultBenchmarkIsSPY() {
        XCTAssertEqual(vm(.starting()).benchmark, "SPY")
    }
}
