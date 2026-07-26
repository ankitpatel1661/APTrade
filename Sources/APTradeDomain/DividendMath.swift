import Foundation

/// A dividend event as the Swift domain sees it (bridged from the shared core
/// by infrastructure; pure value here).
public struct DividendEvent: Equatable, Sendable {
    public let symbol: String
    public let exDate: Date
    public let amountPerShare: Money

    public init(symbol: String, exDate: Date, amountPerShare: Money) {
        self.symbol = symbol
        self.exDate = exDate
        self.amountPerShare = amountPerShare
    }
}

/// How frequently a security pays dividends, inferred from historical ex-dates.
public enum DividendCadence: Equatable, Sendable {
    case monthly, quarterly, semiAnnual, annual
}

/// Pure dividend & income math: held-shares reconstruction from a transaction
/// ledger, trailing-twelve-month rate, cadence inference/projection, and cash
/// aggregation. No networking, no persistence — Foundation only.
public enum DividendMath {
    private static let secondsPerDay: TimeInterval = 86_400
    /// Average calendar year, including the leap-day quarter — the annualization unit for
    /// `dividendGrowthRate`'s window, span and centroid arithmetic. Kotlin's `SECONDS_PER_YEAR`.
    private static let secondsPerYear: TimeInterval = 365.25 * 86_400

    /// Shares held STRICTLY BEFORE `date`: sum of buy quantities minus sell
    /// quantities across transactions with `txn.date < date` (dividend entries
    /// contribute nothing; DRIP buys count like any buy).
    public static func sharesHeld(symbol: String, at date: Date,
                                  transactions: [Transaction]) -> Quantity {
        var net = Decimal(0)
        for txn in transactions where txn.symbol == symbol && txn.date < date {
            switch txn.side {
            case .buy:
                net += txn.quantity.amount
            case .sell:
                net -= txn.quantity.amount
            case .dividend:
                break
            }
        }
        return Quantity(net)
    }

    /// Sum of event amounts with exDate in (asOf − 365 days, asOf]. Zero when none.
    public static func trailingAnnualPerShare(events: [DividendEvent], asOf: Date) -> Money {
        let windowStart = asOf.addingTimeInterval(-365 * secondsPerDay)
        var total = Money(amount: 0)
        for event in events where event.exDate > windowStart && event.exDate <= asOf {
            total = total + event.amountPerShare
        }
        return total
    }

    /// Median gap between consecutive ex-dates → cadence. nil when < 2 events.
    /// Buckets (days): ≤45 monthly, ≤135 quarterly, ≤270 semiAnnual, else annual.
    public static func inferredCadence(events: [DividendEvent]) -> DividendCadence? {
        guard events.count >= 2 else { return nil }

        let sortedDates = events.map(\.exDate).sorted()
        var gapsInDays: [Double] = []
        for i in 1..<sortedDates.count {
            gapsInDays.append(sortedDates[i].timeIntervalSince(sortedDates[i - 1]) / secondsPerDay)
        }
        let sortedGaps = gapsInDays.sorted()
        let count = sortedGaps.count
        let medianGapDays = count % 2 == 1
            ? sortedGaps[count / 2]
            : (sortedGaps[count / 2 - 1] + sortedGaps[count / 2]) / 2

        switch medianGapDays {
        case ...45: return .monthly
        case ...135: return .quarterly
        case ...270: return .semiAnnual
        default: return .annual
        }
    }

    /// Calendar interval for a cadence bucket (monthly 30d, quarterly 91d,
    /// semiAnnual 182d, annual 365d). Shared by `nextProjected` and `projectedSchedule`
    /// so the step sizes are defined in exactly one place.
    private static func cadenceInterval(_ cadence: DividendCadence) -> TimeInterval {
        switch cadence {
        case .monthly: return 30 * secondsPerDay
        case .quarterly: return 91 * secondsPerDay
        case .semiAnnual: return 182 * secondsPerDay
        case .annual: return 365 * secondsPerDay
        }
    }

