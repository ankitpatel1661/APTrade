package com.aptrade.android.ui

import com.aptrade.android.l10n.trf
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.l10n.L10n
import kotlin.math.abs

/**
 * Localized display summary for a [PriceAlert][com.aptrade.shared.domain.PriceAlert]'s
 * [AlertCondition] — computed HERE rather than via `AlertCondition.summary` (the `:shared`
 * commonMain getter is deliberately English-only) against the catalog's pre-provisioned
 * `priceAboveSummaryFormat`/`priceBelowSummaryFormat`/`percentMoveSummaryFormat` Keys.
 *
 * Compose-Android port of desktop `designkit/AlertConditionSummary.kt`'s `alertSummary`
 * (M10.2 Task 5's extraction, itself following Swift T3's `AlertCondition+Summary.swift`
 * pattern): extracted to ONE shared `internal` helper so [com.aptrade.android.watchlist
 * .PriceAlertSheet] (per-symbol arm/view) and [com.aptrade.android.alerts.AlertsCenterSheet]
 * (M10.3 Task 4's cross-symbol list) both render identical condition text from a single
 * implementation rather than each keeping their own private copy. Was previously a private
 * function inside `watchlist/PriceAlertSheet.kt`; Task 4 hoists it here, the same
 * package-neutral move desktop's own Task 5 made, now that a second caller exists.
 */
internal fun alertSummary(condition: AlertCondition): String = when (condition) {
    is AlertCondition.PriceAbove -> trf(L10n.Key.PriceAboveSummaryFormat, condition.threshold.formatted)
    is AlertCondition.PriceBelow -> trf(L10n.Key.PriceBelowSummaryFormat, condition.threshold.formatted)
    is AlertCondition.PercentChange -> trf(L10n.Key.PercentMoveSummaryFormat, abs(condition.magnitude))
}
