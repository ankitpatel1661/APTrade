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

final class RebalancePieTests: XCTestCase {
    private let sliceA = PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50))
    private let sliceB = PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))

    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }

    private func makeRepo(priceA: String, priceB: String) -> FakeRepo {
        let repo = FakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(priceA), previousClose: usd(priceA))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(priceB), previousClose: usd(priceB))
        return repo
    }

    // MARK: (a) Preview matches PieMath.rebalancePlan and leaves stores untouched.

    func test_preview_driftedPie_matchesRebalancePlanAndTouchesNothing() async throws {
        // A: 7 @ $10 = $70. B: 3 @ $10 = $30. 50/50 targets -> sell A $20 / buy B $20.
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(Decimal(7))),
            PieLedgerEntry(symbol: "B", quantity: Quantity(Decimal(3)))
        ]
        let pie = try Pie(id: "pie-1", name: "Test Pie", slices: [sliceA, sliceB],
                          schedule: nil, createdDay: "2025-01-01", ledger: ledger)
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let portfolio = try Portfolio.starting()
            .buying(Asset(symbol: "A", name: "A", kind: .stock), quantity: Quantity(Decimal(7)), at: usd("10"))
            .buying(Asset(symbol: "B", name: "B", kind: .stock), quantity: Quantity(Decimal(3)), at: usd("10"))
        let portfolioStore = FakePortfolioStore(portfolio)
        let repo = makeRepo(priceA: "10", priceB: "10")

        let sut = RebalancePie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo)
        let orders = try await sut.preview(pieId: "pie-1")

        let expected = PieMath.rebalancePlan(
            currentValues: ["A": usd("70"), "B": usd("30")],
            targets: [sliceA, sliceB]
        )
        XCTAssertEqual(orders, expected)
        XCTAssertEqual(orders.count, 2)
        XCTAssertTrue(orders.contains(RebalanceOrder(symbol: "A", side: .sell, amount: usd("20"))))
        XCTAssertTrue(orders.contains(RebalanceOrder(symbol: "B", side: .buy, amount: usd("20"))))

        // Nothing persisted.
        XCTAssertEqual(portfolioStore.saveCallCount, 0)
        XCTAssertEqual(pieStore.pies, [pie])
    }

    // MARK: (b) Execute sells A $20 / buys B $20 — cash unchanged, ledger updated, transactions tagged.

    func test_execute_driftedPie_sellsAndBuysNetZeroCash() async throws {
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(Decimal(7))),
            PieLedgerEntry(symbol: "B", quantity: Quantity(Decimal(3)))
        ]
        let pie = try Pie(id: "pie-2", name: "Test Pie", slices: [sliceA, sliceB],
                          schedule: nil, createdDay: "2025-01-01", ledger: ledger)
        let pieStore = FakePieStore()
        pieStore.pies = [pie]
        let startingPortfolio = try Portfolio.starting()
            .buying(Asset(symbol: "A", name: "A", kind: .stock), quantity: Quantity(Decimal(7)), at: usd("10"))
            .buying(Asset(symbol: "B", name: "B", kind: .stock), quantity: Quantity(Decimal(3)), at: usd("10"))
        let startingCash = startingPortfolio.cash
        let portfolioStore = FakePortfolioStore(startingPortfolio)
        let repo = makeRepo(priceA: "10", priceB: "10")

        let sut = RebalancePie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo)
        let (resultPortfolio, resultPie) = try await sut.execute(pieId: "pie-2", day: "2025-06-01", now: Date())

        // Cash unchanged within a cent (sold $20 of A, bought $20 of B).
        XCTAssertEqual(resultPortfolio.cash.amount, startingCash.amount, accuracy: 0.01)

        XCTAssertEqual(resultPortfolio.position(for: "A")?.quantity, Quantity(Decimal(5)))
        XCTAssertEqual(resultPortfolio.position(for: "B")?.quantity, Quantity(Decimal(5)))

        XCTAssertEqual(resultPie.quantity(of: "A"), Quantity(Decimal(5)))
        XCTAssertEqual(resultPie.quantity(of: "B"), Quantity(Decimal(5)))

        let pieTransactions = resultPortfolio.transactions.filter { $0.pieId == "pie-2" }
        XCTAssertEqual(pieTransactions.count, 2)
        XCTAssertTrue(pieTransactions.contains { $0.symbol == "A" && $0.side == .sell })
        XCTAssertTrue(pieTransactions.contains { $0.symbol == "B" && $0.side == .buy })

        XCTAssertTrue(resultPie.activity.contains { $0.kind == .rebalance && $0.day == "2025-06-01" })

        // Persisted.
        XCTAssertEqual(portfolioStore.saveCallCount, 1)
        XCTAssertEqual(pieStore.pies.first?.id, "pie-2")
    }

    // MARK: (c) Sell-before-buy: buys exceed starting cash but fit after sells free proceeds.

    func test_execute_sellsBeforeBuys_soBuyFitsEvenWithoutStartingCash() async throws {
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(Decimal(7))),
            PieLedgerEntry(symbol: "B", quantity: Quantity(Decimal(3)))
        ]
        let pie = try Pie(id: "pie-3", name: "Test Pie", slices: [sliceA, sliceB],
                          schedule: nil, createdDay: "2025-01-01", ledger: ledger)
        let pieStore = FakePieStore()
        pieStore.pies = [pie]

        // Zero starting cash — the $20 buy of B can only succeed because the $20 sell of
        // A is applied first, freeing exactly enough cash.
        var noCashPortfolio = Portfolio.starting(cash: usd("0"))
        noCashPortfolio = Portfolio(
            cash: noCashPortfolio.cash,
            positions: [
                Position(asset: Asset(symbol: "A", name: "A", kind: .stock), quantity: Quantity(Decimal(7)),
                         averageCost: usd("10"), realizedPnL: usd("0")),
                Position(asset: Asset(symbol: "B", name: "B", kind: .stock), quantity: Quantity(Decimal(3)),
                         averageCost: usd("10"), realizedPnL: usd("0"))
            ],
            transactions: []
        )
        let portfolioStore = FakePortfolioStore(noCashPortfolio)
        let repo = makeRepo(priceA: "10", priceB: "10")

        let sut = RebalancePie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo)
        let (resultPortfolio, _) = try await sut.execute(pieId: "pie-3", day: "2025-06-01", now: Date())

        XCTAssertEqual(resultPortfolio.cash.amount, Decimal(0), accuracy: 0.01)
        XCTAssertEqual(resultPortfolio.position(for: "B")?.quantity, Quantity(Decimal(5)))
    }

    // MARK: Missing pie throws.

    func test_preview_unknownPie_throwsNotFound() async throws {
        let pieStore = FakePieStore()
        let portfolioStore = FakePortfolioStore(.starting())
        let repo = FakeRepo()

        let sut = RebalancePie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo)
        do {
            _ = try await sut.preview(pieId: "missing")
            XCTFail("expected throw")
        } catch {
            XCTAssertEqual(error as? AppError, .notFound)
        }
    }
}

