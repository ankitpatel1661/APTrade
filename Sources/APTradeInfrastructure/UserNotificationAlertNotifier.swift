import Foundation
import UserNotifications
import APTradeApplication
import APTradeDomain

/// Delivers triggered alerts as native macOS notifications. Permission is requested
/// lazily, once, on first use rather than at launch.
///
/// `UNUserNotificationCenter.current()` throws an uncaught `NSInternalInconsistencyException`
/// when there's no proper signed app bundle (e.g. a bare SwiftPM debug binary launched by
/// path) — it cannot be guarded with `try?`. Resolving the center lazily on first use, and
/// only when `Bundle.main` looks like a real app bundle, keeps unbundled runs (dev builds,
/// `swift run`) from crashing instead of merely skipping notification delivery.
public final class UserNotificationAlertNotifier: AlertNotifier, OrderFillNotifier, MarketEventNotifier, @unchecked Sendable {
    private var center: UNUserNotificationCenter?
    private var hasResolvedCenter = false
    private var hasRequestedAuthorization = false

    public init() {}

    public func notify(_ alert: PriceAlert, quote: Quote) async {
        await deliver(
            identifier: alert.id.uuidString,
            title: "\(alert.symbol) alert",
            body: "\(alert.condition.summary) — now \(quote.price.formatted) (\(quote.changePercent.formatted))"
        )
    }

    public func notifyFill(side: TradeSide, symbol: String, quantity: Quantity, amount: Money) async {
        let verb: String
        switch side {
        case .buy: verb = "Bought"
        case .sell: verb = "Sold"
        case .dividend: verb = "Dividend"
        }
        await deliver(
            identifier: "fill-\(UUID().uuidString)",
            title: "Order filled",
            body: "\(verb) \(quantity.formatted) \(symbol.uppercased()) for \(amount.formatted)"
        )
    }

    public func notifyMarketStatus(opened: Bool) async {
        await deliver(
            identifier: "market-\(opened ? "open" : "close")-\(UUID().uuidString)",
            title: opened ? "Market open" : "Market closed",
            body: opened ? "US equities are now open for regular trading."
                         : "US equities have closed for the day."
        )
    }

    public func notifyDigest(summary: String) async {
        await deliver(
            identifier: "digest-\(UUID().uuidString)",
            title: "Daily digest",
            body: summary
        )
    }

    public func notifyEarnings(title: String, body: String) async {
        await deliver(
            identifier: "earnings-\(UUID().uuidString)",
            title: title,
            body: body
        )
    }

    public func notifyPieContribution(title: String, body: String) async {
        await deliver(
            identifier: "pie-contribution-\(UUID().uuidString)",
            title: title,
            body: body
        )
    }

    public func notifyDividend(title: String, body: String) async {
        await deliver(
            identifier: "dividend-\(UUID().uuidString)",
            title: title,
            body: body
        )
    }

    private func deliver(identifier: String, title: String, body: String) async {
        guard let center = resolveCenterIfNeeded() else { return }
        await requestAuthorizationIfNeeded(center)

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        try? await center.add(request)
    }

    private func resolveCenterIfNeeded() -> UNUserNotificationCenter? {
        guard !hasResolvedCenter else { return center }
        hasResolvedCenter = true
        guard Bundle.main.bundleIdentifier != nil else { return nil }
        center = .current()
        return center
    }

    private func requestAuthorizationIfNeeded(_ center: UNUserNotificationCenter) async {
        guard !hasRequestedAuthorization else { return }
        hasRequestedAuthorization = true
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
    }
}
