package com.aptrade.desktop.infra

import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.designkit.signedMoney
import com.aptrade.shared.domain.PortfolioExport
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders a [PortfolioExport] snapshot to PDF bytes, matching the layout of the macOS
 * `PDFExportRenderer` (`Sources/APTradeInfrastructure/PortfolioExportRenderer.swift`):
 * landscape US Letter, 40pt margins, title + timestamp, a label/value summary block, and a
 * fixed-column holdings table with signed/colored P&L. Built directly against PDFBox's
 * low-level `PDPageContentStream` API (rather than Core Text's rich-text framesetter, which
 * has no JVM equivalent) — pagination is driven by tracking the running Y cursor and
 * starting a new page whenever the next row would fall past the bottom margin.
 */

private const val PAGE_WIDTH = 792f
private const val PAGE_HEIGHT = 612f
private const val MARGIN = 40f

// Table column x-offsets, mirroring the macOS renderer's NSTextTab stops (scaled to this
// page's content width of 712pt = 792 - 2*40).
private const val COL_SYMBOL = MARGIN
private const val COL_NAME = MARGIN + 70f
private const val COL_QTY = MARGIN + 250f
private const val COL_AVG = MARGIN + 345f
private const val COL_LAST = MARGIN + 440f
private const val COL_VALUE = MARGIN + 560f
private const val COL_PNL = MARGIN + 660f
private const val COL_ALLOC = MARGIN + 712f

// Approximate macOS labelColor / secondaryLabelColor equivalents in RGB (0..1).
private val COLOR_PRIMARY = Triple(0f, 0f, 0f)
private val COLOR_SECONDARY = Triple(0.42f, 0.42f, 0.44f)

// Matches the macOS renderer's `pnlColor(_:)` calibrated RGB values exactly.
private val COLOR_GAIN = Triple(0.16f, 0.55f, 0.34f)
private val COLOR_LOSS = Triple(0.70f, 0.20f, 0.16f)

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm", Locale.US)

/** Renders [export] as PDF bytes. Landscape US Letter, paginated when holdings overflow. */
fun renderPortfolioPdf(export: PortfolioExport): ByteArray {
    PDDocument().use { document ->
        val cursor = PageCursor(document)
        cursor.newPage()

        cursor.drawText(export.accountName, HELVETICA_BOLD, 22f, COLOR_PRIMARY)
        cursor.advance(28f)

        val generated = Instant.ofEpochSecond(export.generatedAtEpochSeconds)
            .atZone(ZoneOffset.UTC)
            .format(timestampFormatter)
        cursor.drawText("Portfolio Statement · $generated", HELVETICA, 11f, COLOR_SECONDARY)
        cursor.advance(22f)

        val summary = listOf(
            "Total Value" to formatMoney(export.totalValue.amount.toStringExpanded()),
            "Cash" to formatMoney(export.cash.amount.toStringExpanded()),
            "Holdings Value" to formatMoney(export.holdingsValue.amount.toStringExpanded()),
            "Day P&L" to signedMoney(export.dayChange.amount.toStringExpanded()),
            "Unrealized P&L" to signedMoney(export.unrealizedPnL.amount.toStringExpanded()),
        )
        for ((label, value) in summary) {
            cursor.drawSummaryRow(label, value)
            cursor.advance(15f)
        }
        cursor.advance(10f)

        if (export.holdings.isEmpty()) {
            cursor.drawText(
                "No holdings — the account is all cash.",
                HELVETICA, 11f, COLOR_SECONDARY,
            )
        } else {
            cursor.ensureRoomFor(16f)
            cursor.drawTableHeader()
            cursor.advance(16f)

            for (holding in export.holdings) {
                cursor.ensureRoomFor(14f)
                cursor.drawHoldingRow(holding)
                cursor.advance(14f)
            }
        }

        cursor.finish()

        val output = ByteArrayOutputStream()
        document.save(output)
        return output.toByteArray()
    }
}

/** `APTrade-Portfolio-yyyy-MM-dd.<extension>`, using the UTC calendar date of
 *  [epochSeconds] so the name is stable regardless of the caller's local time zone. */
fun exportFileName(extension: String, epochSeconds: Long): String {
    val date = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate()
    return "APTrade-Portfolio-$date.$extension"
}

// PDFBox 3.x removed the static PDType1Font.HELVETICA constants in favor of constructing
// a standard-14 font from Standard14Fonts.FontName.
private val HELVETICA: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA)
private val HELVETICA_BOLD: PDFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

/** Owns the current page/content-stream pair and the running Y cursor, opening a new
 *  landscape page (redrawing nothing — headers/summary are only drawn once, per the macOS
 *  layout) whenever a row would run past the bottom margin. */
private class PageCursor(private val document: PDDocument) {
    private var page: PDPage? = null
    private var stream: PDPageContentStream? = null
    private var y: Float = PAGE_HEIGHT - MARGIN

    fun newPage() {
        stream?.close()
        val newPage = PDPage(PDRectangle(PAGE_WIDTH, PAGE_HEIGHT))
        document.addPage(newPage)
        page = newPage
        stream = PDPageContentStream(document, newPage)
        y = PAGE_HEIGHT - MARGIN
    }

