import XCTest
@testable import APTradeApplication
import APTradeDomain

private final class MemoryAlertStore: AlertStore, @unchecked Sendable {
    var alerts: [PriceAlert]
    init(_ alerts: [PriceAlert]) { self.alerts = alerts }
    func load() -> [PriceAlert] { alerts }
    func save(_ a: [PriceAlert]) { alerts = a }
}

private final class CountingNotifier: AlertNotifier, @unchecked Sendable {
    var count = 0
    func notify(_ alert: PriceAlert, quote: Quote) async { count += 1 }
}

private final class MemorySettingsStore: SettingsStore, @unchecked Sendable {
    var settings: AppSettings
    init(_ settings: AppSettings) { self.settings = settings }
    func load() -> AppSettings { settings }
    func save(_ s: AppSettings) { settings = s }
}

final class AlertUseCasesTests: XCTestCase {
    private func metAlert() -> (PriceAlert, [String: Quote]) {
        let alert = PriceAlert(symbol: "AAPL", condition: .priceAbove(Money(amount: 100)))
        let quote = Quote(symbol: "AAPL", price: Money(amount: 150), previousClose: Money(amount: 100))
        return (alert, ["AAPL": quote])
    }

    func test_notifiesAndTriggers_whenPriceAlertsEnabled() async {
        let (alert, quotes) = metAlert()
        let store = MemoryAlertStore([alert])
        let notifier = CountingNotifier()
        let useCase = EvaluateAlertsUseCase(store: store, notifier: notifier,
                                            settings: MemorySettingsStore(.default))

        let result = await useCase(quotes: quotes)

        XCTAssertEqual(notifier.count, 1)
        XCTAssertTrue(result.first?.isTriggered == true)
    }

    func test_suppressesNotification_butStillTriggers_whenPriceAlertsDisabled() async {
        let (alert, quotes) = metAlert()
        let store = MemoryAlertStore([alert])
        let notifier = CountingNotifier()
        var settings = AppSettings.default
        settings.priceAlerts = false
        let useCase = EvaluateAlertsUseCase(store: store, notifier: notifier,
                                            settings: MemorySettingsStore(settings))

        let result = await useCase(quotes: quotes)

        XCTAssertEqual(notifier.count, 0, "no push when price alerts are off")
        XCTAssertTrue(result.first?.isTriggered == true, "alert still triggers for its in-app badge")
    }
}
