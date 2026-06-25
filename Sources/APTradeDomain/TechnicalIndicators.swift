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
}