    /// Last exDate + cadence interval (monthly 30d, quarterly 91d, semiAnnual 182d,
    /// annual 365d), amount = last event's amount. nil when cadence is nil.
    public static func nextProjected(events: [DividendEvent]) -> DividendEvent? {
        guard let cadence = inferredCadence(events: events),
              let last = events.max(by: { $0.exDate < $1.exDate }) else {
            return nil
        }

        return DividendEvent(
            symbol: last.symbol,
            exDate: last.exDate.addingTimeInterval(cadenceInterval(cadence)),
            amountPerShare: last.amountPerShare
        )
    }

    /// Received dividend cash per calendar month (UTC, "yyyy-MM" keys) from
    /// `.dividend` transactions only.
    public static func monthlyReceived(transactions: [Transaction]) -> [String: Money] {
        var result: [String: Money] = [:]
        for txn in transactions where txn.side == .dividend {
            let key = monthKey(for: txn.date)
            let cash = Money(amount: txn.price.amount * txn.quantity.amount, currencyCode: txn.price.currencyCode)
            let running = result[key] ?? Money(amount: 0, currencyCode: cash.currencyCode)
            result[key] = running + cash
        }
        return result
    }

    /// trailingAnnualPerShare × shares, per held symbol, summed. Symbols absent
    /// from `eventsBySymbol` contribute zero.
    public static func projectedAnnualIncome(positions: [Position],
                                             eventsBySymbol: [String: [DividendEvent]],
                                             asOf: Date) -> Money {
        var total = Money(amount: 0)
        for position in positions {
            let events = eventsBySymbol[position.asset.symbol] ?? []
            let perShare = trailingAnnualPerShare(events: events, asOf: asOf)
            let contribution = Money(amount: perShare.amount * position.quantity.amount,
                                     currencyCode: perShare.currencyCode)
            total = total + contribution
        }
        return total
    }

    /// UTC "yyyy-MM" bucket key for a date, used for monthly aggregation.
    private static func monthKey(for date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone.gmt
        formatter.dateFormat = "yyyy-MM"
        return formatter.string(from: date)
    }

    /// Lower bound on per-symbol annual dividend growth used by forecasts.
    public static let minDividendGrowth = Decimal(string: "-0.20")!
    /// Upper bound on per-symbol annual dividend growth used by forecasts.
    public static let maxDividendGrowth = Decimal(string: "0.25")!

