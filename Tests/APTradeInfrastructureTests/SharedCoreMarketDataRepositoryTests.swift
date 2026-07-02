import Foundation
import XCTest
import APTradeApplication
import APTradeDomain
@preconcurrency import Shared
@testable import APTradeInfrastructure

// NOTE: KMP `Quote`/`Money`/`PricePoint`/`Candle`/`Asset`/`Timeframe` collide with
// APTradeDomain's — Shared.X vs APTradeDomain.X. Kotlin companion functions surface as
// `.companion`; Kotlin `object`s (sealed-class singletons) as `.shared`.
final class SharedCoreMarketDataRepositoryTests: XCTestCase {
    private func kmpMoney(_ text: String) -> Shared.Money {
        Shared.Money.companion.usd(value: text)
    }

    func testMapsMoneyExactly() throws {
        let mapped = try SharedCoreMarketDataRepository.mapMoney(kmpMoney("229.35"))
        XCTAssertEqual(mapped, APTradeDomain.Money(amount: Decimal(string: "229.35")!, currencyCode: "USD"))
    }

    func testMapsQuoteWithPreviousClose() throws {
        let kmp = Shared.Quote(
            symbol: "AAPL",
            price: kmpMoney("229.35"),
            previousClose: kmpMoney("227.45"),
            changePercent: 0.84)
        let mapped = try SharedCoreMarketDataRepository.mapQuote(kmp)
        XCTAssertEqual(mapped.symbol, "AAPL")
        XCTAssertEqual(mapped.price.amount, Decimal(string: "229.35")!)
        XCTAssertEqual(mapped.previousClose.amount, Decimal(string: "227.45")!)
    }

    func testMapsPricePointExactly() throws {
        let kmp = Shared.PricePoint(epochSeconds: 1_700_000_000, close: kmpMoney("229.35"))
        let mapped = try SharedCoreMarketDataRepository.mapPricePoint(kmp)
        XCTAssertEqual(mapped.date, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(mapped.close.amount, Decimal(string: "229.35")!)
    }

    func testMapsCandleExactly() throws {
        let kmp = Shared.Candle(
            epochSeconds: 1_700_000_000,
            open: kmpMoney("100.00"), high: kmpMoney("101.00"),
            low: kmpMoney("99.00"), close: kmpMoney("100.50"), volume: 500.0)
        let mapped = try SharedCoreMarketDataRepository.mapCandle(kmp)
        XCTAssertEqual(mapped.date, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(mapped.open.amount, Decimal(string: "100.00")!)
        XCTAssertEqual(mapped.high.amount, Decimal(string: "101.00")!)
        XCTAssertEqual(mapped.low.amount, Decimal(string: "99.00")!)
        XCTAssertEqual(mapped.close.amount, Decimal(string: "100.50")!)
        XCTAssertEqual(mapped.volume, 500.0)
    }

    func testMapsAssetKinds() {
        XCTAssertEqual(SharedCoreMarketDataRepository.mapAssetKind(.stock), .stock)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapAssetKind(.etf), .etf)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapAssetKind(.crypto), .crypto)
    }

