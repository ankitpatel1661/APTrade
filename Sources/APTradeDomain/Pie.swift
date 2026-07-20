import Foundation

/// How often a contribution is made.
public enum PieCadence: String, Codable, CaseIterable, Sendable {
    case weekly
    case biweekly
    case monthly
}

/// A scheduled recurring contribution to a Pie.
public struct ContributionSchedule: Equatable, Codable, Sendable {
    public let amount: Money
    public let cadence: PieCadence
    /// The schedule's ORIGINAL first due day, fixed for the schedule's entire lifetime
    /// (set once, e.g. by the pie-creation wizard, to the initial `nextDueDay`, and
    /// never advanced afterward). `PieSchedule.dueDays`/`nextDueDay` must always step
    /// from THIS anchor, never from the moving `nextDueDay` cursor below — re-anchoring
    /// on the cursor causes permanent monthly drift once Foundation clamps a step (e.g.
    /// a schedule anchored on the 31st: cursor Jan 31 -> clamped to Feb 28 -> re-anchor
    /// on Feb 28 -> next step Mar 28 forever, instead of the correct Mar 31). Stepping
    /// from the fixed original anchor reproduces the correct Jan 31 -> Feb 28 -> Mar 31
    /// sequence indefinitely.
    public let anchorDay: String
    /// The next due day still to be executed. Advances as contributions execute; always
    /// a genuine `anchorDay`-cadence step (never independently derived).
    public let nextDueDay: String

    public init(amount: Money, cadence: PieCadence, anchorDay: String, nextDueDay: String) {
        self.amount = amount
        self.cadence = cadence
        self.anchorDay = anchorDay
        self.nextDueDay = nextDueDay
    }

    enum CodingKeys: String, CodingKey {
        case amount, cadence, anchorDay, nextDueDay
    }

    /// Custom decode: `anchorDay` defensively falls back to `nextDueDay` when the key is
    /// missing. No persisted schedule predates `anchorDay` in practice (it's introduced
    /// alongside its own first use), but decoding data written before this field existed
    /// should still produce a usable schedule rather than fail outright.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let amount = try container.decode(Money.self, forKey: .amount)
        let cadence = try container.decode(PieCadence.self, forKey: .cadence)
        let nextDueDay = try container.decode(String.self, forKey: .nextDueDay)
        let anchorDay = try container.decodeIfPresent(String.self, forKey: .anchorDay) ?? nextDueDay
        self.amount = amount
        self.cadence = cadence
        self.anchorDay = anchorDay
        self.nextDueDay = nextDueDay
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(amount, forKey: .amount)
        try container.encode(cadence, forKey: .cadence)
        try container.encode(anchorDay, forKey: .anchorDay)
        try container.encode(nextDueDay, forKey: .nextDueDay)
    }
}

/// One slice of a Pie, representing an asset allocation target.
public struct PieSlice: Equatable, Codable, Sendable {
    public let symbol: String
    public let assetKind: AssetKind
    public let targetWeight: Percentage

    public init(symbol: String, assetKind: AssetKind, targetWeight: Percentage) {
        self.symbol = symbol
        self.assetKind = assetKind
        self.targetWeight = targetWeight
    }
}

/// A ledger entry tracking the current quantity of an asset held in a Pie.
public struct PieLedgerEntry: Equatable, Codable, Sendable {
    public let symbol: String
    public let quantity: Quantity

    public init(symbol: String, quantity: Quantity) {
        self.symbol = symbol
        self.quantity = quantity
    }
}

/// The kind of activity recorded in a Pie's activity log.
public enum PieActivityKind: String, Codable, Sendable {
    case contribution
    case rebalance
    case missedInsufficientCash
    case manualAdjustment
}

/// A log entry recording an event that occurred on a Pie (contribution, rebalance, etc.).
public struct PieActivityEntry: Equatable, Codable, Sendable, Identifiable {
    public let id: UUID
    public let kind: PieActivityKind
    public let day: String
    public let amount: Money?

    public init(id: UUID = UUID(), kind: PieActivityKind, day: String, amount: Money?) {
        self.id = id
        self.kind = kind
        self.day = day
        self.amount = amount
    }
}

