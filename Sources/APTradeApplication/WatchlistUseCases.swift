import APTradeDomain

public struct AddToWatchlistUseCase {
    private let store: WatchlistStore
    public init(store: WatchlistStore) { self.store = store }

    @discardableResult
    public func callAsFunction(_ asset: Asset) -> [Asset] {
        var assets = store.load()
        guard !assets.contains(where: { $0.symbol == asset.symbol }) else { return assets }
        assets.append(asset)
        store.save(assets)
        return assets
    }
}

public struct RemoveFromWatchlistUseCase {
    private let store: WatchlistStore
    public init(store: WatchlistStore) { self.store = store }

    @discardableResult
    public func callAsFunction(symbol: String) -> [Asset] {
        var assets = store.load()
        assets.removeAll { $0.symbol == symbol }
        store.save(assets)
        return assets
    }
}

public struct LoadWatchlistUseCase {
    private let store: WatchlistStore
    public init(store: WatchlistStore) { self.store = store }
    public func callAsFunction() -> [Asset] { store.load() }
}
