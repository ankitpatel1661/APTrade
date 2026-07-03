package com.aptrade.desktop.search

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.aptrade.desktop.designkit.MagnifierIcon
import com.aptrade.desktop.designkit.kindLabel
import com.aptrade.shared.domain.Asset
import androidx.compose.runtime.collectAsState

/** Ctrl/Cmd+K command palette. Full-window scrim (click closes), centered 520dp
 *  panel with an auto-focused search field. Up/Down move the selection, Enter adds
 *  the highlighted asset, Esc closes. All fetch/selection logic lives in the VM. */
@Composable
fun PaletteOverlay(
    viewModel: SearchViewModel,
    onAdd: (Asset) -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val focusRequester = remember { FocusRequester() }

    fun commit() {
        viewModel.selectedAsset()?.let { asset ->
            onAdd(asset)
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClose() },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .width(520.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                // Swallow clicks inside the panel so the scrim's close doesn't fire.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                MagnifierIcon(tint = DK.textSecondary, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (state.query.isEmpty()) {
                        Text(
                            "Search stocks, ETFs, crypto",
                            style = TextStyle(
                                fontFamily = InterFamily,
                                fontSize = 16.sp,
                                color = DK.textTertiary,
                            ),
                        )
                    }
                    BasicTextField(
                        value = state.query,
                        onValueChange = viewModel::onQueryChange,
                        singleLine = true,
                        cursorBrush = SolidColor(DK.gold),
                        textStyle = LocalTextStyle.current.merge(
                            TextStyle(
                                fontFamily = InterFamily,
                                fontSize = 16.sp,
                                color = DK.textPrimary,
                            ),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> { viewModel.moveSelection(-1); true }
                                    Key.DirectionDown -> { viewModel.moveSelection(1); true }
                                    Key.Enter, Key.NumPadEnter -> { commit(); true }
                                    Key.Escape -> { onClose(); true }
                                    else -> false
                                }
                            },
                    )
                }
            }
            when {
                state.results.isNotEmpty() -> {
                    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(DK.hairline),
                        )
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            itemsIndexed(state.results, key = { _, a -> a.symbol }) { index, asset ->
                                PaletteRow(
                                    asset = asset,
                                    selected = index == state.selectedIndex,
                                    onClick = {
                                        onAdd(asset)
                                        onClose()
                                    },
                                )
                            }
                        }
                    }
                }
                state.query.isNotBlank() && !state.isSearching -> {
                    Text(
                        "No matches",
                        style = TextStyle(
                            fontFamily = InterFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = DK.textSecondary,
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun PaletteRow(asset: Asset, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) DK.surfaceHi else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                asset.name,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textPrimary,
                ),
                maxLines = 1,
            )
            Text(
                asset.symbol,
                style = TextStyle(
                    fontFamily = InterFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = DK.textSecondary,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
        Text(
            kindLabel(asset.kind).uppercase(),
            style = TextStyle(
                fontFamily = InterFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = DK.textTertiary,
                letterSpacing = 0.8.sp,
            ),
        )
    }
}
