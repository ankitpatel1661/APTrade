package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Transcribed from `Sources/APTradeDomain/PortfolioExport.swift` (`PortfolioExport`,
 * `PortfolioExport.init(portfolio:quotes:accountName:generatedAt:)`). Semantics must not
 * drift from the Swift original. Numbers are carried as `BigDecimal`/`Double` so each
 * renderer can format them (CSV) or emit them as a serializable numeric-as-string DTO
 * (JSON). Pure.
 */

/** A presentation-agnostic snapshot of a portfolio prepared for export: account totals plus
 *  a valued row per holding. */
data class PortfolioExport(
    val generatedAtEpochSeconds: Long,
    val accountName: String,
    val currencyCode: String,
    val totalValue: Money,
    val cash: Money,
    val holdingsValue: Money,
    val dayChange: Money,
    val unrealizedPnL: Money,
    /** Sum of Dividend ledger transactions (quantity x price-per-share) whose date falls in
     *  the current UTC calendar year as of `generatedAtEpochSeconds`. */
    val dividendsReceivedYTD: BigDecimal = BigDecimal.ZERO,
    /** Forward-looking annual dividend income from `DividendMath.projectedAnnualIncome`, or
     *  zero when the caller had no dividend-events data to project from. */
    val projectedAnnualIncome: BigDecimal = BigDecimal.ZERO,
    val holdings: List<Holding>,
) {
    data class Holding(
        val symbol: String,
        val name: String,
        /** The canonical machine key for the asset kind: lowercase ("stock"/"etf"/"crypto"),
         *  matching the Swift domain's `AssetKind.rawValue`. Decided 6b.3 — this is write-only
         *  export data with no in-app consumers, so the casing is a clean break from the enum's
         *  PascalCase `name`. */
        val kind: String,
        val quantity: BigDecimal,
        val averageCost: BigDecimal,
        val lastPrice: BigDecimal,
        val marketValue: BigDecimal,
        val costBasis: BigDecimal,
        val unrealizedPnL: BigDecimal,
        /** Share of total portfolio value, 0...1. */
        val allocation: Double,
    )

    companion object {
        /** Builds an export snapshot by valuing `portfolio` against `quotes` (falling back
         *  to cost basis when a quote is missing, mirroring `Portfolio.valuation`).
         *  Holdings are ordered by market value, largest first. */
        fun from(
            portfolio: Portfolio,
            quotes: Map<String, Quote>,
            accountName: String,
            generatedAtEpochSeconds: Long,
            projectedAnnualIncome: BigDecimal = BigDecimal.ZERO,
        ): PortfolioExport {
            val valuation = portfolio.valuation(quotes)
            val total = valuation.totalValue.amount
            val totalDouble = total.doubleValue(false)

            val rows = portfolio.positions.map { position ->
                val price = quotes[position.asset.symbol]?.price?.amount ?: position.averageCost.amount
                val qty = position.quantity
                val marketValue = price * qty
                val costBasis = position.averageCost.amount * qty
                val allocation = if (totalDouble == 0.0) {
                    0.0
                } else {
                    marketValue.doubleValue(false) / totalDouble
                }
                Holding(
                    symbol = position.asset.symbol,
                    name = position.asset.name,
                    kind = position.asset.kind.name.lowercase(),
                    quantity = qty,
                    averageCost = position.averageCost.amount,
                    lastPrice = price,
                    marketValue = marketValue,
                    costBasis = costBasis,
                    unrealizedPnL = marketValue - costBasis,
                    allocation = allocation,
                )
            }.sortedByDescending { it.marketValue }

            return PortfolioExport(
                generatedAtEpochSeconds = generatedAtEpochSeconds,
                accountName = accountName,
                currencyCode = valuation.totalValue.currencyCode,
                totalValue = valuation.totalValue,
                cash = valuation.cash,
                holdingsValue = valuation.holdingsValue,
                dayChange = valuation.dayChange,
                unrealizedPnL = valuation.unrealizedPnL,
                dividendsReceivedYTD = dividendsReceivedYTD(portfolio.transactions, generatedAtEpochSeconds),
                projectedAnnualIncome = projectedAnnualIncome,
                holdings = rows,
            )
        }

        /** Sum of Dividend transactions (quantity x price-per-share) dated within the same
         *  UTC calendar year as [asOfEpochSeconds]. Pure ledger arithmetic — no quotes, no
         *  networking. Mirrors `PortfolioExport.dividendsReceivedYTD` (Swift). */
        private fun dividendsReceivedYTD(transactions: List<Transaction>, asOfEpochSeconds: Long): BigDecimal {
            val currentYear = utcYear(asOfEpochSeconds)
            var total = BigDecimal.ZERO
            for (txn in transactions) {
                if (txn.side != TradeSide.Dividend) continue
                if (utcYear(txn.epochSeconds) != currentYear) continue
                total += txn.quantity * txn.price.amount
            }
            return total
        }

        // --- UTC epoch-day civil-date math (private copy) ---------------------------------
        // DividendMath.kt (shared/src/commonMain/kotlin/com/aptrade/shared/domain/
        // DividendMath.kt) already implements this exact Hinnant civil-date algorithm,
        // privately — but per that file's own doc, the house precedent is each type keeps
        // its OWN private copy rather than widening visibility to share it. Only the minimal
        // piece this file needs (UTC epoch-seconds -> epoch-day -> civil year) is reproduced
        // here.

        private const val SECONDS_PER_DAY = 86_400L

        private fun utcYear(epochSeconds: Long): Long {
            val epochDay = floorDiv(epochSeconds, SECONDS_PER_DAY)
            return civilYearFromDays(epochDay)
        }

        private fun floorDiv(x: Long, y: Long): Long {
            val q = x / y
            return if ((x xor y) < 0 && q * y != x) q - 1 else q
        }

        private fun civilYearFromDays(z0: Long): Long {
            val z = z0 + 719_468L
            val era = floorDiv(z, 146_097L)
            val doe = z - era * 146_097L // [0, 146096]
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365 // [0, 399]
            val y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
            val mp = (5 * doy + 2) / 153 // [0, 11]
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt() // [1, 12]
            return if (m <= 2) y + 1 else y
        }
    }
}

