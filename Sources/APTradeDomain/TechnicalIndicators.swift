import Foundation

/// Pure technical-analysis calculations over a series of closing prices. Each function
/// returns an array aligned 1:1 with the input, with `nil` during the warm-up period
/// before the indicator has enough data to be defined.
public enum TechnicalIndicators {
    /// Simple moving average over `period` values.
    public static func sma(_ values: [Double], period: Int) -> [Double?] {
        guard period > 0, values.count >= period else { return Array(repeating: nil, count: values.count) }
        var result = [Double?](repeating: nil, count: values.count)
        var window = 0.0
        for i in values.indices {
            window += values[i]
            if i >= period { window -= values[i - period] }
            if i >= period - 1 { result[i] = window / Double(period) }
        }
        return result
    }

    /// Exponential moving average, seeded with the SMA of the first `period` values.
    public static func ema(_ values: [Double], period: Int) -> [Double?] {
        guard period > 0, values.count >= period else { return Array(repeating: nil, count: values.count) }
        var result = [Double?](repeating: nil, count: values.count)
        let multiplier = 2.0 / (Double(period) + 1.0)
        let seed = values[0..<period].reduce(0, +) / Double(period)
        result[period - 1] = seed
        var previous = seed
        for i in period..<values.count {
            previous = (values[i] - previous) * multiplier + previous
            result[i] = previous
        }
        return result
    }

    /// Relative Strength Index using Wilder's smoothing. Values range 0...100.
    public static func rsi(_ values: [Double], period: Int = 14) -> [Double?] {
        guard period > 0, values.count > period else { return Array(repeating: nil, count: values.count) }
        var result = [Double?](repeating: nil, count: values.count)

        var gainSum = 0.0, lossSum = 0.0
        for i in 1...period {
            let change = values[i] - values[i - 1]
            if change >= 0 { gainSum += change } else { lossSum -= change }
        }
        var avgGain = gainSum / Double(period)
        var avgLoss = lossSum / Double(period)
        result[period] = rsiValue(avgGain: avgGain, avgLoss: avgLoss)

        for i in (period + 1)..<values.count {
            let change = values[i] - values[i - 1]
            let gain = max(change, 0)
            let loss = max(-change, 0)
            avgGain = (avgGain * Double(period - 1) + gain) / Double(period)
            avgLoss = (avgLoss * Double(period - 1) + loss) / Double(period)
            result[i] = rsiValue(avgGain: avgGain, avgLoss: avgLoss)
        }
        return result
    }

    private static func rsiValue(avgGain: Double, avgLoss: Double) -> Double {
        guard avgLoss > 0 else { return 100 }
        let rs = avgGain / avgLoss
        return 100 - 100 / (1 + rs)
    }

    /// Anchored VWAP: the volume-weighted average of typical prices, accumulated from the
    /// start of the series. Returns `nil` until cumulative volume is positive (e.g. for
    /// sources that don't report volume). `typicalPrices` and `volumes` must be equal length.
    public static func vwap(typicalPrices: [Double], volumes: [Double]) -> [Double?] {
        guard typicalPrices.count == volumes.count else {
            return Array(repeating: nil, count: typicalPrices.count)
        }
        var result = [Double?](repeating: nil, count: typicalPrices.count)
        var cumulativePV = 0.0
        var cumulativeVolume = 0.0
        for i in typicalPrices.indices {
            cumulativePV += typicalPrices[i] * volumes[i]
            cumulativeVolume += volumes[i]
            if cumulativeVolume > 0 { result[i] = cumulativePV / cumulativeVolume }
        }
        return result
    }

    /// Bollinger Bands: the SMA `middle` band with `upper`/`lower` bands set `multiplier`
    /// population standard deviations away. Aligned 1:1 with the input.
    public static func bollingerBands(_ values: [Double], period: Int = 20, multiplier: Double = 2)
        -> (upper: [Double?], middle: [Double?], lower: [Double?]) {
        let middle = sma(values, period: period)
        var upper = [Double?](repeating: nil, count: values.count)
        var lower = [Double?](repeating: nil, count: values.count)
        guard values.count >= period, period > 0 else { return (upper, middle, lower) }
        for i in (period - 1)..<values.count {
            guard let mean = middle[i] else { continue }
            let window = values[(i - period + 1)...i]
            let variance = window.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(period)
            let deviation = variance.squareRoot()
            upper[i] = mean + multiplier * deviation
            lower[i] = mean - multiplier * deviation
        }
        return (upper, middle, lower)
    }

    /// MACD: the `macd` line (fast EMA − slow EMA), its `signal` EMA, and the `histogram`
    /// (macd − signal). Aligned 1:1 with the input.
    public static func macd(_ values: [Double], fast: Int = 12, slow: Int = 26, signal: Int = 9)
        -> (macd: [Double?], signal: [Double?], histogram: [Double?]) {
        let fastEMA = ema(values, period: fast)
        let slowEMA = ema(values, period: slow)
        var macdLine = [Double?](repeating: nil, count: values.count)
        for i in values.indices {
            if let f = fastEMA[i], let s = slowEMA[i] { macdLine[i] = f - s }
        }

        var signalLine = [Double?](repeating: nil, count: values.count)
        if let start = macdLine.firstIndex(where: { $0 != nil }) {
            // macdLine is contiguous from `start` (slow EMA defines it), so a plain EMA aligns.
            let contiguous = macdLine[start...].compactMap { $0 }
            for (offset, value) in ema(contiguous, period: signal).enumerated() {
                signalLine[start + offset] = value
            }
        }

        var histogram = [Double?](repeating: nil, count: values.count)
        for i in values.indices {
            if let m = macdLine[i], let s = signalLine[i] { histogram[i] = m - s }
        }
        return (macdLine, signalLine, histogram)
    }
}
