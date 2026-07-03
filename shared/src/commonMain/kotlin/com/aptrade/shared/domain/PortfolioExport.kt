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
    val holdings: List<Holding>,
) {
    data class Holding(
        val symbol: String,
        val name: String,
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
                    kind = position.asset.kind.name,
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
                holdings = rows,
            )
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
    return lines.joinToString("\n")
}

// MARK: - JSON rendering

@Serializable
private data class PortfolioExportDto(
    val generatedAtEpochSeconds: Long,
    val accountName: String,
    val currencyCode: String,
    val totalValue: String,
    val cash: String,
    val holdingsValue: String,
    val dayChange: String,
    val unrealizedPnL: String,
    val holdings: List<HoldingDto>,
)

@Serializable
private data class HoldingDto(
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
