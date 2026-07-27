package com.aptrade.android.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aptrade.android.l10n.tr
import com.aptrade.android.l10n.trf
import com.aptrade.shared.domain.AmountInput
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalMath
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.targetRange
import com.aptrade.shared.l10n.L10n
import kotlin.math.roundToInt

/**
 * Material 3 port of desktop's `GoalCard` (`desktopApp/.../goals/GoalCard.kt`) — same progress +
 * honest-projection card, same edit dialog, same [GoalCardUi]/[goalCardUi] contract, rendered
 * with this platform's own widgets ([Card]/[LinearProgressIndicator]/[AlertDialog]) rather than
 * desktop's hand-drawn Compose Desktop shapes. A PORT, not a hoist — see [GoalCardUi]'s own KDoc
 * for why the money-formatted state type itself is per-platform; this composable is the other
 * half of that same split.
 *
 * Shared by the Income surface (income goal) and the Performance surface (value goal, wired by
 * M11.3 Task 5) — same card, same edit dialog, different `title`/`kind`/`ui`.
 *
 * BINDING (carry-notes §1.3): every caller renders this card UNCONDITIONALLY — never inside an
 * empty-holdings, loading, or "report loaded" branch. A goal is a plan; it is most useful before
 * you hold anything. See `PortfolioScreen.kt`'s `PerformanceSection` call site for the
 * enforcement on this platform.
 */
@Composable
fun GoalCard(
    title: String,
    kind: GoalKind,
    ui: GoalCardUi?,
    onSet: (Money) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEditing by remember { mutableStateOf(false) }
    var seedText by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (ui != null) {
                    TextButton(onClick = { seedText = ui.targetAmountText; isEditing = true }) {
                        Text(tr(L10n.Key.EditGoal))
                    }
                    TextButton(
                        onClick = onRemove,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(tr(L10n.Key.RemoveGoal))
                    }
                }
            }
            if (ui == null) {
                TextButton(onClick = { seedText = ""; isEditing = true }) {
                    Text(tr(L10n.Key.SetGoal))
                }
            } else {
                LinearProgressIndicator(
                    progress = { ui.fraction.coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        ui.currentText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        ui.targetText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(ui.fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    goalProjectionText(ui.projection),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (isEditing) {
        GoalEditDialog(
            title = title,
            kind = kind,
            initialText = seedText,
            onSave = { amount -> isEditing = false; onSet(amount) },
            onCancel = { isEditing = false },
        )
    }
}

/** Renders every [GoalProjection] case as its own honest sentence — never collapsed into a
 *  generic on-track/off-track binary, and never a fabricated ETA (carry-notes §3.5). Byte-for-
 *  byte the same branching as desktop's `goalProjectionText`, over this platform's [tr]/[trf].
 *
 *  `BeyondHorizon` INTERPOLATES [GoalMath.HORIZON_YEARS] rather than baking "30" into the copy,
 *  so the string cannot silently go stale if the constant moves. */
@Composable
fun goalProjectionText(projection: GoalProjection?): String = when (projection) {
    GoalProjection.Reached -> tr(L10n.Key.GoalReached)
    GoalProjection.NotOnTrack -> tr(L10n.Key.GoalNotOnTrack)
    GoalProjection.InsufficientHistory, null -> tr(L10n.Key.GoalNeedsHistory)
    GoalProjection.BeyondHorizon -> trf(L10n.Key.GoalBeyondHorizonFmt, GoalMath.HORIZON_YEARS.toInt().toString())
    is GoalProjection.Years -> {
        val rounded = if (projection.value < 10.0) {
            String.format(java.util.Locale.ROOT, "%.1f", projection.value)
        } else {
            projection.value.roundToInt().toString()
        }
        trf(L10n.Key.GoalYearsFmt, rounded)
    }
}

/** Goal-target entry, Material 3 [AlertDialog] idiom (this screen's existing dialog pattern —
 *  see `PortfolioScreen.kt`'s reset/export confirmations). Reuses the shared [AmountInput]
 *  parser rather than inventing a second validator, but validates against [GoalKind.targetRange]
 *  — an income target and a value target are different quantities at different scales, so the
 *  field's supporting text must describe the range it is actually enforcing (carry-notes §1.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditDialog(
    title: String,
    kind: GoalKind,
    initialText: String,
    onSave: (Money) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val parsed = AmountInput.parse(text, kind.targetRange)
    val hintKey = when (kind) {
        GoalKind.Income -> L10n.Key.IncomeGoalRange
        GoalKind.Value -> L10n.Key.ValueGoalRange
    }
    val isInvalid = parsed == null && text.isNotBlank()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(tr(L10n.Key.GoalTarget)) },
                singleLine = true,
                isError = isInvalid,
                supportingText = { Text(tr(hintKey)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) {
                Text(tr(L10n.Key.SaveAction))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(tr(L10n.Key.Cancel)) }
        },
    )
}