    fun advance(amount: Float) {
        y -= amount
    }

    /** Starts a new page if the next [rowHeight]-tall row would fall below the bottom
     *  margin. */
    fun ensureRoomFor(rowHeight: Float) {
        if (y - rowHeight < MARGIN) {
            newPage()
        }
    }

    fun drawText(text: String, font: PDFont, size: Float, color: Triple<Float, Float, Float>) {
        val active = stream ?: error("no active content stream")
        active.beginText()
        active.setFont(font, size)
        active.setNonStrokingColor(color.first, color.second, color.third)
        active.newLineAtOffset(MARGIN, y)
        active.showText(text)
        active.endText()
    }

    fun drawSummaryRow(label: String, value: String) {
        val active = stream ?: error("no active content stream")
        active.beginText()
        active.setFont(HELVETICA_BOLD, 11f)
        active.setNonStrokingColor(COLOR_PRIMARY.first, COLOR_PRIMARY.second, COLOR_PRIMARY.third)
        active.newLineAtOffset(MARGIN, y)
        active.showText("$label:")
        active.endText()

        active.beginText()
        active.setFont(HELVETICA, 11f)
        active.setNonStrokingColor(COLOR_PRIMARY.first, COLOR_PRIMARY.second, COLOR_PRIMARY.third)
        active.newLineAtOffset(MARGIN + 110f, y)
        active.showText(value)
        active.endText()
    }

    fun drawTableHeader() {
        val active = stream ?: error("no active content stream")
        val font = HELVETICA_BOLD
        val size = 9f
        drawColumn(active, font, size, COLOR_PRIMARY, "SYMBOL", COL_SYMBOL, alignRight = false)
        drawColumn(active, font, size, COLOR_PRIMARY, "NAME", COL_NAME, alignRight = false)
        drawColumn(active, font, size, COLOR_PRIMARY, "QTY", COL_QTY, alignRight = true)
        drawColumn(active, font, size, COLOR_PRIMARY, "AVG COST", COL_AVG, alignRight = true)
        drawColumn(active, font, size, COLOR_PRIMARY, "LAST", COL_LAST, alignRight = true)
        drawColumn(active, font, size, COLOR_PRIMARY, "MKT VALUE", COL_VALUE, alignRight = true)
        drawColumn(active, font, size, COLOR_PRIMARY, "UNREAL P&L", COL_PNL, alignRight = true)
        drawColumn(active, font, size, COLOR_PRIMARY, "ALLOC", COL_ALLOC, alignRight = true)
    }

    fun drawHoldingRow(holding: PortfolioExport.Holding) {
        val active = stream ?: error("no active content stream")
        val font = HELVETICA
        val size = 9.5f

        val qty = holding.quantity.toStringExpanded()
        val avg = formatMoney(holding.averageCost.toStringExpanded())
        val last = formatMoney(holding.lastPrice.toStringExpanded())
        val value = formatMoney(holding.marketValue.toStringExpanded())
        val pnl = signedMoney(holding.unrealizedPnL.toStringExpanded())
        val alloc = String.format(Locale.US, "%.1f%%", holding.allocation * 100)
        val name = truncate(holding.name, 26)

        drawColumn(active, font, size, COLOR_PRIMARY, holding.symbol, COL_SYMBOL, alignRight = false)
        drawColumn(active, font, size, COLOR_PRIMARY, name, COL_NAME, alignRight = false)
        drawColumn(active, font, size, COLOR_PRIMARY, qty, COL_QTY, alignRight = true)
        drawColumn(active, font, size, COLOR_PRIMARY, avg, COL_AVG, alignRight = true)
        drawColumn(active, font, size, COLOR_PRIMARY, last, COL_LAST, alignRight = true)
        drawColumn(active, font, size, COLOR_PRIMARY, value, COL_VALUE, alignRight = true)

        val pnlColor = pnlColor(holding.unrealizedPnL.doubleValue(false))
        drawColumn(active, font, size, pnlColor, pnl, COL_PNL, alignRight = true)
        drawColumn(active, font, size, COLOR_PRIMARY, alloc, COL_ALLOC, alignRight = true)
    }

    private fun drawColumn(
        stream: PDPageContentStream,
        font: PDFont,
        size: Float,
        color: Triple<Float, Float, Float>,
        text: String,
        x: Float,
        alignRight: Boolean,
    ) {
        val originX = if (alignRight) {
            x - font.getStringWidth(text) / 1000f * size
        } else {
            x
        }
        stream.beginText()
        stream.setFont(font, size)
        stream.setNonStrokingColor(color.first, color.second, color.third)
        stream.newLineAtOffset(originX, y)
        stream.showText(text)
        stream.endText()
    }

    private fun pnlColor(amount: Double): Triple<Float, Float, Float> = when {
        amount > 0 -> COLOR_GAIN
        amount < 0 -> COLOR_LOSS
        else -> COLOR_PRIMARY
    }

    private fun truncate(text: String, maxCount: Int): String =
        if (text.length <= maxCount) text else text.take(maxCount - 1) + "…"

    fun finish() {
        stream?.close()
        stream = null
    }
}
