import Foundation
#if canImport(AppKit)
import AppKit
typealias PlatformFont = NSFont
typealias PlatformColor = NSColor
#elseif canImport(UIKit)
import UIKit
typealias PlatformFont = UIFont
typealias PlatformColor = UIColor
#endif
import CoreText
import APTradeApplication
import APTradeDomain

/// Semantic label colors. AppKit exposes these as `NSColor.labelColor` /
/// `.secondaryLabelColor`; UIKit's equivalents are `.label` / `.secondaryLabel`.
/// These accessors keep the renderer's macOS color values identical to before
/// this file was made cross-platform.
private extension PlatformColor {
    static var primaryText: PlatformColor {
        #if canImport(AppKit)
        return .labelColor
        #elseif canImport(UIKit)
        return .label
        #endif
    }

    static var secondaryText: PlatformColor {
        #if canImport(AppKit)
        return .secondaryLabelColor
        #elseif canImport(UIKit)
        return .secondaryLabel
        #endif
    }
}

/// Renders a `PortfolioExport` to PDF, Excel (.xlsx), or Word (.docx). PDF is drawn with
/// Core Text into a Core Graphics PDF context; the two OOXML formats are assembled as ZIP
/// packages of hand-written XML parts. All output is genuine, conformant document data.
public struct DefaultPortfolioExportRenderer: PortfolioExportRenderer {
    public init() {}

    public func render(_ export: PortfolioExport, as format: PortfolioExportFormat) throws -> Data {
        switch format {
        case .pdf: return PDFExportRenderer().render(export)
        case .excel: return XLSXExportRenderer().render(export)
        case .word: return DOCXExportRenderer().render(export)
        }
    }
}

// MARK: - Shared formatting

private enum ExportFormatting {
    static func currency(_ amount: Decimal, code: String) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US")
        formatter.numberStyle = .currency
        formatter.currencyCode = code
        formatter.maximumFractionDigits = 2
        return formatter.string(from: amount as NSDecimalNumber) ?? "\(amount)"
    }

    static func signedCurrency(_ amount: Decimal, code: String) -> String {
        let base = currency(amount, code: code)
        return amount > 0 ? "+" + base : base
    }

    static func quantity(_ amount: Decimal) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US")
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 8
        return formatter.string(from: amount as NSDecimalNumber) ?? "\(amount)"
    }

    static func percent(_ fraction: Double) -> String {
        String(format: "%.1f%%", fraction * 100)
    }

    static func timestamp(_ date: Date) -> String {
        date.formatted(.dateTime.month(.wide).day().year().hour().minute())
    }

    static func double(_ amount: Decimal) -> Double {
        (amount as NSDecimalNumber).doubleValue
    }
}

// MARK: - PDF

private struct PDFExportRenderer {
    // Landscape US Letter, comfortably fitting the holdings table.
    private let pageSize = CGSize(width: 792, height: 612)
    private let margin: CGFloat = 40

