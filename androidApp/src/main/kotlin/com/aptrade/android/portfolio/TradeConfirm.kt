package com.aptrade.android.portfolio

import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.l10n.L10n

/** Pure port of desktop's `TradeConfirm.kt`, itself a port of `Sources/APTradeApp/
 *  TradeSheet.swift`'s `attemptSubmit`/confirm-string logic (lines 42-58, 60-66) — same
 *  functions, same shared L10n keys, no Compose/coroutines, a truth table the tests can
 *  exercise without composables.
 *
 *  RECORDED MECHANISM DIVERGENCE: macOS presents the confirmation via the native
 *  `.confirmationDialog` sheet-of-a-sheet; desktop Compose shows an in-panel confirm layer
 *  instead. Android's [TradeSheet] follows the same in-panel-confirm-layer shape as desktop
 *  (its `ModalBottomSheet` swaps its content for a confirm step) rather than a second,
 *  separate system sheet. */
enum class SubmitAction {
    /** `confirmTrades` on — show the confirm layer first. */
    ShowConfirm,

    /** `confirmTrades` off — submit immediately. */
    SubmitDirectly,
}

/** The entire gating truth table in one pure function — `confirmTrades` is the snapshot
 *  taken once when the sheet opens (never re-read mid-sheet), matching desktop/macOS. */
fun attemptSubmit(confirmTrades: Boolean): SubmitAction =
    if (confirmTrades) SubmitAction.ShowConfirm else SubmitAction.SubmitDirectly

/** `"Buy %@ %@?"` / `"Sell %@ %@?"` — L10n `.confirmBuyTitleFormat` / `.confirmSellTitleFormat`,
 *  with the quantity text then the uppercased symbol substituted into the two placeholders in
 *  that order. */
fun confirmTitle(side: TradeSide, quantityText: String, symbol: String): String {
    val key = if (side == TradeSide.Buy) L10n.Key.ConfirmBuyTitleFormat else L10n.Key.ConfirmSellTitleFormat
    return trf(key, quantityText, symbol.uppercase())
}

/** `"%@: %@"` — L10n `.confirmEstimateFormat`, built from the side-dependent "Estimated
 *  cost"/"Estimated proceeds" label (`.estimatedCost` / `.estimatedProceeds`) and the sheet's
 *  own formatted estimate string (or the em dash placeholder when no estimate is available). */
fun confirmMessage(side: TradeSide, estimateDisplay: String): String {
    val label = tr(if (side == TradeSide.Buy) L10n.Key.EstimatedCost else L10n.Key.EstimatedProceeds)
    return trf(L10n.Key.ConfirmEstimateFormat, label, estimateDisplay)
}

/** The confirm layer's action-button label — `.confirmBuy` / `.confirmSell` ("Confirm Buy" /
 *  "Confirm Sell"), independent of the title format. */
fun confirmActionLabel(side: TradeSide): String =
    tr(if (side == TradeSide.Buy) L10n.Key.ConfirmBuy else L10n.Key.ConfirmSell)
