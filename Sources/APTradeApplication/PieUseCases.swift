import Foundation
import APTradeDomain

public struct LoadPies: Sendable {
    private let store: PieStore

    public init(store: PieStore) {
        self.store = store
    }

    public func callAsFunction() -> [Pie] {
        store.load()
    }
}

public struct SavePie: Sendable {
    private let store: PieStore

    public init(store: PieStore) {
        self.store = store
    }

    /// Create a new Pie or replace an existing one with the same id.
    ///
    /// - Parameter pie: The Pie to save.
    /// - Returns: The complete list of Pies after the save operation.
    public func callAsFunction(_ pie: Pie) -> [Pie] {
        var pies = store.load()
        if let index = pies.firstIndex(where: { $0.id == pie.id }) {
            pies[index] = pie
        } else {
            pies.append(pie)
        }
        store.save(pies)
        return pies
    }
}

public struct DeletePie: Sendable {
    private let store: PieStore

    public init(store: PieStore) {
        self.store = store
    }

    /// Delete a Pie by id. No-op if the id is not found.
    ///
    /// - Parameter id: The id of the Pie to delete.
    /// - Returns: The complete list of Pies after the delete operation.
    public func callAsFunction(id: String) -> [Pie] {
        let pies = store.load()
        let filtered = pies.filter { $0.id != id }
        store.save(filtered)
        return filtered
    }
}
