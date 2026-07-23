package com.aptrade.android.invest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aptrade.android.income.IncomeSection
import com.aptrade.android.l10n.tr
import com.aptrade.android.plans.PlansSection
import com.aptrade.shared.l10n.L10n

/** The Invest tab host (M10.3 IA restructure — Android counterpart of Swift `InvestView` /
 *  desktop `InvestSection`): a pill-row section switcher over the two sections
 *  [com.aptrade.android.portfolio.PortfolioScreen] used to host as its fifth/sixth sections —
 *  Plans and Income — moved here unchanged (their own view models, own lifecycles, own
 *  internal navigation; only the instantiation site moved, not their internals). Pill idiom
 *  copied from [com.aptrade.android.portfolio.PortfolioScreen]'s `SectionSwitcher`.
 *
 *  [section]/[onSelectSection] are HOISTED to the caller ([com.aptrade.android.MainActivity]'s
 *  `AppNavHost`, constraint 3) rather than kept as local `remember` state here, so Home/
 *  deep-link navigation can jump straight to a section by writing it directly — no request/
 *  clear handoff to get wrong on first composition (the Swift I-1 lesson).
 *
 *  Each section is hosted inside a single-item [LazyColumn], exactly matching how
 *  `PortfolioScreen`'s own outer `LazyColumn` hosted `item { PlansSection(...) }` / `item {
 *  IncomeSection() }` before this move — neither section builds its own scroll container. */
enum class InvestSection { Plans, Income }

/** [InvestSection]'s display label — same plain-function idiom as
 *  [com.aptrade.android.markets.MarketsScreen]'s `MarketsSection.label()` /
 *  `PortfolioScreen`'s `PortfolioSection.label()`. */
private fun InvestSection.label(): String = when (this) {
    InvestSection.Plans -> tr(L10n.Key.PlansSection)
    InvestSection.Income -> tr(L10n.Key.IncomeSection)
}

@Composable
fun InvestScreen(
    padding: PaddingValues,
    section: InvestSection,
    onSelectSection: (InvestSection) -> Unit,
    confirmTrades: Boolean,
    // M10.3 Task 5: threaded through to [IncomeSection]'s DRIP card — see that composable's
    // own KDoc for why these come from the Activity-scoped SettingsViewModel rather than a
    // screen-local copy.
    dripEnabled: Boolean,
    onDripChanged: (Boolean) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(
                start = padding.calculateStartPadding(layoutDirection),
                end = padding.calculateEndPadding(layoutDirection),
                top = padding.calculateTopPadding(),
            ),
    ) {
        SectionPicker(
            selected = section,
            onSelect = onSelectSection,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
        ) {
            when (section) {
                InvestSection.Plans -> item { PlansSection(confirmTrades = confirmTrades) }
                InvestSection.Income -> item {
                    IncomeSection(dripEnabled = dripEnabled, onDripChanged = onDripChanged)
                }
            }
        }
    }
}

/** Plans / Income segmented row — built from the same
 *  [SingleChoiceSegmentedButtonRow]/[SegmentedButton] pair
 *  [com.aptrade.android.portfolio.PortfolioScreen]'s `SectionSwitcher` uses (this app's
 *  established pill idiom). Text-only, matching that switcher exactly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionPicker(
    selected: InvestSection,
    onSelect: (InvestSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.segmentedRowWidth()) {
        InvestSection.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, InvestSection.entries.size),
            ) {
                Text(
                    option.label(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Segmented rows fill the width on phones but cap at 480dp and center on wider (tablet)
 *  windows — mirrors [com.aptrade.android.portfolio.PortfolioScreen]'s identically-named
 *  private helper. */
private fun Modifier.segmentedRowWidth(): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = 480.dp)
    .fillMaxWidth()
