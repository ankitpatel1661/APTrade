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
    fun events(dto: FinnhubEarningsCalendarDTO): List<EarningsEvent> =
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
