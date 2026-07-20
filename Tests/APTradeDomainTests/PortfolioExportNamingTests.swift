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

    // MARK: - dividendsReceivedYTD (PortfolioExport(portfolio:quotes:accountName:) convenience init)

    private func usd(_ s: String) -> Money { Money(amount: Decimal(string: s) ?? 0) }
    private func qty(_ s: String) -> Quantity { Quantity(Decimal(string: s) ?? 0) }

    /// A UTC instant for the given calendar day, at noon (clear of any DST/midnight edge).
    private func utcDay(_ year: Int, _ month: Int, _ day: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        var components = DateComponents()
        components.year = year; components.month = month; components.day = day; components.hour = 12
        return calendar.date(from: components)!
    }

    func test_dividendsReceivedYTD_sumsOnlyCurrentCalendarYearDividendTransactions() {
        let asset = Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)
        let position = Position(asset: asset, quantity: qty("10"), averageCost: usd("100"), realizedPnL: Money(amount: 0))
        let transactions = [
            // 2025 dividend — excluded, prior calendar year.
            Transaction(symbol: "AAPL", side: .dividend, quantity: qty("5"), price: usd("2.00"), date: utcDay(2025, 12, 1)),
            // Two 2026 dividends — included: 10 × 0.50 = 5.00, and 20 × 1.00 = 20.00 -> 25.00.
            Transaction(symbol: "AAPL", side: .dividend, quantity: qty("10"), price: usd("0.50"), date: utcDay(2026, 3, 1)),
            Transaction(symbol: "AAPL", side: .dividend, quantity: qty("20"), price: usd("1.00"), date: utcDay(2026, 5, 1)),
            // A same-year buy — not a dividend, excluded regardless of amount.
            Transaction(symbol: "AAPL", side: .buy, quantity: qty("10"), price: usd("100"), date: utcDay(2026, 4, 1))
        ]
        let portfolio = Portfolio(cash: usd("50000"), positions: [position], transactions: transactions)

        let export = PortfolioExport(portfolio: portfolio, quotes: [:], accountName: "APTrade Portfolio",
                                     generatedAt: fixed)

        XCTAssertEqual(export.dividendsReceivedYTD, Decimal(string: "25.00"))
        XCTAssertEqual(export.projectedAnnualIncome, 0, "no projection was supplied to this convenience init call")
    }
}
