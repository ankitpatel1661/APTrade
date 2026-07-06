package com.aptrade.desktop.watchlist

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.l10n.L10n
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.shared.domain.AlertCondition
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.PriceAlert

/** Modal price-alert dialog — the Compose port of `Sources/APTradeApp/PriceAlertSheet.swift`.
 *  A dim scrim over a 360dp surface panel (TradeDialog's overlay idiom): asset header +
 *  close, an existing-alerts list (bell icon, summary, delete, strikethrough+grey once
 *  triggered), a segmented Price above / Price below / % move control, a labeled decimal
 *  field, and a gold "Add Alert" button gated on [AlertFormState.isValid].
 *
 *  Esc is consumed here on the panel's own preview-key handler so it never reaches the
 *  window's Esc-priority chain — same ownership pattern as TradeDialog/AccountPanel. */
@Composable
fun PriceAlertSheet(
    asset: Asset,
    currentPriceText: String?,
    existing: List<PriceAlert>,
    onCreate: (AlertCondition) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(AlertKind.Below) }
    var priceText by remember { mutableStateOf("") }
    var percentText by remember { mutableStateOf("5") }
    val focusRequester = remember { FocusRequester() }

    val form = AlertFormState(kind, priceText, percentText)
    val canAdd = form.isValid()
    val addAlert = {
        form.toCondition()?.let(onCreate)
        onDismiss()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                // Consume Esc before the window's preview handler (TradeDialog pattern).
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss(); true
                    } else {
                        false
                    }
                }
                .focusRequester(focusRequester)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* absorb: keep the panel from dismissing itself */ },
        ) {
            Header(asset = asset, onDismiss = onDismiss)

            if (existing.isNotEmpty()) {
                DkDivider()
                ExistingAlerts(existing = existing, onDelete = onDelete)
            }

            DkDivider()

            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(20.dp),
            ) {
                if (currentPriceText != null) {
                    Text(
                        trf(L10n.Key.CurrentPriceFormat, "$$currentPriceText"),
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 12.sp,
                            color = DK.textSecondary,
                        ),
                    )
                }

                KindSegmented(selected = kind, onSelect = { kind = it })

                when (kind) {
                    AlertKind.Above, AlertKind.Below -> LabeledField(
                        label = tr(L10n.Key.TargetPriceLabel),
                        value = priceText,
                        onValueChange = { priceText = it },
                        focusRequester = focusRequester,
                        onSubmit = { if (canAdd) addAlert() },
                    )
                    AlertKind.Percent -> LabeledField(
                        label = tr(L10n.Key.DailyMoveLabel),
                        value = percentText,
                        onValueChange = { percentText = it },
                        focusRequester = focusRequester,
                        onSubmit = { if (canAdd) addAlert() },
                    )
                }

                AddAlertButton(enabled = canAdd, onClick = addAlert)
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun DkDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
}

@Composable
private fun Header(asset: Asset, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                asset.name,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, color = DK.textPrimary,
                ),
            )
            Text(
                asset.symbol,
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, color = DK.textSecondary,
                ),
            )
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(DK.surfaceHi)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "✕",
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = DK.textSecondary,
                ),
            )
        }
    }
}

@Composable
private fun ExistingAlerts(existing: List<PriceAlert>, onDelete: (String) -> Unit) {
    Column {
        for (alert in existing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    if (alert.isTriggered) "🔕" else "🔔",  // bell.slash / bell.fill stand-ins
                    style = TextStyle(fontSize = 12.sp),
                )
                Text(
                    alertSummary(alert.condition),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 13.sp,
                        color = if (alert.isTriggered) DK.textTertiary else DK.textPrimary,
                        textDecoration = if (alert.isTriggered) {
                            androidx.compose.ui.text.style.TextDecoration.LineThrough
                        } else {
                            null
                        },
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "🗑",  // trash stand-in
                    style = TextStyle(fontSize = 11.sp, color = DK.textTertiary),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDelete(alert.id) },
                )
            }
        }
    }
}

@Composable
private fun KindSegmented(selected: AlertKind, onSelect: (AlertKind) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        for (option in AlertKind.entries) {
            val isSelected = option == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) DK.surfaceHi else Color.Transparent)
                    .then(
                        if (isSelected) {
                            Modifier.border(1.dp, DK.gold.copy(alpha = 0.40f), RoundedCornerShape(50))
                        } else {
                            Modifier
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(option) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    kindLabel(option),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) DK.textPrimary else DK.textSecondary,
                    ),
                )
            }
        }
    }
}

/** Segmented-control label per kind — exact strings from `PriceAlertSheet.swift`'s
 *  `Kind`/`kindLabel` (L10n.priceAboveKind/.priceBelowKind/.percentMoveKind). */
private fun kindLabel(kind: AlertKind): String = when (kind) {
    AlertKind.Above -> tr(L10n.Key.PriceAboveKind)
    AlertKind.Below -> tr(L10n.Key.PriceBelowKind)
    AlertKind.Percent -> tr(L10n.Key.PercentMoveKind)
}

/** Localized existing-alert row text, computed HERE rather than via
 *  `AlertCondition.summary` (the `:shared` commonMain getter is deliberately English-only —
 *  see its doc comment — and out of scope for this desktop-only retrofit). Mirrors the exact
 *  shape of `AlertCondition.summary` (`Money.formatted` for the threshold, `abs(magnitude)`
 *  for the percent figure) against the catalog's pre-provisioned
 *  `priceAboveSummaryFormat`/`priceBelowSummaryFormat`/`percentMoveSummaryFormat` Keys. */
private fun alertSummary(condition: AlertCondition): String = when (condition) {
    is AlertCondition.PriceAbove -> trf(L10n.Key.PriceAboveSummaryFormat, condition.threshold.formatted)
    is AlertCondition.PriceBelow -> trf(L10n.Key.PriceBelowSummaryFormat, condition.threshold.formatted)
    is AlertCondition.PercentChange -> trf(L10n.Key.PercentMoveSummaryFormat, kotlin.math.abs(condition.magnitude))
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label.uppercase(),
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = DK.textTertiary, letterSpacing = 1.sp,
            ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(DK.surfaceHi)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(DK.gold),
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.Medium,
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
private fun AddAlertButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(if (enabled) DK.goldGradient else SolidColor(DK.surface))
            .then(
                if (enabled) Modifier else Modifier.border(1.dp, DK.hairline, RoundedCornerShape(50)),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onClick() }
            .padding(vertical = 10.dp),
    ) {
        Text(
            tr(L10n.Key.AddAlert),
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (enabled) DK.bgBottom else DK.textTertiary,
            ),
        )
    }
}
