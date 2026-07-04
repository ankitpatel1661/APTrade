package com.aptrade.desktop.infra

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The ONE place in the app that touches AWT: a native macOS Save panel for exporting the
 *  portfolio as CSV / JSON. Kept out of designkit (which stays framework-clean) and off the
 *  domain/application layers. Blocking `FileDialog` is fine here — export is a deliberate,
 *  user-initiated action, and the panel is modal by nature.
 *
 *  Silently returns when the user cancels (no file selected). Any write failure is swallowed
 *  rather than crashing the UI: a failed export is not worth taking the window down for. */
fun saveTextFile(suggestedName: String, content: String) {
    val dialog = FileDialog(null as Frame?, "Export", FileDialog.SAVE).apply {
        file = suggestedName
        isVisible = true
    }
    val directory = dialog.directory ?: return
    val chosen = dialog.file ?: return
    try {
        File(directory, chosen).writeText(content)
    } catch (_: Exception) {
        // A failed disk write must not crash the app; the user can retry the export.
    }
}

/** Binary sibling of [saveTextFile] for exports whose payload is raw bytes (PDF). Same native
 *  macOS Save panel, same cancel/failure discipline: a cancelled panel or a failed write is
 *  swallowed rather than taking the window down. */
fun saveBinaryFile(suggestedName: String, bytes: ByteArray) {
    val dialog = FileDialog(null as Frame?, "Export", FileDialog.SAVE).apply {
        file = suggestedName
        isVisible = true
    }
    val directory = dialog.directory ?: return
    val chosen = dialog.file ?: return
    try {
        File(directory, chosen).writeBytes(bytes)
    } catch (_: Exception) {
        // A failed disk write must not crash the app; the user can retry the export.
    }
}
