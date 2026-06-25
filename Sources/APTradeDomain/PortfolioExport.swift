import Foundation

/// The file formats a portfolio statement can be exported to. Each maps to a real,
/// standards-compliant document — a Core Graphics PDF, an OOXML spreadsheet (.xlsx),
/// or an OOXML word-processing document (.docx).
public enum PortfolioExportFormat: String, CaseIterable, Sendable {
    case pdf
    case excel
    case word

    public var displayName: String {
        switch self {
        case .pdf: return "PDF Document"
        case .excel: return "Excel Spreadsheet"
        case .word: return "Word Document"
        }
    }

    public var fileExtension: String {
        switch self {
        case .pdf: return "pdf"
        case .excel: return "xlsx"
        case .word: return "docx"
        }
    }
}

/// A presentation-agnostic snapshot of a portfolio prepared for export: account totals
/// plus a valued row per holding. Numbers are carried as raw `Decimal`/`Double` so each
/// renderer can format (PDF/Word) or emit them as live numeric cells (Excel). Pure.
public struct PortfolioExport: Sendable, Equatable {
    public let generatedAt: Date
    public let accountName: String
    public let currencyCode: String
    public let totalValue: Decimal
    public let cash: Decimal
    public let holdingsValue: Decimal
    public let dayChange: Decimal
    public let unrealizedPnL: Decimal
    public let holdings: [Holding]

    public struct Holding: Sendable, Equatable {
        public let symbol: String
        public let name: String
        public let kind: String
        public let quantity: Decimal
        public let averageCost: Decimal
        public let lastPrice: Decimal
        public let marketValue: Decimal
        public let costBasis: Decimal
        public let unrealizedPnL: Decimal
        /// Share of total portfolio value, 0...1.
        public let allocation: Double

        public init(symbol: String, name: String, kind: String, quantity: Decimal,
                    averageCost: Decimal, lastPrice: Decimal, marketValue: Decimal,
                    costBasis: Decimal, unrealizedPnL: Decimal, allocation: Double) {
            self.symbol = symbol
            self.name = name
            self.kind = kind
            self.quantity = quantity
            self.averageCost = averageCost
            self.lastPrice = lastPrice
            self.marketValue = marketValue
            self.costBasis = costBasis
            self.unrealizedPnL = unrealizedPnL
            self.allocation = allocation
        }
    }

    public init(generatedAt: Date, accountName: String, currencyCode: String,
                totalValue: Decimal, cash: Decimal, holdingsValue: Decimal,
                dayChange: Decimal, unrealizedPnL: Decimal, holdings: [Holding]) {
        self.generatedAt = generatedAt
        self.accountName = accountName
        self.currencyCode = currencyCode
        self.totalValue = totalValue
        self.cash = cash
        self.holdingsValue = holdingsValue
        self.dayChange = dayChange
        self.unrealizedPnL = unrealizedPnL
        self.holdings = holdings
    }
}

public extension PortfolioExport {
    /// Builds an export snapshot by valuing `portfolio` against `quotes` (falling back to
    /// cost basis when a quote is missing, mirroring `Portfolio.valuation`). Holdings are
    /// ordered by market value, largest first.
    init(portfolio: Portfolio, quotes: [String: Quote], accountName: String,
         generatedAt: Date = Date()) {
        let valuation = portfolio.valuation(quotes: quotes)
        let total = valuation.totalValue.amount
        let totalDouble = (total as NSDecimalNumber).doubleValue

        let rows = portfolio.positions.map { position -> Holding in
            let price = quotes[position.asset.symbol]?.price.amount ?? position.averageCost.amount
            let qty = position.quantity.amount
            let marketValue = price * qty
            let costBasis = position.averageCost.amount * qty
            let allocation = totalDouble == 0
                ? 0
                : (marketValue as NSDecimalNumber).doubleValue / totalDouble
            return Holding(
                symbol: position.asset.symbol,
                name: position.asset.name,
                kind: position.asset.kind.rawValue,
                quantity: qty,
                averageCost: position.averageCost.amount,
                lastPrice: price,
                marketValue: marketValue,
                costBasis: costBasis,
                unrealizedPnL: marketValue - costBasis,
                allocation: allocation
            )
        }.sorted { $0.marketValue > $1.marketValue }

        self.init(
            generatedAt: generatedAt,
            accountName: accountName,
            currencyCode: valuation.totalValue.currencyCode,
            totalValue: total,
            cash: valuation.cash.amount,
            holdingsValue: valuation.holdingsValue.amount,
            dayChange: valuation.dayChange.amount,
            unrealizedPnL: valuation.unrealizedPnL.amount,
            holdings: rows
        )
    }
}
