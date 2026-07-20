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
        let sut = BuyAssetUseCase(repository: PriceRepo(price: usd("100")), store: store, serializer: TradeSerializer())
        let result = try await sut(asset: aapl, quantity: Quantity(Decimal(2)))
        XCTAssertEqual(result.position(for: "AAPL")?.quantity, Quantity(Decimal(2)))
        XCTAssertEqual(store.portfolio.cash, usd("99800"))
    }

    func test_buy_propagatesTradeError() async {
        let store = MemoryStore(.starting())
        let sut = BuyAssetUseCase(repository: PriceRepo(price: usd("100000")), store: store, serializer: TradeSerializer())
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
        let sut = SellAssetUseCase(repository: PriceRepo(price: usd("150")), store: store, serializer: TradeSerializer())
        let result = try await sut(symbol: "AAPL", quantity: Quantity(Decimal(2)))
        XCTAssertNil(result.position(for: "AAPL"))
    }

    func test_reset_restoresStartingCash() async {
        let store = MemoryStore(.starting(cash: usd("5")))
        let result = await ResetPortfolioUseCase(store: store, serializer: TradeSerializer())()
        XCTAssertEqual(result.cash, usd("100000"))
        XCTAssertEqual(store.portfolio.cash, usd("100000"))
    }

    // MARK: (fast-follow regression) A manual buy racing a serialized pie contribution
    // through ONE shared TradeSerializer must never interleave — both effects must be
    // present in the final portfolio, never a lost update from one save clobbering the
    // other.

    func test_manualBuyRacesPieContribution_throughSharedSerializer_bothReflectedNoLostUpdate() async throws {
        let usd: @Sendable (String) -> Money = { Money(amount: Decimal(string: $0) ?? 0) }
        let pieSlice = PieSlice(symbol: "PIE", assetKind: .stock, targetWeight: Percentage(value: 100))
        let pie = try Pie(id: "pie-race", name: "Race Pie", slices: [pieSlice], schedule: nil, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(.starting())
        let log = TradeCallLog()
        let gate = TradeGateController()
        let repo = GatedTradeRepo(
            quotes: ["PIE": Quote(symbol: "PIE", price: usd("10"), previousClose: usd("10")),
                     "AAPL": Quote(symbol: "AAPL", price: usd("20"), previousClose: usd("20"))],
            log: log, gate: gate
        )
        let serializer = TradeSerializer()
        let contributeToPie = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo,
                                              serializer: serializer)
        let buy = BuyAssetUseCase(repository: repo, store: portfolioStore, serializer: serializer)

        // The pie contribution's quote fetch is the FIRST call into the repo — it parks
        // on the gate, holding the serializer's lock for as long as it's parked.
        let contributeTask = Task {
            try await contributeToPie(pieId: "pie-race", amount: usd("100"), day: "2025-06-01", now: Date())
        }
        while await log.events.isEmpty { await Task.yield() }

        // The manual buy races in while the contribution still holds the lock.
        let buyTask = Task {
            try await buy(asset: Asset(symbol: "AAPL", name: "AAPL", kind: .stock), quantity: Quantity(Decimal(1)))
        }
        // A bounded real-time window: if the two use cases weren't sharing the
        // serializer, the buy's quote fetch would log during this window, BEFORE the
        // gate opens.
        try await Task.sleep(for: .milliseconds(150))
        let midEvents = await log.events
        XCTAssertEqual(midEvents.count, 1, "the manual buy must not start until the pie contribution fully finishes")

        await gate.release()
        _ = try await contributeTask.value
        _ = try await buyTask.value

        // $100 pie contribution (10 shares of PIE @ $10) + a $20 manual buy (1 share of
        // AAPL) = $120 spent total -- both effects present, no lost update.
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("99880"))
        XCTAssertEqual(portfolioStore.portfolio.position(for: "PIE")?.quantity, Quantity(Decimal(10)))
        XCTAssertEqual(portfolioStore.portfolio.position(for: "AAPL")?.quantity, Quantity(Decimal(1)))
    }
}

/// Actor-guarded append-only event log shared across concurrently-launched Tasks in a
/// single test. Mirrors `ContributeToPieTests`' identical private helper (duplicated
/// rather than shared across test files — Swift's file-private `private` access on
/// those declarations doesn't cross file boundaries within the same target).
private actor TradeCallLog {
    private(set) var events: [String] = []
    func append(_ event: String) { events.append(event) }
}

/// A manually-releasable async gate: `wait()` suspends until `release()` is called.
private actor TradeGateController {
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func release() {
        isOpen = true
        waiters.forEach { $0.resume() }
        waiters.removeAll()
    }
}

/// A `MarketDataRepository` fake whose very FIRST `quote(for:)` call logs itself, then
/// suspends on `gate` before returning — every later call resolves immediately. Lets a
/// test force two concurrently-launched calls (here, a pie contribution and a manual
/// buy) into a genuine overlap window instead of relying on scheduler timing luck.
private actor GatedTradeRepo: MarketDataRepository {
    private var quotes: [String: Quote]
    private let log: TradeCallLog
    private let gate: TradeGateController
    private var firstCallStarted = false

    init(quotes: [String: Quote], log: TradeCallLog, gate: TradeGateController) {
        self.quotes = quotes
        self.log = log
        self.gate = gate
    }

    func quote(for symbol: String) async throws -> Quote {
        let isFirst = !firstCallStarted
        firstCallStarted = true
        await log.append("quote-\(symbol)")
        if isFirst {
            await gate.wait()
        }
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }

    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}
