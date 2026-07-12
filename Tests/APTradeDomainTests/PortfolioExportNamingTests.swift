import XCTest
@testable import APTradeDomain

final class PortfolioExportNamingTests: XCTestCase {
    /// 2026-07-12 12:00:00 UTC as a fixed reference instant.
    private let fixed = Date(timeIntervalSince1970: 1_783_857_600)

    func test_fileStem_isDateStampedWithPosixFormat() {
        XCTAssertEqual(PortfolioExportNaming.fileStem(on: fixed), "APTrade-Portfolio-2026-07-12")
    }

    func test_filename_appendsFormatExtension() {
        XCTAssertEqual(PortfolioExportNaming.filename(for: .pdf, on: fixed), "APTrade-Portfolio-2026-07-12.pdf")
        XCTAssertEqual(PortfolioExportNaming.filename(for: .excel, on: fixed), "APTrade-Portfolio-2026-07-12.xlsx")
        XCTAssertEqual(PortfolioExportNaming.filename(for: .word, on: fixed), "APTrade-Portfolio-2026-07-12.docx")
    }
}