    func testMapsTimeframes() {
        XCTAssertEqual(SharedCoreMarketDataRepository.mapTimeframe(.oneDay), .oneday)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapTimeframe(.oneWeek), .oneweek)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapTimeframe(.oneMonth), .onemonth)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapTimeframe(.oneYear), .oneyear)
    }

    func testMapsKotlinErrorsToAppError() {
        func nsError(_ kotlin: Any) -> Error {
            NSError(domain: "KotlinException", code: 0, userInfo: ["KotlinException": kotlin])
        }
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(Shared.QuoteError.RateLimited.shared)), .rateLimited)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(Shared.QuoteError.NotFound.shared)), .notFound)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(nsError(Shared.QuoteError.Network(reason: "boom"))), .network)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(URLError(.timedOut)), .network)
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(AppError.notFound), .notFound)
    }

    func testMapsPlainNSErrorWithNoKotlinExceptionKeyToNetwork() {
        let error = NSError(domain: "SomeOtherDomain", code: 1, userInfo: ["unrelated": "value"])
        XCTAssertEqual(SharedCoreMarketDataRepository.mapError(error), .network)
    }

    func testQuoteForSuccessReturnsMappedQuote() async throws {
        let kmp = Shared.Quote(
            symbol: "AAPL",
            price: kmpMoney("229.35"),
            previousClose: kmpMoney("227.45"),
            changePercent: 0.84)
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { symbols in
                XCTAssertEqual(symbols, ["AAPL"])
                return [kmp]
            },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { _ in [] })
        let quote = try await repo.quote(for: "AAPL")
        XCTAssertEqual(quote.symbol, "AAPL")
        XCTAssertEqual(quote.price.amount, Decimal(string: "229.35")!)
        XCTAssertEqual(quote.previousClose.amount, Decimal(string: "227.45")!)
    }

    func testQuoteForEmptyResultThrowsNotFound() async {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { _ in [] })
        do {
            _ = try await repo.quote(for: "AAPL")
            XCTFail("Expected AppError.notFound")
        } catch {
            XCTAssertEqual(error as? AppError, .notFound)
        }
    }

    func testQuoteForKotlinErrorMapsToAppError() async {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in
                throw NSError(
                    domain: "KotlinException", code: 0,
                    userInfo: ["KotlinException": Shared.QuoteError.RateLimited.shared])
            },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { _ in [] })
        do {
            _ = try await repo.quote(for: "AAPL")
            XCTFail("Expected AppError.rateLimited")
        } catch {
            XCTAssertEqual(error as? AppError, .rateLimited)
        }
    }

    func testHistoryForSuccessReturnsMappedPoints() async throws {
        let kmp = [Shared.PricePoint(epochSeconds: 1_700_000_000, close: kmpMoney("229.35"))]
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { symbol, timeframe in
                XCTAssertEqual(symbol, "AAPL")
                XCTAssertEqual(timeframe, .oneweek)
                return kmp
            },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { _ in [] })

        let points = try await repo.history(for: "AAPL", timeframe: .oneWeek)
        XCTAssertEqual(points.count, 1)
        XCTAssertEqual(points[0].close.amount, Decimal(string: "229.35")!)
    }

    func testCandlesForKotlinErrorMapsToAppError() async {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in
                throw NSError(
                    domain: "KotlinException", code: 0,
                    userInfo: ["KotlinException": Shared.QuoteError.RateLimited.shared])
            },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { _ in [] })

        do {
            _ = try await repo.candles(for: "AAPL", timeframe: .oneDay)
            XCTFail("Expected AppError.rateLimited")
        } catch {
            XCTAssertEqual(error as? AppError, .rateLimited)
        }
    }

    func testProfileForSuccessReturnsMappedAsset() async throws {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { _ in [] })

        let asset = try await repo.profile(for: "AAPL")
        XCTAssertEqual(asset.symbol, "AAPL")
        XCTAssertEqual(asset.name, "Apple Inc.")
        XCTAssertEqual(asset.kind, .stock)
    }

    func testSearchForSuccessReturnsMappedAssets() async throws {
        let kmp = [Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock)]
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { query in
                XCTAssertEqual(query, "apple")
                return kmp
            })

        let assets = try await repo.search(query: "apple")
        XCTAssertEqual(assets.count, 1)
        XCTAssertEqual(assets[0].symbol, "AAPL")
        XCTAssertEqual(assets[0].name, "Apple Inc.")
        XCTAssertEqual(assets[0].kind, .stock)
    }

    func testSearchForKotlinErrorMapsToAppError() async {
        let repo = SharedCoreMarketDataRepository(
            fallback: RecordingRepository(),
            fetch: { _ in [] },
            fetchHistory: { _, _ in [] },
            fetchCandles: { _, _ in [] },
            fetchProfile: { _ in Shared.Asset(symbol: "AAPL", name: "Apple Inc.", kind: .stock) },
            fetchSearch: { _ in
                throw NSError(
                    domain: "KotlinException", code: 0,
                    userInfo: ["KotlinException": Shared.QuoteError.RateLimited.shared])
            })

        do {
            _ = try await repo.search(query: "apple")
            XCTFail("Expected AppError.rateLimited")
        } catch {
            XCTAssertEqual(error as? AppError, .rateLimited)
        }
    }
}

private actor CallLog {
    var calls: [String] = []
    func record(_ name: String) { calls.append(name) }
}

private final class RecordingRepository: APTradeApplication.MarketDataRepository, @unchecked Sendable {
    private let log = CallLog()
    var calls: [String] { get async { await log.calls } }

    func quote(for symbol: String) async throws -> APTradeDomain.Quote {
        await log.record("quote")
        throw AppError.notFound
    }
    func history(for symbol: String, timeframe: APTradeDomain.Timeframe) async throws -> [APTradeDomain.PricePoint] {
        await log.record("history"); return []
    }
    func candles(for symbol: String, timeframe: APTradeDomain.Timeframe) async throws -> [APTradeDomain.Candle] {
        await log.record("candles"); return []
    }
    func profile(for symbol: String) async throws -> APTradeDomain.Asset {
        await log.record("profile"); return APTradeDomain.Asset(symbol: symbol, name: symbol, kind: .stock)
    }
    func search(query: String) async throws -> [APTradeDomain.Asset] {
        await log.record("search"); return []
    }
}
