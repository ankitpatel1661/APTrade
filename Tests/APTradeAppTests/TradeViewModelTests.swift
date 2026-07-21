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

/// Fixed $7 price — chosen so `cash / price` lands on a repeating decimal whose 5th
/// digit forces an UP rounding at 4dp (1/7 = 0.1428571… → half-to-even rounds to
/// 0.1429), the exact shape that exposed the max-buy dead end fixed alongside setMax's
/// sell-side bug.
private final class SevenDollarRepo: MarketDataRepository, @unchecked Sendable {
    func quote(for symbol: String) async throws -> Quote {
        Quote(symbol: symbol, price: Money(amount: 7), previousClose: Money(amount: 7))
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

final class TradeFakeFillNotifier: OrderFillNotifier, @unchecked Sendable {
    var fills: [(side: TradeSide, symbol: String)] = []
    func notifyFill(side: TradeSide, symbol: String, quantity: Quantity, amount: Money) async {
        fills.append((side, symbol))
    }
}

final class TradeFakeSettingsStore: SettingsStore, @unchecked Sendable {
    var settings: AppSettings
    init(_ settings: AppSettings = .default) { self.settings = settings }
    func load() -> AppSettings { settings }
    func save(_ s: AppSettings) { settings = s }
}

@MainActor
final class TradeViewModelTests: XCTestCase {
    private let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)

    private func make(_ store: MemoryStore,
                      notifier: OrderFillNotifier = TradeFakeFillNotifier(),
                      settings: SettingsStore = TradeFakeSettingsStore()) -> TradeViewModel {
        let repo = FixedRepo()
        return TradeViewModel(
            asset: aapl,
            buy: BuyAssetUseCase(repository: repo, store: store, serializer: TradeSerializer()),
            sell: SellAssetUseCase(repository: repo, store: store, serializer: TradeSerializer()),
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            notifyOrderFill: NotifyOrderFillUseCase(notifier: notifier, settings: settings)
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

    /// Regression for the max-sell dead end: `sharesOwned.formatted` rounds to 4dp
    /// half-to-even, which can round a DRIP-accrued fractional holding UP past what's
    /// actually owned — `canSubmit`'s `quantity.amount <= sharesOwned.amount` then fails
    /// and Sell is permanently disabled for that position. `setMax()` must render the
    /// EXACT held `Decimal`, not the rounded display string.
    func test_setMax_onSell_withFractionalDripHolding_usesExactAmount_andCanSubmit() async {
        let fractional = Quantity(Decimal(string: "0.12345678")!)
        let start = try! Portfolio.starting().buying(aapl, quantity: fractional, at: Money(amount: 100))
        let store = MemoryStore(start)
        let vm = make(store)
        await vm.load()
        vm.side = .sell
        vm.setMax()

        XCTAssertEqual(vm.quantityText, "0.12345678")
        XCTAssertTrue(vm.canSubmit, "max-sell on a fractional holding must be submittable")

        await vm.submit()

        XCTAssertTrue(vm.didComplete)
        XCTAssertNil(vm.errorMessage)
        XCTAssertEqual(store.portfolio.position(for: "AAPL")?.quantity.amount ?? 0, 0,
                        "the exact holding was sold, leaving no residual position")
    }

    /// Symmetric regression on the buy arm: rounding the max-affordable quantity UP (the
    /// old `.formatted`-based behavior) can push `quantity * price` a fraction of a cent
    /// past `availableCash`, so a max-buy could itself fail with "not enough cash" the
    /// moment it's submitted. Rounding DOWN to 4dp must keep the estimated cost within
    /// the cash actually available.
    func test_setMax_onBuy_roundsDownTo4dp_soEstimatedCostNeverExceedsCash() async {
        let store = MemoryStore(.starting(cash: Money(amount: 1)))
        let repo = SevenDollarRepo()
        let vm = TradeViewModel(
            asset: aapl,
            buy: BuyAssetUseCase(repository: repo, store: store, serializer: TradeSerializer()),
            sell: SellAssetUseCase(repository: repo, store: store, serializer: TradeSerializer()),
            fetchPortfolio: FetchPortfolioUseCase(store: store),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            notifyOrderFill: NotifyOrderFillUseCase(notifier: TradeFakeFillNotifier(), settings: TradeFakeSettingsStore())
        )
        await vm.load()
        vm.side = .buy
        vm.setMax()

        XCTAssertEqual(vm.quantityText, "0.1428", "1/7 rounded DOWN to 4dp, not the half-to-even 0.1429")
        XCTAssertTrue(vm.canSubmit, "rounding down must never let the estimated cost exceed cash")
    }

    func test_buy_notifiesFill_whenOrderFillsEnabled() async {
        let store = MemoryStore(.starting())
        let notifier = TradeFakeFillNotifier()
        let vm = make(store, notifier: notifier, settings: TradeFakeSettingsStore(.default))
        await vm.load()
        vm.side = .buy
        vm.quantityText = "2"
        await vm.submit()
        XCTAssertTrue(vm.didComplete)
        XCTAssertEqual(notifier.fills.count, 1)
        XCTAssertEqual(notifier.fills.first?.side, .buy)
    }

    func test_buy_suppressesFillNotification_whenOrderFillsDisabled() async {
        let store = MemoryStore(.starting())
        let notifier = TradeFakeFillNotifier()
        var settings = AppSettings.default
        settings.orderFills = false
        let vm = make(store, notifier: notifier, settings: TradeFakeSettingsStore(settings))
        await vm.load()
        vm.side = .buy
        vm.quantityText = "2"
        await vm.submit()
        XCTAssertTrue(vm.didComplete, "the trade still executes")
        XCTAssertTrue(notifier.fills.isEmpty, "no fill notification when disabled")
    }

    func test_failedOrder_doesNotNotify() async {
        let store = MemoryStore(.starting(cash: Money(amount: 10)))
        let notifier = TradeFakeFillNotifier()
        let vm = make(store, notifier: notifier)
        await vm.load()
        vm.side = .buy
        vm.quantityText = "2"   // 400 > 10, fails
        await vm.submit()
        XCTAssertFalse(vm.didComplete)
        XCTAssertTrue(notifier.fills.isEmpty)
    }
}