    /// Annualized growth of a symbol's dividend, measured over at most the last five
    /// years of history and clamped to `minDividendGrowth ... maxDividendGrowth`.
    /// Returns `0` when there is too little history to measure honestly
    /// (fewer than two years spanned, or fewer than two payments).
    ///
    /// Compares the AVERAGE PER-PAYMENT amount across the oldest half of the window against the
    /// newest half (payment-count-normalized) rather than a raw day-windowed sum sampled at two
    /// instants, so a cadence change (e.g. quarterly -> monthly) doesn't read as growth. The
    /// annualization exponent's divisor is the elapsed time between the two quantities actually
    /// being compared: the gap, in years, between each half's CENTROID (mean ex-date) — not the
    /// window's total first-to-last span.
    ///
    /// WHY IT IS SHAPED THIS WAY (do not "simplify" it back). The original implementation sampled
    /// `trailingAnnualPerShare` at two instants (`asOf`, and `window[0].exDate + 1 year`). That is
    /// an aliasing bug: whether a rolling 365-day window catches 4 or 5 payments of an exact
    /// quarterly (91-day) cadence depends entirely on where `asOf` falls relative to an ex-date —
    /// four 91-day gaps span only 364 days, one day short of the window — so a genuinely FLAT
    /// payer read anywhere from −20% to +25%/yr purely from ex-date phase (reproduced at
    /// `0.13678039345929127` for a flat $0.25/quarter payer; see
    /// `test_growthRate_flatHistoryWithExactCadenceAliasing_isZero`), compounding to a ~45x
    /// divergence in reported income at a 30-year horizon. Comparing per-payment AVERAGES over
    /// count-matched halves is immune to this: a flat payer's early- and late-half averages are
    /// equal regardless of which instant `asOf` is, because no day-count window boundary is
    /// computed at all. (The old code also mismatched its early anchor — `first + 365.25 days` —
    /// against `trailingAnnualPerShare`'s strict 365-day window, a 21,600-second gap that
    /// structurally excluded the early window's own first event. That class of bug cannot recur
    /// here: this function computes no window-membership cutoff.)
    ///
    /// Both halves of the fix must stay together. Count-matched halves with the OLD
    /// `totalSpanYears - 1` divisor removes the aliasing but introduces a different, systematic
    /// bias: centroids are separated by roughly `span / 2`, not `span − 1`, and those coincide
    /// only at exactly a two-year span. That defect is silent and calibrated-looking, and just as
    /// capable of a multiple-x divergence at 30 years. The divisor must be the actual measured
    /// interval — the centroid gap — which is what makes the annualization recover a known true
    /// per-payment CAGR exactly (`test_growthRate_recoversAKnownTruePerPaymentCagr`).
    ///
    /// On an odd-count window the middle event is dropped from BOTH halves. That is deliberate:
    /// assigning it to either half reintroduces an asymmetry between the two payment counts, and
    /// with it the artifact this algorithm exists to remove — a flat payer carrying one year-end
    /// special reads +19.97%/yr the moment that special lands on the middle event. Pinned by
    /// `test_growthRate_oddCountWindow_dropsTheMiddleEventFromBothHalves`.
    ///
    /// RESIDUAL LIMITATION: count-matched halves are immune to phase relative to `asOf` — they are
    /// NOT immune to phase relative to a payer's own cadence when that payer mixes an irregular,
    /// much larger payment into an otherwise-regular schedule (a REIT/BDC paying a regular
    /// quarterly rate plus a year-end special distribution can land that one oversized payment in
    /// either half). Same class of artifact, roughly an order of magnitude smaller, and bounded by
    /// the clamp like everything else this function returns.
    ///
    /// MEASURED-ZERO VS. UNMEASURABLE: every early return reports `0` regardless of WHY — a
    /// genuinely flat payer with two full years of history and a payer with one payment ever both
    /// read `0`. That conflation is right for `incomeForecast` (a forecast that cannot measure
    /// growth should compound at 0%), but not for a caller that must tell "nothing to report" from
    /// "reported a real zero" — see `hasMeasurableGrowth`, which shares every precondition through
    /// `measuredDividendGrowthRate` so the two cannot drift apart.
    public static func dividendGrowthRate(events: [DividendEvent], asOf: Date) -> Decimal {
        measuredDividendGrowthRate(events: events, asOf: asOf) ?? 0
    }

    /// True when `events` carries enough history for `dividendGrowthRate` to actually MEASURE a
    /// rate, as opposed to defaulting to `0` purely for want of data. Shares
    /// `measuredDividendGrowthRate`'s preconditions exactly — a genuinely flat payer with
    /// sufficient history returns `true` here (with `dividendGrowthRate` reporting a real,
    /// measured `0`); a payer with too little history returns `false` (with `dividendGrowthRate`
    /// ALSO reporting `0`, indistinguishably, unless this function is consulted separately).
    public static func hasMeasurableGrowth(events: [DividendEvent], asOf: Date) -> Bool {
        measuredDividendGrowthRate(events: events, asOf: asOf) != nil
    }

    /// True when at least one position `incomeForecast` would actually project (positive trailing
    /// income, positive quantity — its own identical inclusion test, reproduced here) has
    /// `hasMeasurableGrowth` history for its own events. Lets a caller aggregate the per-symbol
    /// measurability question across a whole portfolio in one call, the same way
    /// `projectedAnnualIncome` aggregates the per-symbol income sum.
    public static func anyPositionHasMeasurableGrowth(positions: [Position],
                                                      eventsBySymbol: [String: [DividendEvent]],
                                                      asOf: Date) -> Bool {
        positions.contains { position in
            let events = eventsBySymbol[position.asset.symbol] ?? []
            return trailingAnnualPerShare(events: events, asOf: asOf).amount > 0
                && position.quantity.amount > 0
                && hasMeasurableGrowth(events: events, asOf: asOf)
        }
    }

