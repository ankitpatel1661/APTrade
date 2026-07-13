import Foundation
import APTradeApplication
import APTradeDomain

/// Finnhub `/calendar/earnings` payload. Every field optional — the mapper drops rows it
/// can't use rather than failing the whole response (mirrors `FinnhubArticleDTO`'s
/// all-optional convention). The endpoint carries no company name; `EarningsEvent.companyName`
/// is left empty and UIs fall back to the symbol.
struct FinnhubEarningsCalendarDTO: Decodable {
    let earningsCalendar: [FinnhubEarningsEntryDTO]?
}

struct FinnhubEarningsEntryDTO: Decodable {
    let symbol: String?
    let date: String?       // yyyy-MM-dd
    let hour: String?        // "bmo" | "amc" | "dmh" | ""
    let epsEstimate: Double?
    let epsActual: Double?
}

enum FinnhubEarningsMapper {
    static func events(from data: Data) throws -> [EarningsEvent] {
        let decoded: FinnhubEarningsCalendarDTO
        do { decoded = try JSONDecoder().decode(FinnhubEarningsCalendarDTO.self, from: data) }
        catch { throw AppError.decoding }
        return (decoded.earningsCalendar ?? []).compactMap(event(from:))
    }

    /// Skips any entry missing a non-blank symbol or date — the same drop rule as the
    /// Kotlin twin (`FinnhubEarningsMapper.events`).
    private static func event(from dto: FinnhubEarningsEntryDTO) -> EarningsEvent? {
        guard let symbol = dto.symbol, !symbol.isEmpty,
              let day = dto.date, !day.isEmpty else { return nil }

        let session: EarningsSession
        switch dto.hour {
        case "bmo": session = .beforeOpen
        case "amc": session = .afterClose
        case "dmh": session = .duringMarket
        default: session = .unknown
        }

        return EarningsEvent(
            symbol: symbol,
            companyName: "",
            day: day,
            session: session,
            epsEstimate: dto.epsEstimate,
            epsActual: dto.epsActual
        )
    }
}
