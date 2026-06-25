import APTradeDomain

public struct LoadAlertsUseCase: Sendable {
    private let store: AlertStore
    public init(store: AlertStore) { self.store = store }
    public func callAsFunction() -> [PriceAlert] { store.load() }
}

public struct CreatePriceAlertUseCase: Sendable {
    private let store: AlertStore
    public init(store: AlertStore) { self.store = store }
    public func callAsFunction(symbol: String, condition: AlertCondition) -> [PriceAlert] {
        var alerts = store.load()
        alerts.append(PriceAlert(symbol: symbol, condition: condition))
        store.save(alerts)
        return alerts
    }
}

public struct RemovePriceAlertUseCase: Sendable {
    private let store: AlertStore
    public init(store: AlertStore) { self.store = store }
    public func callAsFunction(id: PriceAlert.ID) -> [PriceAlert] {
        let alerts = store.load().filter { $0.id != id }
        store.save(alerts)
        return alerts
    }
}

/// Checks every untriggered alert against its symbol's live quote, marks matches as
/// triggered, persists the change, and notifies for each newly-triggered alert.
///
/// The alert still triggers (so its in-app badge updates) even when the user has turned
/// off price-alert notifications — only the outward push via the notifier is suppressed.
public struct EvaluateAlertsUseCase: Sendable {
    private let store: AlertStore
    private let notifier: AlertNotifier
    private let settings: SettingsStore
    public init(store: AlertStore, notifier: AlertNotifier, settings: SettingsStore) {
        self.store = store
        self.notifier = notifier
        self.settings = settings
    }

    public func callAsFunction(quotes: [String: Quote]) async -> [PriceAlert] {
        var alerts = store.load()
        let notificationsEnabled = settings.load().priceAlerts
        var changed = false
        for index in alerts.indices {
            let alert = alerts[index]
            guard !alert.isTriggered, let quote = quotes[alert.symbol], alert.isMet(by: quote) else { continue }
            alerts[index] = alert.triggered()
            changed = true
            if notificationsEnabled {
                await notifier.notify(alert, quote: quote)
            }
        }
        if changed { store.save(alerts) }
        return alerts
    }
}
