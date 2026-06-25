import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class TradeViewModel {
    let asset: Asset
    private let buy: BuyAssetUseCase
    private let sell: SellAssetUseCase
    private let fetchPortfolio: FetchPortfolioUseCase
    private let fetchQuotes: FetchQuotesUseCase
    private let notifyOrderFill: NotifyOrderFillUseCase

    var side: TradeSide = .buy
    var quantityText: String = ""
    private(set) var quote: Quote?
    private(set) var portfolio: Portfolio
    private(set) var errorMessage: String?
    private(set) var isSubmitting = false
    private(set) var didComplete = false

    init(asset: Asset,
         buy: BuyAssetUseCase,
         sell: SellAssetUseCase,
         fetchPortfolio: FetchPortfolioUseCase,
         fetchQuotes: FetchQuotesUseCase,
         notifyOrderFill: NotifyOrderFillUseCase) {
        self.asset = asset
        self.buy = buy
        self.sell = sell
        self.fetchPortfolio = fetchPortfolio
        self.fetchQuotes = fetchQuotes
        self.notifyOrderFill = notifyOrderFill
        self.portfolio = fetchPortfolio()
    }

    var quantity: Quantity { Quantity(Decimal(string: quantityText) ?? 0) }
    var availableCash: Money { portfolio.cash }
    var sharesOwned: Quantity { portfolio.position(for: asset.symbol)?.quantity ?? Quantity(0) }

    /// Estimated cost (buy) or proceeds (sell) at the current quote.
    var estimatedAmount: Money? {
        guard let price = quote?.price else { return nil }
        return Money(amount: price.amount * quantity.amount, currencyCode: price.currencyCode)
    }

    var canSubmit: Bool {
        guard !isSubmitting, !quantity.isZero, quote != nil else { return false }
        switch side {
        case .buy:
            guard let cost = estimatedAmount?.amount else { return false }
            return cost <= availableCash.amount
        case .sell:
            return quantity.amount <= sharesOwned.amount
        }
    }

    func load() async {
        portfolio = fetchPortfolio()
        let quotes = await fetchQuotes(symbols: [asset.symbol])
        if case .success(let q) = quotes[asset.symbol] { quote = q }
    }

    /// Fills the quantity field with the maximum: shares owned (sell), or the largest
    /// whole-and-fractional amount affordable at the current price (buy).
    func setMax() {
        switch side {
        case .sell:
            quantityText = sharesOwned.isZero ? "" : sharesOwned.formatted
        case .buy:
            guard let price = quote?.price, price.amount > 0 else { return }
            let maxQty = availableCash.amount / price.amount
            quantityText = Quantity(maxQty).formatted
        }
    }

    func submit() async {
        guard !isSubmitting, !quantity.isZero, quote != nil else { return }
        errorMessage = nil
        isSubmitting = true
        defer { isSubmitting = false }
        let filledQuantity = quantity
        let filledAmount = estimatedAmount
        do {
            switch side {
            case .buy:
                portfolio = try await buy(asset: asset, quantity: quantity)
            case .sell:
                portfolio = try await sell(symbol: asset.symbol, quantity: quantity)
            }
            didComplete = true
            if let filledAmount {
                await notifyOrderFill(side: side, symbol: asset.symbol,
                                      quantity: filledQuantity, amount: filledAmount)
            }
        } catch let error as TradeError {
            errorMessage = message(for: error)
        } catch {
            errorMessage = "Couldn't place the order. Try again."
        }
    }

    private func message(for error: TradeError) -> String {
        switch error {
        case .insufficientFunds: return "Not enough cash for this order."
        case .insufficientShares: return "You don't own that many shares."
        case .invalidQuantity: return "Enter a quantity greater than zero."
        }
    }
}
