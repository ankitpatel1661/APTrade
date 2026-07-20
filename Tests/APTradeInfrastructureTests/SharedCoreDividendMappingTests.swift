import Foundation
import XCTest
import APTradeApplication
import APTradeDomain
@preconcurrency import Shared
@testable import APTradeInfrastructure

// NOTE: see SharedCoreMarketDataRepositoryTests.swift for the Shared.X vs
// APTradeDomain.X naming-collision note. `kmpMoney` here builds valid bridged
// `Shared.Money` values the same way; the malformed-amountText fixture goes
// through `Shared.BignumBigDecimal` directly (see below) since `Shared.Money`
// only ever wraps a real BigDecimal — there's no bridged constructor that
// accepts a raw (possibly-invalid) amount string.
final class SharedCoreDividendMappingTests: XCTestCase {
    private func kmpMoney(_ text: String) -> Shared.Money {
        Shared.Money.companion.usd(value: text)
    }

    /// A `Shared.Money` whose `amountText` (BigDecimal.toStringExpanded()) is a
    /// valid-but-astronomically-long digit string — legitimately constructed
    /// through the bridge (significand 1, exponent -100_000), yet long enough
    /// that Swift's `Decimal(string:)` returns nil, exercising `mapMoney`'s
    /// `AppError.decoding` path with a real bridged value (not a fake).
    private func kmpUnparseableMoney() -> Shared.Money {
        let tinyBigDecimal = Shared.BignumBigDecimal.companion.fromIntWithExponent(
            int: 1, exponent: -100_000, decimalMode: nil)
        return Shared.Money(amount: tinyBigDecimal, currencyCode: "USD")
    }

    private func makeRepository(
        fetchDividends: @escaping @Sendable (String, Int64) async throws -> [Shared.DividendEvent]
    ) -> SharedCoreMarketDataRepository {
        SharedCoreMarketDataRepository(
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { _ in [] },
            fetchDividends: fetchDividends)
    }

    func testDividendEventsMapsEpochAndAmountExactly() async throws {
        let kmpEvents = [
            Shared.DividendEvent(symbol: "AAPL", exDateEpochSeconds: 1_700_000_000, amountPerShare: kmpMoney("0.24")),
            Shared.DividendEvent(symbol: "AAPL", exDateEpochSeconds: 1_707_000_000, amountPerShare: kmpMoney("0.25")),
        ]
        let repo = makeRepository(fetchDividends: { _, _ in kmpEvents })

        let events = try await repo.dividendEvents(for: "AAPL", since: Date(timeIntervalSince1970: 0))

        XCTAssertEqual(events.count, 2)
        XCTAssertEqual(events[0].symbol, "AAPL")
        XCTAssertEqual(events[0].exDate, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(events[0].amountPerShare.amount, Decimal(string: "0.24")!)
        XCTAssertEqual(events[1].exDate, Date(timeIntervalSince1970: 1_707_000_000))
        XCTAssertEqual(events[1].amountPerShare.amount, Decimal(string: "0.25")!)
    }

    private actor Capture {
        private(set) var symbol: String?
        private(set) var epoch: Int64?

        func record(symbol: String, epoch: Int64) {
            self.symbol = symbol
            self.epoch = epoch
        }
    }

    func testDividendEventsPassesSinceAsEpochSeconds() async throws {
        let since = Date(timeIntervalSince1970: 1_650_000_000)
        let capture = Capture()
        let repo = makeRepository(fetchDividends: { symbol, epoch in
            await capture.record(symbol: symbol, epoch: epoch)
            return []
        })

        _ = try await repo.dividendEvents(for: "AAPL", since: since)

        let capturedSymbol = await capture.symbol
        let capturedEpoch = await capture.epoch
        XCTAssertEqual(capturedSymbol, "AAPL")
        XCTAssertEqual(capturedEpoch, 1_650_000_000)
    }

    func testDividendEventsWithMalformedAmountTextThrowsDecodingError() async {
        let malformed = Shared.DividendEvent(
            symbol: "AAPL", exDateEpochSeconds: 1_700_000_000, amountPerShare: kmpUnparseableMoney())
        let repo = makeRepository(fetchDividends: { _, _ in [malformed] })

        do {
            _ = try await repo.dividendEvents(for: "AAPL", since: Date(timeIntervalSince1970: 0))
            XCTFail("Expected AppError.decoding")
        } catch {
            XCTAssertEqual(error as? AppError, .decoding)
        }
    }
}
