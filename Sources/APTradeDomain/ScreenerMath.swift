import Foundation

/// One symbol's fully computed technical snapshot. All metrics nullable —
/// insufficient history yields nil, never a crash.
public struct ScreenerSnapshotRow: Equatable, Codable, Sendable, Identifiable {
    public var id: String { symbol }
    public let symbol: String
    public let name: String
    public let close: Double
    public let dayChangePercent: Double?
    public let rsi14: Double?
    public let macd: Double?
    public let macdSignal: Double?
    public let macdHistogram: Double?
    public let sma50: Double?
    public let sma200: Double?
    public let ema20: Double?
    /// (close − sma50)/sma50 × 100
    public let pctVsSma50: Double?
    public let pctVsSma200: Double?
    /// (close − lower)/(upper − lower). nil when upper == lower (zero-variance/flat-price
    /// window) — the division would be 0/0, so this degrades to nil rather than NaN.
    public let bollingerPercentB: Double?
    /// (upper − lower)/middle. On a flat-price window upper == lower, so this is 0.0 exactly
    /// (a well-defined zero, not nil) — bandwidth of 0 correctly says "no band width."
    public let bollingerBandwidth: Double?
    /// Max/min of the highs/lows across *whatever candles the caller passed in* — ScreenerMath
    /// does no date filtering of its own. The caller is responsible for supplying ~1 year of
    /// daily candles; if it passes more or less, these silently reflect that different window
    /// rather than a true rolling 52-week range.
    public let week52High: Double?
    public let week52Low: Double?
    /// (high − close)/high × 100, ≥ 0
    public let pctTo52wHigh: Double?
    /// (close − low)/low × 100, ≥ 0
    public let pctTo52wLow: Double?
    /// today ÷ mean(last 20 daily volumes). The 20-day window is INCLUSIVE of today's own
    /// volume (`volumes.suffix(20)`, the trailing window ending today) — chosen deliberately;
    /// preserve this convention in any transcription (e.g. the Kotlin port), since inclusive
    /// vs. exclusive materially changes the value. nil when that average is 0.
    public let relativeVolume: Double?
    /// histogram ≤ 0 yesterday, > 0 today
    public let macdCrossedUp: Bool
    public let macdCrossedDown: Bool
    public let goldenCross: Bool
    public let deathCross: Bool

    public init(
        symbol: String,
        name: String,
        close: Double,
        dayChangePercent: Double?,
        rsi14: Double?,
        macd: Double?,
        macdSignal: Double?,
        macdHistogram: Double?,
        sma50: Double?,
        sma200: Double?,
        ema20: Double?,
        pctVsSma50: Double?,
        pctVsSma200: Double?,
        bollingerPercentB: Double?,
        bollingerBandwidth: Double?,
        week52High: Double?,
        week52Low: Double?,
        pctTo52wHigh: Double?,
        pctTo52wLow: Double?,
        relativeVolume: Double?,
        macdCrossedUp: Bool,
        macdCrossedDown: Bool,
        goldenCross: Bool,
        deathCross: Bool
    ) {
        self.symbol = symbol
        self.name = name
        self.close = close
        self.dayChangePercent = dayChangePercent
        self.rsi14 = rsi14
        self.macd = macd
        self.macdSignal = macdSignal
        self.macdHistogram = macdHistogram
        self.sma50 = sma50
        self.sma200 = sma200
        self.ema20 = ema20
        self.pctVsSma50 = pctVsSma50
        self.pctVsSma200 = pctVsSma200
        self.bollingerPercentB = bollingerPercentB
        self.bollingerBandwidth = bollingerBandwidth
        self.week52High = week52High
        self.week52Low = week52Low
        self.pctTo52wHigh = pctTo52wHigh
        self.pctTo52wLow = pctTo52wLow
        self.relativeVolume = relativeVolume
        self.macdCrossedUp = macdCrossedUp
        self.macdCrossedDown = macdCrossedDown
        self.goldenCross = goldenCross
        self.deathCross = deathCross
    }
}

