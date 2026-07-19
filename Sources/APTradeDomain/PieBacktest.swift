import Foundation

/// One data point in a DCA backtest report: cumulative amount invested and portfolio
/// value as of a given day.
public struct BacktestPoint: Equatable, Sendable {
    public let day: String
    public let invested: Money
    public let value: Money

    public init(day: String, invested: Money, value: Money) {
        self.day = day
        self.invested = invested
        self.value = value
    }
}

/// Result of replaying a Pie's contribution schedule over historical daily closes,
/// alongside a lump-sum comparison (investing the same total on day one instead of
/// spreading it out over the schedule).
public struct BacktestReport: Equatable, Sendable {
    public let points: [BacktestPoint]
    public let totalInvested: Money
    public let finalValue: Money
    public let totalReturn: Percentage
    public let lumpSumFinalValue: Money

    public init(
        points: [BacktestPoint],
        totalInvested: Money,
        finalValue: Money,
        totalReturn: Percentage,
        lumpSumFinalValue: Money
    ) {
        self.points = points
        self.totalInvested = totalInvested
        self.finalValue = finalValue
        self.totalReturn = totalReturn
        self.lumpSumFinalValue = lumpSumFinalValue
    }
}

/// Historical dollar-cost-averaging (DCA) backtest: replays a Pie's contribution
/// schedule over a table of historical daily closes, buying via `PieMath.distribute`
/// on each executable due day, and compares the result against a lump-sum baseline
/// (investing the full total on day one).
public enum PieMathBacktest {