    /// The shared body behind `dividendGrowthRate` and `hasMeasurableGrowth`: `nil` wherever there
    /// is too little data to measure honestly, a real (possibly zero) rate otherwise. See
    /// `dividendGrowthRate`'s doc comment for the full derivation this implements.
    private static func measuredDividendGrowthRate(events: [DividendEvent], asOf: Date) -> Decimal? {
        let windowStart = asOf.addingTimeInterval(-5 * secondsPerYear)
        let window = events
            .filter { $0.exDate <= asOf && $0.exDate >= windowStart }
            .sorted { $0.exDate < $1.exDate }
        guard window.count >= 2 else { return nil }

        let totalSpanYears = window[window.count - 1].exDate
            .timeIntervalSince(window[0].exDate) / secondsPerYear
        guard totalSpanYears >= 2 else { return nil }

        // Count-normalized halves, NOT a day-windowed sum sampled at an instant — see the doc
        // comment on `dividendGrowthRate` for why that distinction is the entire fix. On an odd
        // count the middle event falls out of both halves, by design.
        let half = window.count / 2
        let earlyHalf = Array(window[0..<half])
        let lateHalf = Array(window[(window.count - half)...])
        let earlyAvg = averagePerPayment(earlyHalf)
        let lateAvg = averagePerPayment(lateHalf)
        guard earlyAvg > 0, lateAvg > 0 else { return nil }

        // The annualization exponent's divisor: the elapsed time between what is actually being
        // compared — each half's centroid (mean ex-date) — in years, NOT the window's total
        // first-to-last span, and NOT `span - 1`.
        let centroidGapYears = (meanEpochSeconds(lateHalf) - meanEpochSeconds(earlyHalf)) / secondsPerYear
        guard centroidGapYears > 0, centroidGapYears.isFinite else { return nil }

        let ratio = lateAvg / earlyAvg
        guard ratio > 0 else { return nil }
        let rate = pow(ratio, 1.0 / centroidGapYears) - 1.0
        guard rate.isFinite else { return nil }

        return clampGrowth(Decimal(rate))
    }

    /// Average per-share payment amount across `events` (assumed non-empty; callers only pass
    /// non-empty half-windows), as a `Double` — the annualization root below is a fractional
    /// exponentiation `Decimal` cannot do, so the ratio is carried in `Double` and the clamp
    /// bounds the blast radius of any drift.
    private static func averagePerPayment(_ events: [DividendEvent]) -> Double {
        guard !events.isEmpty else { return 0 }
        var total = Decimal(0)
        for event in events { total += event.amountPerShare.amount }
        return NSDecimalNumber(decimal: total / Decimal(events.count)).doubleValue
    }

    /// Mean ex-date (epoch seconds) across `events` (assumed non-empty) — the "centroid"
    /// `measuredDividendGrowthRate` compares between its early and late halves.
    private static func meanEpochSeconds(_ events: [DividendEvent]) -> Double {
        guard !events.isEmpty else { return 0 }
        var total = 0.0
        for event in events { total += event.exDate.timeIntervalSince1970 }
        return total / Double(events.count)
    }

    private static func clampGrowth(_ value: Decimal) -> Decimal {
        min(max(value, minDividendGrowth), maxDividendGrowth)
    }

    /// One projected year of dividend income. `yearOffset` 1 is the trailing twelve-month
    /// rate carried forward without growth (a deliberately conservative first year);
    /// growth compounds starting at `yearOffset` 2.
    public struct ForecastYear: Equatable, Sendable {
        public let yearOffset: Int
        public let income: Money
        public init(yearOffset: Int, income: Money) {
            self.yearOffset = yearOffset
            self.income = income
        }
    }

