package com.aptrade.android.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.money
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.TradeSide
import com.aptrade.shared.l10n.L10n
import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** The asset a [TradeSheet] is opened against: the identity (symbol/name), an optional
 *  pre-formatted price to display, and the side the caller pre-selected. Decouples the sheet
 *  from any one screen's row model so both the Portfolio holdings list and the DetailScreen
 *  can drive it. */
data class TradeSheetInfo(
    val symbol: String,
    val name: String,
    val priceText: String?,
    val initialSide: TradeSide,
)

/** Strips `money()`'s currency symbol and thousands-grouping commas from a display-formatted
 *  amount (e.g. `"$63,758.00"` -> `"63758.00"`, `"-$12.50"` -> `"-12.50"`) so it can be re-parsed
 *  by [Money.usd], which expects a raw decimal string.
 *
 *  ROOT CAUSE (UAT round 2, crash on confirm layer): unlike desktop's `TradeDialog`, which
 *  threads a raw `Quote.price.amountText` through as `priceText` and only formats it for
 *  display at the point of use (`PortfolioViewModel.kt`'s "RAW — TradeDialog re-parses +
 *  SuperscriptPrice" comment), Android's [TradeSheetInfo.priceText] is already `money()`-
 *  formatted at the source (`DetailViewModel`/`PortfolioViewModel`, for the form's AssistChip).
 *  [estimateDisplay] fed that formatted string straight into `Money.usd(it)` ->
 *  `BigDecimal.parseString`, which throws `NumberFormatException: Invalid digit for radix $`
 *  the instant the confirm layer first composes and calls this function — killing the app
 *  before the confirmation layer could ever appear. Stripping the formatting back out here
 *  (Android-only; [TradeSheetInfo.priceText]'s existing display contract is unchanged) fixes it
 *  without touching the shared `Money`/`L10n` catalog or desktop's own (already-raw) path.
 *
 *  SAFETY INVARIANT: this strip is only correct while `ui/Mappers.kt`'s `money()` stays pinned
 *  to `Locale.US` ("$"/"," with "." decimals). If `money()` ever localizes its separators,
 *  thread the raw `Money.amountText` through [TradeSheetInfo] instead of re-parsing display
 *  output — `TradeSheetTest` only covers the current US shape. */
internal fun rawAmountText(displayText: String): String =
    displayText.filter { it.isDigit() || it == '.' || it == '-' }

/** Quantity × price as a display-formatted string ([com.aptrade.android.ui.money]), or the
 *  em dash placeholder when the quantity doesn't parse to a positive decimal or no live price
 *  is available. Mirrors desktop `TradeFormState.estimateText` + the confirm layer's own
 *  `estimateDisplay` fallback (`TradeDialog.kt`) — exact decimal math via bignum `BigDecimal`,
 *  never `Double`. */
internal fun estimateDisplay(priceText: String?, quantityText: String): String {
    val quantity = quantityText.trim().let {
        if (it.isEmpty()) return@let null
        try {
            val value = BigDecimal.parseString(it)
            if (value.isZero() || value.isNegative) null else value
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }
    val price = priceText?.let {
        try {
            Money.usd(rawAmountText(it))
        } catch (e: ArithmeticException) {
            null
        } catch (e: NumberFormatException) {
            null
        }
    }
    if (quantity == null || price == null) return "—"
    return money((price.amount * quantity).toStringExpanded())
}

/**
 * Bottom-sheet BUY/SELL form shared by [PortfolioScreen] (per-holding) and the DetailScreen.
 *
 * Success detection is caller-agnostic: the sheet snapshots [transactionCount] when it opens and
 * dismisses once it grows (a trade appended a transaction). On failure the count is unchanged, so
 * the sheet stays put and surfaces [tradeError] inline — but only after the user has attempted a
 * submit in THIS sheet ([hasSubmitted]), never a stale error from before it opened. All callbacks
 * are effect-scoped; never invoked during composition. Mirrors the desktop TradeDialog semantics.
 *
 * [confirmTrades] is a snapshot taken once by the caller when the sheet opens (mirroring desktop
 * `TradeDialog`'s own `confirmTrades: Boolean` — see its KDoc): this composable never re-reads
 * the live setting mid-sheet. When true, Confirm swaps the form for an in-sheet confirmation
 * layer (side/symbol/quantity/estimated value, from the pure `TradeConfirm.kt` — spec A4) before
 * calling [onSubmit]; when false, Confirm calls [onSubmit] immediately (unchanged behavior).
 *
 * RECORDED MECHANISM DIVERGENCE (mirrors desktop's own recorded divergence from macOS's native
 * `.confirmationDialog`): the confirm step is an in-sheet content swap rather than a second,
 * separate system sheet — the closest equivalent `ModalBottomSheet` supports. The sheet's
 * dismiss request (scrim tap / system back) is layered the same way desktop layers Esc: while
 * the confirm layer is showing, it dismisses ONLY the confirm layer (back to the form); a second
 * dismiss is needed to close the whole sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeSheet(
    info: TradeSheetInfo,
    tradeError: String?,
    transactionCount: Int,
    confirmTrades: Boolean,
    onSubmit: (TradeSide, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var side by remember { mutableStateOf(info.initialSide) }
    var quantity by remember { mutableStateOf("") }
    var hasSubmitted by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    val countAtOpen = remember(info) { transactionCount }
    LaunchedEffect(transactionCount) {
        if (transactionCount > countAtOpen) onDismiss()
    }

    val validQuantity = quantity.trim().toDoubleOrNull()?.let { it > 0.0 } == true
    val performSubmit = { hasSubmitted = true; onSubmit(side, quantity) }
    // The gating logic itself lives in the pure, tested attemptSubmit() (TradeConfirm.kt).
    val attemptSubmitAction = {
        when (attemptSubmit(confirmTrades)) {
            SubmitAction.ShowConfirm -> showConfirm = true
            SubmitAction.SubmitDirectly -> performSubmit()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (showConfirm) showConfirm = false else onDismiss() },
        sheetState = sheetState,
    ) {
        if (showConfirm) {
            ConfirmLayer(
                side = side,
                quantityText = quantity,
                symbol = info.symbol,
                estimateDisplay = estimateDisplay(info.priceText, quantity),
                onConfirm = { showConfirm = false; performSubmit() },
                onCancel = { showConfirm = false },
            )
        } else {
            Column(
                Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.symbol, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            info.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    info.priceText?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                }

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = side == TradeSide.Buy,
                        onClick = { side = TradeSide.Buy },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("BUY") }
                    SegmentedButton(
                        selected = side == TradeSide.Sell,
                        onClick = { side = TradeSide.Sell },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("SELL") }
                }

                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (hasSubmitted && tradeError != null) {
                    Text(tradeError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                TextButton(
                    onClick = attemptSubmitAction,
                    enabled = validQuantity,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Confirm") }
            }
        }
    }
}

/** The in-sheet confirm layer (RECORDED MECHANISM DIVERGENCE — see [TradeSheet]'s KDoc).
 *  Title/message text come from the pure, tested `TradeConfirm.kt` builders, which mirror
 *  desktop/macOS's confirmation dialog title/message/buttons exactly. */
@Composable
private fun ConfirmLayer(
    side: TradeSide,
    quantityText: String,
    symbol: String,
    estimateDisplay: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(confirmTitle(side, quantityText, symbol), style = MaterialTheme.typography.headlineSmall)
        Text(
            confirmMessage(side, estimateDisplay),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(tr(L10n.Key.Cancel)) }
            TextButton(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmActionLabel(side)) }
        }
    }
}
