package com.aptrade.desktop.infra

import java.awt.Desktop
import java.net.URI

/** Opens [url] in the user's default browser via AWT `Desktop.browse`. Sibling of
 *  [saveTextFile] — the only other place in the app that reaches for AWT — kept out of
 *  designkit (which stays framework-clean) and off the domain/application layers.
 *
 *  Best-effort and silent by contract: a headless environment (no `Desktop` support), a
 *  malformed URL, or a launch failure is swallowed rather than crashing the UI. Opening a
 *  news article is a convenience, not a critical path. */
fun openUrlInBrowser(url: String) {
    try {
        if (!Desktop.isDesktopSupported()) return
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return
        desktop.browse(URI(url))
    } catch (_: Exception) {
        // A failed browser launch must not crash the app; the article link is a convenience.
    }
}