    func render(_ export: PortfolioExport) -> Data {
        let attributed = buildDocument(export)
        let data = NSMutableData()
        guard let consumer = CGDataConsumer(data: data as CFMutableData) else { return Data() }
        var mediaBox = CGRect(origin: .zero, size: pageSize)
        guard let context = CGContext(consumer: consumer, mediaBox: &mediaBox, nil) else { return Data() }

        let textRect = CGRect(x: margin, y: margin,
                              width: pageSize.width - margin * 2,
                              height: pageSize.height - margin * 2)
        let framesetter = CTFramesetterCreateWithAttributedString(attributed)
        let total = attributed.length
        var start = 0

        while start < total {
            context.beginPDFPage(nil)
            let path = CGPath(rect: textRect, transform: nil)
            let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: start, length: 0), path, nil)
            CTFrameDraw(frame, context)
            let visible = CTFrameGetVisibleStringRange(frame)
            context.endPDFPage()
            if visible.length == 0 { break }   // guard against non-advancing layout
            start += visible.length
        }

        context.closePDF()
        return data as Data
    }

    private func buildDocument(_ export: PortfolioExport) -> NSAttributedString {
        let code = export.currencyCode
        let doc = NSMutableAttributedString()

        doc.append(line(export.accountName, font: .systemFont(ofSize: 22, weight: .bold)))
        doc.append(line("Portfolio Statement · \(ExportFormatting.timestamp(export.generatedAt))",
                        font: .systemFont(ofSize: 11), color: .secondaryText, spacingAfter: 14))

        // Summary
        let summary: [(String, String)] = [
            ("Total Value", ExportFormatting.currency(export.totalValue, code: code)),
            ("Cash", ExportFormatting.currency(export.cash, code: code)),
            ("Holdings Value", ExportFormatting.currency(export.holdingsValue, code: code)),
            ("Day P&L", ExportFormatting.signedCurrency(export.dayChange, code: code)),
            ("Unrealized P&L", ExportFormatting.signedCurrency(export.unrealizedPnL, code: code))
        ]
        for (label, value) in summary {
            let row = NSMutableAttributedString()
            row.append(NSAttributedString(string: label + ":  ",
                attributes: [.font: PlatformFont.systemFont(ofSize: 11, weight: .semibold)]))
            row.append(NSAttributedString(string: value,
                attributes: [.font: PlatformFont.monospacedDigitSystemFont(ofSize: 11, weight: .regular)]))
            row.append(NSAttributedString(string: "\n"))
            doc.append(row)
        }
        doc.append(NSAttributedString(string: "\n"))

        // Holdings table
        if export.holdings.isEmpty {
            doc.append(line("No holdings — the account is all cash.",
                            font: .systemFont(ofSize: 11), color: .secondaryText))
        } else {
            doc.append(tableRow(
                symbol: "SYMBOL", name: "NAME", qty: "QTY", avg: "AVG COST",
                last: "LAST", value: "MKT VALUE", pnl: "UNREAL P&L", alloc: "ALLOC",
                font: .systemFont(ofSize: 9, weight: .bold), pnlColor: .primaryText))
            for holding in export.holdings {
                doc.append(tableRow(
                    symbol: holding.symbol,
                    name: truncate(holding.name, to: 26),
                    qty: ExportFormatting.quantity(holding.quantity),
                    avg: ExportFormatting.currency(holding.averageCost, code: code),
                    last: ExportFormatting.currency(holding.lastPrice, code: code),
                    value: ExportFormatting.currency(holding.marketValue, code: code),
                    pnl: ExportFormatting.signedCurrency(holding.unrealizedPnL, code: code),
                    alloc: ExportFormatting.percent(holding.allocation),
                    font: .monospacedDigitSystemFont(ofSize: 9.5, weight: .regular),
                    pnlColor: pnlColor(holding.unrealizedPnL)))
            }
        }
        return doc
    }

    private func pnlColor(_ amount: Decimal) -> PlatformColor {
        if amount > 0 { return PlatformColor(red: 0.16, green: 0.55, blue: 0.34, alpha: 1) }
        if amount < 0 { return PlatformColor(red: 0.70, green: 0.20, blue: 0.16, alpha: 1) }
        return .primaryText
    }

    private func tableRow(symbol: String, name: String, qty: String, avg: String,
                          last: String, value: String, pnl: String, alloc: String,
                          font: PlatformFont, pnlColor: PlatformColor) -> NSAttributedString {
        let paragraph = NSMutableParagraphStyle()
        paragraph.tabStops = [
            NSTextTab(textAlignment: .left, location: 70),    // Name
            NSTextTab(textAlignment: .right, location: 250),  // Qty
            NSTextTab(textAlignment: .right, location: 345),  // Avg cost
            NSTextTab(textAlignment: .right, location: 440),  // Last
            NSTextTab(textAlignment: .right, location: 560),  // Market value
            NSTextTab(textAlignment: .right, location: 660),  // Unrealized P&L
            NSTextTab(textAlignment: .right, location: 712)   // Allocation
        ]
        paragraph.paragraphSpacing = 3
        paragraph.lineBreakMode = .byClipping

        let result = NSMutableAttributedString()
        let base: [NSAttributedString.Key: Any] = [.font: font, .paragraphStyle: paragraph]
        result.append(NSAttributedString(string: "\(symbol)\t\(name)\t\(qty)\t\(avg)\t\(last)\t\(value)\t",
                                         attributes: base))
        var pnlAttrs = base
        pnlAttrs[.foregroundColor] = pnlColor
        result.append(NSAttributedString(string: pnl, attributes: pnlAttrs))
        result.append(NSAttributedString(string: "\t\(alloc)\n", attributes: base))
        return result
    }

    private func line(_ text: String, font: PlatformFont, color: PlatformColor = .primaryText,
                      spacingAfter: CGFloat = 2) -> NSAttributedString {
        let paragraph = NSMutableParagraphStyle()
        paragraph.paragraphSpacing = spacingAfter
        return NSAttributedString(string: text + "\n",
                                  attributes: [.font: font, .foregroundColor: color,
                                               .paragraphStyle: paragraph])
    }

    private func truncate(_ text: String, to maxCount: Int) -> String {
        text.count <= maxCount ? text : String(text.prefix(maxCount - 1)) + "…"
    }
}

