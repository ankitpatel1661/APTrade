package com.aptrade.android.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.aptrade.shared.domain.TradeSide

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

/**
 * Bottom-sheet BUY/SELL form shared by [PortfolioScreen] (per-holding) and the DetailScreen.
 *
 * Success detection is caller-agnostic: the sheet snapshots [transactionCount] when it opens and
 * dismisses once it grows (a trade appended a transaction). On failure the count is unchanged, so
 * the sheet stays put and surfaces [tradeError] inline — but only after the user has attempted a
 * submit in THIS sheet ([hasSubmitted]), never a stale error from before it opened. All callbacks
 * are effect-scoped; never invoked during composition. Mirrors the desktop TradeDialog semantics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeSheet(
    info: TradeSheetInfo,
    tradeError: String?,
    transactionCount: Int,
    onSubmit: (TradeSide, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var side by remember { mutableStateOf(info.initialSide) }
    var quantity by remember { mutableStateOf("") }
    var hasSubmitted by remember { mutableStateOf(false) }
    val countAtOpen = remember(info) { transactionCount }
    LaunchedEffect(transactionCount) {
        if (transactionCount > countAtOpen) onDismiss()
    }

    val validQuantity = quantity.trim().toDoubleOrNull()?.let { it > 0.0 } == true

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
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
                onClick = { hasSubmitted = true; onSubmit(side, quantity) },
                enabled = validQuantity,
                modifier = Modifier.align(Alignment.End),
            ) { Text("Confirm") }
        }
    }
}
