package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import kotlinx.serialization.Serializable

/** Finnhub `/calendar/earnings` payload. Every field optional — the mapper drops rows it
 *  can't use rather than failing the whole response (news-DTO convention). The endpoint
 *  does not carry company names; [EarningsEvent.companyName] is left empty and UIs fall
 *  back to the symbol. */
@Serializable
data class FinnhubEarningsCalendarDTO(
    val earningsCalendar: List<FinnhubEarningsEntryDTO> = emptyList(),
)

@Serializable
data class FinnhubEarningsEntryDTO(
    val symbol: String? = null,
    val date: String? = null,       // yyyy-MM-dd
    val hour: String? = null,       // "bmo" | "amc" | "dmh" | ""
    val epsEstimate: Double? = null,
    val epsActual: Double? = null,
)

object FinnhubEarningsMapper {
    /** Maps the payload to events, dropping unusable rows AND collapsing duplicate
     *  (symbol, date) rows to the LAST one. Finnhub lists estimate revisions as extra rows
     *  for the same company/day (observed live: BNY twice on 2026-07-15, estimates 2.2023
     *  then 2.2271) — downstream, list keys and notification identity assume at most one
     *  event per (symbol, day), and duplicate LazyColumn keys crash Compose outright. Last
     *  row wins (the fresher revision); `associateBy` on a LinkedHashMap keeps the first
     *  encounter's position, so output order is stable. */
    fun events(dto: FinnhubEarningsCalendarDTO): List<EarningsEvent> =
        mappedRows(dto).associateBy { it.symbol to it.day }.values.toList()

    private fun mappedRows(dto: FinnhubEarningsCalendarDTO): List<EarningsEvent> =
        dto.earningsCalendar.mapNotNull { entry ->
            val symbol = entry.symbol?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val day = entry.date?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EarningsEvent(
                symbol = symbol,
                companyName = "",
                day = day,
                session = when (entry.hour) {
                    "bmo" -> EarningsSession.BeforeOpen
                    "amc" -> EarningsSession.AfterClose
                    "dmh" -> EarningsSession.DuringMarket
                    else -> EarningsSession.Unknown
                },
                epsEstimate = entry.epsEstimate,
                epsActual = entry.epsActual,
            )
        }
}