// MARK: - XLSX (OOXML SpreadsheetML)

private struct XLSXExportRenderer {
    private enum Cell {
        case text(String)
        case number(Double)
    }

    func render(_ export: PortfolioExport) -> Data {
        var writer = ZipArchiveWriter()
        writer.addFile("[Content_Types].xml", contentTypes)
        writer.addFile("_rels/.rels", rootRels)
        writer.addFile("xl/workbook.xml", workbook)
        writer.addFile("xl/_rels/workbook.xml.rels", workbookRels)
        writer.addFile("xl/worksheets/sheet1.xml", worksheet(export))
        return writer.finalize()
    }

    private var contentTypes: String {
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
        <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
        </Types>
        """
    }

    private var rootRels: String {
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
        """
    }

    private var workbook: String {
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <sheets><sheet name="Portfolio" sheetId="1" r:id="rId1"/></sheets>
        </workbook>
        """
    }

    private var workbookRels: String {
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
        </Relationships>
        """
    }

    private func worksheet(_ export: PortfolioExport) -> String {
        let code = export.currencyCode
        var rows: [[Cell]] = [
            [.text(export.accountName)],
            [.text("Portfolio Statement · \(ExportFormatting.timestamp(export.generatedAt))")],
            [],
            [.text("Total Value (\(code))"), .number(ExportFormatting.double(export.totalValue))],
            [.text("Cash (\(code))"), .number(ExportFormatting.double(export.cash))],
            [.text("Holdings Value (\(code))"), .number(ExportFormatting.double(export.holdingsValue))],
            [.text("Day P&L (\(code))"), .number(ExportFormatting.double(export.dayChange))],
            [.text("Unrealized P&L (\(code))"), .number(ExportFormatting.double(export.unrealizedPnL))],
            [],
            [.text("Symbol"), .text("Name"), .text("Kind"), .text("Quantity"),
             .text("Avg Cost"), .text("Last Price"), .text("Market Value"),
             .text("Cost Basis"), .text("Unrealized P&L"), .text("Allocation %")]
        ]
        for holding in export.holdings {
            rows.append([
                .text(holding.symbol),
                .text(holding.name),
                .text(holding.kind.uppercased()),
                .number(ExportFormatting.double(holding.quantity)),
                .number(ExportFormatting.double(holding.averageCost)),
                .number(ExportFormatting.double(holding.lastPrice)),
                .number(ExportFormatting.double(holding.marketValue)),
                .number(ExportFormatting.double(holding.costBasis)),
                .number(ExportFormatting.double(holding.unrealizedPnL)),
                .number(holding.allocation * 100)
            ])
        }

        var sheetData = ""
        for (rowIndex, cells) in rows.enumerated() {
            let rowNumber = rowIndex + 1
            sheetData += "<row r=\"\(rowNumber)\">"
            for (columnIndex, cell) in cells.enumerated() {
                let reference = "\(Self.columnLetter(columnIndex))\(rowNumber)"
                switch cell {
                case .text(let value):
                    sheetData += "<c r=\"\(reference)\" t=\"inlineStr\"><is><t xml:space=\"preserve\">\(XML.escape(value))</t></is></c>"
                case .number(let value):
                    sheetData += "<c r=\"\(reference)\"><v>\(value)</v></c>"
                }
            }
            sheetData += "</row>"
        }

        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <sheetData>\(sheetData)</sheetData>
        </worksheet>
        """
    }

    /// 0-based column index → spreadsheet column letters (0→A, 25→Z, 26→AA).
    private static func columnLetter(_ index: Int) -> String {
        var n = index
        var letters = ""
        repeat {
            letters = String(UnicodeScalar(UInt8(65 + n % 26))) + letters
            n = n / 26 - 1
        } while n >= 0
        return letters
    }
}

// MARK: - DOCX (OOXML WordprocessingML)

private struct DOCXExportRenderer {
    func render(_ export: PortfolioExport) -> Data {
        var writer = ZipArchiveWriter()
        writer.addFile("[Content_Types].xml", contentTypes)
        writer.addFile("_rels/.rels", rootRels)
        writer.addFile("word/document.xml", document(export))
        return writer.finalize()
    }

    private var contentTypes: String {
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        </Types>
        """
    }

    private var rootRels: String {
        """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
        """
    }