    /// Projects annual dividend income forward, per holding, summed.
    ///
    /// Year 1 (`yearOffset` 1) holds each symbol at its current trailing twelve-month
    /// per-share rate — no growth is applied yet, a deliberately conservative choice.
    /// From year 2 onward each symbol grows at its clamped historical dividend growth
    /// rate. With DRIP on, each year's dividends buy more shares at that symbol's quoted
    /// price (`pricesBySymbol`), falling back to `averageCost` when no quote is
    /// available, with the assumed price itself growing at the same rate — a stated
    /// simplification, surfaced to the user as a caption.
    public static func incomeForecast(positions: [Position],
                                      pricesBySymbol: [String: Money],
                                      eventsBySymbol: [String: [DividendEvent]],
                                      years: Int,
                                      dripEnabled: Bool,
                                      asOf: Date) -> [ForecastYear] {
        guard years > 0 else { return [] }

        struct Projection {
            var shares: Decimal
            var perShare: Decimal
            var price: Decimal
            let growth: Decimal
        }

        var projections: [Projection] = []
        for position in positions {
            let events = eventsBySymbol[position.asset.symbol] ?? []
            let perShare = trailingAnnualPerShare(events: events, asOf: asOf).amount
            guard perShare > 0, position.quantity.amount > 0 else { continue }
            let price = pricesBySymbol[position.asset.symbol]?.amount ?? position.averageCost.amount
            projections.append(Projection(shares: position.quantity.amount,
                                          perShare: perShare,
                                          price: price,
                                          growth: dividendGrowthRate(events: events, asOf: asOf)))
        }

        var out: [ForecastYear] = []
        for offset in 1...years {
            var total: Decimal = 0
            for index in projections.indices {
                if offset > 1 {
                    projections[index].perShare *= (1 + projections[index].growth)
                    projections[index].price *= (1 + projections[index].growth)
                }
                let income = projections[index].shares * projections[index].perShare
                total += income
                if dripEnabled, projections[index].price > 0 {
                    projections[index].shares += income / projections[index].price
                }
            }
            out.append(ForecastYear(yearOffset: offset, income: Money(amount: total)))
        }
        return out
    }

    /// One projected dividend payment for a held symbol. Estimated, never declared —
    /// the source data contains historical ex-dates only.
    public struct ScheduledDividend: Equatable, Sendable {
        public let symbol: String
        public let exDate: Date
        public let perShare: Money
        public let estimatedAmount: Money
        public init(symbol: String, exDate: Date, perShare: Money, estimatedAmount: Money) {
            self.symbol = symbol
            self.exDate = exDate
            self.perShare = perShare
            self.estimatedAmount = estimatedAmount
        }
    }

    /// Rolls each holding's inferred payment cadence forward from its last real event,
    /// emitting estimated payments in `(asOf, through]`, ascending by date.
    public static func projectedSchedule(positions: [Position],
                                         eventsBySymbol: [String: [DividendEvent]],
                                         through: Date,
                                         asOf: Date) -> [ScheduledDividend] {
        var out: [ScheduledDividend] = []
        for position in positions {
            let shares = position.quantity.amount
            guard shares > 0 else { continue }
            let events = eventsBySymbol[position.asset.symbol] ?? []
            guard let seed = nextProjected(events: events),
                  let cadence = inferredCadence(events: events) else { continue }

            let step = cadenceInterval(cadence)
            var next = seed.exDate
            // Roll a stale projection forward until it is genuinely in the future.
            while next <= asOf { next = next.addingTimeInterval(step) }

            while next <= through {
                out.append(ScheduledDividend(
                    symbol: position.asset.symbol,
                    exDate: next,
                    perShare: seed.amountPerShare,
                    estimatedAmount: Money(amount: shares * seed.amountPerShare.amount)))
                next = next.addingTimeInterval(step)
            }
        }
        return out.sorted { $0.exDate < $1.exDate }
    }
}
