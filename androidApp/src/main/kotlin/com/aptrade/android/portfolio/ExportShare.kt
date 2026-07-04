package com.aptrade.android.portfolio

import android.content.Context
import android.content.Intent

/** The two portfolio export encodings the share sheet offers. Each carries its MIME type and the
 *  suggested attachment filename so the chooser and receiving app see the right shape. */
enum class ExportFormat(val mimeType: String, val fileName: String, val label: String) {
    Csv("text/csv", "portfolio.csv", "CSV"),
    Json("application/json", "portfolio.json", "JSON"),
}

/**
 * Fires an Android share sheet ([Intent.ACTION_SEND]) carrying the rendered export as
 * [Intent.EXTRA_TEXT].
 *
 * RECORDED DIVERGENCE (text-payload v1): the desktop app writes the CSV/JSON to a real file via a
 * save-file dialog; Android v1 hands the payload inline as EXTRA_TEXT rather than a content:// URI
 * attachment. This keeps Task 4 free of a FileProvider + cache-file lifecycle — the receiving app
 * (mail, notes, drive) still gets the full document as shareable text. Attachment-URI export is a
 * deliberate follow-up, not an oversight.
 */
fun shareExport(context: Context, format: ExportFormat, payload: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = format.mimeType
        putExtra(Intent.EXTRA_TITLE, format.fileName)
        putExtra(Intent.EXTRA_SUBJECT, format.fileName)
        putExtra(Intent.EXTRA_TEXT, payload)
    }
    val chooser = Intent.createChooser(send, "Export portfolio").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
