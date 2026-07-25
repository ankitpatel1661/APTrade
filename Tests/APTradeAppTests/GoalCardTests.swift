import XCTest
import APTradeDomain
@testable import APTradeApp

/// Verifies `GoalCard.projectionText` renders each `GoalProjection` case (and the
/// not-yet-computed `nil` state) as its own distinct string — the M11.1 Task 12 review
/// flagged this as a place a naive switch could silently collapse two honest readings
/// (e.g. "not on track" and "needs more history") into the same copy.
@MainActor
final class GoalCardTests: XCTestCase {
    func test_reached_rendersGoalReachedCopy() {
        XCTAssertEqual(GoalCard.projectionText(.reached), tr(.goalReached))
    }

    func test_notOnTrack_rendersNotOnTrackCopy() {
        XCTAssertEqual(GoalCard.projectionText(.notOnTrack), tr(.goalNotOnTrack))
    }

    func test_insufficientHistory_rendersNeedsHistoryCopy() {
        XCTAssertEqual(GoalCard.projectionText(.insufficientHistory), tr(.goalNeedsHistory))
    }

    func test_nilProjection_rendersSameNeedsHistoryCopy() {
        // No goal has been projected yet (e.g. immediately after `setIncomeGoal`, before
        // the first refresh) — reads as "needs more history", never as an error or blank.
        XCTAssertEqual(GoalCard.projectionText(nil), tr(.goalNeedsHistory))
    }

    func test_beyondHorizon_rendersBeyondHorizonCopy() {
        XCTAssertEqual(GoalCard.projectionText(.beyondHorizon), tr(.goalBeyondHorizon))
    }

    func test_years_belowTen_roundsToOneDecimal() {
        XCTAssertEqual(GoalCard.projectionText(.years(4.26)),
                       String(format: tr(.goalYearsFormat), "4.3"))
    }

    func test_years_tenOrAbove_roundsToWholeNumber() {
        XCTAssertEqual(GoalCard.projectionText(.years(12.6)),
                       String(format: tr(.goalYearsFormat), "13"))
    }

    /// The five real `GoalProjection` cases plus `nil` must never collapse pairwise —
    /// each is a distinct, informative string.
    func test_allCasesAreDistinct() {
        let texts: Set<String> = [
            GoalCard.projectionText(.reached),
            GoalCard.projectionText(.notOnTrack),
            GoalCard.projectionText(.insufficientHistory),
            GoalCard.projectionText(.beyondHorizon),
            GoalCard.projectionText(.years(5)),
        ]
        XCTAssertEqual(texts.count, 5, "each GoalProjection case must render distinct copy")
    }
}