// MARK: - CSV rendering
//
// Stable, comma-separated format designed for this port (no Swift CSV renderer exists to
// transcribe). Layout:
//
//   Account,<accountName>
//   Generated At (epoch seconds),<generatedAtEpochSeconds>
//   Currency,<currencyCode>
//   <blank line>
//   Symbol,Name,Kind,Quantity,Average Cost,Last Price,Market Value,Cost Basis,Unrealized PnL,Allocation
//   <one row per holding, largest market value first>
//   <blank line>
//   Total Value,<totalValue>
//   Cash,<cash>
//   Holdings Value,<holdingsValue>
//   Day Change,<dayChange>
//   Unrealized PnL,<unrealizedPnL>
//   Dividends Received (YTD),<dividendsReceivedYTD>
//   Projected Annual Income,<projectedAnnualIncome>
//
// Amounts are plain decimal strings (`toStringExpanded()`), never locale-formatted.
// Allocation is a plain decimal fraction (0..1) rendered with up to 6 fractional digits,
// trailing zeroes trimmed (e.g. "0.5", "0.340659"). Fields never contain commas, so no
// quoting/escaping is required.

/** Fraction (0..1) as a plain decimal string with up to 6 dp, never scientific notation. */
private fun renderFraction(value: Double): String {
    if (value == 0.0) return "0"
    val micro = kotlin.math.round(value * 1_000_000).toLong()   // fixed-point micro-units
    val whole = micro / 1_000_000
    val frac = (micro % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return if (frac.isEmpty()) whole.toString() else "$whole.$frac"
}

/** Renders this export as the stable CSV format documented above. */
fun PortfolioExport.renderCsv(): String {
    val lines = mutableListOf<String>()
    lines += "Account,$accountName"
    lines += "Generated At (epoch seconds),$generatedAtEpochSeconds"
    lines += "Currency,$currencyCode"
    lines += ""
    lines += "Symbol,Name,Kind,Quantity,Average Cost,Last Price,Market Value,Cost Basis,Unrealized PnL,Allocation"
    for (holding in holdings) {
        lines += listOf(
            holding.symbol,
            holding.name,
            holding.kind,
            holding.quantity.toStringExpanded(),
            holding.averageCost.toStringExpanded(),
            holding.lastPrice.toStringExpanded(),
            holding.marketValue.toStringExpanded(),
            holding.costBasis.toStringExpanded(),
            holding.unrealizedPnL.toStringExpanded(),
            renderFraction(holding.allocation),
        ).joinToString(",")
    }
    lines += ""
    lines += "Total Value,${totalValue.amount.toStringExpanded()}"
    lines += "Cash,${cash.amount.toStringExpanded()}"
    lines += "Holdings Value,${holdingsValue.amount.toStringExpanded()}"
    lines += "Day Change,${dayChange.amount.toStringExpanded()}"
    lines += "Unrealized PnL,${unrealizedPnL.amount.toStringExpanded()}"
    lines += "Dividends Received (YTD),${dividendsReceivedYTD.toStringExpanded()}"
    lines += "Projected Annual Income,${projectedAnnualIncome.toStringExpanded()}"
    return lines.joinToString("\n")
}

// MARK: - JSON rendering

/** `internal` (not `private`) so a hand-written legacy-export fixture (a pre-M8.2 JSON blob,
 *  missing both income keys) can be decoded directly in `PortfolioExportTest` to verify the
 *  two new fields fall back to their `"0"` defaults rather than failing to decode. */
@Serializable
internal data class PortfolioExportDto(
    val generatedAtEpochSeconds: Long,
    val accountName: String,
    val currencyCode: String,
    val totalValue: String,
    val cash: String,
    val holdingsValue: String,
    val dayChange: String,
    val unrealizedPnL: String,
    val dividendsReceivedYTD: String = "0",
    val projectedAnnualIncome: String = "0",
    val holdings: List<HoldingDto>,
)

@Serializable
internal data class HoldingDto(
    val symbol: String,
    val name: String,
    val kind: String,
    val quantity: String,
    val averageCost: String,
    val lastPrice: String,
    val marketValue: String,
    val costBasis: String,
    val unrealizedPnL: String,
    val allocation: Double,
)

private val exportJson = Json { prettyPrint = false }

/** Renders this export as JSON. BigDecimal fields are carried as exact decimal strings
 *  (`toStringExpanded()`), never as JSON numbers, to avoid a Double round-trip. */
fun PortfolioExport.renderJson(): String {
    val dto = PortfolioExportDto(
        generatedAtEpochSeconds = generatedAtEpochSeconds,
        accountName = accountName,
        currencyCode = currencyCode,
        totalValue = totalValue.amount.toStringExpanded(),
        cash = cash.amount.toStringExpanded(),
        holdingsValue = holdingsValue.amount.toStringExpanded(),
        dayChange = dayChange.amount.toStringExpanded(),
        unrealizedPnL = unrealizedPnL.amount.toStringExpanded(),
        dividendsReceivedYTD = dividendsReceivedYTD.toStringExpanded(),
        projectedAnnualIncome = projectedAnnualIncome.toStringExpanded(),
        holdings = holdings.map { holding ->
            HoldingDto(
                symbol = holding.symbol,
                name = holding.name,
                kind = holding.kind,
                quantity = holding.quantity.toStringExpanded(),
                averageCost = holding.averageCost.toStringExpanded(),
                lastPrice = holding.lastPrice.toStringExpanded(),
                marketValue = holding.marketValue.toStringExpanded(),
                costBasis = holding.costBasis.toStringExpanded(),
                unrealizedPnL = holding.unrealizedPnL.toStringExpanded(),
                allocation = holding.allocation,
            )
        },
    )
    return exportJson.encodeToString(PortfolioExportDto.serializer(), dto)
}