/// Builds per-symbol technical snapshots from daily candle history.
public enum ScreenerMath {
    /// Builds one row from ascending daily candles (oldest first, last = today). Needs ≥ 2
    /// bars for day change, ≥ 201 for SMA-200/crosses; every metric degrades to nil
    /// independently on short history — never a crash. Cross flags need BOTH yesterday's and
    /// today's indicator values, computed here because only the scanner sees the full series.
    ///
    /// nil only when `candles` is empty (no close at all).
    public static func snapshot(symbol: String, name: String, candles: [Candle]) -> ScreenerSnapshotRow? {
        guard !candles.isEmpty else { return nil }

        let closes = candles.map { ($0.close.amount as NSDecimalNumber).doubleValue }
        let highs = candles.map { ($0.high.amount as NSDecimalNumber).doubleValue }
        let lows = candles.map { ($0.low.amount as NSDecimalNumber).doubleValue }
        let volumes = candles.map { $0.volume }

        let close = closes[closes.count - 1]

        let dayChangePercent: Double? = {
            guard closes.count >= 2, closes[closes.count - 2] != 0 else { return nil }
            return (closes[closes.count - 1] - closes[closes.count - 2]) / closes[closes.count - 2] * 100
        }()

        let rsiSeries = TechnicalIndicators.rsi(closes, period: 14)
        let rsi14 = rsiSeries.last ?? nil

        let macdResult = TechnicalIndicators.macd(closes)
        let macd = macdResult.macd.last ?? nil
        let macdSignal = macdResult.signal.last ?? nil
        let macdHistogram = macdResult.histogram.last ?? nil

        let sma50Series = TechnicalIndicators.sma(closes, period: 50)
        let sma50 = sma50Series.last ?? nil
        let sma200Series = TechnicalIndicators.sma(closes, period: 200)
        let sma200 = sma200Series.last ?? nil
        let ema20Series = TechnicalIndicators.ema(closes, period: 20)
        let ema20 = ema20Series.last ?? nil

        let pctVsSma50 = sma50.flatMap { $0 != 0 ? (close - $0) / $0 * 100 : nil }
        let pctVsSma200 = sma200.flatMap { $0 != 0 ? (close - $0) / $0 * 100 : nil }

        let bands = TechnicalIndicators.bollingerBands(closes)
        let upper = bands.upper.last ?? nil
        let middle = bands.middle.last ?? nil
        let lower = bands.lower.last ?? nil
        let bollingerPercentB: Double? = {
            // u == l on a flat-price window (zero variance): (close-l)/(u-l) would be 0/0 → nil.
            guard let u = upper, let l = lower, u != l else { return nil }
            return (close - l) / (u - l)
        }()
        let bollingerBandwidth: Double? = {
            // u == l on a flat-price window collapses this to 0.0/m == 0.0 (a real, defined
            // zero-width band), not nil — only missing inputs (nil bands) yield nil here.
            guard let u = upper, let l = lower, let m = middle, m != 0 else { return nil }
            return (u - l) / m
        }()

        let week52High = highs.max()
        let week52Low = lows.min()
        let pctTo52wHigh = week52High.flatMap { $0 != 0 ? (($0 - close) / $0) * 100 : nil }
        let pctTo52wLow = week52Low.flatMap { $0 != 0 ? ((close - $0) / $0) * 100 : nil }

        let relativeVolume: Double? = {
            // INCLUSIVE 20-day window: `suffix(20)` is the trailing window ending at (and
            // including) today, so today's own volume is counted in both the numerator and
            // the average it's divided by. This is deliberate, not incidental — do not change
            // to `dropLast().suffix(20)` (exclusive) without updating every consumer/port,
            // since inclusive vs. exclusive materially changes the resulting ratio.
            let window = volumes.suffix(20)
            guard !window.isEmpty else { return nil }
            let average = window.reduce(0, +) / Double(window.count)
            guard average > 0 else { return nil }
            return volumes[volumes.count - 1] / average
        }()

        return ScreenerSnapshotRow(
            symbol: symbol,
            name: name,
            close: close,
            dayChangePercent: dayChangePercent,
            rsi14: rsi14,
            macd: macd,
            macdSignal: macdSignal,
            macdHistogram: macdHistogram,
            sma50: sma50,
            sma200: sma200,
            ema20: ema20,
            pctVsSma50: pctVsSma50,
            pctVsSma200: pctVsSma200,
            bollingerPercentB: bollingerPercentB,
            bollingerBandwidth: bollingerBandwidth,
            week52High: week52High,
            week52Low: week52Low,
            pctTo52wHigh: pctTo52wHigh,
            pctTo52wLow: pctTo52wLow,
            relativeVolume: relativeVolume,
            macdCrossedUp: crossedUp(series: macdResult.histogram),
            macdCrossedDown: crossedDown(series: macdResult.histogram),
            goldenCross: crossedUp(series: sma50Series, versus: sma200Series),
            deathCross: crossedDown(series: sma50Series, versus: sma200Series)
        )
    }

    /// True when `series` was ≤ 0 at index n−2 and > 0 at index n−1 (both non-nil).
    private static func crossedUp(series: [Double?]) -> Bool {
        guard series.count >= 2,
              let yesterday = series[series.count - 2],
              let today = series[series.count - 1]
        else { return false }
        return yesterday <= 0 && today > 0
    }

    /// True when `series` was ≥ 0 at index n−2 and < 0 at index n−1 (both non-nil).
    private static func crossedDown(series: [Double?]) -> Bool {
        guard series.count >= 2,
              let yesterday = series[series.count - 2],
              let today = series[series.count - 1]
        else { return false }
        return yesterday >= 0 && today < 0
    }

    /// True when `series` was ≤ `versus` at index n−2 and > `versus` at index n−1 (all four non-nil).
    private static func crossedUp(series: [Double?], versus other: [Double?]) -> Bool {
        guard series.count >= 2, other.count >= 2,
              let ySelf = series[series.count - 2], let yOther = other[other.count - 2],
              let tSelf = series[series.count - 1], let tOther = other[other.count - 1]
        else { return false }
        return ySelf <= yOther && tSelf > tOther
    }

    /// True when `series` was ≥ `versus` at index n−2 and < `versus` at index n−1 (all four non-nil).
    private static func crossedDown(series: [Double?], versus other: [Double?]) -> Bool {
        guard series.count >= 2, other.count >= 2,
              let ySelf = series[series.count - 2], let yOther = other[other.count - 2],
              let tSelf = series[series.count - 1], let tOther = other[other.count - 1]
        else { return false }
        return ySelf >= yOther && tSelf < tOther
    }
}

/// One scanner run's results across all symbols in scope.
public struct ScreenerSnapshot: Equatable, Codable, Sendable {
    /// `MarketCalendar` day-string (`yyyy-MM-dd`) of `scannedAt`, used to gate once-per-day scans.
    public let tradingDay: String
    public let scannedAt: Date
    public let rows: [ScreenerSnapshotRow]
    /// Symbols whose data fetch failed and were excluded from `rows`.
    public let failedSymbols: [String]

    public init(tradingDay: String, scannedAt: Date, rows: [ScreenerSnapshotRow], failedSymbols: [String]) {
        self.tradingDay = tradingDay
        self.scannedAt = scannedAt
        self.rows = rows
        self.failedSymbols = failedSymbols
    }
}
