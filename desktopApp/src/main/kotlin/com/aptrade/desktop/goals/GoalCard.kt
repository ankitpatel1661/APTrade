package com.aptrade.desktop.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.desktop.plans.WizardTextField
import com.aptrade.shared.domain.AmountInput
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalMath
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import com.aptrade.shared.domain.targetRange
import com.aptrade.shared.l10n.L10n
import kotlin.math.roundToInt

/**
 * One goal's worth of card state, pre-formatted per this codebase's VM→UI contract.
 *
 * [projection] stays a raw [GoalProjection] rather than a rendered sentence so the copy resolves
 * inside composition and re-renders when the user changes language — and so no caller has to
 * string-match to tell "already met" from "unreachable" (carry-notes §3.5).
 *
 * [targetAmountText] is the RAW `Money.amountText`, used ONLY to refill the edit field. Never
 * feed [currentText]/[targetText] into a parser — they carry "$" and grouping separators.
 */
data class GoalCardUi(
    val currentText: String,
    val targetText: String,
    val targetAmountText: String,
    val fraction: Double,
    val projection: GoalProjection,
)

/** Maps a (possibly absent) goal to card state. `null` means "no goal set" — the card still
 *  RENDERS, showing its "Set a goal" affordance; it is never hidden (carry-notes §1.3). */
fun goalCardUi(goal: PortfolioGoal?, current: Money, projection: GoalProjection?): GoalCardUi? {
    if (goal == null) return null
    return GoalCardUi(
        currentText = formatMoney(current.amountText),
        targetText = formatMoney(goal.target.amountText),
        targetAmountText = goal.target.amountText,
        fraction = GoalMath.progress(current, goal.target),
        // "Not computed yet" and "not enough history" read identically to a user, and both are
        // honest; collapsing them here keeps every downstream branch exhaustive over five cases.
        projection = projection ?: GoalProjection.InsufficientHistory,
    )
}

/**
 * Progress + honest projection for one portfolio goal. Shared by the Income surface (income goal)
 * and the Performance surface (value goal) — same card, same edit dialog, different
 * `title`/`kind`/`ui`.
 *
 * BINDING (carry-notes §1.3): every caller renders this card UNCONDITIONALLY — never inside an
 * empty-ledger, loading, or "report loaded" branch. A goal is a plan; it is most useful before you
 * hold anything, and the Swift wave shipped both cards behind exactly those gates before the
 * ruling. Follow the DRIP-card precedent: hoist above the state switch.
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.textPrimary),
            )
            Spacer(Modifier.weight(1f))
            if (ui != null) {
                TextAction(tr(L10n.Key.EditGoal), DK.textSecondary) { seedText = ui.targetAmountText; isEditing = true }
                Spacer(Modifier.width(14.dp))
                TextAction(tr(L10n.Key.RemoveGoal), DK.down, onRemove)
            }
        }
        if (ui == null) {
            TextAction(tr(L10n.Key.SetGoal), DK.gold) { seedText = ""; isEditing = true }
        } else {
            ProgressBar(ui.fraction)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    ui.currentText,
                    style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary, fontFeatureSettings = "tnum"),
                )
                Text("/", style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary))
                Text(
                    ui.targetText,
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary, fontFeatureSettings = "tnum"),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${(ui.fraction * 100).roundToInt()}%",
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.gold, fontFeatureSettings = "tnum"),
                )
            }
            Text(
                goalProjectionText(ui.projection),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
            )
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

/** Renders every [GoalProjection] case as its own honest sentence — never collapsed into a generic
 *  on-track/off-track binary, and never a fabricated ETA (carry-notes §3.5).
 *
 *  `BeyondHorizon` INTERPOLATES [GoalMath.HORIZON_YEARS] rather than baking "30" into the copy, so
 *  the string cannot silently go stale if the constant moves. */
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

@Composable
private fun ProgressBar(fraction: Double) {
    val clamped = fraction.coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(DK.hairline)) {
        Box(Modifier.fillMaxWidth(clamped).height(6.dp).clip(RoundedCornerShape(50)).background(DK.gold))
    }
}

@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color),
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    )
}

/** Goal-target entry. Reuses the shared [AmountInput] parser rather than inventing a second
 *  validator, but validates against [GoalKind.targetRange] — an income target and a value target
 *  are different quantities at different scales, so the field's hint must describe the range it is
 *  actually enforcing (carry-notes §1.5). */
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

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    tr(L10n.Key.GoalTarget).uppercase(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
                )
                WizardTextField(value = text, onValueChange = { text = it }, placeholder = "0", fontSize = 20.sp)
                Text(
                    tr(hintKey),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                        color = if (parsed == null && text.isNotBlank()) DK.down else DK.textTertiary,
                    ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                DialogButton(tr(L10n.Key.Cancel), DK.textSecondary, enabled = true, onClick = onCancel)
                DialogButton(tr(L10n.Key.SaveAction), DK.gold, enabled = parsed != null) { parsed?.let(onSave) }
            }
        }
    }
}

@Composable
private fun RowScope.DialogButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f).clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (enabled) 0.16f else 0.06f))
            .border(1.dp, color.copy(alpha = if (enabled) 0.4f else 0.18f), RoundedCornerShape(50))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (enabled) color else color.copy(alpha = 0.45f),
            ),
        )
    }
}
