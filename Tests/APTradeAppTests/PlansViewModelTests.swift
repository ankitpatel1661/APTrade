import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private final class MemoryPieStore: PieStore, @unchecked Sendable {
    var pies: [Pie] = []
    var saveCallCount = 0
    func load() -> [Pie] { pies }
    func save(_ pies: [Pie]) {
        self.pies = pies
        saveCallCount += 1
    }
}

private final class MemoryStore: PortfolioStore, @unchecked Sendable {
    var portfolio: Portfolio
    init(_ portfolio: Portfolio) { self.portfolio = portfolio }
    func load() -> Portfolio { portfolio }
    func save(_ portfolio: Portfolio) { self.portfolio = portfolio }
}

/// Counts `quote(for:)` calls so a test can assert a use case was never even attempted
/// (as opposed to attempted-and-failed).
private final class QuoteSpyRepo: MarketDataRepository, @unchecked Sendable {
    var quoteCallCount = 0
    var quotes: [String: Quote] = [:]
    func quote(for symbol: String) async throws -> Quote {
        quoteCallCount += 1
        guard let q = quotes[symbol] else { throw AppError.notFound }
        return q
    }
    func history(for symbol: String, timeframe: Timeframe) async throws -> [PricePoint] { [] }
}

@MainActor
final class PlansViewModelTests: XCTestCase {
    private let sliceA = PieSlice(symbol: "A", assetKind: .stock, targetWeight: Percentage(value: 50))
    private let sliceB = PieSlice(symbol: "B", assetKind: .stock, targetWeight: Percentage(value: 50))
    private let fixedNow = Date(timeIntervalSince1970: 1_753_000_000) // 2025-07-20, a Sunday

    private func usd(_ amount: Decimal) -> Money { Money(amount: amount) }

