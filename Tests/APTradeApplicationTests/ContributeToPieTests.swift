import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class FakePortfolioStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    var saveCallCount = 0
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) {
        self.portfolio = portfolio
        saveCallCount += 1
    }
}

final class ContributeToPieTests: XCTestCase {
    private let sliceA = PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50))
    private let sliceB = PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))

    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }

    private func makeRepo(priceA: String, priceB: String) -> FakeRepo {
        let repo = FakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(priceA), previousClose: usd(priceA))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(priceB), previousClose: usd(priceB))
        return repo
    }

    // MARK: (a) Fresh 50/50 pie, $100 contribution at A=$10, B=$25

    func test_contribute_freshPie_buysProportionalSharesAndTagsPieId() async throws {
        let pie = try Pie(id: "pie-1", name: "Test Pie", slices: [sliceA, sliceB],
                          schedule: nil, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolioStore = FakePortfolioStore(.starting())
        let repo = makeRepo(priceA: "10", priceB: "25")

        let sut = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, serializer: TradeSerializer())
        let outcome = try await sut(pieId: "pie-1", amount: usd("100"), day: "2025-06-01", now: Date())

        guard case let .executed(resultPortfolio, resultPie) = outcome else {
            XCTFail("expected .executed"); return
        }

        XCTAssertEqual(resultPortfolio.position(for: "A")?.quantity, Quantity(Decimal(5)))
        XCTAssertEqual(resultPortfolio.position(for: "B")?.quantity, Quantity(Decimal(2)))
        XCTAssertEqual(resultPortfolio.cash, usd("99900"))

        let pieTransactions = resultPortfolio.transactions.filter { $0.pieId == "pie-1" }
        XCTAssertEqual(pieTransactions.count, 2)
        XCTAssertTrue(pieTransactions.allSatisfy { $0.pieId == "pie-1" })

        XCTAssertEqual(resultPie.quantity(of: "A"), Quantity(Decimal(5)))
        XCTAssertEqual(resultPie.quantity(of: "B"), Quantity(Decimal(2)))

        XCTAssertEqual(resultPie.activity.count, 1)
        XCTAssertEqual(resultPie.activity.first?.kind, .contribution)
        XCTAssertEqual(resultPie.activity.first?.day, "2025-06-01")
        XCTAssertEqual(resultPie.activity.first?.amount, usd("100"))

        // Both stores persisted — exactly one save each (pins single-save semantics,
        // not one save per buy).
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("99900"))
        XCTAssertEqual(portfolioStore.saveCallCount, 1)
        XCTAssertEqual(pieStore.pies.first?.id, "pie-1")
        XCTAssertEqual(pieStore.pies.first?.quantity(of: "A"), Quantity(Decimal(5)))
    }

    // MARK: (b) Insufficient cash — whole contribution skipped, portfolio untouched

    func test_contribute_insufficientCash_skipsWholeContribution() async throws {
        let pie = try Pie(id: "pie-2", name: "Test Pie", slices: [sliceA, sliceB],
                          schedule: nil, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let startingPortfolio = Portfolio.starting(cash: usd("50"))
        let portfolioStore = FakePortfolioStore(startingPortfolio)
        let repo = makeRepo(priceA: "10", priceB: "25")

        let sut = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, serializer: TradeSerializer())
        let outcome = try await sut(pieId: "pie-2", amount: usd("100"), day: "2025-06-01", now: Date())

        guard case let .skippedInsufficientCash(resultPie) = outcome else {
            XCTFail("expected .skippedInsufficientCash"); return
        }

        // Portfolio completely untouched — and never even saved (whole-skip, not
        // save-with-no-changes).
        XCTAssertEqual(portfolioStore.portfolio, startingPortfolio)
        XCTAssertEqual(portfolioStore.portfolio.transactions.count, 0)
        XCTAssertEqual(portfolioStore.saveCallCount, 0)

        // Pie gains a missedInsufficientCash entry, ledger unchanged.
        XCTAssertEqual(resultPie.activity.count, 1)
        XCTAssertEqual(resultPie.activity.first?.kind, .missedInsufficientCash)
        XCTAssertEqual(resultPie.activity.first?.day, "2025-06-01")
        XCTAssertEqual(resultPie.activity.first?.amount, usd("100"))
        XCTAssertEqual(resultPie.quantity(of: "A"), Quantity(Decimal(0)))
        XCTAssertEqual(resultPie.quantity(of: "B"), Quantity(Decimal(0)))

        // Pie saved with the missed entry.
        XCTAssertEqual(pieStore.pies.first?.activity.count, 1)
    }

    // MARK: (c) Drifted ledger routes entire contribution to the underweight slice
    // (Task 2 fixture (b): A=$70, B=$30 at 50/50 targets, $20 contribution -> all to B)

    func test_contribute_driftedLedger_routesToUnderweightSlice() async throws {
        // A: 7 shares @ $10 = $70. B: 3 shares @ $10 = $30.
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(Decimal(7))),
            PieLedgerEntry(symbol: "B", quantity: Quantity(Decimal(3)))
        ]
        let pie = try Pie(id: "pie-3", name: "Drifted Pie", slices: [sliceA, sliceB],
                          schedule: nil, createdDay: "2025-01-01", ledger: ledger)
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolioStore = FakePortfolioStore(.starting())
        let repo = makeRepo(priceA: "10", priceB: "10")

        let sut = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, serializer: TradeSerializer())
        let outcome = try await sut(pieId: "pie-3", amount: usd("20"), day: "2025-06-01", now: Date())

        guard case let .executed(resultPortfolio, resultPie) = outcome else {
            XCTFail("expected .executed"); return
        }

        // All $20 routed to underweight B (2 shares @ $10); nothing bought for A.
        XCTAssertNil(resultPortfolio.position(for: "A"))
        XCTAssertEqual(resultPortfolio.position(for: "B")?.quantity, Quantity(Decimal(2)))
        XCTAssertEqual(resultPortfolio.cash, usd("99980"))

        XCTAssertEqual(resultPie.quantity(of: "A"), Quantity(Decimal(7)))
        XCTAssertEqual(resultPie.quantity(of: "B"), Quantity(Decimal(5)))
    }

    // MARK: Missing quote propagates as a thrown error

    func test_contribute_missingQuoteForSliceSymbol_throws() async throws {
        let pie = try Pie(id: "pie-4", name: "Test Pie", slices: [sliceA, sliceB],
                          schedule: nil, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolioStore = FakePortfolioStore(.starting())
        let repo = FakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd("10"), previousClose: usd("10"))
        // "B" quote deliberately missing.

        let sut = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, serializer: TradeSerializer())

        do {
            _ = try await sut(pieId: "pie-4", amount: usd("100"), day: "2025-06-01", now: Date())
            XCTFail("expected throw")
        } catch {
            XCTAssertEqual(error as? AppError, .notFound)
        }

        // Neither store mutated on failure.
        XCTAssertEqual(portfolioStore.portfolio, .starting())
        XCTAssertEqual(pieStore.pies.first?.activity.count, 0)
    }

    // MARK: (F1 regression) Two concurrent ContributeToPie calls through ONE shared
    // TradeSerializer must never interleave their load-modify-save sequences — the
    // final portfolio/pie state must reflect BOTH contributions exactly, never a lost
    // update from one call's save clobbering the other's.

    func test_concurrentContributions_throughSharedSerializer_bothReflectedNoLostUpdate() async throws {
        // A local (non-instance-capturing) helper, so the `Task { }` closures below stay
        // `@Sendable`-clean without capturing the non-Sendable `XCTestCase` via `self`.
        let usd: @Sendable (String) -> Money = { Money(amount: Decimal(string: $0) ?? 0) }
        let pie = try Pie(id: "pie-race", name: "Race Pie", slices: [sliceA, sliceB],
                          schedule: nil, createdDay: "2025-01-01")
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolioStore = FakePortfolioStore(.starting())
        let log = CallLog()
        let gate = GateController()
        let repo = GatedContributeRepo(
            quotes: ["A": Quote(symbol: "A", price: usd("10"), previousClose: usd("10")),
                     "B": Quote(symbol: "B", price: usd("10"), previousClose: usd("10"))],
            log: log, gate: gate
        )
        let serializer = TradeSerializer()
        let sut = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo, serializer: serializer)

        // Launch the first contribution — its very first quote fetch parks on `gate`,
        // holding the serializer's lock for the whole time it's parked.
        let firstTask = Task {
            try await sut(pieId: "pie-race", amount: usd("100"), day: "2025-06-01", now: Date())
        }
        // Wait for the first call to actually be inside its gated quote fetch (real
        // suspension, not just "launched") before racing the second call against it.
        while await log.events.isEmpty { await Task.yield() }

        let secondTask = Task {
            try await sut(pieId: "pie-race", amount: usd("50"), day: "2025-06-01", now: Date())
        }
        // A bounded real-time window: if the serializer failed to lock, the second
        // call's quote fetch would log during this window, BEFORE the gate opens.
        try await Task.sleep(for: .milliseconds(150))
        let midEvents = await log.events
        XCTAssertEqual(midEvents.count, 1, "the second contribution must not start until the first fully finishes")

        await gate.release()
        let firstOutcome = try await firstTask.value
        let secondOutcome = try await secondTask.value

        guard case .executed = firstOutcome, case .executed = secondOutcome else {
            XCTFail("both contributions should execute (ample cash for both)"); return
        }

        // Both $100 and $50 must be reflected — $150 total spent, no lost update.
        XCTAssertEqual(portfolioStore.portfolio.cash, usd("99850"))
        XCTAssertEqual(pieStore.pies.first?.activity.filter { $0.kind == .contribution }.count, 2)
        let totalBought = (pieStore.pies.first?.quantity(of: "A").amount ?? 0)
            + (pieStore.pies.first?.quantity(of: "B").amount ?? 0)
        XCTAssertEqual(totalBought, Decimal(15), "5 (A) + 5 (B) from the $100 call, plus 2.5 + 2.5 from the $50 call")
    }
}

/// Actor-guarded append-only event log shared across concurrently-launched Tasks in a
/// single test.
private actor CallLog {
    private(set) var events: [String] = []
    func append(_ event: String) { events.append(event) }
}

/// A manually-releasable async gate: `wait()` suspends until `release()` is called.
private actor GateController {
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
/// suspends on `gate` before returning — every later call resolves immediately. This
/// creates exactly one controllable, mid-load-modify-save suspension point that a test
/// can use to force two concurrently-launched calls into a genuine overlap window,
/// rather than relying on scheduler timing luck.
private actor GatedContributeRepo: MarketDataRepository {
    private var quotes: [String: Quote]
    private let log: CallLog
    private let gate: GateController
    private var firstCallStarted = false

    init(quotes: [String: Quote], log: CallLog, gate: GateController) {
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
