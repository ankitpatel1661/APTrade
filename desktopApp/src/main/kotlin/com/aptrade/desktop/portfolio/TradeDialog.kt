package com.aptrade.desktop.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.SuperscriptPrice
import com.aptrade.desktop.designkit.splitPrice
import com.aptrade.shared.l10n.L10n
import com.aptrade.desktop.l10n.tr
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.TradeSide

/** Modal paper-trade sheet — the Compose port of `Sources/APTradeApp/TradeSheet.swift`. A dim
 *  scrim over a 420dp surface panel: asset header with the live price, a Buy/Sell segmented
 *  toggle, a plain quantity field, an estimated-cost/proceeds row, and Cancel / Confirm.
 *
 *  All derived state (parsed quantity, estimate, submit-enabled) comes from the pure
 *  [TradeFormState]; the live price is reconstructed from its lossless `amountText` bridge
 *  string via [Money.usd] purely to feed that helper. Confirm delegates to `onSubmit` and the
 *  dialog closes on success (signalled by `tradeError` clearing and the caller dismissing).
 *
 *  [confirmTrades] is a snapshot taken once by the caller when the dialog opens (Main.kt
 *  reads `notificationSettings.confirmTrades` into a `remember(target)`), matching
 *  `TradeSheet.swift:12-18`'s `let confirmTrades: Bool` snapshot-at-init — this composable
 *  never re-reads the live setting mid-dialog. [attemptSubmit] (`TradeConfirm.kt`) gates
 *  whether Buy/Sell shows the in-dialog confirm layer first or calls `onSubmit` immediately.
 *
 *  RECORDED MECHANISM DIVERGENCE: macOS presents the confirmation via a native
 *  `.confirmationDialog` (a sheet-of-a-sheet). Compose Desktop has no equivalent, so
 *  `showConfirm` swaps the panel's body for an in-panel confirm layer with the same
 *  title/message/button semantics (`TradeSheet.swift:42-47`).
 *
 *  Esc is consumed here on the panel's own preview-key handler so it never reaches the
 *  window's Esc-priority chain — see the ownership note in Main.kt. Esc ownership within
 *  this dialog is layered: while the confirm layer is showing, Esc dismisses ONLY the confirm
 *  layer (back to the form); a second Esc is needed to dismiss the whole dialog. */
@Composable
fun TradeDialog(
    asset: Asset,
    initialSide: TradeSide,
    priceText: String?,
    tradeError: String?,
    confirmTrades: Boolean,
    onSubmit: (side: TradeSide, quantityText: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var side by remember { mutableStateOf(initialSide) }
    var quantityText by remember { mutableStateOf("") }
    // The VM's tradeError is shared and may be stale from a prior dialog; only surface it once
    // the user has actually attempted a submit in THIS dialog instance.
    var hasSubmitted by remember { mutableStateOf(false) }
    // The in-dialog confirm layer (RECORDED MECHANISM DIVERGENCE, see doc comment above).
    var showConfirm by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val form = TradeFormState(side, priceText, quantityText)
    val price: Money? = priceText?.let { Money.usd(it) }
    val estimateRaw = form.estimateText(price)
    val canSubmit = form.canSubmit()
    val performSubmit = { hasSubmitted = true; onSubmit(side, quantityText) }
    // TradeSheet.swift:60-66 attemptSubmit: flag on → show the confirm layer; flag off →
    // submit immediately. Gating logic itself lives in the pure, tested attemptSubmit().
    val attemptSubmit = {
        when (attemptSubmit(confirmTrades)) {
            SubmitAction.ShowConfirm -> showConfirm = true
            SubmitAction.SubmitDirectly -> performSubmit()
        }
    }
    // splitPrice-formatted display string, reused by both the estimate row and the confirm
    // layer's message so the two never disagree on formatting.
    val estimateDisplay = estimateRaw?.let { splitPrice(it).let { p -> "$" + p.whole + "." + p.fraction } } ?: "—"

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // Click-off the scrim dismisses; clicks on the panel are swallowed below.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                // Consume Esc before the window's preview handler; also eat scrim clicks.
                // Esc ownership is layered here: the confirm layer (if showing) claims Esc
                // first and only closes itself, returning to the form — a second Esc is
                // needed to close the whole dialog. This extends the existing Esc-ownership
                // chain (AccountPanel, PaletteOverlay) one level deeper.
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        if (showConfirm) showConfirm = false else onDismiss()
                        true
                    } else {
                        false
                    }
                }
                .focusRequester(focusRequester)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* absorb: keep the panel from dismissing itself */ }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (showConfirm) {
                ConfirmLayer(
                    side = side,
                    quantityText = quantityText,
                    symbol = asset.symbol,
                    estimateDisplay = estimateDisplay,
                    onConfirm = { showConfirm = false; performSubmit() },
                    onCancel = { showConfirm = false },
                )
            } else {
                Header(asset = asset, priceText = priceText)
                SideToggle(
                    side = side,
                    onSelect = {
                        if (it != side) {
                            side = it
                            quantityText = ""
                        }
                    },
                )
                QuantityField(
                    value = quantityText,
                    onValueChange = { quantityText = it },
                    focusRequester = focusRequester,
                    onSubmit = { if (canSubmit) attemptSubmit() },
                )
                EstimateRow(side = side, estimateRaw = estimateRaw)
                if (hasSubmitted && tradeError != null) {
                    Text(
                        tradeError,
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium, color = DK.down,
                        ),
                    )
                }
                Text(
                    tr(L10n.Key.SimulatedPaperTradingFooter),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = DK.textTertiary, letterSpacing = 0.6.sp,
                    ),
                )
                Actions(
                    side = side,
                    canSubmit = canSubmit,
                    onCancel = onDismiss,
                    onConfirm = attemptSubmit,
                )
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** The in-dialog confirm layer (RECORDED MECHANISM DIVERGENCE — see [TradeDialog]'s doc
 *  comment). Title/message text come from the pure, tested `TradeConfirm.kt` builders, which
 *  mirror `TradeSheet.swift:42-47`'s `.confirmationDialog` title/message/buttons exactly. */