/// Validation errors when constructing a Pie.
public enum PieError: Error, Equatable {
    case invalidWeights
    case duplicateSymbols
    case emptySlices
}

/// A portfolio allocation strategy: divide cash or holdings proportionally across a set
/// of assets (slices) on a recurring schedule. Each Pie tracks its holdings in a ledger
/// and records activity (contributions, rebalances, etc.) for auditability.
///
/// Construction validates: slices non-empty, symbols unique, and weights sum to exactly 100.
public struct Pie: Equatable, Codable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let slices: [PieSlice]
    public let schedule: ContributionSchedule?
    public let createdDay: String
    public let ledger: [PieLedgerEntry]
    public let activity: [PieActivityEntry]

    /// Create a Pie with validation.
    ///
    /// - Parameters:
    ///   - id: UUID string identifier (generated if omitted).
    ///   - name: Display name for this Pie.
    ///   - slices: Asset allocation targets.
    ///   - schedule: Optional recurring contribution schedule.
    ///   - createdDay: Creation date as `yyyy-MM-dd`.
    ///   - ledger: Current holdings (defaults to empty).
    ///   - activity: Activity log entries (defaults to empty).
    ///
    /// - Throws: `PieError.emptySlices` if slices is empty.
    /// - Throws: `PieError.duplicateSymbols` if any symbol appears more than once.
    /// - Throws: `PieError.invalidWeights` if slice weights do not sum to exactly 100.
    public init(
        id: String = UUID().uuidString,
        name: String,
        slices: [PieSlice],
        schedule: ContributionSchedule?,
        createdDay: String,
        ledger: [PieLedgerEntry] = [],
        activity: [PieActivityEntry] = []
    ) throws {
        // Validate: non-empty
        guard !slices.isEmpty else {
            throw PieError.emptySlices
        }

        // Validate: unique symbols
        let symbolSet = Set(slices.map(\.symbol))
        guard symbolSet.count == slices.count else {
            throw PieError.duplicateSymbols
        }

        // Validate: weights sum to exactly 100
        let weightSum = slices.reduce(Decimal(0)) { $0 + $1.targetWeight.value }
        guard weightSum == 100 else {
            throw PieError.invalidWeights
        }

        self.id = id
        self.name = name
        self.slices = slices
        self.schedule = schedule
        self.createdDay = createdDay
        self.ledger = ledger
        self.activity = activity
    }

    /// Get the quantity of a specific asset held in this Pie's ledger.
    ///
    /// - Parameter symbol: The asset symbol to look up.
    /// - Returns: The quantity held, or `.init(0)` if the symbol is not in the ledger.
    public func quantity(of symbol: String) -> Quantity {
        ledger.first(where: { $0.symbol == symbol })?.quantity ?? Quantity(0)
    }

    // MARK: Codable

    /// Custom decoder to route through the validating init when decoding from JSON.
    /// This ensures that stored data conforms to Pie invariants when loaded.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let id = try container.decode(String.self, forKey: .id)
        let name = try container.decode(String.self, forKey: .name)
        let slices = try container.decode([PieSlice].self, forKey: .slices)
        let schedule = try container.decodeIfPresent(ContributionSchedule.self, forKey: .schedule)
        let createdDay = try container.decode(String.self, forKey: .createdDay)
        let ledger = try container.decode([PieLedgerEntry].self, forKey: .ledger)
        let activity = try container.decode([PieActivityEntry].self, forKey: .activity)

        try self.init(
            id: id,
            name: name,
            slices: slices,
            schedule: schedule,
            createdDay: createdDay,
            ledger: ledger,
            activity: activity
        )
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case slices
        case schedule
        case createdDay
        case ledger
        case activity
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(name, forKey: .name)
        try container.encode(slices, forKey: .slices)
        try container.encodeIfPresent(schedule, forKey: .schedule)
        try container.encode(createdDay, forKey: .createdDay)
        try container.encode(ledger, forKey: .ledger)
        try container.encode(activity, forKey: .activity)
    }
}