    private func makeVM(pieStore: MemoryPieStore, portfolioStore: MemoryStore, repo: any MarketDataRepository) -> PlansViewModel {
        LocalizationManager.shared.language = .english
        let now = fixedNow // captured as a value below — avoids capturing non-Sendable `self`
        let serializer = TradeSerializer()
        return PlansViewModel(
            loadPies: LoadPies(store: pieStore),
            deletePie: DeletePie(store: pieStore, serializer: serializer),
            contributeToPie: ContributeToPie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo,
                                             serializer: serializer),
            rebalancePie: RebalancePie(pieStore: pieStore, portfolioStore: portfolioStore, market: repo,
                                       serializer: serializer),
            reconcileLedgers: ReconcilePieLedgers(pieStore: pieStore, portfolioStore: portfolioStore, now: { now },
                                                  serializer: serializer),
            fetchQuotes: FetchQuotesUseCase(repository: repo),
            now: { now }
        )
    }

    /// A portfolio that actually holds `quantityA`/`quantityB` shares of A/B, so
    /// `ReconcilePieLedgers` never clamps a pie's ledger down in tests that aren't
    /// specifically exercising reconciliation.
    private func backingPortfolio(quantityA: Decimal, quantityB: Decimal, priceA: Decimal = 10, priceB: Decimal = 10) -> Portfolio {
        var portfolio = Portfolio.starting()
        if quantityA > 0 {
            portfolio = try! portfolio.buying(Asset(symbol: "A", name: "A", kind: .stock),
                                              quantity: Quantity(quantityA), at: usd(priceA))
        }
        if quantityB > 0 {
            portfolio = try! portfolio.buying(Asset(symbol: "B", name: "B", kind: .stock),
                                              quantity: Quantity(quantityB), at: usd(priceB))
        }
        return portfolio
    }

    // MARK: - onAppear builds rows from fake quotes

    func test_onAppear_buildsRowsFromFakeQuotes() async throws {
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(8)),
            PieLedgerEntry(symbol: "B", quantity: Quantity(2)),
        ]
        let pie = try Pie(id: "pie-1", name: "Growth", slices: [sliceA, sliceB], schedule: nil,
                          createdDay: "2025-01-01", ledger: ledger)
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(backingPortfolio(quantityA: 8, quantityB: 2))
        let repo = VMFakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(10), previousClose: usd(10))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()

        XCTAssertEqual(vm.rows.count, 1)
        let row = try XCTUnwrap(vm.rows.first)
        XCTAssertEqual(row.id, "pie-1")
        XCTAssertEqual(row.name, "Growth")
        XCTAssertEqual(row.currentValue, usd(100))
        XCTAssertNil(row.nextContributionLabel)
        XCTAssertEqual(row.sliceWeights.map(\.0), ["A", "B"])
        XCTAssertEqual(row.sliceWeights.map(\.1), [Percentage(value: 50), Percentage(value: 50)])
    }

    // MARK: - Drift badge math (>5pp fixture)

    func test_onAppear_maxDriftPP_reflectsLargestSliceDrift() async throws {
        // A: 8 shares @ $10 = $80 (target 50%, actual 80% -> drift +30pp)
        // B: 2 shares @ $10 = $20 (target 50%, actual 20% -> drift -30pp)
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(8)),
            PieLedgerEntry(symbol: "B", quantity: Quantity(2)),
        ]
        let pie = try Pie(id: "pie-drift", name: "Drifted", slices: [sliceA, sliceB], schedule: nil,
                          createdDay: "2025-01-01", ledger: ledger)
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(backingPortfolio(quantityA: 8, quantityB: 2))
        let repo = VMFakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(10), previousClose: usd(10))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()

        let row = try XCTUnwrap(vm.rows.first)
        XCTAssertEqual(row.maxDriftPP, 30)
        XCTAssertGreaterThan(row.maxDriftPP, 5, "drift badge should show above the 5pp threshold")
    }

    func test_onAppear_atTarget_maxDriftPP_isZero_noBadge() async throws {
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(5)),
            PieLedgerEntry(symbol: "B", quantity: Quantity(5)),
        ]
        let pie = try Pie(id: "pie-balanced", name: "Balanced", slices: [sliceA, sliceB], schedule: nil,
                          createdDay: "2025-01-01", ledger: ledger)
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(backingPortfolio(quantityA: 5, quantityB: 5))
        let repo = VMFakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(10), previousClose: usd(10))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()

        let row = try XCTUnwrap(vm.rows.first)
        XCTAssertEqual(row.maxDriftPP, 0)
    }

    // MARK: - Reconcile-before-display

    func test_onAppear_reconcilesLedgersBeforeBuildingRows() async throws {
        let sliceC = PieSlice(symbol: "C", assetKind: .stock, targetWeight: Percentage(value: 100))
        let pie1 = try Pie(id: "pie1", name: "P1", slices: [sliceC], schedule: nil, createdDay: "2025-01-01",
                           ledger: [PieLedgerEntry(symbol: "C", quantity: Quantity(3))])
        let pie2 = try Pie(id: "pie2", name: "P2", slices: [sliceC], schedule: nil, createdDay: "2025-01-01",
                           ledger: [PieLedgerEntry(symbol: "C", quantity: Quantity(9))])
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie1, pie2]

        // Portfolio only actually holds 10 of C; pies together over-claim 12 (3 + 9).
        let portfolio = try! Portfolio.starting().buying(Asset(symbol: "C", name: "C", kind: .stock),
                                                         quantity: Quantity(10), at: usd(10))
        let portfolioStore = MemoryStore(portfolio)
        let repo = VMFakeRepo()
        repo.quotes["C"] = Quote(symbol: "C", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()

        // pie1's smaller claim (3) is preserved in full; pie2's larger claim (9) absorbs
        // the shortfall and is clamped to 7 (10 - 3).
        XCTAssertEqual(pieStore.pies.first(where: { $0.id == "pie1" })?.quantity(of: "C"), Quantity(3))
        XCTAssertEqual(pieStore.pies.first(where: { $0.id == "pie2" })?.quantity(of: "C"), Quantity(7))

        // Rows must reflect the RECONCILED ledger, not the pre-reconcile claim.
        let row2 = try XCTUnwrap(vm.rows.first { $0.id == "pie2" })
        XCTAssertEqual(row2.currentValue, usd(70), "row should be built from the reconciled (clamped) ledger")
    }

    // MARK: - contributeNow refreshes rows

    func test_contributeNow_sufficientCash_refreshesRows() async throws {
        let pie = try Pie(id: "pie-1", name: "Growth", slices: [sliceA, sliceB], schedule: nil, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(.starting())
        let repo = VMFakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(10), previousClose: usd(10))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()
        XCTAssertEqual(vm.rows.first?.currentValue, usd(0))

        await vm.contributeNow(id: "pie-1", amount: usd(100))

        XCTAssertNil(vm.errorMessage)
        XCTAssertEqual(vm.rows.first?.currentValue, usd(100))
    }

    // MARK: - contributeNow insufficient cash surfaces localized error

    func test_contributeNow_insufficientCash_setsLocalizedErrorMessage() async throws {
        let pie = try Pie(id: "pie-1", name: "Growth", slices: [sliceA, sliceB], schedule: nil, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(.starting(cash: usd(10)))
        let repo = VMFakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(10), previousClose: usd(10))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()
        await vm.contributeNow(id: "pie-1", amount: usd(100))

        XCTAssertEqual(vm.errorMessage, "Not enough cash for this contribution.")
        XCTAssertEqual(portfolioStore.portfolio.cash, usd(10), "portfolio must be untouched on a skipped contribution")
    }

    // MARK: - Rebalance request -> confirm flow mutates stores

    func test_requestRebalance_thenConfirm_mutatesStoresAndClearsPreview() async throws {
        // A: 7 shares @ $10 = $70 (target 50%), B: 3 shares @ $10 = $30 (target 50%) -> drifted.
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(7)),
            PieLedgerEntry(symbol: "B", quantity: Quantity(3)),
        ]
        let pie = try Pie(id: "pie-1", name: "Drifted", slices: [sliceA, sliceB], schedule: nil,
                          createdDay: "2025-01-01", ledger: ledger)
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(backingPortfolio(quantityA: 7, quantityB: 3))
        let repo = VMFakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(10), previousClose: usd(10))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()

        await vm.requestRebalance(id: "pie-1")
        let preview = try XCTUnwrap(vm.rebalancePreview)
        XCTAssertFalse(preview.isEmpty)

        await vm.confirmRebalance(id: "pie-1")

        XCTAssertNil(vm.rebalancePreview)
        // After rebalancing to 50/50 on $100 total, both slices should hold $50 worth (5 shares @ $10).
        XCTAssertEqual(pieStore.pies.first?.quantity(of: "A"), Quantity(5))
        XCTAssertEqual(pieStore.pies.first?.quantity(of: "B"), Quantity(5))
        XCTAssertTrue(pieStore.pies.first?.activity.contains { $0.kind == .rebalance } ?? false)
    }

    // MARK: - deletePie

    func test_deletePie_removesFromStoreAndRows() async throws {
        let pie = try Pie(id: "pie-1", name: "Growth", slices: [sliceA, sliceB], schedule: nil, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(.starting())
        let repo = VMFakeRepo()

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()
        XCTAssertEqual(vm.rows.count, 1)

        await vm.deletePie(id: "pie-1")

        XCTAssertTrue(vm.rows.isEmpty)
        XCTAssertTrue(pieStore.pies.isEmpty)
    }

    // MARK: - openDetail builds target/actual/drift, activity, schedule

    func test_openDetail_buildsSlicesWithTargetActualDriftAndActivityAndSchedule() async throws {
        let schedule = ContributionSchedule(amount: usd(50), cadence: .monthly, anchorDay: "2025-07-25", nextDueDay: "2025-07-25")
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(8)),
            PieLedgerEntry(symbol: "B", quantity: Quantity(2)),
        ]
        let activity = [PieActivityEntry(kind: .contribution, day: "2025-06-01", amount: usd(100))]
        let pie = try Pie(id: "pie-1", name: "Growth", slices: [sliceA, sliceB], schedule: schedule,
                          createdDay: "2025-01-01", ledger: ledger, activity: activity)
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(backingPortfolio(quantityA: 8, quantityB: 2))
        let repo = VMFakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(10), previousClose: usd(10))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()
        await vm.openDetail(id: "pie-1")

        let detail = try XCTUnwrap(vm.detail)
        XCTAssertEqual(detail.pieId, "pie-1")
        XCTAssertEqual(detail.slices.count, 2)
        let sliceADetail = try XCTUnwrap(detail.slices.first { $0.symbol == "A" })
        XCTAssertEqual(sliceADetail.targetWeight, Percentage(value: 50))
        XCTAssertEqual(sliceADetail.actualWeight, Percentage(value: 80))
        XCTAssertEqual(sliceADetail.drift, Percentage(value: 30))
        XCTAssertEqual(sliceADetail.currentValue, usd(80))
        XCTAssertEqual(detail.activity, activity)
        XCTAssertEqual(detail.schedule, schedule)
    }

    // MARK: - nextContributionLabel is formatted via L10n

    func test_onAppear_scheduledPie_setsFormattedNextContributionLabel() async throws {
        let schedule = ContributionSchedule(amount: usd(50), cadence: .monthly, anchorDay: "2025-07-25", nextDueDay: "2025-07-25")
        let pie = try Pie(id: "pie-1", name: "Growth", slices: [sliceA, sliceB], schedule: schedule, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(.starting())
        let repo = VMFakeRepo()

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()

        XCTAssertEqual(vm.rows.first?.nextContributionLabel, "Next: Jul 25")
    }

    // MARK: - contributeNow guards non-positive amounts before calling the use case

    func test_contributeNow_zeroOrNegativeAmount_setsErrorAndNeverCallsUseCase() async throws {
        let pie = try Pie(id: "pie-1", name: "Growth", slices: [sliceA, sliceB], schedule: nil, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie]
        let portfolioStore = MemoryStore(.starting())
        let repo = QuoteSpyRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(10), previousClose: usd(10))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()
        repo.quoteCallCount = 0 // reset the count run up by onAppear's own row-building fetch

        await vm.contributeNow(id: "pie-1", amount: usd(0))
        XCTAssertEqual(vm.errorMessage, "Enter an amount greater than zero.")
        XCTAssertEqual(repo.quoteCallCount, 0, "ContributeToPie must never run for a non-positive amount")
        XCTAssertTrue(pieStore.pies.first?.activity.isEmpty ?? false, "no activity entry for a rejected contribution")

        await vm.contributeNow(id: "pie-1", amount: usd(-5))
        XCTAssertEqual(vm.errorMessage, "Enter an amount greater than zero.")
        XCTAssertEqual(repo.quoteCallCount, 0)
        XCTAssertTrue(pieStore.pies.first?.activity.isEmpty ?? false)
    }

    // MARK: - Stale rebalance preview is cleared on navigation away

    func test_openDetail_clearsStaleRebalancePreview() async throws {
        // A: 7 shares @ $10 = $70 (target 50%), B: 3 shares @ $10 = $30 (target 50%) -> drifted, so
        // requestRebalance produces a non-empty preview.
        let ledger = [
            PieLedgerEntry(symbol: "A", quantity: Quantity(7)),
            PieLedgerEntry(symbol: "B", quantity: Quantity(3)),
        ]
        let pie1 = try Pie(id: "pie-1", name: "Drifted", slices: [sliceA, sliceB], schedule: nil,
                          createdDay: "2025-01-01", ledger: ledger)
        let sliceC = PieSlice(symbol: "C", assetKind: .stock, targetWeight: Percentage(value: 100))
        let pie2 = try Pie(id: "pie-2", name: "Other", slices: [sliceC], schedule: nil, createdDay: "2025-01-01")
        let pieStore = MemoryPieStore()
        pieStore.pies = [pie1, pie2]
        let portfolioStore = MemoryStore(backingPortfolio(quantityA: 7, quantityB: 3))
        let repo = VMFakeRepo()
        repo.quotes["A"] = Quote(symbol: "A", price: usd(10), previousClose: usd(10))
        repo.quotes["B"] = Quote(symbol: "B", price: usd(10), previousClose: usd(10))
        repo.quotes["C"] = Quote(symbol: "C", price: usd(10), previousClose: usd(10))

        let vm = makeVM(pieStore: pieStore, portfolioStore: portfolioStore, repo: repo)
        await vm.onAppear()
        await vm.requestRebalance(id: "pie-1")
        XCTAssertNotNil(vm.rebalancePreview)

        await vm.openDetail(id: "pie-2")

        XCTAssertNil(vm.rebalancePreview, "a stale preview from a different pie must not linger after navigating away")
    }
}
