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

        let sut = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo)
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

        let sut = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo)
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

        let sut = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo)
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

        let sut = ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo)

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
}