    /// A dedicated Gregorian/ET calendar, used only to step one calendar day backward
    /// from `startDay` (for `PieSchedule.nextDueDay`'s `afterDay` bound below). No
    /// `Date()` (current-time) calls anywhere — every date here is derived from the
    /// `startDay` string parameter, so results stay fully deterministic.
    private static var parsingCalendar: Calendar {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "America/New_York") ?? .gmt
        return cal
    }

    /// Replays `slices`' contribution schedule (`amount` every `cadence`, starting at
    /// `startDay`) over `dailyCloses`, buying via `PieMath.distribute` on each
    /// executable due day, and compares the result to a lump-sum baseline.
    ///
    /// **Anchor/afterDay choice:** `PieSchedule.dueDays` never treats its own anchor
    /// day as a candidate — stepping starts at `anchor + 1×cadence` (see its doc
    /// comment: "the bare anchor itself is never a `dueDays` candidate"). A DCA plan,
    /// however, must be able to execute its very first contribution ON `startDay`
    /// itself when `startDay` is tradeable. So the first due day is found via
    /// `PieSchedule.nextDueDay(anchorDay: startDay, afterDay: <day before startDay>,
    /// ...)`, whose step-0 case *does* treat the anchor as eligible (per its own doc
    /// comment: "Step 0 (the anchor itself) is eligible"). Every subsequent due day
    /// then comes from `PieSchedule.dueDays(anchorDay: startDay, afterDay: <first due
    /// day>, throughDay: endDay, ...)`, which correctly steps forward cadence-by-
    /// cadence from there — its `afterDay` window check is against each step's
    /// *rolled* value, same as the first due day, so the two calls stay consistent.
    ///
    /// - Parameters:
    ///   - slices: Target allocation for the Pie being replayed.
    ///   - amount: Contribution amount on each due day.
    ///   - cadence: Contribution frequency.
    ///   - startDay: First possible contribution day, `yyyy-MM-dd`.
    ///   - endDay: Last day of the backtest window, `yyyy-MM-dd`.
    ///   - dailyCloses: `dailyCloses[symbol][day]` = closing price. A due day missing a
    ///     close for ANY slice symbol is skipped entirely — no partial buy — mirroring
    ///     live insufficient-data semantics.
    ///   - calendar: Market calendar for trading-day/holiday resolution.
    /// - Returns: `nil` if no due day in the window is executable (every due day is
    ///   missing a close for at least one slice symbol — "insufficient history").
    public static func dcaBacktest(
        slices: [PieSlice],
        amount: Money,
        cadence: PieCadence,
        startDay: String,
        endDay: String,
        dailyCloses: [String: [String: Money]],
        calendar: MarketCalendar
    ) -> BacktestReport? {
        guard !slices.isEmpty else { return nil }
        guard let dayBeforeStart = dayBefore(startDay, calendar: calendar) else { return nil }

        let firstDue = PieSchedule.nextDueDay(
            anchorDay: startDay, cadence: cadence, afterDay: dayBeforeStart, calendar: calendar
        )
        // nextDueDay falls back to returning `afterDay` unchanged on malformed input or
        // cap exhaustion (both unreachable for well-formed callers); guard defensively
        // since a returned day before `startDay` cannot be a genuine due day.
        guard firstDue >= startDay, firstDue <= endDay else { return nil }

        let laterDueDays = PieSchedule.dueDays(
            anchorDay: startDay, cadence: cadence, afterDay: firstDue, throughDay: endDay, calendar: calendar
        )
        let allDueDays = ([firstDue] + laterDueDays).sorted()

        let currencyCode = amount.currencyCode
        var quantities: [String: Decimal] = [:]
        var totalInvested = Decimal(0)
        var points: [BacktestPoint] = []
        var firstExecutedCloses: [String: Money]?

        for day in allDueDays {
            guard let closes = closesForAllSymbols(day: day, slices: slices, dailyCloses: dailyCloses) else {
                continue // Missing (or non-positive) close for at least one slice: skip this contribution.
            }

            var currentValues: [String: Money] = [:]
            for slice in slices {
                let qty = quantities[slice.symbol] ?? Decimal(0)
                let close = closes[slice.symbol]?.amount ?? Decimal(0)
                currentValues[slice.symbol] = Money(amount: qty * close, currencyCode: currencyCode)
            }

            let allocation = PieMath.distribute(contribution: amount, currentValues: currentValues, targets: slices)
            for slice in slices {
                guard let share = allocation[slice.symbol]?.amount, let close = closes[slice.symbol]?.amount else {
                    continue
                }
                let bought = share / close // Decimal division: full precision, never rounded.
                quantities[slice.symbol] = (quantities[slice.symbol] ?? Decimal(0)) + bought
            }

            totalInvested += amount.amount
            if firstExecutedCloses == nil {
                firstExecutedCloses = closes
            }

            let value = portfolioValue(quantities: quantities, closes: closes, slices: slices, currencyCode: currencyCode)
            points.append(BacktestPoint(
                day: day,
                invested: Money(amount: rounded2dp(totalInvested), currencyCode: currencyCode),
                value: value
            ))
        }

        guard let firstExecutedCloses else { return nil } // No due day was executable.

        // `closesOnOrBefore(endDay, ...)` is guaranteed to find at least the last
        // executed day above (it's <= endDay and already passed closesForAllSymbols),
        // so this nil branch is unreachable in practice; kept as a defensive fallback
        // rather than a force-unwrap.
        guard let (finalDay, finalCloses) = closesOnOrBefore(day: endDay, slices: slices, dailyCloses: dailyCloses)
        else { return nil }

        let finalValue = portfolioValue(quantities: quantities, closes: finalCloses, slices: slices, currencyCode: currencyCode)
        points.append(BacktestPoint(
            day: finalDay,
            invested: Money(amount: rounded2dp(totalInvested), currencyCode: currencyCode),
            value: finalValue
        ))

        let totalReturn = returnPercentage(invested: totalInvested, finalValue: finalValue.amount)

        // Lump sum: one distribute() of the whole total, on the first executable day,
        // valued at the same final closes used above.
        let lumpSumAllocation = PieMath.distribute(
            contribution: Money(amount: totalInvested, currencyCode: currencyCode),
            currentValues: [:],
            targets: slices
        )
        var lumpSumQuantities: [String: Decimal] = [:]
        for slice in slices {
            guard let share = lumpSumAllocation[slice.symbol]?.amount,
                  let close = firstExecutedCloses[slice.symbol]?.amount, close > 0
            else { continue }
            lumpSumQuantities[slice.symbol] = share / close
        }
        let lumpSumFinalValue = portfolioValue(
            quantities: lumpSumQuantities, closes: finalCloses, slices: slices, currencyCode: currencyCode
        )

        return BacktestReport(
            points: points,
            totalInvested: Money(amount: rounded2dp(totalInvested), currencyCode: currencyCode),
            finalValue: finalValue,
            totalReturn: totalReturn,
            lumpSumFinalValue: lumpSumFinalValue
        )
    }

    // MARK: - Helpers

    /// The calendar day immediately before `day`, in the same `yyyy-MM-dd` shape. `nil`
    /// on malformed input.
    private static func dayBefore(_ day: String, calendar: MarketCalendar) -> String? {
        guard let date = PieSchedule.date(fromDay: day, calendar: calendar) else { return nil }
        guard let previous = parsingCalendar.date(byAdding: .day, value: -1, to: date) else { return nil }
        return calendar.tradingDay(of: previous)
    }

    /// Every slice symbol's close on `day`, or `nil` if ANY slice symbol is missing a
    /// close (or has a non-positive close) on that day — mirrors the live
    /// "insufficient data" behavior of skipping the whole contribution rather than
    /// buying partially.
    private static func closesForAllSymbols(
        day: String, slices: [PieSlice], dailyCloses: [String: [String: Money]]
    ) -> [String: Money]? {
        var result: [String: Money] = [:]
        for slice in slices {
            guard let close = dailyCloses[slice.symbol]?[day], close.amount > 0 else { return nil }
            result[slice.symbol] = close
        }
        return result
    }

    /// The most recent day <= `day` (by date-string comparison) on which every slice
    /// symbol has a close, along with those closes. Candidate days are drawn from the
    /// first slice's close map — a day can only be valid if that slice also has a
    /// close on it, so no candidate is missed by restricting the scan to that map.
    private static func closesOnOrBefore(
        day: String, slices: [PieSlice], dailyCloses: [String: [String: Money]]
    ) -> (day: String, closes: [String: Money])? {
        guard let firstSymbol = slices.first?.symbol, let firstMap = dailyCloses[firstSymbol] else { return nil }
        let candidates = firstMap.keys.filter { $0 <= day }.sorted(by: >)
        for candidate in candidates {
            if let closes = closesForAllSymbols(day: candidate, slices: slices, dailyCloses: dailyCloses) {
                return (candidate, closes)
            }
        }
        return nil
    }

    /// Sum of quantity x close across `slices`, rounded to 2 dp as a `Money`.
    private static func portfolioValue(
        quantities: [String: Decimal], closes: [String: Money], slices: [PieSlice], currencyCode: String
    ) -> Money {
        var total = Decimal(0)
        for slice in slices {
            guard let close = closes[slice.symbol]?.amount else { continue }
            total += (quantities[slice.symbol] ?? Decimal(0)) * close
        }
        return Money(amount: rounded2dp(total), currencyCode: currencyCode)
    }

    /// `(finalValue / invested - 1) * 100`, rounded to 2 dp. Zero invested returns 0%.
    private static func returnPercentage(invested: Decimal, finalValue: Decimal) -> Percentage {
        guard invested != 0 else { return Percentage(value: 0) }
        let raw = ((finalValue / invested) - 1) * 100
        return Percentage(value: rounded2dp(raw))
    }

    /// Rounds a Decimal to 2 decimal places using NSDecimalRound with `.plain` rounding
    /// mode (mirrors `PieMath`'s private rounding helper).
    private static func rounded2dp(_ d: Decimal) -> Decimal {
        var result = d
        var input = d
        NSDecimalRound(&result, &input, 2, .plain)
        return result
    }
}
