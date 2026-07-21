package com.aptrade.desktop.infra

import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioExport
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Extracts all text from every page of a rendered PDF, via PDFBox's PDFTextStripper —
 *  we assert on content, not pixels. */
private fun extractText(bytes: ByteArray): String {
    Loader.loadPDF(bytes).use { document ->
        return PDFTextStripper().getText(document)
    }
}

private fun oneHoldingExport(): PortfolioExport = PortfolioExport(
    generatedAtEpochSeconds = 1_700_000_000L,
    accountName = "Test Account",
    currencyCode = "USD",
    totalValue = Money.usd("100100"),
    cash = Money.usd("91000"),
    holdingsValue = Money.usd("9100"),
    dayChange = Money.usd("50"),
    unrealizedPnL = Money.usd("100"),
    holdings = listOf(
        PortfolioExport.Holding(
            symbol = "AAPL",
            name = "Apple Inc.",
            kind = "Stock",
            quantity = BigDecimal.parseString("10"),
            averageCost = BigDecimal.parseString("300"),
            lastPrice = BigDecimal.parseString("310"),
            marketValue = BigDecimal.parseString("3100"),
            costBasis = BigDecimal.parseString("3000"),
            unrealizedPnL = BigDecimal.parseString("100"),
            allocation = 3100.0 / 100100.0,
        ),
    ),
)

private fun allCashExport(): PortfolioExport = PortfolioExport(
    generatedAtEpochSeconds = 1_700_000_000L,
    accountName = "Test Account",
    currencyCode = "USD",
    totalValue = Money.usd("91000"),
    cash = Money.usd("91000"),
    holdingsValue = Money.usd("0"),
    dayChange = Money.usd("0"),
    unrealizedPnL = Money.usd("0"),
    holdings = emptyList(),
)

class PdfPortfolioRendererTest {

    @Test
    fun pdfContainsStatementHeaderAndSummary() {
        val bytes = renderPortfolioPdf(oneHoldingExport())
        val text = extractText(bytes)

        assertTrue(text.contains("Portfolio Statement"))
        assertTrue(text.contains("Total Value"))
        assertTrue(text.contains("Cash"))
        assertTrue(text.contains("Holdings Value"))
        assertTrue(text.contains("Unrealized P&L"))
        assertTrue(text.contains("AAPL"))
    }

    @Test
    fun pdfEmptyHoldingsMessage() {
        val bytes = renderPortfolioPdf(allCashExport())
        val text = extractText(bytes)

        assertTrue(text.contains("No holdings — the account is all cash."))
    }

    @Test
    fun pdfContainsDividendIncomeSummaryRows() {
        val export = oneHoldingExport().copy(
            dividendsReceivedYTD = BigDecimal.parseString("500.25"),
            projectedAnnualIncome = BigDecimal.parseString("1200"),
        )
        val bytes = renderPortfolioPdf(export)
        val text = extractText(bytes)

        assertTrue(text.contains("Dividends Received (YTD)"))
        assertTrue(text.contains("Projected Annual Income"))
        assertTrue(text.contains("$500.25"))
        assertTrue(text.contains("$1,200.00"))
    }

    @Test
    fun pdfTableHeaderColumns() {
        val bytes = renderPortfolioPdf(oneHoldingExport())
        val text = extractText(bytes)

        assertTrue(text.contains("SYMBOL"))
        assertTrue(text.contains("QTY"))
        assertTrue(text.contains("AVG COST"))
        assertTrue(text.contains("MKT VALUE"))
        assertTrue(text.contains("ALLOC"))
    }

    @Test
    fun pdfPaginatesWhenHoldingsOverflowPage() {
        val manyHoldings = (1..80).map { i ->
            PortfolioExport.Holding(
                symbol = "SYM$i",
                name = "Company Name Number $i Inc.",
                kind = "Stock",
                quantity = BigDecimal.parseString("10"),
                averageCost = BigDecimal.parseString("100"),
                lastPrice = BigDecimal.parseString("110"),
                marketValue = BigDecimal.parseString("1100"),
                costBasis = BigDecimal.parseString("1000"),
                unrealizedPnL = BigDecimal.parseString("100"),
                allocation = 1.0 / 80,
            )
        }
        val export = oneHoldingExport().copy(holdings = manyHoldings)
        val bytes = renderPortfolioPdf(export)

        Loader.loadPDF(bytes).use { document ->
            assertTrue(document.numberOfPages > 1, "expected pagination across multiple pages")
        }
    }

    @Test
    fun exportFileNameIsDateStamped() {
        val epochSeconds = Instant.from(
            LocalDate.of(2026, 7, 3).atStartOfDay(ZoneOffset.UTC),
        ).epochSecond

        assertEquals("APTrade-Portfolio-2026-07-03.pdf", exportFileName("pdf", epochSeconds))
    }

    @Test
    fun truncateIsCharExactAtTheBoundary() {
        // A name exactly at the cap passes through untouched; one char over collapses to
        // `take(25) + "…"` — exactly 26 chars, with the 26th being the ellipsis.
        val exact = "A".repeat(26)                 // 26 chars: fits
        assertEquals(exact, truncate(exact, 26))

        val over = "A".repeat(27)                  // 27 chars: overflows by one
        val expected = "A".repeat(25) + "…"        // 25 A's + ellipsis = 26 chars
        assertEquals(expected, truncate(over, 26))
        assertEquals(26, truncate(over, 26).length)
    }
}
