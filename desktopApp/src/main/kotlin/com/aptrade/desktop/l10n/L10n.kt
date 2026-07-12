package com.aptrade.desktop.l10n

/** Moved to shared (com.aptrade.shared.l10n.L10n) so Android reuses the catalog.
 *  This alias keeps every desktop call site (`L10n.Key.Watchlist`, `L10n.string(...)`)
 *  compiling unchanged. */
typealias L10n = com.aptrade.shared.l10n.L10n
