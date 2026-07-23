package com.aptrade.desktop.designkit

import com.aptrade.desktop.l10n.trf
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.l10n.L10n

/**
 * Localized display summary for a [PriceAlert][com.aptrade.shared.domain.PriceAlert]'s
 * [AlertCondition] — computed HERE rather than via `AlertCondition.summary` (the `:shared`
 * commonMain getter is deliberately English-only — see its doc comment — and out of scope
 * for this desktop-only retrofit). Mirrors the exact shape of `AlertCondition.summary`
 * (`Money.formatted` for the threshold, `abs(magnitude)` for the percent figure) against
 * the catalog's pre-provisioned `priceAboveSummaryFormat`/`priceBelowSummaryFormat`/
 * `percentMoveSummaryFormat` Keys.
 *
 * Compose-port counterpart of `Sources/APTradeApp/AlertCondition+Summary.swift`'s
 * `localizedSummary` extension (Swift T3 pattern): extracted to ONE shared helper so
 * `PriceAlertSheet` (per-symbol arm/view), `HomePane`'s Alerts card 2-row preview, and
 * `AlertsCenterDialog` (M10.2 Task 5's cross-symbol list) all render identical condition
 * text from a single implementation rather than each keeping their own copy. Originally
 * lived as an `internal` function inside `watchlist/PriceAlertSheet.kt` (Task 4's minimal
 * widened-visibility step); Task 5 extracts it the rest of the way into this standalone,
 * package-neutral file now that a third caller (the Alerts center) exists.
 */
internal fun alertSummary(condition: AlertCondition): String = when (condition) {
    is AlertCondition.PriceAbove -> trf(L10n.Key.PriceAboveSummaryFormat, condition.threshold.formatted)
    is AlertCondition.PriceBelow -> trf(L10n.Key.PriceBelowSummaryFormat, condition.threshold.formatted)
    is AlertCondition.PercentChange -> trf(L10n.Key.PercentMoveSummaryFormat, kotlin.math.abs(condition.magnitude))
}