// MARK: - ReconcilePieLedgers

final class ReconcilePieLedgersTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private let sliceA = PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 100))

    // MARK: (d) portfolio holds 3 A; pie1 ledger 4, pie2 ledger 1 -> pie1 clamps to 2
    // (largest first), pie2 keeps 1; only pie1 gains manualAdjustment.

    func test_reconcile_largestLedgerClampsFirst() throws {
        let pie1 = try Pie(id: "pie1", name: "Pie 1", slices: [sliceA], schedule: nil,
                          createdDay: "2025-01-01",
                          ledger: [PieLedgerEntry(symbol: "A", quantity: Quantity(Decimal(4)))])
        let pie2 = try Pie(id: "pie2", name: "Pie 2", slices: [sliceA], schedule: nil,
                          createdDay: "2025-01-01",
                          ledger: [PieLedgerEntry(symbol: "A", quantity: Quantity(Decimal(1)))])
        let pieStore = FakePieStore()
        pieStore.pies = [pie1, pie2]

        let portfolio = Portfolio(
            cash: usd("100"),
            positions: [Position(asset: Asset(symbol: "A", name: "A", kind: .stock),
                                 quantity: Quantity(Decimal(3)), averageCost: usd("10"), realizedPnL: usd("0"))],
            transactions: []
        )
        let portfolioStore = StubPortfolioStore(portfolio)

        // Fixed clock, injected — proves the activity day is stamped from `now`/`calendar`,
        // not the wall clock, so this assertion is deterministic regardless of when the
        // test suite actually runs.
        let fixedDay = "2025-03-15"
        let fixedNow = try XCTUnwrap(PieSchedule.date(fromDay: fixedDay, calendar: MarketCalendar()))

        let sut = ReconcilePieLedgers(pieStore: pieStore, portfolioStore: portfolioStore, now: { fixedNow })
        let result = sut()

        let resultPie1 = result.first { $0.id == "pie1" }
        let resultPie2 = result.first { $0.id == "pie2" }

        XCTAssertEqual(resultPie1?.quantity(of: "A"), Quantity(Decimal(2)))
        XCTAssertEqual(resultPie2?.quantity(of: "A"), Quantity(Decimal(1)))

        XCTAssertTrue(resultPie1?.activity.contains { $0.kind == .manualAdjustment } ?? false)
        XCTAssertFalse(resultPie2?.activity.contains { $0.kind == .manualAdjustment } ?? true)
        XCTAssertEqual(resultPie1?.activity.first { $0.kind == .manualAdjustment }?.day, fixedDay)

        // Persisted.
        XCTAssertEqual(pieStore.pies.first { $0.id == "pie1" }?.quantity(of: "A"), Quantity(Decimal(2)))
    }

    // MARK: (e) No over-claim -> no clamp, no activity.

    func test_reconcile_noOverClaim_noActivity() throws {
        let pie1 = try Pie(id: "pie1", name: "Pie 1", slices: [sliceA], schedule: nil,
                          createdDay: "2025-01-01",
                          ledger: [PieLedgerEntry(symbol: "A", quantity: Quantity(Decimal(2)))])
        let pieStore = FakePieStore()
        pieStore.pies = [pie1]

        let portfolio = Portfolio(
            cash: usd("100"),
            positions: [Position(asset: Asset(symbol: "A", name: "A", kind: .stock),
                                 quantity: Quantity(Decimal(3)), averageCost: usd("10"), realizedPnL: usd("0"))],
            transactions: []
        )
        let portfolioStore = StubPortfolioStore(portfolio)

        let sut = ReconcilePieLedgers(pieStore: pieStore, portfolioStore: portfolioStore)
        let result = sut()

        XCTAssertEqual(result.first?.quantity(of: "A"), Quantity(Decimal(2)))
        XCTAssertTrue(result.first?.activity.isEmpty ?? false)
    }

    // MARK: Tie-break: equal ledgers -> lexicographically first pie id clamps first.

    func test_reconcile_tieBreak_lexicographicallyFirstClampsFirst() throws {
        let pieA = try Pie(id: "pieA", name: "Pie A", slices: [sliceA], schedule: nil,
                          createdDay: "2025-01-01",
                          ledger: [PieLedgerEntry(symbol: "A", quantity: Quantity(Decimal(3)))])
        let pieB = try Pie(id: "pieB", name: "Pie B", slices: [sliceA], schedule: nil,
                          createdDay: "2025-01-01",
                          ledger: [PieLedgerEntry(symbol: "A", quantity: Quantity(Decimal(3)))])
        let pieStore = FakePieStore()
        pieStore.pies = [pieA, pieB]

        let portfolio = Portfolio(
            cash: usd("100"),
            positions: [Position(asset: Asset(symbol: "A", name: "A", kind: .stock),
                                 quantity: Quantity(Decimal(3)), averageCost: usd("10"), realizedPnL: usd("0"))],
            transactions: []
        )
        let portfolioStore = StubPortfolioStore(portfolio)

        let sut = ReconcilePieLedgers(pieStore: pieStore, portfolioStore: portfolioStore)
        let result = sut()

        let resultA = result.first { $0.id == "pieA" }
        let resultB = result.first { $0.id == "pieB" }

        // pieA (lexicographically first) clamps first: reduced to 0. pieB keeps its 3.
        XCTAssertEqual(resultA?.quantity(of: "A"), Quantity(Decimal(0)))
        XCTAssertEqual(resultB?.quantity(of: "A"), Quantity(Decimal(3)))
        XCTAssertTrue(resultA?.activity.contains { $0.kind == .manualAdjustment } ?? false)
        XCTAssertFalse(resultB?.activity.contains { $0.kind == .manualAdjustment } ?? true)
    }
}

private final class StubPortfolioStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    var saveCallCount = 0
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) {
        self.portfolio = portfolio
        saveCallCount += 1
    }
}
