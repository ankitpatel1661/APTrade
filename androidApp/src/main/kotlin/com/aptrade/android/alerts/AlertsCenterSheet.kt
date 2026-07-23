package com.aptrade.android.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aptrade.android.AppGraph
import com.aptrade.android.l10n.tr
import com.aptrade.android.ui.alertSummary
import com.aptrade.shared.domain.PriceAlert
import com.aptrade.shared.l10n.L10n

/**
 * The Alerts center: every price alert across every symbol, in one place — the "read" half
 * of alerts (creation stays per-symbol, from a watchlist row's bell or the detail page,
 * unchanged). Reached from Home's bell/card (M10.3 Task 4, replacing that task's inert
 * placeholders). Android [ModalBottomSheet] port of desktop `AlertsCenterDialog.kt` (a fixed
 * 420x520dp [androidx.compose.ui.window.Dialog] there — a near-fullscreen bottom sheet here,
 * per the [com.aptrade.android.plans.PieWizardSheet] idiom: this app's own modal convention for
 * multi-row content, rather than desktop's dim-scrim overlay window).
 *
 * Owns its own [AlertsCenterViewModel] via `viewModel {}` (`DetailScreen.kt:84`/
 * [com.aptrade.android.screener.ScreenerScreen] precedent: constructed directly from
 * [AppGraph]'s IO-dispatched factory, not cached across sheet opens the way an Activity-scoped
 * `viewModel {}` normally would — see [AppGraph.makeAlertsCenterViewModel]'s own KDoc). Loads
 * once on first composition ([LaunchedEffect]); the sheet's own dismissal (Esc/scrim/back-swipe
 * all funnel through Material3's [ModalBottomSheet] `onDismissRequest`) needs no explicit
 * teardown — unlike desktop's dialog (which owns a raw `CoroutineScope` it must `cancel()` on
 * dismiss), this VM's `viewModelScope` is cleared by its `ViewModelStore` automatically once
 * this composable leaves the tree, so there is nothing for this call site to cancel itself.
 *
 * [onOpenDetail] is the row-click tap-through: the caller (`MainActivity.kt`'s `AppNavHost`) is
 * expected to both navigate `detail/{symbol}` AND dismiss this sheet — see that call site's own
 * comment, mirroring desktop `AlertsCenterDialog`'s `onSelectSymbol` contract exactly ([asset]
 * still exists on [AlertsCenterViewModel] and is unit-tested for cross-platform semantic
 * parity; this call site only needs the bare symbol string, same divergence desktop's own KDoc
 * documents for its `onSelectSymbol`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsCenterSheet(onDismiss: () -> Unit, onOpenDetail: (String) -> Unit) {
    val viewModel: AlertsCenterViewModel = viewModel { AppGraph.makeAlertsCenterViewModel() }
    LaunchedEffect(Unit) { viewModel.load() }

    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            SheetHeader()
            HorizontalDivider()
            if (state.isEmpty) {
                EmptyState(modifier = Modifier.padding(horizontal = 24.dp))
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    for (group in state.groups) {
                        GroupSection(
                            group = group,
                            onRemove = viewModel::remove,
                            onSelect = { onOpenDetail(group.symbol) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr(L10n.Key.AlertsCenterTitle),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            tr(L10n.Key.AlertsEmpty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 40.dp),
        )
    }
}

@Composable
private fun GroupSection(group: AlertsCenterGroup, onRemove: (String) -> Unit, onSelect: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            group.symbol,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        for (alert in group.alerts) {
            AlertRow(alert = alert, onRemove = { onRemove(alert.id) }, onSelect = onSelect)
        }
    }
}

/** One alert row: a colored dot (primary armed / de-emphasized triggered — mirrors desktop
 *  `AlertsCenterDialog.kt`'s "dot as status indicator" idiom), the shared condition summary
 *  (strikethrough + de-emphasized once triggered, matching desktop's `AlertRow`'s
 *  `isTriggered` treatment — same colors [com.aptrade.android.watchlist.PriceAlertSheet]'s own
 *  `ExistingAlertRow` already uses for this exact distinction), an ARMED chip for untriggered
 *  alerts only, and a ✕ remove control at a ≥48dp touch target ([IconButton]'s default box
 *  size already meets this — M9.3 lesson / M10.3 Global Constraint 6). The row itself is
 *  clickable (tap-through to the asset); the ✕ [IconButton] is a nested, independently
 *  clickable control that consumes its own pointer event first — the established
 *  `WatchlistPane`/`PriceAlertSheet` row+delete-button idiom, same as desktop's own
 *  nested-clickable note. */
@Composable
private fun AlertRow(alert: PriceAlert, onRemove: () -> Unit, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
    ) {
        Box(
            Modifier.size(6.dp).clip(CircleShape)
                .background(if (alert.isTriggered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary),
        )
        Text(
            alertSummary(alert.condition),
            style = MaterialTheme.typography.bodyMedium,
            color = if (alert.isTriggered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (alert.isTriggered) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        if (!alert.isTriggered) {
            ArmedChip()
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Close,
                // No dedicated L10n key exists for this action (house rule: tr()/trf() only,
                // no new keys this task) — matching PriceAlertSheet's own ExistingAlertRow
                // delete IconButton precedent, which likewise passes null here.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ArmedChip() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            tr(L10n.Key.AlertArmed).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