@Composable
private fun ConfirmLayer(
    side: TradeSide,
    quantityText: String,
    symbol: String,
    estimateDisplay: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        confirmTitle(side, quantityText, symbol),
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
        ),
    )
    Text(
        confirmMessage(side, estimateDisplay),
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, color = DK.textSecondary,
        ),
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onCancel() }
                .padding(vertical = 12.dp),
        ) {
            Text(
                tr(L10n.Key.Cancel),
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textSecondary,
                ),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .background(Brush.linearGradient(listOf(DK.goldDeep, DK.gold, DK.goldLight)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onConfirm() }
                .padding(vertical = 12.dp),
        ) {
            Text(
                confirmActionLabel(side),
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, color = DK.bgBottom,
                ),
            )
        }
    }
}

@Composable
private fun Header(asset: Asset, priceText: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            asset.symbol.uppercase(),
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = DK.gold, letterSpacing = 2.sp,
            ),
        )
        Text(
            asset.name,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, color = DK.textPrimary,
            ),
        )
        Spacer(Modifier.height(2.dp))
        if (priceText != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr(L10n.Key.MarketPrice),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, color = DK.textSecondary,
                    ),
                )
                SuperscriptPrice(amountText = priceText, size = 24.sp)
            }
        }
    }
}

@Composable
private fun SideToggle(side: TradeSide, onSelect: (TradeSide) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        for (option in listOf(TradeSide.Buy, TradeSide.Sell)) {
            val selected = option == side
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) DK.surfaceHi else Color.Transparent)
                    .then(
                        if (selected) Modifier.border(1.dp, DK.gold.copy(alpha = 0.40f), RoundedCornerShape(50))
                        else Modifier,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(option) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    tr(if (option == TradeSide.Buy) L10n.Key.Buy else L10n.Key.Sell),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (selected) DK.textPrimary else DK.textSecondary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun QuantityField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(
            tr(L10n.Key.QuantityLabel),
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = DK.textTertiary, letterSpacing = 1.sp,
            ),
        )
        Box {
            if (value.isEmpty()) {
                Text(
                    "0",
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                        color = DK.textTertiary, fontFeatureSettings = "tnum",
                    ),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(DK.gold),
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        fontFamily = InterFamily, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                        color = DK.textPrimary, fontFeatureSettings = "tnum",
                    ),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (event.type == KeyEventType.KeyDown && isEnter) {
                            onSubmit(); true
                        } else {
                            false
                        }
                    },
            )
        }
    }
}

@Composable
private fun EstimateRow(side: TradeSide, estimateRaw: String?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr(if (side == TradeSide.Buy) L10n.Key.EstimatedCost else L10n.Key.EstimatedProceeds),
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, color = DK.textSecondary,
            ),
        )
        // `estimateRaw` is a plain decimal string from TradeFormState; group + prefix it with
        // splitPrice (the same pure string helper the detail StatGrid uses), never Money math.
        val display = estimateRaw?.let { splitPrice(it).let { p -> "$" + p.whole + "." + p.fraction } } ?: "—"
        Text(
            display,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = DK.textPrimary, fontFeatureSettings = "tnum",
            ),
        )
    }
}

@Composable
private fun Actions(
    side: TradeSide,
    canSubmit: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onCancel() }
                .padding(vertical = 12.dp),
        ) {
            Text(
                tr(L10n.Key.Cancel),
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textSecondary,
                ),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .background(if (canSubmit) Brush.linearGradient(listOf(DK.goldDeep, DK.gold, DK.goldLight)) else SolidColor(DK.surface))
                .then(
                    if (canSubmit) Modifier
                    else Modifier.border(1.dp, DK.hairline, RoundedCornerShape(50)),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = canSubmit,
                ) { onConfirm() }
                .padding(vertical = 12.dp),
        ) {
            Text(
                tr(if (side == TradeSide.Buy) L10n.Key.Buy else L10n.Key.Sell),
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = if (canSubmit) DK.bgBottom else DK.textTertiary,
                ),
            )
        }
    }
}
