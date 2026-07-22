package com.aptrade.desktop.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aptrade.desktop.AppGraph
import com.aptrade.desktop.LocalAppGraph
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.alertSummary
import com.aptrade.desktop.l10n.tr
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.l10n.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The Alerts center: every price alert across every symbol, in one place — the "read" half
 * of alerts (creation stays per-symbol, from a watchlist row's bell or the detail page,
 * unchanged). Reached from Home's bell/card (M10.2 Task 5). Compose port of
 * `Sources/APTradeApp/AlertsCenterView.swift`.
 *
 * Renders via [Dialog] — the SAME house dialog idiom
 * [com.aptrade.desktop.plans.PieWizardDialog] establishes: a dim scrim, a fixed
 * 420x520dp surface panel (matching Swift's macOS `.frame(width: 420, height: 520)`),
 * Esc consumed on the panel's own `onPreviewKeyEvent` before the window's Esc-priority
 * chain ever sees it.
 *
 * Owns its own [AlertsCenterViewModel] + scope — mirrors
 * [com.aptrade.desktop.plans.PieWizardDialog]/[com.aptrade.desktop.screener.ScreenBuilderDialog]:
 * a fresh instance every time the dialog mounts (this composable is only ever present in
 * the tree while the dialog is open — see `Main.kt`'s `if (alertsCenterOpen)` guard),
 * cancelled on dismiss.
 *
 * [onSelectSymbol] is the row-click tap-through. The desktop `DetailScreen` only needs the
 * bare symbol string (unlike Swift's `AssetDetailView(asset:)`, which needs a full `Asset`)
 * — [AlertsCenterViewModel.asset] still exists and is unit-tested for Swift-semantic
 * parity, it's simply not needed by THIS call site to route navigation. The caller
 * (`Main.kt`'s `AppRoot`) is expected to both route `sidebarSelection` to
 * `Markets(Watchlist)` (the only destination `openSymbol` renders under today) AND open
 * the detail screen, then dismiss this dialog — see that call site's own comment.
 */
@Composable
fun AlertsCenterDialog(onDismiss: () -> Unit, onSelectSymbol: (String) -> Unit) {
    val graph: AppGraph = LocalAppGraph.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val viewModel = remember { graph.makeAlertsCenterViewModel(scope) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    LaunchedEffect(Unit) { viewModel.load() }

    val state by viewModel.state.collectAsState()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(420.dp)
                    .height(520.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DK.surface)
                    .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss(); true
                        } else {
                            false
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            ) {
                DialogHeader(onDismiss = onDismiss)
                Box(Modifier.fillMaxWidth().height(1.dp).background(DK.hairline))
                if (state.isEmpty) {
                    EmptyState(modifier = Modifier.weight(1f))
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        for (group in state.groups) {
                            GroupSection(
                                group = group,
                                onRemove = viewModel::remove,
                                onSelect = { onSelectSymbol(group.symbol) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogHeader(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Text(
            tr(L10n.Key.AlertsCenterTitle),
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, color = DK.textPrimary,
            ),
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(DK.surfaceHi)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
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
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            tr(L10n.Key.AlertsEmpty),
            style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, color = DK.textSecondary),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun GroupSection(group: AlertsCenterGroup, onRemove: (String) -> Unit, onSelect: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            group.symbol,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = DK.textPrimary,
            ),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        for (alert in group.alerts) {
            AlertRow(alert = alert, onRemove = { onRemove(alert.id) }, onSelect = onSelect)
        }
    }
}

/** One alert row: a colored dot (gold armed / grey triggered — this design kit has no
 *  bell-glyph precedent, see `HomePane.kt`'s `AlertsCard` doc comment for the same "dot as
 *  status indicator" idiom), the shared condition summary (strikethrough once triggered,
 *  matching `AlertsCenterView.swift`'s `alertRow`), an ARMED chip for untriggered alerts
 *  only, and a ✕ remove control. The row itself is clickable (tap-through to the asset);
 *  the ✕ is a nested, independently-clickable control (the established
 *  `WatchlistPane.kt` row+delete-button idiom — Compose's inner `clickable` consumes its
 *  own pointer events before they'd reach the row's). */
@Composable
private fun AlertRow(alert: PriceAlert, onRemove: () -> Unit, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect() }
            .padding(vertical = 8.dp),
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape)
                .background(if (alert.isTriggered) DK.textTertiary else DK.gold),
        )
        Text(
            alertSummary(alert.condition),
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 13.sp,
                color = if (alert.isTriggered) DK.textTertiary else DK.textPrimary,
                textDecoration = if (alert.isTriggered) TextDecoration.LineThrough else null,
            ),
            modifier = Modifier.weight(1f),
        )
        if (!alert.isTriggered) {
            Text(
                tr(L10n.Key.AlertArmed).uppercase(),
                style = TextStyle(
                    fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = DK.gold, letterSpacing = 0.6.sp,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DK.gold.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            "✕",
            style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, color = DK.textTertiary),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onRemove() },
        )
    }
}
