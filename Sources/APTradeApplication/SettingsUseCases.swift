import APTradeDomain

public struct LoadSettingsUseCase: Sendable {
    private let store: SettingsStore
    public init(store: SettingsStore) { self.store = store }
    public func callAsFunction() -> AppSettings { store.load() }
}

public struct SaveSettingsUseCase: Sendable {
    private let store: SettingsStore
    public init(store: SettingsStore) { self.store = store }
    public func callAsFunction(_ settings: AppSettings) { store.save(settings) }
}

/// Pushes an order-fill confirmation, but only when the user has order-fill
/// notifications enabled. A no-op otherwise, so callers needn't check the preference.
public struct NotifyOrderFillUseCase: Sendable {
    private let notifier: OrderFillNotifier
    private let settings: SettingsStore
    public init(notifier: OrderFillNotifier, settings: SettingsStore) {
        self.notifier = notifier
        self.settings = settings
    }

    public func callAsFunction(side: TradeSide, symbol: String, quantity: Quantity, amount: Money) async {
        guard settings.load().orderFills else { return }
        await notifier.notifyFill(side: side, symbol: symbol, quantity: quantity, amount: amount)
    }
}
