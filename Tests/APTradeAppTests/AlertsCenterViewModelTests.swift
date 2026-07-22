import XCTest
@testable import APTradeApp
import APTradeApplication
import APTradeDomain

private struct Boom: Error {}

@MainActor
final class AlertsCenterViewModelTests: XCTestCase {
    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }

    /// Every dependency defaults to a harmless empty double so a single test only has to
    /// override the source it actually cares about — mirrors `HomeViewModelTests.makeVM`.
    private func makeVM(
        alertStore: VMFakeAlertStore = VMFakeAlertStore(),
        watchlist: VMFakeStore = VMFakeStore([]),
        loadAlerts: (() throws -> [PriceAlert])? = nil
    ) -> AlertsCenterViewModel {
        AlertsCenterViewModel(
            loadAlerts: loadAlerts ?? { LoadAlertsUseCase(store: alertStore)() },
            removeAlert: { RemovePriceAlertUseCase(store: alertStore)(id: $0) },
            loadWatchlist: { LoadWatchlistUseCase(store: watchlist)() }
        )
    }

    // MARK: - Load + group by symbol

    func test_load_groupsBySymbol_alphabetical() {
        let store = VMFakeAlertStore()
        store.alerts = [
            PriceAlert(symbol: "TSLA", condition: .priceAbove(usd("300"))),
            PriceAlert(symbol: "AAPL", condition: .priceAbove(usd("200"))),
            PriceAlert(symbol: "MSFT", condition: .priceBelow(usd("100")))
        ]
        let vm = makeVM(alertStore: store)

        XCTAssertEqual(vm.groups.map(\.symbol), ["AAPL", "MSFT", "TSLA"])
    }

    func test_load_alertsWithinSymbol_keepStoredOrder() {
        let store = VMFakeAlertStore()
        let first = PriceAlert(symbol: "AAPL", condition: .priceAbove(usd("200")))
        let second = PriceAlert(symbol: "AAPL", condition: .priceBelow(usd("150")))
        store.alerts = [first, second]
        let vm = makeVM(alertStore: store)

        XCTAssertEqual(vm.groups.first?.alerts.map(\.id), [first.id, second.id])
    }

    // MARK: - Empty state

    func test_empty_whenNoAlerts() {
        let vm = makeVM()

        XCTAssertTrue(vm.isEmpty)
        XCTAssertTrue(vm.groups.isEmpty)
    }

    func test_notEmpty_whenAlertsExist() {
        let store = VMFakeAlertStore()
        store.alerts = [PriceAlert(symbol: "AAPL", condition: .priceAbove(usd("200")))]
        let vm = makeVM(alertStore: store)

        XCTAssertFalse(vm.isEmpty)
    }

    // MARK: - Remove persists via the injected path AND updates state

    func test_remove_persistsViaInjectedPath_andUpdatesState() {
        let store = VMFakeAlertStore()
        let toRemove = PriceAlert(symbol: "AAPL", condition: .priceAbove(usd("200")))
        let toKeep = PriceAlert(symbol: "MSFT", condition: .priceBelow(usd("100")))
        store.alerts = [toRemove, toKeep]
        let vm = makeVM(alertStore: store)

        vm.remove(toRemove.id)

        // Persisted: the backing store no longer has it.
        XCTAssertEqual(store.alerts.map(\.id), [toKeep.id])
        // State: the VM's own groups reflect the removal immediately.
        XCTAssertEqual(vm.groups.flatMap(\.alerts).map(\.id), [toKeep.id])
    }

    // MARK: - Condition summary reuses the SAME helper PriceAlertSheet uses

    func test_conditionSummary_matchesSharedHelper_forEveryCase() {
        let above = AlertCondition.priceAbove(usd("150"))
        let below = AlertCondition.priceBelow(usd("120"))
        let percent = AlertCondition.percentChange(Percentage(value: 5))

        XCTAssertEqual(above.localizedSummary, String(format: tr(.priceAboveSummaryFormat), usd("150").formatted))
        XCTAssertEqual(below.localizedSummary, String(format: tr(.priceBelowSummaryFormat), usd("120").formatted))
        XCTAssertEqual(percent.localizedSummary, String(format: tr(.percentMoveSummaryFormat), "5"))
    }

    // MARK: - Store-load failure degrades gracefully (no crash, empty)

    func test_loadFailure_degradesToEmpty_noCrash() {
        let vm = makeVM(loadAlerts: { throw Boom() })

        XCTAssertTrue(vm.isEmpty)
        XCTAssertTrue(vm.groups.isEmpty)
    }

    // MARK: - Asset lookup for tap-through

    func test_asset_resolvesFromWatchlist_whenPresent() {
        let aapl = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let vm = makeVM(watchlist: VMFakeStore([aapl]))

        XCTAssertEqual(vm.asset(for: "AAPL"), aapl)
    }

    func test_asset_fallsBackGracefully_whenNotOnWatchlist() {
        let vm = makeVM(watchlist: VMFakeStore([]))

        let asset = vm.asset(for: "ZZZZ")

        XCTAssertEqual(asset.symbol, "ZZZZ")
    }
}
