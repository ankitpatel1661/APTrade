package com.aptrade.desktop.portfolio

import com.aptrade.desktop.l10n.L10n
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.shared.domain.TradeSide

/** Pure port of `Sources/APTradeApp/TradeSheet.swift`'s `attemptSubmit`/confirm-string
 *  logic (lines 42-58, 60-66). No Compose, no coroutines — a small value type the dialog
 *  renders directly and a truth table the tests can exercise without composables.
 *
 *  RECORDED MECHANISM DIVERGENCE: macOS presents the confirmation via the native
 *  `.confirmationDialog` sheet-of-a-sheet. Compose Desktop has no equivalent, so the
 *  dialog shows an in-panel confirm layer (same title/message/button semantics, different
 *  rendering surface) — see [TradeDialog]'s `showConfirm` state. */
enum class SubmitAction {
    /** [TradeSheet.swift:62] `showConfirm = true` — flag on, show the confirm layer first. */
    ShowConfirm,

    /** [TradeSheet.swift:64] `performSubmit()` — flag off, submit immediately. */
    SubmitDirectly,
}

/** [TradeSheet.swift:60-66] `attemptSubmit()`: the entire gating truth table in one pure
 *  function — `confirmTrades` is the snapshot taken once when the dialog opens (never
 *  re-read mid-dialog, matching the Swift `let confirmTrades: Bool` snapshot-at-init). */
fun attemptSubmit(confirmTrades: Boolean): SubmitAction =
    if (confirmTrades) SubmitAction.ShowConfirm else SubmitAction.SubmitDirectly

/** [TradeSheet.swift:50-53] `confirmTitle`: `"Buy %@ %@?"` / `"Sell %@ %@?"` — L10n
 *  `.confirmBuyTitleFormat` / `.confirmSellTitleFormat`, with the quantity text then the
 *  uppercased symbol substituted into the two placeholders in that order. */
fun confirmTitle(side: TradeSide, quantityText: String, symbol: String): String {
    val key = if (side == TradeSide.Buy) L10n.Key.ConfirmBuyTitleFormat else L10n.Key.ConfirmSellTitleFormat
    return trf(key, quantityText, symbol.uppercase())
}

/** [TradeSheet.swift:55-58] `confirmMessage`: `"%@: %@"` — L10n `.confirmEstimateFormat`,
 *  built from the side-dependent "Estimated cost"/"Estimated proceeds" label
 *  (`.estimatedCost` / `.estimatedProceeds`) and the dialog's own formatted estimate
 *  string (or the em dash placeholder when no estimate is available). */
fun confirmMessage(side: TradeSide, estimateDisplay: String): String {
    val label = tr(if (side == TradeSide.Buy) L10n.Key.EstimatedCost else L10n.Key.EstimatedProceeds)
    return trf(L10n.Key.ConfirmEstimateFormat, label, estimateDisplay)
}

/** [TradeSheet.swift:43] the confirm layer's action-button label — `.confirmBuy` /
 *  `.confirmSell` ("Confirm Buy" / "Confirm Sell"), independent of the title format. */
fun confirmActionLabel(side: TradeSide): String =
    tr(if (side == TradeSide.Buy) L10n.Key.ConfirmBuy else L10n.Key.ConfirmSell)