    private func document(_ export: PortfolioExport) -> String {
        let code = export.currencyCode
        var body = ""
        body += heading(export.accountName, size: 36, bold: true)
        body += heading("Portfolio Statement · \(ExportFormatting.timestamp(export.generatedAt))",
                        size: 20, bold: false)
        body += paragraph("")

        let summary: [(String, String)] = [
            ("Total Value", ExportFormatting.currency(export.totalValue, code: code)),
            ("Cash", ExportFormatting.currency(export.cash, code: code)),
            ("Holdings Value", ExportFormatting.currency(export.holdingsValue, code: code)),
            ("Day P&L", ExportFormatting.signedCurrency(export.dayChange, code: code)),
            ("Unrealized P&L", ExportFormatting.signedCurrency(export.unrealizedPnL, code: code))
        ]
        for (label, value) in summary {
            body += summaryParagraph(label: label, value: value)
        }
        body += paragraph("")

        let header = ["Symbol", "Name", "Kind", "Quantity", "Avg Cost",
                      "Last Price", "Market Value", "Unrealized P&L", "Allocation %"]
        var tableRows = [tableRow(header, bold: true)]
        for holding in export.holdings {
            tableRows.append(tableRow([
                holding.symbol,
                holding.name,
                holding.kind.uppercased(),
                ExportFormatting.quantity(holding.quantity),
                ExportFormatting.currency(holding.averageCost, code: code),
                ExportFormatting.currency(holding.lastPrice, code: code),
                ExportFormatting.currency(holding.marketValue, code: code),
                ExportFormatting.signedCurrency(holding.unrealizedPnL, code: code),
                ExportFormatting.percent(holding.allocation)
            ], bold: false))
        }
        if !export.holdings.isEmpty {
            body += table(tableRows, columns: header.count)
        }

        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
        <w:body>\(body)<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr></w:body>
        </w:document>
        """
    }

    private func heading(_ text: String, size: Int, bold: Bool) -> String {
        let runProps = "<w:rPr>\(bold ? "<w:b/>" : "")<w:sz w:val=\"\(size)\"/></w:rPr>"
        return "<w:p><w:pPr><w:spacing w:after=\"60\"/></w:pPr><w:r>\(runProps)<w:t xml:space=\"preserve\">\(XML.escape(text))</w:t></w:r></w:p>"
    }

    private func paragraph(_ text: String) -> String {
        "<w:p><w:r><w:t xml:space=\"preserve\">\(XML.escape(text))</w:t></w:r></w:p>"
    }

    private func summaryParagraph(label: String, value: String) -> String {
        "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">\(XML.escape(label)): </w:t></w:r>"
        + "<w:r><w:t xml:space=\"preserve\">\(XML.escape(value))</w:t></w:r></w:p>"
    }

    private func tableRow(_ cells: [String], bold: Bool) -> String {
        var row = "<w:tr>"
        for cell in cells {
            let runProps = bold ? "<w:rPr><w:b/></w:rPr>" : ""
            row += "<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>"
            row += "<w:p><w:r>\(runProps)<w:t xml:space=\"preserve\">\(XML.escape(cell))</w:t></w:r></w:p></w:tc>"
        }
        row += "</w:tr>"
        return row
    }

    private func table(_ rows: [String], columns: Int) -> String {
        let borders = """
        <w:tblBorders>\
        <w:top w:val="single" w:sz="4" w:space="0" w:color="CCCCCC"/>\
        <w:left w:val="single" w:sz="4" w:space="0" w:color="CCCCCC"/>\
        <w:bottom w:val="single" w:sz="4" w:space="0" w:color="CCCCCC"/>\
        <w:right w:val="single" w:sz="4" w:space="0" w:color="CCCCCC"/>\
        <w:insideH w:val="single" w:sz="4" w:space="0" w:color="CCCCCC"/>\
        <w:insideV w:val="single" w:sz="4" w:space="0" w:color="CCCCCC"/>\
        </w:tblBorders>
        """
        // <w:tblGrid> with one <w:gridCol> per column is required by the schema; omitting it
        // makes Word prompt to repair the file. Columns share the printable width evenly.
        let columnWidth = columns > 0 ? 10_800 / columns : 10_800
        let grid = "<w:tblGrid>" + String(repeating: "<w:gridCol w:w=\"\(columnWidth)\"/>", count: columns) + "</w:tblGrid>"
        return "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>\(borders)</w:tblPr>\(grid)\(rows.joined())</w:tbl>"
    }
}

// MARK: - XML escaping

private enum XML {
    static func escape(_ string: String) -> String {
        var result = string
        result = result.replacingOccurrences(of: "&", with: "&amp;")
        result = result.replacingOccurrences(of: "<", with: "&lt;")
        result = result.replacingOccurrences(of: ">", with: "&gt;")
        result = result.replacingOccurrences(of: "\"", with: "&quot;")
        result = result.replacingOccurrences(of: "'", with: "&apos;")
        return result
    }
}
