package com.aptrade.android.markets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aptrade.android.calendar.CalendarScreen
import com.aptrade.android.l10n.tr
import com.aptrade.android.news.NewsScreen
import com.aptrade.android.screener.ScreenerScreen
import com.aptrade.android.watchlist.WatchlistScreen
import com.aptrade.shared.l10n.L10n

/** The Markets tab host (M10.3 IA restructure — Android counterpart of Swift `MarketsView` /
 *  desktop `MarketsSection`): a button-styled search field that navigates to the existing
 *  "search" route (it is styled per this app's [androidx.compose.material3.OutlinedTextField]
 *  field idiom but is really a button — no new search UI), then a pill-row section switcher
 *  over the four screens that used to be separate bottom tabs — Watchlist, Screener, Calendar,
 *  News — hosted here completely unchanged (same view models, same params, same lifecycles).
 *  Pill idiom copied from [com.aptrade.android.portfolio.PortfolioScreen]'s `SectionSwitcher`
 *  ([SingleChoiceSegmentedButtonRow]/[SegmentedButton] pair).
 *
 *  [section]/[onSelectSection] are HOISTED to the caller ([com.aptrade.android.MainActivity]'s
 *  `AppNavHost`, constraint 3) rather than kept as local `remember` state here, so Home/
 *  deep-link navigation can jump straight to a section by writing it directly — no request/
 *  clear handoff to get wrong on first composition (the Swift I-1 lesson).
 *
 *  [padding] is [com.aptrade.android.AppShell]'s Scaffold content padding. Only its top/start/
 *  end insets are consumed here (search field + pills sit directly under the "APTrade" bar);
 *  the bottom inset (clearing the bottom NavigationBar) is forwarded to whichever wrapped
 *  screen is active, exactly as each of those screens already expects from its own former
 *  life as a direct tab root. */
enum class MarketsSection { Watchlist, Screener, Calendar, News }

/** [MarketsSection]'s display label. A plain function (not an enum property) so it calls [tr]
 *  fresh on every read — recomposes correctly when the active language changes, mirroring
 *  [com.aptrade.android.portfolio.PortfolioScreen]'s `PortfolioSection.label()`. */
private fun MarketsSection.label(): String = when (this) {
    MarketsSection.Watchlist -> tr(L10n.Key.Watchlist)
    MarketsSection.Screener -> tr(L10n.Key.ScreenerTab)
    MarketsSection.Calendar -> tr(L10n.Key.CalendarTab)
    MarketsSection.News -> tr(L10n.Key.News)
}

@Composable
fun MarketsScreen(
    padding: PaddingValues,
    section: MarketsSection,
    onSelectSection: (MarketsSection) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDetail: (String) -> Unit,
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
        SearchFieldButton(
            onClick = onOpenSearch,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
        SectionPicker(
            selected = section,
            onSelect = onSelectSection,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        val innerPadding = PaddingValues(bottom = padding.calculateBottomPadding())
        Box(Modifier.fillMaxSize()) {
            when (section) {
                MarketsSection.Watchlist -> WatchlistScreen(
                    padding = innerPadding,
                    onOpenSearch = onOpenSearch,
                    onOpenDetail = onOpenDetail,
                )
                MarketsSection.Screener -> ScreenerScreen(
                    padding = innerPadding,
                    onOpenDetail = onOpenDetail,
                )
                MarketsSection.Calendar -> CalendarScreen(padding = innerPadding)
                MarketsSection.News -> NewsScreen(padding = innerPadding)
            }
        }
    }
}

/** Styled as an [androidx.compose.material3.OutlinedTextField] (M3's default 4dp corner
 *  radius + hairline outline) but is really a button — tapping opens the existing "search"
 *  route, matching Swift `MarketsView.searchField`'s "styled as a text field but is really a
 *  button" comment verbatim. */
@Composable
private fun SearchFieldButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                tr(L10n.Key.SearchAssetsPlaceholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Watchlist / Screener / Calendar / News segmented row — built from the same
 *  [SingleChoiceSegmentedButtonRow]/[SegmentedButton] pair
 *  [com.aptrade.android.portfolio.PortfolioScreen]'s `SectionSwitcher` uses (this app's
 *  established pill idiom). Text-only, same as that switcher — deliberately no icons, so there
 *  is no clash with the bottom [com.aptrade.android.ShellTab] icons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionPicker(
    selected: MarketsSection,
    onSelect: (MarketsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.segmentedRowWidth()) {
        MarketsSection.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, MarketsSection.entries.size),
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
 *  private helper (same rationale: full-bleed segments on a wide window stretch into comically
 *  wide tap targets). */
private fun Modifier.segmentedRowWidth(): Modifier = this
    .fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = 480.dp)
    .fillMaxWidth()
