import APTradeDomain

/// Localized display summary for a `PriceAlert`'s condition — `AlertCondition` is a domain
/// enum whose own `summary` returns English prose, so this extension maps it to a localized
/// string while leaving the embedded money/percent formatting untouched.
///
/// Extracted so `PriceAlertSheet` (per-symbol arm/view) and `AlertsCenterView` (cross-symbol
/// list) render identical condition text from ONE implementation rather than each keeping
/// their own copy.
extension AlertCondition {
    @MainActor
    var localizedSummary: String {
        switch self {
        case .priceAbove(let money):
            return String(format: tr(.priceAboveSummaryFormat), money.formatted)
        case .priceBelow(let money):
            return String(format: tr(.priceBelowSummaryFormat), money.formatted)
        case .percentChange(let pct):
            return String(format: tr(.percentMoveSummaryFormat), "\(abs(pct.value))")
        }
    }
}
