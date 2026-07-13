package com.aptrade.shared.domain

/** When in the trading day a company reports. */
enum class EarningsSession { BeforeOpen, AfterClose, DuringMarket, Unknown }

/** One upcoming (or just-reported) earnings release. [day] is the market-local date as
 *  `yyyy-MM-dd` — the same string shape MarketCalendar.tradingDay produces, so day math
 *  and grouping are plain string equality. [companyName] may be empty (Finnhub omits it
 *  sometimes); UIs fall back to [symbol]. */
data class EarningsEvent(
    val symbol: String,
    val companyName: String,
    val day: String,
    val session: EarningsSession,
    val epsEstimate: Double?,
    val epsActual: Double?,
)
