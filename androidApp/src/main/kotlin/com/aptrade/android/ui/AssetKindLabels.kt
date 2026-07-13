package com.aptrade.android.ui

import androidx.compose.runtime.Composable
import com.aptrade.android.l10n.tr
import com.aptrade.shared.domain.AllocationSlice
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.l10n.L10n

/** The plain-word singular kind label ("Stock" / "Aktie") — desktop `designkit.kindLabel`
 *  parity. Composable so it reads [tr] live: ViewModels carry the [AssetKind] itself and a
 *  language switch recomposes every label without touching cached state. */
@Composable
fun AssetKind.localizedLabel(): String = when (this) {
    AssetKind.Stock -> tr(L10n.Key.StockKindLabel)
    AssetKind.Etf -> tr(L10n.Key.EtfKindLabel)
    AssetKind.Crypto -> tr(L10n.Key.CryptoKindLabel)
}

/** A slice's display label, localized live: by-class slices translate their
 *  [AllocationSlice.kind] through the plural section words (desktop `PortfolioPane.sliceLabel`
 *  / macOS `PortfolioView.swift` parity); by-holding slices (kind == null) show the symbol
 *  carried in [AllocationSlice.label]. */
@Composable
fun AllocationSlice.localizedLabel(): String = when (kind) {
    AssetKind.Stock -> tr(L10n.Key.StocksLabel)
    AssetKind.Etf -> tr(L10n.Key.EtfsLabel)
    AssetKind.Crypto -> tr(L10n.Key.CryptoLabel)
    null -> label
}
