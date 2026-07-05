package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DAY = 86_400L

private fun point(epoch: Long, close: String) =
    PortfolioPerformancePoint(epoch, Money.usd(close), Money.usd("0"))

class PerformanceResampleTest {

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertTrue(emptyList<PortfolioPerformancePoint>().resampledDaily().isEmpty())
    }

    @Test
    fun singlePointDayIsPreservedUnchanged() {
        val p = point(DAY * 5, "100.00")
        val result = listOf(p).resampledDaily()
        assertEquals(listOf(p), result)
    }

    @Test
    fun multipleIntradayPointsInOneDayCollapseToTheLastValue() {
        // All three points fall in UTC day 5 (epoch/86400 == 5); the max-epoch point (the
        // last intraday sample, i.e. that day's closing value) must be the one kept.
        val morning = point(DAY * 5 + 100, "100.00")
        val midday = point(DAY * 5 + 200, "105.00")
        val closing = point(DAY * 5 + 300, "110.00")

        val result = listOf(morning, midday, closing).resampledDaily()

        assertEquals(1, result.size)
        assertEquals(closing, result.first())
    }

    @Test
    fun twoPointsSameDayDifferentValuesKeepsTheLaterMaxEpochOne() {
        val earlier = point(DAY * 2 + 10, "50.00")
        val later = point(DAY * 2 + 20, "51.00")

        // Feed them in reverse input order to ensure selection is by epoch, not input position.
        val result = listOf(later, earlier).resampledDaily()

        assertEquals(1, result.size)
        assertEquals(later, result.first())
    }

    @Test
    fun multiDayInputCollapsesToOnePointPerDaySortedAscending() {
        val day1Morning = point(DAY * 1 + 100, "10.00")
        val day1Closing = point(DAY * 1 + 500, "11.00")
        val day2Only = point(DAY * 2 + 200, "12.00")
        val day3Morning = point(DAY * 3 + 50, "13.00")
        val day3Closing = point(DAY * 3 + 900, "14.00")

        // Shuffle input order to prove the result is sorted, not merely input-order-preserving.
        val result = listOf(day3Closing, day1Morning, day2Only, day3Morning, day1Closing).resampledDaily()

        assertEquals(3, result.size)
        assertEquals(day1Closing, result[0])
        assertEquals(day2Only, result[1])
        assertEquals(day3Closing, result[2])
        // Ascending by epochSeconds.
        assertTrue(result[0].epochSeconds < result[1].epochSeconds)
        assertTrue(result[1].epochSeconds < result[2].epochSeconds)
    }

    @Test
    fun alreadyDailyInputIsUnchanged() {
        val d1 = point(DAY * 10, "1.00")
        val d2 = point(DAY * 11, "2.00")
        val d3 = point(DAY * 12, "3.00")

        val result = listOf(d1, d2, d3).resampledDaily()

        assertEquals(listOf(d1, d2, d3), result)
    }

    @Test
    fun weekendDatesAreKeptNoTradingCalendarFiltering() {
        // 1970-01-03 was a Saturday, day index 2 (epoch/86400 == 2); 1970-01-04 (Sunday) is
        // day index 3. Both must survive resampling untouched — this function does no
        // trading-calendar filtering, only intraday collapsing.
        val saturday = point(DAY * 2 + 123, "5.00")
        val sunday = point(DAY * 3 + 456, "6.00")

        val result = listOf(saturday, sunday).resampledDaily()

        assertEquals(listOf(saturday, sunday), result)
    }
}
