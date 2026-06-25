import XCTest
import APTradeDomain
@testable import APTradeInfrastructure

final class PortfolioExportRendererTests: XCTestCase {
    private let renderer = DefaultPortfolioExportRenderer()

    private func sampleExport() -> PortfolioExport {
        PortfolioExport(
            generatedAt: Date(timeIntervalSince1970: 1_700_000_000),
            accountName: "APTrade Portfolio",
            currencyCode: "USD",
            totalValue: 99_849.33,
            cash: 47_163.37,
            holdingsValue: 52_685.96,
            dayChange: -823.57,
            unrealizedPnL: -150.67,
            holdings: [
                .init(symbol: "MSFT", name: "Microsoft Corporation", kind: "stock",
                      quantity: 90, averageCost: 365.46, lastPrice: 365.46,
                      marketValue: 32_891.40, costBasis: 32_891.40, unrealizedPnL: 0, allocation: 0.329),
                .init(symbol: "SPY", name: "SPDR S&P 500 ETF", kind: "etf",
                      quantity: 11, averageCost: 738.77, lastPrice: 733.24,
                      marketValue: 8_065.64, costBasis: 8_126.46, unrealizedPnL: -60.82, allocation: 0.081)
            ]
        )
    }

    func test_pdf_hasPDFSignature() throws {
        let data = try renderer.render(sampleExport(), as: .pdf)
        XCTAssertGreaterThan(data.count, 1000)
        XCTAssertEqual(data.prefix(4), Data("%PDF".utf8), "PDF must start with the %PDF magic header")
        try write(data, ext: "pdf")
    }

    func test_xlsx_isAZipPackage() throws {
        let data = try renderer.render(sampleExport(), as: .excel)
        XCTAssertGreaterThan(data.count, 200)
        XCTAssertEqual(data.prefix(4), Data([0x50, 0x4b, 0x03, 0x04]), "xlsx must be a ZIP (PK\\x03\\x04)")
        try write(data, ext: "xlsx")
    }

    func test_docx_isAZipPackage() throws {
        let data = try renderer.render(sampleExport(), as: .word)
        XCTAssertGreaterThan(data.count, 200)
        XCTAssertEqual(data.prefix(4), Data([0x50, 0x4b, 0x03, 0x04]), "docx must be a ZIP (PK\\x03\\x04)")
        try write(data, ext: "docx")
    }

    func test_emptyPortfolio_stillRendersEveryFormat() throws {
        let empty = PortfolioExport(generatedAt: Date(), accountName: "APTrade Portfolio",
                                    currencyCode: "USD", totalValue: 100_000, cash: 100_000,
                                    holdingsValue: 0, dayChange: 0, unrealizedPnL: 0, holdings: [])
        for format in PortfolioExportFormat.allCases {
            let data = try renderer.render(empty, as: format)
            XCTAssertGreaterThan(data.count, 100, "\(format) should still produce a file")
        }
    }

    /// Writes each rendered file to a stable temp path so the structure can be validated
    /// with `unzip`/`xmllint` outside the test process.
    private func write(_ data: Data, ext: String) throws {
        let url = URL(fileURLWithPath: "/tmp/aptrade-export-sample.\(ext)")
        try data.write(to: url)
    }
}
