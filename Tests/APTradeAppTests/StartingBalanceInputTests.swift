import XCTest
import APTradeDomain
@testable import APTradeApp

final class StartingBalanceInputTests: XCTestCase {
    private let us = Locale(identifier: "en_US")
    private let de = Locale(identifier: "de_DE")

    func test_parse_plainAmount() {
        XCTAssertEqual(StartingBalanceInput.parse("25000", locale: us), Money(amount: 25_000))
    }

    func test_parse_groupedAmount_usLocale() {
        XCTAssertEqual(StartingBalanceInput.parse("1,250,000", locale: us), Money(amount: 1_250_000))
    }

    func test_parse_germanDecimalComma() {
        XCTAssertEqual(StartingBalanceInput.parse("25.000,50", locale: de),
                       Money(amount: Decimal(string: "25000.50") ?? 0))
    }

    func test_parse_rejectsBelowMinimum() {
        XCTAssertNil(StartingBalanceInput.parse("999", locale: us))
    }

    func test_parse_rejectsAboveMaximum() {
        XCTAssertNil(StartingBalanceInput.parse("10000001", locale: us))
    }

    func test_parse_acceptsInclusiveBounds() {
        XCTAssertEqual(StartingBalanceInput.parse("1000", locale: us), Money(amount: 1_000))
        XCTAssertEqual(StartingBalanceInput.parse("10000000", locale: us), Money(amount: 10_000_000))
    }

    func test_parse_rejectsGarbageAndEmpty() {
        XCTAssertNil(StartingBalanceInput.parse("", locale: us))
        XCTAssertNil(StartingBalanceInput.parse("abc", locale: us))
        XCTAssertNil(StartingBalanceInput.parse("-5000", locale: us))
    }

    // MARK: - Whole-branch review fix 5: per-goal-kind ranges

    /// Before the fix, `GoalCard` validated every goal target — income or value — against
    /// `StartingBalanceInput`'s starting-balance range, making an ordinary income goal like
    /// "$50/month in dividends" ($600/yr) unsettable (below the $1,000 floor). The income
    /// goal's own range is ~$100–$1,000,000/yr; both bounds must be accepted, and the
    /// values just outside each bound must still be rejected.
    func test_parse_customRange_incomeGoalBounds_bothEndsInclusive() {
        let range = GoalKind.income.targetRange
        XCTAssertEqual(StartingBalanceInput.parse("100", range: range, locale: us), Money(amount: 100))
        XCTAssertEqual(StartingBalanceInput.parse("1000000", range: range, locale: us), Money(amount: 1_000_000))
        XCTAssertNil(StartingBalanceInput.parse("99", range: range, locale: us))
        XCTAssertNil(StartingBalanceInput.parse("1000001", range: range, locale: us))
    }

    /// An income goal an ordinary user would actually set — $600/yr ("$50/month") — was
    /// unsettable under the old shared starting-balance range ($1,000 floor); it must be
    /// accepted under the income-specific range.
    func test_parse_incomeGoalRange_acceptsOrdinarySmallAnnualTarget() {
        XCTAssertEqual(StartingBalanceInput.parse("600", range: GoalKind.income.targetRange, locale: us),
                       Money(amount: 600))
    }

    /// Before the fix, the same shared range capped a value goal at $10,000,000, even
    /// though a value goal is meant to run far past a portfolio's opening cash. The
    /// value goal's own range is ~$1,000–$100,000,000; both bounds must be accepted, and
    /// the values just outside each bound must still be rejected.
    func test_parse_customRange_valueGoalBounds_bothEndsInclusive() {
        let range = GoalKind.value.targetRange
        XCTAssertEqual(StartingBalanceInput.parse("1000", range: range, locale: us), Money(amount: 1_000))
        XCTAssertEqual(StartingBalanceInput.parse("100000000", range: range, locale: us), Money(amount: 100_000_000))
        XCTAssertNil(StartingBalanceInput.parse("999", range: range, locale: us))
        XCTAssertNil(StartingBalanceInput.parse("100000001", range: range, locale: us))
    }

    /// A value goal above the old shared $10,000,000 ceiling — unsettable before the fix —
    /// must now be accepted under the value-specific range.
    func test_parse_valueGoalRange_acceptsTargetAboveOldStartingBalanceCeiling() {
        XCTAssertEqual(StartingBalanceInput.parse("50000000", range: GoalKind.value.targetRange, locale: us),
                       Money(amount: 50_000_000))
    }
}
