# Market-Holiday + Earnings Calendar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Holiday-aware `MarketCalendar` on both codebases plus an S&P-500 earnings calendar: a fourth Calendar tab, a "Next earnings" detail stat, and a settings-gated earnings-day notification — on all four platforms.

**Architecture:** Twin pure-domain holiday rule sets (Swift Domain + shared `commonMain`, identical math and test tables). Earnings flow clones the news adapter pattern: `EarningsCalendarRepository` port → `FinnhubEarningsRepository` adapter (key-gated, `Empty` fallback) → `FetchEarningsCalendar` use case filtering to `SP500Symbols ∪ watchlist ∪ portfolio`. Notifications ride the existing `MarketActivityPlanner` once-per-trading-day gate via a new `EarningsCheckDue` event; Android gains the coordinator loop it never had.

**Tech Stack:** Kotlin Multiplatform (shared/commonMain + desktop Compose + Android Compose), Swift 6 / SwiftUI (macOS + iOS from one package), Ktor + URLSession, Finnhub `/calendar/earnings`.

**Spec:** `docs/superpowers/specs/2026-07-13-market-earnings-calendar-design.md` — read it first.

## Global Constraints

- Domain layers stay pure: no framework imports, no network, no persistence (`Sources/APTradeDomain`, `shared/src/commonMain/.../domain`).
- `shared/commonMain` stays Compose-free and JVM-free (Apple xcframework builds from it).
- All user-visible strings go through the L10n catalogs (EN/DE/IT/ES), both codebases. English values must be identical across codebases.
- Gains green (`Theme.up`/`GainGreen`) and losses red are price-direction only; gold owns brand/accent. Banner rows use gold/hairline, never price colors.
- Numerics render `.monospacedDigit()` (Swift) / `fontFeatureSettings = "tnum"` or `.monospacedDigit()` equivalent (Compose).
- Kotlin builds: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"` before every `./gradlew`; use the committed wrapper only.
- Swift tests: `DEVELOPER_DIR=/Applications/Xcode.app swift test`. If `Shared.xcframework` is missing: `./gradlew :shared:assembleSharedReleaseXCFramework` first (needs `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`).
- iOS suite: park `APTrade.xcodeproj` aside, then `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild test -scheme APTradeLite-Package -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -skipPackagePluginValidation ARCHS=arm64`, restore the xcodeproj.
- Verify Gradle test counts from JUnit XML with `--rerun-tasks` (cached runs lie).
- New Kotlin L10n keys bump `L10nCatalogTest`'s pinned count (currently 227 → 249 after Task 5).
- Commit messages end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Work on branch `feature/market-earnings-calendar` off `main`.

---

## Task 1: Kotlin `USMarketHolidays` + holiday-aware `MarketCalendar`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/USMarketHolidays.kt`
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/MarketCalendar.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/USMarketHolidaysTest.kt`
- Test (modify): `shared/src/commonTest/kotlin/com/aptrade/shared/domain/MarketCalendarTest.kt` (add cases; keep existing ones green)

**Interfaces:**
- Consumes: `MarketCalendar`'s existing private helpers `floorDiv`, `floorMod`, `civilFromDays`, `daysFromCivil`, `isoWeekday` — `USMarketHolidays` gets its own copies of the two Hinnant functions (it must stay dependency-free of `MarketCalendar` internals).
- Produces (later tasks rely on these exact names):
  - `enum class USMarketHoliday { NewYearsDay, MartinLutherKingDay, WashingtonsBirthday, GoodFriday, MemorialDay, Juneteenth, IndependenceDay, LaborDay, Thanksgiving, Christmas }`
  - `object USMarketHolidays { fun fullHoliday(localEpochDay: Long): USMarketHoliday?; fun isHalfDay(localEpochDay: Long): Boolean }`
  - On `MarketCalendar`: `fun localEpochDay(atEpochSeconds: Long): Long`, `fun holiday(localEpochDay: Long): USMarketHoliday?`, `fun isHalfDay(localEpochDay: Long): Boolean` (thin delegates), and `status()` now holiday/half-day aware.

- [ ] **Step 1: Write the failing tests**

Create `USMarketHolidaysTest.kt`:

```kotlin
package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Epoch-day of a proleptic-Gregorian date — mirror of the production daysFromCivil,
 *  duplicated here so the tests are readable as plain dates. */
private fun day(y: Long, m: Int, d: Int): Long {
    val yy = if (m <= 2) y - 1 else y
    val era = if (yy >= 0) yy / 400 else (yy - 399) / 400
    val yoe = yy - era * 400
    val mp = if (m > 2) m - 3 else m + 9
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}

class USMarketHolidaysTest {

    // ---- 2026 (fixed reference year; July 4 2026 is a SATURDAY -> observed Friday July 3) ----

    @Test fun newYears2026() = assertEquals(USMarketHoliday.NewYearsDay, USMarketHolidays.fullHoliday(day(2026, 1, 1)))
    @Test fun mlk2026_thirdMondayJan() = assertEquals(USMarketHoliday.MartinLutherKingDay, USMarketHolidays.fullHoliday(day(2026, 1, 19)))
    @Test fun washington2026_thirdMondayFeb() = assertEquals(USMarketHoliday.WashingtonsBirthday, USMarketHolidays.fullHoliday(day(2026, 2, 16)))
    @Test fun goodFriday2026() = assertEquals(USMarketHoliday.GoodFriday, USMarketHolidays.fullHoliday(day(2026, 4, 3)))
    @Test fun memorial2026_lastMondayMay() = assertEquals(USMarketHoliday.MemorialDay, USMarketHolidays.fullHoliday(day(2026, 5, 25)))
    @Test fun juneteenth2026() = assertEquals(USMarketHoliday.Juneteenth, USMarketHolidays.fullHoliday(day(2026, 6, 19)))
    @Test fun independence2026_observedFridayJul3() {
        assertEquals(USMarketHoliday.IndependenceDay, USMarketHolidays.fullHoliday(day(2026, 7, 3)))
        assertNull(USMarketHolidays.fullHoliday(day(2026, 7, 4))) // the Saturday itself is not a market day anyway
    }
    @Test fun labor2026_firstMondaySep() = assertEquals(USMarketHoliday.LaborDay, USMarketHolidays.fullHoliday(day(2026, 9, 7)))
    @Test fun thanksgiving2026_fourthThursdayNov() = assertEquals(USMarketHoliday.Thanksgiving, USMarketHolidays.fullHoliday(day(2026, 11, 26)))
    @Test fun christmas2026() = assertEquals(USMarketHoliday.Christmas, USMarketHolidays.fullHoliday(day(2026, 12, 25)))

    // ---- 2027 spot checks (rules, not a copied table) ----

    @Test fun newYears2027_fridayUnshifted() = assertEquals(USMarketHoliday.NewYearsDay, USMarketHolidays.fullHoliday(day(2027, 1, 1)))
    @Test fun goodFriday2027() = assertEquals(USMarketHoliday.GoodFriday, USMarketHolidays.fullHoliday(day(2027, 3, 26)))
    @Test fun juneteenth2027_saturdayObservedFriday() {
        assertEquals(USMarketHoliday.Juneteenth, USMarketHolidays.fullHoliday(day(2027, 6, 18)))
        assertNull(USMarketHolidays.fullHoliday(day(2027, 6, 19)))
    }
    @Test fun independence2027_sundayObservedMonday() =
        assertEquals(USMarketHoliday.IndependenceDay, USMarketHolidays.fullHoliday(day(2027, 7, 5)))
    @Test fun christmas2027_saturdayObservedFriday() =
        assertEquals(USMarketHoliday.Christmas, USMarketHolidays.fullHoliday(day(2027, 12, 24)))

    // ---- Good Friday across years pins the Easter math ----

    @Test fun goodFriday2028() = assertEquals(USMarketHoliday.GoodFriday, USMarketHolidays.fullHoliday(day(2028, 4, 14)))

    // ---- non-holidays ----

    @Test fun plainWednesdayIsNoHoliday() = assertNull(USMarketHolidays.fullHoliday(day(2026, 7, 15)))

    // ---- half-days (13:00 ET close) ----

    @Test fun dayAfterThanksgiving2026IsHalfDay() = assertTrue(USMarketHolidays.isHalfDay(day(2026, 11, 27)))
    @Test fun christmasEve2026_thursdayIsHalfDay() = assertTrue(USMarketHolidays.isHalfDay(day(2026, 12, 24)))
    @Test fun july3_2026_isFullClosureNotHalfDay() {
        // July 4 2026 is Saturday -> observed ON July 3, which therefore is a FULL closure.
        assertFalse(USMarketHolidays.isHalfDay(day(2026, 7, 3)))
    }
    @Test fun july3_2025_wasHalfDay() = assertTrue(USMarketHolidays.isHalfDay(day(2025, 7, 3))) // Thu, Jul 4 2025 = Fri
    @Test fun christmasEve2027_saturdayIsNotHalfDay() = assertFalse(USMarketHolidays.isHalfDay(day(2027, 12, 24).let { it })) // Dec 24 2027 is the OBSERVED Christmas (full closure)
    @Test fun plainDayIsNotHalfDay() = assertFalse(USMarketHolidays.isHalfDay(day(2026, 7, 15)))
}
```

Add to the existing `MarketCalendarTest.kt` (keep every existing test untouched):

```kotlin
    // ---- holiday awareness (2026-07-13 increment) ----
    // Helper: epoch seconds for an ET wall-clock instant already exists in this file's
    // idiom — reuse the file's existing helper if present; otherwise compute via
    // known UTC instants as the existing tests do.

    @Test
    fun thanksgivingMiddayIsClosed() {
        // 2026-11-26 12:00 ET = 17:00 UTC = 1795021200
        assertEquals(MarketStatus.CLOSED, MarketCalendar().status(1_795_021_200L))
    }

    @Test
    fun halfDayClosesAtOnePmEt() {
        // 2026-11-27 12:59 ET = 17:59 UTC = 1795111140 -> OPEN
        assertEquals(MarketStatus.OPEN, MarketCalendar().status(1_795_111_140L))
        // 2026-11-27 13:00 ET = 18:00 UTC = 1795111200 -> CLOSED
        assertEquals(MarketStatus.CLOSED, MarketCalendar().status(1_795_111_200L))
    }

    @Test
    fun plainWednesdayStaysOpen() {
        // 2026-07-15 12:00 ET = 16:00 UTC (EDT) = 1784131200
        assertEquals(MarketStatus.OPEN, MarketCalendar().status(1_784_131_200L))
    }

    @Test
    fun holidayLookupDelegates() {
        val cal = MarketCalendar()
        // 2026-11-26 12:00 ET
        val epochDay = cal.localEpochDay(1_795_021_200L)
        assertEquals(USMarketHoliday.Thanksgiving, cal.holiday(epochDay))
        assertEquals(false, cal.isHalfDay(epochDay))
    }
```

> Note for the implementer: verify each hardcoded epoch constant with `TZ=UTC date -d @<n>` (or `python3 -c "import datetime;print(datetime.datetime.utcfromtimestamp(<n>))"`) before trusting it; fix the constant, not the production code, if you mistyped.

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest --tests '*USMarketHolidays*' --tests '*MarketCalendar*' --rerun-tasks
```
Expected: compilation FAILS — `USMarketHoliday` unresolved.

- [ ] **Step 3: Implement `USMarketHolidays.kt`**

```kotlin
package com.aptrade.shared.domain

/** The ten NYSE full-closure holidays. Cases map 1:1 to L10n keys (Task 5). */
enum class USMarketHoliday {
    NewYearsDay, MartinLutherKingDay, WashingtonsBirthday, GoodFriday, MemorialDay,
    Juneteenth, IndependenceDay, LaborDay, Thanksgiving, Christmas,
}

/**
 * Pure, computed US equity market holiday rules — valid for any year, nothing expires.
 * All inputs/outputs are market-local epoch days (days since 1970-01-01 in the wall-clock
 * date already localized by MarketCalendar). Uses the same Hinnant civil-date algorithms
 * as MarketCalendar (private copies — this object must not reach into that class).
 *
 * Rules (NYSE):
 *  - Fixed-date holidays (New Year's, Juneteenth, July 4, Christmas) observe
 *    Saturday -> preceding Friday, Sunday -> following Monday.
 *  - Floating: MLK = 3rd Mon Jan; Washington's Birthday = 3rd Mon Feb; Memorial = last
 *    Mon May; Labor = 1st Mon Sep; Thanksgiving = 4th Thu Nov.
 *  - Good Friday = Easter Sunday (anonymous Gregorian algorithm) minus 2 days.
 *  - Half-days (13:00 ET close): day after Thanksgiving; July 3 and December 24 when
 *    they are weekdays NOT already consumed as the observed July 4 / Christmas.
 */
object USMarketHolidays {

    fun fullHoliday(localEpochDay: Long): USMarketHoliday? {
        val (year, _, _) = civilFromDays(localEpochDay)
        return holidaysFor(year)[localEpochDay]
    }

    fun isHalfDay(localEpochDay: Long): Boolean {
        val (year, _, _) = civilFromDays(localEpochDay)
        if (holidaysFor(year).containsKey(localEpochDay)) return false
        val weekday = isoWeekday(localEpochDay)
        if (weekday !in 1..5) return false
        val dayAfterThanksgiving = nthWeekdayOfMonth(year, month = 11, isoWeekday = 4, n = 4) + 1
        if (localEpochDay == dayAfterThanksgiving) return true
        val july3 = daysFromCivil(year, 7, 3)
        val christmasEve = daysFromCivil(year, 12, 24)
        return localEpochDay == july3 || localEpochDay == christmasEve
    }

    // Cached per-year tables — the calendar screen queries 14 consecutive days.
    private val cache = HashMap<Long, Map<Long, USMarketHoliday>>()

    private fun holidaysFor(year: Long): Map<Long, USMarketHoliday> = cache.getOrPut(year) {
        buildMap {
            put(observed(daysFromCivil(year, 1, 1)), USMarketHoliday.NewYearsDay)
            put(nthWeekdayOfMonth(year, 1, isoWeekday = 1, n = 3), USMarketHoliday.MartinLutherKingDay)
            put(nthWeekdayOfMonth(year, 2, isoWeekday = 1, n = 3), USMarketHoliday.WashingtonsBirthday)
            put(easterSunday(year) - 2, USMarketHoliday.GoodFriday)
            put(lastWeekdayOfMonth(year, 5, isoWeekday = 1), USMarketHoliday.MemorialDay)
            put(observed(daysFromCivil(year, 6, 19)), USMarketHoliday.Juneteenth)
            put(observed(daysFromCivil(year, 7, 4)), USMarketHoliday.IndependenceDay)
            put(nthWeekdayOfMonth(year, 9, isoWeekday = 1, n = 1), USMarketHoliday.LaborDay)
            put(nthWeekdayOfMonth(year, 11, isoWeekday = 4, n = 4), USMarketHoliday.Thanksgiving)
            put(observed(daysFromCivil(year, 12, 25)), USMarketHoliday.Christmas)
        }
    }

    /** Saturday -> Friday before; Sunday -> Monday after; weekday unchanged. */
    private fun observed(epochDay: Long): Long = when (isoWeekday(epochDay)) {
        6 -> epochDay - 1
        7 -> epochDay + 1
        else -> epochDay
    }

    /** Epoch day of the nth <isoWeekday> (1=Mon..7=Sun) of the month. */
    private fun nthWeekdayOfMonth(year: Long, month: Int, isoWeekday: Int, n: Int): Long {
        val first = daysFromCivil(year, month, 1)
        val delta = floorMod((isoWeekday - isoWeekday(first)).toLong(), 7L)
        return first + delta + (n - 1) * 7L
    }

    /** Epoch day of the last <isoWeekday> of the month. */
    private fun lastWeekdayOfMonth(year: Long, month: Int, isoWeekday: Int): Long {
        val nextMonthFirst = if (month == 12) daysFromCivil(year + 1, 1, 1) else daysFromCivil(year, month + 1, 1)
        val last = nextMonthFirst - 1
        val delta = floorMod((isoWeekday(last) - isoWeekday).toLong(), 7L)
        return last - delta
    }

    /** Anonymous Gregorian Easter algorithm -> epoch day of Easter Sunday. */
    private fun easterSunday(year: Long): Long {
        val y = year
        val a = y % 19
        val b = y / 100
        val c = y % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = ((h + l - 7 * m + 114) / 31).toInt()
        val day = ((h + l - 7 * m + 114) % 31 + 1).toInt()
        return daysFromCivil(y, month, day)
    }

    // --- Hinnant civil-date math (private copies; identical to MarketCalendar's) ---

    private fun floorDiv(a: Long, b: Long): Long = Math.floorDiv(a, b)
    private fun floorMod(a: Long, b: Long): Long = Math.floorMod(a, b)

    private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = floorDiv(y, 400L)
        val yoe = y - era * 400L
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
        val z = z0 + 719_468L
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple(if (m <= 2) y + 1 else y, m, d)
    }

    private fun isoWeekday(epochDay: Long): Int = (floorMod(epochDay + 3, 7L) + 1).toInt()
}
```

> **`Math.floorDiv` is JVM-only — commonMain must NOT use it.** Check how the existing `MarketCalendar.kt` implements `floorDiv`/`floorMod` in commonMain (it has pure-Kotlin versions) and copy those two function bodies verbatim instead of the `Math.` calls above. Same for `civilFromDays`'s exact tuple shape — mirror the existing file's implementation precisely; the algorithms above are the reference semantics.

- [ ] **Step 4: Make `MarketCalendar.kt` holiday-aware**

In `status(atEpochSeconds)`, after the existing weekend check and before the minutes check, insert:

```kotlin
        if (USMarketHolidays.fullHoliday(localEpochDay) != null) return MarketStatus.CLOSED
```

and replace the fixed `closeMinute`:

```kotlin
        val closeMinute = if (USMarketHolidays.isHalfDay(localEpochDay)) 13 * 60 else 16 * 60
```

Add three public members (bottom of the class, above the private helpers):

```kotlin
    /** Market-local epoch day for an instant — the key the holiday helpers take. */
    fun localEpochDay(atEpochSeconds: Long): Long {
        val localSeconds = atEpochSeconds + offsetSecondsFor(atEpochSeconds)
        return floorDiv(localSeconds, SECONDS_PER_DAY)
    }

    /** The full-closure holiday on a market-local day, or null on trading days. */
    fun holiday(localEpochDay: Long): USMarketHoliday? = USMarketHolidays.fullHoliday(localEpochDay)

    /** True when the market closes at 13:00 ET on this market-local day. */
    fun isHalfDay(localEpochDay: Long): Boolean = USMarketHolidays.isHalfDay(localEpochDay)
```

Also delete the "(Roadmap item on both sides: a real US market holiday calendar.)" line from the class KDoc and describe the new behavior in one sentence.

- [ ] **Step 5: Run the tests until green**

```bash
./gradlew :shared:jvmTest --tests '*USMarketHolidays*' --tests '*MarketCalendar*' --rerun-tasks
```
Expected: PASS. Then the whole shared suite (`./gradlew :shared:jvmTest --rerun-tasks`) — the planner/coordinator tests must stay green (they use plain-weekday instants; if one used a holiday date by accident, fix the TEST date, not the calendar).

- [ ] **Step 6: Commit**

```bash
git add shared/src
git commit -m "feat(shared): computed US market holidays + holiday-aware MarketCalendar

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: Kotlin earnings domain — `EarningsEvent`, port, `SP500Symbols`, `FetchEarningsCalendar`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/EarningsEvent.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/SP500Symbols.kt` (generated — see Step 3)
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/EarningsPorts.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/EarningsUseCases.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/EarningsUseCasesTest.kt`

**Interfaces:**
- Produces:
  - `data class EarningsEvent(val symbol: String, val companyName: String, val day: String /* yyyy-MM-dd market-local */, val session: EarningsSession, val epsEstimate: Double?, val epsActual: Double?)`
  - `enum class EarningsSession { BeforeOpen, AfterClose, DuringMarket, Unknown }`
  - `interface EarningsCalendarRepository { suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> }`
  - `object EmptyEarningsRepository : EarningsCalendarRepository` (returns `emptyList()`)
  - `class FetchEarningsCalendar(repository, ownSymbols: suspend () -> Set<String>)` with
    `suspend fun execute(fromDay: String, toDay: String): List<EarningsEvent>` (filtered to `SP500Symbols.set ∪ ownSymbols()`, sorted by day then symbol, own symbols first within a day) and
    `suspend fun nextEarnings(symbol: String, fromDay: String, toDay: String): EarningsEvent?`
  - `object SP500Symbols { val set: Set<String> }`

- [ ] **Step 1: Generate `SP500Symbols.kt`**

Fetch the constituent list and generate the file (network step, done once at implementation time):

```bash
python3 - <<'EOF'
import urllib.request, re, io, csv
url = "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies"
html = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})).read().decode()
# First wikitable, first column = ticker. Grab hrefs like /wiki/... followed by ticker text in the constituents table.
tickers = sorted(set(re.findall(r'<td><a rel="nofollow"[^>]*>([A-Z]+(?:\.[A-Z])?)</a>', html)))
assert 490 <= len(tickers) <= 520, f"unexpected count {len(tickers)} — inspect the page structure"
lines = [",\n".join('    "%s"' % t for t in tickers)]
body = f'''package com.aptrade.shared.domain

/** S&P 500 constituents (snapshot {__import__("datetime").date.today()}), used to keep the
 *  earnings calendar readable. Constituents drift a few times a year; refresh this file
 *  opportunistically at maintenance time. Held/watched symbols are always shown regardless,
 *  so staleness only affects "other companies" rows. Tickers use Finnhub's dot-class form
 *  normalized to dashes (e.g. BRK.B -> BRK-B is NOT applied — keep the dot form Wikipedia
 *  uses and normalize at compare time in the use case).
 */
object SP500Symbols {{
    val set: Set<String> = setOf(
{lines[0]},
    )
}}
'''
open("shared/src/commonMain/kotlin/com/aptrade/shared/domain/SP500Symbols.kt", "w").write(body)
print(f"wrote {len(tickers)} tickers")
EOF
```

Expected: `wrote ~503 tickers`. If the regex misses (page structure changed), open the page and adapt the extraction — the assert guards against silent garbage. Spot-check the output file contains `"AAPL"`, `"MSFT"`, `"NVDA"`, `"BRK.B"`.

- [ ] **Step 2: Write the failing use-case tests**

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun event(symbol: String, day: String, session: EarningsSession = EarningsSession.AfterClose) =
    EarningsEvent(symbol = symbol, companyName = "$symbol Inc.", day = day, session = session, epsEstimate = 1.0, epsActual = null)

private class FakeEarningsRepository(private val events: List<EarningsEvent>) : EarningsCalendarRepository {
    var calls = 0
    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> {
        calls++
        return events
    }
}

class EarningsUseCasesTest {

    @Test
    fun filtersToIndexPlusOwnSymbols() = runTest {
        val repo = FakeEarningsRepository(listOf(
            event("AAPL", "2026-07-20"),           // in index
            event("TINYCO", "2026-07-20"),         // not index, not owned -> dropped
            event("MYPENNY", "2026-07-21"),        // not index, but OWNED -> kept
        ))
        val fetch = FetchEarningsCalendar(repo) { setOf("MYPENNY") }
        val out = fetch.execute("2026-07-20", "2026-07-27")
        assertEquals(listOf("AAPL", "MYPENNY"), out.map { it.symbol })
    }

    @Test
    fun ownSymbolsSortFirstWithinADay() = runTest {
        val repo = FakeEarningsRepository(listOf(
            event("AAPL", "2026-07-20"),
            event("ZTS", "2026-07-20"),
            event("MSFT", "2026-07-20"),
        ))
        val fetch = FetchEarningsCalendar(repo) { setOf("ZTS") }
        val out = fetch.execute("2026-07-20", "2026-07-27")
        assertEquals(listOf("ZTS", "AAPL", "MSFT"), out.map { it.symbol }) // owned pinned, rest alphabetical
    }

    @Test
    fun nextEarningsPicksEarliestForSymbol() = runTest {
        val repo = FakeEarningsRepository(listOf(
            event("AAPL", "2026-07-24"),
            event("AAPL", "2026-10-22"),
            event("MSFT", "2026-07-21"),
        ))
        val fetch = FetchEarningsCalendar(repo) { emptySet() }
        assertEquals("2026-07-24", fetch.nextEarnings("AAPL", "2026-07-13", "2026-08-12")?.day)
    }

    @Test
    fun nextEarningsNullWhenAbsent() = runTest {
        val fetch = FetchEarningsCalendar(FakeEarningsRepository(emptyList())) { emptySet() }
        assertNull(fetch.nextEarnings("AAPL", "2026-07-13", "2026-08-12"))
    }

    @Test
    fun repositoryFailureDegradesToEmpty() = runTest {
        val repo = object : EarningsCalendarRepository {
            override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> =
                throw QuoteError.Network("boom")
        }
        val fetch = FetchEarningsCalendar(repo) { emptySet() }
        assertEquals(emptyList(), fetch.execute("2026-07-20", "2026-07-27"))
    }

    @Test
    fun dotClassTickersMatchDashForm() = runTest {
        // Finnhub reports BRK.B as "BRK.B"; a user may hold "BRK-B". Normalization makes them meet.
        val repo = FakeEarningsRepository(listOf(event("BRK.B", "2026-07-20")))
        val fetch = FetchEarningsCalendar(repo) { setOf("BRK-B") }
        val out = fetch.execute("2026-07-20", "2026-07-27")
        assertEquals(listOf("BRK.B"), out.map { it.symbol })
        // and it counts as OWN (pinned) — verify via a mixed day
    }
}
```

> If `QuoteError.Network`'s constructor differs (check `shared/src/commonMain/.../application` for its actual shape — it exists and is used by news tests), adapt the throw line to the real signature.

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew :shared:jvmTest --tests '*EarningsUseCases*' --rerun-tasks
```
Expected: compilation FAILS — `EarningsCalendarRepository` unresolved.

- [ ] **Step 4: Implement**

`EarningsEvent.kt`:

```kotlin
package com.aptrade.shared.domain

/** When in the trading day a company reports. */
enum class EarningsSession { BeforeOpen, AfterClose, DuringMarket, Unknown }

/** One upcoming (or just-reported) earnings release. [day] is the market-local date as
 *  `yyyy-MM-dd` — the same string shape MarketCalendar.tradingDay produces, so day math
 *  and grouping are plain string equality. [companyName] may be empty (Finnhub omits it
 *  sometimes); UIs fall back to [symbol]. */
data class EarningsEvent(
    val symbol: String,
    val companyName: String,
    val day: String,
    val session: EarningsSession,
    val epsEstimate: Double?,
    val epsActual: Double?,
)
```

`EarningsPorts.kt`:

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent

/** Supplies earnings-calendar events in a day range (inclusive, `yyyy-MM-dd`). */
interface EarningsCalendarRepository {
    suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent>
}

/** No-key fallback: the Calendar tab renders its needs-key state, holidays still show. */
object EmptyEarningsRepository : EarningsCalendarRepository {
    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> = emptyList()
}
```

`EarningsUseCases.kt`:

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.SP500Symbols
import kotlinx.coroutines.CancellationException

/** Ticker forms differ between sources ("BRK.B" vs "BRK-B"); compare on a dot/dash-blind key. */
private fun normalized(symbol: String): String = symbol.uppercase().replace('-', '.')

/**
 * Serves both earnings surfaces. [ownSymbols] is a provider (watchlist ∪ portfolio read
 * fresh per call — both change at runtime). Filtering: keep events whose symbol is in the
 * S&P 500 snapshot OR owned; owned events sort before index events within a day, then
 * alphabetically. Failures degrade to an empty list (CancellationException excepted) —
 * the calendar's holiday banners must render even when the network is down.
 */
class FetchEarningsCalendar(
    private val repository: EarningsCalendarRepository,
    private val ownSymbols: suspend () -> Set<String>,
) {
    suspend fun execute(fromDay: String, toDay: String): List<EarningsEvent> {
        val events = try {
            repository.earnings(fromDay, toDay)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val own = ownSymbols().mapTo(HashSet(), ::normalized)
        val index = SP500Symbols.set.mapTo(HashSet(), ::normalized)
        return events
            .filter { normalized(it.symbol) in own || normalized(it.symbol) in index }
            .sortedWith(
                compareBy<EarningsEvent> { it.day }
                    .thenBy { normalized(it.symbol) !in own } // owned first
                    .thenBy { it.symbol },
            )
    }

    /** Earliest event for [symbol] in the window, or null. Uses the same fetch (and any
     *  caching the repository provides) as the calendar screen. */
    suspend fun nextEarnings(symbol: String, fromDay: String, toDay: String): EarningsEvent? {
        val key = normalized(symbol)
        val events = try {
            repository.earnings(fromDay, toDay)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return events.filter { normalized(it.symbol) == key }.minByOrNull { it.day }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :shared:jvmTest --tests '*EarningsUseCases*' --rerun-tasks
```
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add shared/src
git commit -m "feat(shared): earnings domain — EarningsEvent, port, SP500 filter, FetchEarningsCalendar

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: Kotlin `FinnhubEarningsRepository` (DTO + mapper + Ktor adapter + TTL cache)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/FinnhubEarningsDTO.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/infrastructure/FinnhubEarningsRepository.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/infrastructure/FinnhubEarningsMapperTest.kt`

**Interfaces:**
- Consumes: `EarningsCalendarRepository`, `EarningsEvent`, `EarningsSession` (Task 2); `defaultYahooHttpClient()` and the JSON/error-mapping idiom from `FinnhubNewsRepository.kt` — open that file and mirror its client construction, error mapping, and `@Throws` annotations exactly.
- Produces: `class FinnhubEarningsRepository(apiKey: String) : EarningsCalendarRepository` with an in-memory per-range TTL cache (6h), and `object FinnhubEarningsMapper { fun events(dto: FinnhubEarningsCalendarDTO): List<EarningsEvent> }`.

- [ ] **Step 1: Write the failing mapper tests**

```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.EarningsSession
import kotlin.test.Test
import kotlin.test.assertEquals

class FinnhubEarningsMapperTest {

    private val sample = """
        {"earningsCalendar":[
          {"date":"2026-07-24","epsActual":null,"epsEstimate":1.52,"hour":"amc","quarter":3,
           "revenueActual":null,"revenueEstimate":90000000000,"symbol":"AAPL","year":2026},
          {"date":"2026-07-21","epsActual":2.11,"epsEstimate":2.05,"hour":"bmo","symbol":"KO"},
          {"date":"2026-07-22","hour":"dmh","symbol":"XYZ"},
          {"hour":"amc","symbol":"NODATE"},
          {"date":"2026-07-23","hour":"weird","symbol":"ODD"}
        ]}
    """.trimIndent()

    @Test
    fun mapsFieldsAndSessions() {
        val dto = finnhubJson.decodeFromString(FinnhubEarningsCalendarDTO.serializer(), sample)
        val events = FinnhubEarningsMapper.events(dto)
        val bySymbol = events.associateBy { it.symbol }
        assertEquals(4, events.size) // NODATE dropped (no date)
        assertEquals(EarningsSession.AfterClose, bySymbol.getValue("AAPL").session)
        assertEquals(1.52, bySymbol.getValue("AAPL").epsEstimate)
        assertEquals(EarningsSession.BeforeOpen, bySymbol.getValue("KO").session)
        assertEquals(2.11, bySymbol.getValue("KO").epsActual)
        assertEquals(EarningsSession.DuringMarket, bySymbol.getValue("XYZ").session)
        assertEquals(EarningsSession.Unknown, bySymbol.getValue("ODD").session)
        assertEquals("2026-07-24", bySymbol.getValue("AAPL").day)
        assertEquals("", bySymbol.getValue("AAPL").companyName) // endpoint carries no name
    }

    @Test
    fun emptyPayloadMapsToEmpty() {
        val dto = finnhubJson.decodeFromString(FinnhubEarningsCalendarDTO.serializer(), "{}")
        assertEquals(emptyList(), FinnhubEarningsMapper.events(dto))
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :shared:jvmTest --tests '*FinnhubEarningsMapper*' --rerun-tasks
```
Expected: compilation FAILS.

- [ ] **Step 3: Implement DTO + mapper**

`FinnhubEarningsDTO.kt` (reuses the existing lenient `finnhubJson` from `FinnhubNewsDTO.kt` — same package):

```kotlin
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
```

- [ ] **Step 4: Run mapper tests — expect PASS.** Same command as Step 2.

- [ ] **Step 5: Implement the Ktor adapter with TTL cache**

`FinnhubEarningsRepository.kt` — copy the request/error idiom from `FinnhubNewsRepository.kt` (same package; open it side by side). Reference shape:

```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.EarningsCalendarRepository
import com.aptrade.shared.domain.EarningsEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Finnhub-backed [EarningsCalendarRepository]. Same client seam and error tolerance as
 * [FinnhubNewsRepository]. Responses are cached in-memory per (fromDay, toDay) for
 * [ttlMillis] (default 6h — earnings dates don't move intraday) so the calendar screen,
 * every detail screen, and the daily notification check share one network call.
 */
class FinnhubEarningsRepository internal constructor(
    private val apiKey: String,
    private val client: HttpClient,
    private val nowMillis: () -> Long = { currentTimeMillis() },
    private val ttlMillis: Long = 6 * 60 * 60 * 1000L,
) : EarningsCalendarRepository {

    constructor(apiKey: String) : this(apiKey, defaultYahooHttpClient())

    private data class CacheEntry(val atMillis: Long, val events: List<EarningsEvent>)
    private val mutex = Mutex()
    private val cache = HashMap<Pair<String, String>, CacheEntry>()

    override suspend fun earnings(fromDay: String, toDay: String): List<EarningsEvent> {
        val key = fromDay to toDay
        mutex.withLock {
            cache[key]?.let { if (nowMillis() - it.atMillis < ttlMillis) return it.events }
        }
        val body: String = client.get("https://finnhub.io/api/v1/calendar/earnings") {
            url.parameters.append("from", fromDay)
            url.parameters.append("to", toDay)
            url.parameters.append("token", apiKey)
        }.body()
        val events = FinnhubEarningsMapper.events(
            finnhubJson.decodeFromString(FinnhubEarningsCalendarDTO.serializer(), body),
        )
        mutex.withLock { cache[key] = CacheEntry(nowMillis(), events) }
        return events
    }
}
```

**Adapt to reality:** `currentTimeMillis()` — commonMain has no `System.currentTimeMillis`; check how the shared module gets "now" elsewhere (`Clock`/expect-actual/epoch seconds provider in the coordinator wiring) and use that mechanism. Also mirror `FinnhubNewsRepository`'s exact error mapping (it maps HTTP/transport failures to `QuoteError` variants — copy that `try/catch` structure verbatim) and its `@Throws` annotation if present. The plan's cache logic and endpoint parameters are binding; the plumbing idiom follows the news file.

Add a cache test to `FinnhubEarningsMapperTest.kt`'s file (JVM test can construct the internal ctor with a Ktor `MockEngine` if the news tests already use one — check `shared/src/*Test*/.../infrastructure/` for the existing HTTP-fake idiom and mirror it; if none exists, test the cache through a fake `HttpClient` the same way news does, or extract cache-decision into a small pure function and test that):

```kotlin
    @Test
    fun secondCallWithinTtlServesCache() { /* mirror the news suite's HTTP-fake idiom; assert one engine hit for two earnings() calls */ }
```

- [ ] **Step 6: Run the infrastructure tests, then the whole shared suite**

```bash
./gradlew :shared:jvmTest --rerun-tasks
```
Expected: PASS, count grows by this task's tests.

- [ ] **Step 7: Commit**

```bash
git add shared/src
git commit -m "feat(shared): FinnhubEarningsRepository — /calendar/earnings adapter with 6h TTL cache

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: Kotlin planner + settings — `EarningsCheckDue` once per trading day

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/application/MarketActivityPlanner.kt` (find via `grep -rn 'class MarketActivityPlanner' shared/src`)
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/settings/AppSettings.kt`
- Test (modify): the existing planner test file (find via `grep -rln 'MarketActivityPlanner' shared/src/commonTest`)

**Interfaces:**
- Consumes: `MarketCalendar.tradingDay(atEpochSeconds)`, `MarketStatus`.
- Produces:
  - `ScheduledNotification.EarningsCheckDue` (new enum case beside `MarketOpened/MarketClosed/DigestDue`).
  - `SchedulerState` gains `val lastEarningsDay: String? = null` (constructor default keeps old persisted JSON loading).
  - `AppSettings` gains `val earningsReports: Boolean = true`.

- [ ] **Step 1: Write the failing planner tests** (append to the existing planner test file, following its existing helpers for building instants/states — read the file first and reuse its fixtures):

```kotlin
    @Test
    fun earningsCheckFiresOncePerTradingDayWhenEnabled() {
        // Use the file's existing "market open Tuesday" instant fixture.
        val settings = AppSettings(earningsReports = true)
        val (events1, state1) = planner.plan(openTuesdayInstant, SchedulerState(lastStatus = MarketStatus.OPEN), settings)
        assertTrue(ScheduledNotification.EarningsCheckDue in events1)
        val (events2, _) = planner.plan(openTuesdayInstant + 60, state1, settings)
        assertFalse(ScheduledNotification.EarningsCheckDue in events2)
    }

    @Test
    fun earningsCheckSuppressedWhenToggleOff() {
        val settings = AppSettings(earningsReports = false)
        val (events, _) = planner.plan(openTuesdayInstant, SchedulerState(lastStatus = MarketStatus.OPEN), settings)
        assertFalse(ScheduledNotification.EarningsCheckDue in events)
    }
```

> `openTuesdayInstant`, `planner`, and the plan(...) argument order are whatever the existing test file already uses — adapt names to its fixtures; the two behaviors above are the requirement. If the planner is stateless-object style, construct as the file does.

- [ ] **Step 2: Run to verify failure** — `./gradlew :shared:jvmTest --tests '*MarketActivityPlanner*' --rerun-tasks`. Expected: compilation FAILS (`EarningsCheckDue`).

- [ ] **Step 3: Implement**

In `AppSettings.kt`, after `newsDigest`:

```kotlin
    val earningsReports: Boolean = true,
```

(one line; the KDoc gains: "earningsReports (calendar increment) defaults on — a held/watched company reporting today is high-signal.")

In the planner file: add the enum case `EarningsCheckDue`, add `val lastEarningsDay: String? = null` to `SchedulerState`, and inside `plan(...)`, mirror the digest block exactly (directly after it):

```kotlin
        if (status == MarketStatus.OPEN) {
            val day = calendar.tradingDay(nowEpochSeconds)
            if (state.lastEarningsDay != day && settings.earningsReports) {
                events.add(ScheduledNotification.EarningsCheckDue)
                newState = newState.copy(lastEarningsDay = day)
            }
        }
```

(Adapt variable names — `nowEpochSeconds` vs `now`, `copy` vs mutation — to the file's existing digest block; the Kotlin planner is a transcription of the Swift one shown in the spec.)

Check `SchedulerStateStore`'s persisted JSON (find `FileSchedulerStateStore`/serializer): if `SchedulerState` is `@Serializable`, the new nullable-default field is backward-compatible; confirm the store's round-trip test still passes.

- [ ] **Step 4: Run to verify pass** — same command. Then full `:shared:jvmTest --rerun-tasks` (settings round-trip tests must absorb the new field via defaults).

- [ ] **Step 5: Commit**

```bash
git add shared/src
git commit -m "feat(shared): EarningsCheckDue planner event + earningsReports setting

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: Kotlin L10n — 22 calendar keys ×4 languages

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/l10n/L10n.kt`
- Test (modify): `shared/src/commonTest/kotlin/com/aptrade/shared/l10n/L10nCatalogTest.kt`

**Interfaces — produces these exact key names (UI tasks depend on them):**
`CalendarTab, MarketClosedBannerFmt, ClosesEarlyBannerFmt, HolidayNewYears, HolidayMLK, HolidayWashington, HolidayGoodFriday, HolidayMemorial, HolidayJuneteenth, HolidayIndependence, HolidayLabor, HolidayThanksgiving, HolidayChristmas, SessionBeforeOpen, SessionAfterClose, SessionDuringMarket, NextEarnings, EarningsReportsToggle, EarningsReportsSubtitle, EarningsTodayTitle, EarningsTodayBodyFmt, NoUpcomingEarnings`

- [ ] **Step 1: Bump the catalog count test first (failing test)** — in `L10nCatalogTest.kt` change `assertEquals(227, ...)` to `assertEquals(249, ...)`, rename the backtick test to say 249, and extend the KDoc history paragraph ("The calendar increment added 22 keys ..."). Run `./gradlew :shared:jvmTest --tests '*L10nCatalog*' --rerun-tasks` — FAILS (227 ≠ 249).

- [ ] **Step 2: Add the keys.** In the `Key` enum (new "// Calendar" section after the news keys):

```kotlin
        // Calendar (market holidays + earnings)
        CalendarTab(english = "Calendar"),
        MarketClosedBannerFmt(english = "Market closed — %s"),
        ClosesEarlyBannerFmt(english = "Closes 1:00 PM — %s"),
        HolidayNewYears(english = "New Year's Day"),
        HolidayMLK(english = "Martin Luther King Jr. Day"),
        HolidayWashington(english = "Washington's Birthday"),
        HolidayGoodFriday(english = "Good Friday"),
        HolidayMemorial(english = "Memorial Day"),
        HolidayJuneteenth(english = "Juneteenth"),
        HolidayIndependence(english = "Independence Day"),
        HolidayLabor(english = "Labor Day"),
        HolidayThanksgiving(english = "Thanksgiving Day"),
        HolidayChristmas(english = "Christmas Day"),
        SessionBeforeOpen(english = "Before open"),
        SessionAfterClose(english = "After close"),
        SessionDuringMarket(english = "During market"),
        NextEarnings(english = "Next earnings"),
        EarningsReportsToggle(english = "Earnings reports"),
        EarningsReportsSubtitle(english = "When a company you hold or watch reports today"),
        EarningsTodayTitle(english = "Earnings today"),
        EarningsTodayBodyFmt(english = "%1${'$'}s reports today · %2${'$'}s"),
        NoUpcomingEarnings(english = "No earnings in the next two weeks"),
```

German block (after the last existing DE entry, same map):

```kotlin
            Key.CalendarTab to "Kalender",
            Key.MarketClosedBannerFmt to "Markt geschlossen — %s",
            Key.ClosesEarlyBannerFmt to "Schließt 13:00 Uhr — %s",
            Key.HolidayNewYears to "Neujahr",
            Key.HolidayMLK to "Martin-Luther-King-Tag",
            Key.HolidayWashington to "Washingtons Geburtstag",
            Key.HolidayGoodFriday to "Karfreitag",
            Key.HolidayMemorial to "Memorial Day",
            Key.HolidayJuneteenth to "Juneteenth",
            Key.HolidayIndependence to "Unabhängigkeitstag",
            Key.HolidayLabor to "Labor Day",
            Key.HolidayThanksgiving to "Thanksgiving",
            Key.HolidayChristmas to "Weihnachten",
            Key.SessionBeforeOpen to "Vor Handelsbeginn",
            Key.SessionAfterClose to "Nach Handelsschluss",
            Key.SessionDuringMarket to "Während des Handels",
            Key.NextEarnings to "Nächster Quartalsbericht",
            Key.EarningsReportsToggle to "Quartalsberichte",
            Key.EarningsReportsSubtitle to "Wenn ein gehaltenes oder beobachtetes Unternehmen heute berichtet",
            Key.EarningsTodayTitle to "Quartalszahlen heute",
            Key.EarningsTodayBodyFmt to "%1${'$'}s berichtet heute · %2${'$'}s",
            Key.NoUpcomingEarnings to "Keine Quartalsberichte in den nächsten zwei Wochen",
```

Italian block:

```kotlin
            Key.CalendarTab to "Calendario",
            Key.MarketClosedBannerFmt to "Mercato chiuso — %s",
            Key.ClosesEarlyBannerFmt to "Chiude alle 13:00 — %s",
            Key.HolidayNewYears to "Capodanno",
            Key.HolidayMLK to "Giorno di Martin Luther King",
            Key.HolidayWashington to "Compleanno di Washington",
            Key.HolidayGoodFriday to "Venerdì Santo",
            Key.HolidayMemorial to "Memorial Day",
            Key.HolidayJuneteenth to "Juneteenth",
            Key.HolidayIndependence to "Giorno dell'Indipendenza",
            Key.HolidayLabor to "Labor Day",
            Key.HolidayThanksgiving to "Giorno del Ringraziamento",
            Key.HolidayChristmas to "Natale",
            Key.SessionBeforeOpen to "Prima dell'apertura",
            Key.SessionAfterClose to "Dopo la chiusura",
            Key.SessionDuringMarket to "Durante la seduta",
            Key.NextEarnings to "Prossima trimestrale",
            Key.EarningsReportsToggle to "Trimestrali",
            Key.EarningsReportsSubtitle to "Quando una società che possiedi o segui pubblica i risultati oggi",
            Key.EarningsTodayTitle to "Trimestrali oggi",
            Key.EarningsTodayBodyFmt to "%1${'$'}s pubblica i risultati oggi · %2${'$'}s",
            Key.NoUpcomingEarnings to "Nessuna trimestrale nelle prossime due settimane",
```

Spanish block:

```kotlin
            Key.CalendarTab to "Calendario",
            Key.MarketClosedBannerFmt to "Mercado cerrado — %s",
            Key.ClosesEarlyBannerFmt to "Cierra a las 13:00 — %s",
            Key.HolidayNewYears to "Año Nuevo",
            Key.HolidayMLK to "Día de Martin Luther King",
            Key.HolidayWashington to "Natalicio de Washington",
            Key.HolidayGoodFriday to "Viernes Santo",
            Key.HolidayMemorial to "Memorial Day",
            Key.HolidayJuneteenth to "Juneteenth",
            Key.HolidayIndependence to "Día de la Independencia",
            Key.HolidayLabor to "Labor Day",
            Key.HolidayThanksgiving to "Día de Acción de Gracias",
            Key.HolidayChristmas to "Navidad",
            Key.SessionBeforeOpen to "Antes de la apertura",
            Key.SessionAfterClose to "Tras el cierre",
            Key.SessionDuringMarket to "Durante la sesión",
            Key.NextEarnings to "Próximo informe trimestral",
            Key.EarningsReportsToggle to "Informes trimestrales",
            Key.EarningsReportsSubtitle to "Cuando una empresa que posees o sigues presenta resultados hoy",
            Key.EarningsTodayTitle to "Resultados hoy",
            Key.EarningsTodayBodyFmt to "%1${'$'}s presenta resultados hoy · %2${'$'}s",
            Key.NoUpcomingEarnings to "Sin informes trimestrales en las próximas dos semanas",
```

> Check how existing `*Fmt` keys encode placeholders in this catalog (e.g. `ActiveAlertsFormat`, `AddedSymbolFmt`) and match that convention — if the catalog uses `%s`/`%d` with a `trf(...)` formatter, use plain `%s` twice and positional args only if `trf` supports them; otherwise restructure `EarningsTodayBodyFmt` to a single `%s` ("AAPL · After close" prebuilt by the caller). The UI tasks below assume `trf(key, symbol, sessionText)` two-arg form — align them with whatever you find.

Also add the holiday-name mapping helper the UIs share — append to `USMarketHolidays.kt`… **no** (domain must not import l10n). Instead each UI layer maps `USMarketHoliday -> L10n.Key`; the mapping function is defined once per app in Tasks 7/9 (Kotlin) and lives in the Swift app layer for macOS/iOS.

- [ ] **Step 3: Run** `./gradlew :shared:jvmTest --tests '*L10nCatalog*' --rerun-tasks` — PASS (count 249; the every-key-resolves-in-4-languages test covers the new entries).

- [ ] **Step 4: Commit**

```bash
git add shared/src
git commit -m "feat(l10n): 22 calendar keys (tab, banners, holidays, sessions, earnings) in EN/DE/IT/ES

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: Shared calendar-day grouping helper + desktop wiring, coordinator, notifier, settings toggle

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/MarketCalendar.kt` (one new public method)
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/CalendarDays.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/CalendarDaysTest.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt` (earnings wiring)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/DesktopMarketActivityCoordinator.kt` (EarningsCheckDue handling)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/infra/TrayNotifier.kt` (find exact path via `grep -rln 'class TrayNotifier' desktopApp`) — add `notifyEarnings`
- Modify: desktop settings notifications page (find via `grep -rln 'MarketOpenAndClose' desktopApp/src/main`) — add the Earnings reports toggle row
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (coordinator construction gains the earnings closure)
- Test (modify): the desktop coordinator test file (find via `grep -rln 'DesktopMarketActivityCoordinator' desktopApp/src/test`)

**Interfaces:**
- Produces:
  - `MarketCalendar.dayString(localEpochDay: Long): String` — public wrapper over the private `formatLocalDate`.
  - `data class CalendarDay(val day: String, val localEpochDay: Long, val holiday: USMarketHoliday?, val isHalfDay: Boolean, val events: List<EarningsEvent>)`
  - `fun buildCalendarDays(startLocalEpochDay: Long, count: Int, calendar: MarketCalendar, events: List<EarningsEvent>): List<CalendarDay>` — a day appears in the output only if it has a holiday, a half-day, or ≥1 event (empty plain days collapse).
  - Desktop `AppGraph`: `val earningsRepository: EarningsCalendarRepository` (key-gated; desktop key is startup-frozen like its news pair) and `val fetchEarningsCalendar: FetchEarningsCalendar`, plus `val earningsKeyMissing: Boolean`.
  - `DesktopMarketActivityCoordinator` gains constructor param `notifyEarnings: suspend (title: String, body: String) -> Unit` and `fetchTodaysOwnEarnings: suspend () -> List<EarningsEvent>` (a closure AppGraph/Main builds: fetch today→today, intersect watchlist ∪ portfolio symbols).

- [ ] **Step 1: Failing tests for `buildCalendarDays`**

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.USMarketHoliday
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarDaysTest {
    private val calendar = MarketCalendar()
    // 2026-11-23 (Mon) local epoch day: compute via calendar.localEpochDay of 2026-11-23 12:00 ET
    // = 17:00 UTC = 1794762000
    private val monday = calendar.localEpochDay(1_794_762_000L)

    private fun ev(symbol: String, day: String) = EarningsEvent(symbol, "", day, EarningsSession.AfterClose, null, null)

    @Test
    fun holidayAndHalfDayRowsAppearEventlessDaysCollapse() {
        // Window Mon Nov 23 .. Sun Nov 29 2026: Thanksgiving Thu 26 (holiday), Fri 27 half-day.
        val days = buildCalendarDays(monday, 7, calendar, events = listOf(ev("AAPL", "2026-11-24")))
        assertEquals(listOf("2026-11-24", "2026-11-26", "2026-11-27"), days.map { it.day })
        assertEquals(USMarketHoliday.Thanksgiving, days[1].holiday)
        assertTrue(days[2].isHalfDay)
        assertEquals(listOf("AAPL"), days[0].events.map { it.symbol })
    }

    @Test
    fun eventsOutsideWindowAreDropped() {
        val days = buildCalendarDays(monday, 2, calendar, events = listOf(ev("AAPL", "2026-12-25")))
        assertTrue(days.none { it.events.isNotEmpty() })
    }
}
```

- [ ] **Step 2: Run — compilation FAILS.** `./gradlew :shared:jvmTest --tests '*CalendarDays*' --rerun-tasks`

- [ ] **Step 3: Implement**

`MarketCalendar.kt` — add beside the Task-1 additions:

```kotlin
    /** `yyyy-MM-dd` for a market-local epoch day (public face of formatLocalDate). */
    fun dayString(localEpochDay: Long): String = formatLocalDate(localEpochDay)
```

`CalendarDays.kt`:

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.MarketCalendar
import com.aptrade.shared.domain.USMarketHoliday

/** One rendered day on the Calendar tab. Only days that have something to say survive
 *  grouping (holiday, half-day, or at least one earnings event) — see [buildCalendarDays]. */
data class CalendarDay(
    val day: String,
    val localEpochDay: Long,
    val holiday: USMarketHoliday?,
    val isHalfDay: Boolean,
    val events: List<EarningsEvent>,
)

/** Groups a pre-filtered, pre-sorted event list (FetchEarningsCalendar.execute output —
 *  its ordering is preserved within a day) into the next [count] days starting at
 *  [startLocalEpochDay]. Pure; both Kotlin apps' ViewModels call this, and its tests are
 *  the single source of truth for grouping behavior. */
fun buildCalendarDays(
    startLocalEpochDay: Long,
    count: Int,
    calendar: MarketCalendar,
    events: List<EarningsEvent>,
): List<CalendarDay> {
    val byDay = events.groupBy { it.day }
    return (0 until count).mapNotNull { offset ->
        val epochDay = startLocalEpochDay + offset
        val day = calendar.dayString(epochDay)
        val holiday = calendar.holiday(epochDay)
        val halfDay = calendar.isHalfDay(epochDay)
        val dayEvents = byDay[day].orEmpty()
        if (holiday == null && !halfDay && dayEvents.isEmpty()) return@mapNotNull null
        CalendarDay(day = day, localEpochDay = epochDay, holiday = holiday, isHalfDay = halfDay, events = dayEvents)
    }
}
```

Run Step 2's command — PASS.

- [ ] **Step 4: Desktop AppGraph earnings wiring**

In `AppGraph.kt`, beside the existing `newsRepository`/`keyMissing` pair (desktop keeps its startup-frozen style — the desktop key is file-dropped and the app relaunched, per platform convention):

```kotlin
    val earningsRepository: EarningsCalendarRepository by lazy {
        FinnhubKeyConfig().finnhubApiKey()?.let { FinnhubEarningsRepository(it) } ?: EmptyEarningsRepository
    }
    val earningsKeyMissing: Boolean get() = /* reuse however keyMissing is derived for news — same expression, same source */
    val fetchEarningsCalendar: FetchEarningsCalendar by lazy {
        FetchEarningsCalendar(earningsRepository) {
            // watchlist ∪ portfolio symbols, read fresh per call
            val watchlist = /* the same store/use case the coordinator's digest uses */ fetchWatchlist.execute().map { it.symbol }
            val positions = /* portfolio store load */ loadPortfolio().positions.map { it.asset.symbol }
            (watchlist + positions).toSet()
        }
    }
```

> The commented fragments name intent; resolve them against AppGraph's real members (it already exposes watchlist + portfolio access for the digest and portfolio tab — reuse those exact members, do not create new stores). Match how `newsRepository`'s key read is written in this file and mirror it.

- [ ] **Step 5: Coordinator + TrayNotifier + settings toggle**

`DesktopMarketActivityCoordinator.kt` — add the two constructor params after `notifyDigest`:

```kotlin
    private val notifyEarnings: suspend (title: String, body: String) -> Unit,
    private val fetchTodaysOwnEarnings: suspend () -> List<EarningsEvent>,
```

and the event handler beside `DigestDue`:

```kotlin
                ScheduledNotification.EarningsCheckDue -> {
                    for (event in fetchTodaysOwnEarnings()) {
                        notifyEarnings(
                            L10n.string(L10n.Key.EarningsTodayTitle, language()),
                            /* body via the same string-format mechanism the digest uses */
                            earningsBody(event),
                        )
                    }
                }
```

> Look at how this coordinator resolves localized strings for the digest (it receives pre-localized strings or a language provider — mirror that exactly; if `notifyDigest` takes a raw summary string built by Main.kt, keep symmetry: have Main.kt pass a `notifyEarnings` closure that formats with `trf(L10n.Key.EarningsTodayBodyFmt, event.symbol, sessionLabel(event.session))` in UI land, and the coordinator only forwards `EarningsEvent`s: in that case the param becomes `notifyEarnings: suspend (EarningsEvent) -> Unit` — CHOOSE the shape that keeps ALL localization out of this class, matching the digest's existing division of labor, and update the Interfaces block accordingly in your task report.)

`TrayNotifier` — add a method mirroring `notifyFill`'s implementation pattern:

```kotlin
    fun notifyEarnings(title: String, body: String) { /* same TrayIcon.displayMessage call notifyFill uses */ }
```

`Main.kt` — construct the coordinator with the two new arguments:

```kotlin
    fetchTodaysOwnEarnings = {
        val today = /* marketCalendar.tradingDay(nowEpochSeconds()) — the calendar+clock the planner wiring already has */
        val own = /* same ownSymbols closure AppGraph.fetchEarningsCalendar uses */
        AppGraph.fetchEarningsCalendar.execute(today, today).filter { normalizedOwn(own, it.symbol) }
    },
```

> `FetchEarningsCalendar.execute` already keeps owned symbols; for the notification we need ONLY owned — add a tiny public helper on `FetchEarningsCalendar`: `suspend fun ownedToday(day: String): List<EarningsEvent>` implemented as `execute(day, day).filter { normalized(it.symbol) in ownSymbols().map(::normalized) }` — add it in Task 2's file now with a test in `EarningsUseCasesTest` (own symbol kept, index-but-not-owned dropped). Then this closure is one line: `AppGraph.fetchEarningsCalendar.ownedToday(today)`.

Desktop settings Notifications page — add below the Market open & close row, exactly mirroring that row's composable call:

```kotlin
    ToggleRow(
        title = tr(L10n.Key.EarningsReportsToggle),
        subtitle = tr(L10n.Key.EarningsReportsSubtitle),
        checked = settings.earningsReports,
        onCheckedChange = { onUpdate { s -> s.copy(earningsReports = it) } },
    )
```

(Adapt the row-composable name/params to the file's actual idiom.)

- [ ] **Step 6: Extend the desktop coordinator test** — add a case following the file's existing fixtures: planner returns `EarningsCheckDue` (drive it via a state/settings combo, or the file's fake-planner idiom if it has one) → coordinator calls `notifyEarnings` once per owned event; empty `fetchTodaysOwnEarnings` → no calls.

- [ ] **Step 7: Run desktop + shared suites**

```bash
./gradlew :shared:jvmTest :desktopApp:test --rerun-tasks
```
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add shared/src desktopApp/src
git commit -m "feat(desktop): earnings wiring, EarningsCheckDue delivery via tray, earnings toggle; shared calendar-day grouping

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 7: Desktop Calendar tab — pane, view model, detail Next-earnings stat

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/ui/AppShell.kt` (AppTab + title)
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/calendar/CalendarViewModel.kt`
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/calendar/CalendarPane.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (route the new tab)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/detail/DetailViewModel.kt` + `DetailPane.kt` (Next-earnings stat)
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/calendar/CalendarViewModelTest.kt`

**Interfaces:**
- Consumes: `buildCalendarDays`, `FetchEarningsCalendar`, `MarketCalendar.localEpochDay/dayString`, L10n keys from Task 5, `AppGraph.earningsKeyMissing`.
- Produces: `data class CalendarUiState(val days: List<CalendarDay> = emptyList(), val ownSymbols: Set<String> = emptySet(), val isLoading: Boolean = false, val needsKey: Boolean = false)`; `class CalendarViewModel(fetch: FetchEarningsCalendar, calendar: MarketCalendar, ownSymbols: suspend () -> Set<String>, needsKey: Boolean, nowEpochSeconds: () -> Long, scope: CoroutineScope)` with `fun load()`; desktop `DetailUiState` gains `val nextEarningsText: String? = null` (pre-localized "Jul 24 · After close" is NOT pre-localized — carry the `EarningsEvent?` instead: `val nextEarnings: EarningsEvent? = null`, render localizes).

- [ ] **Step 1: Failing ViewModel test**

```kotlin
package com.aptrade.desktop.calendar

// Follow desktopApp's existing ViewModel test idiom (see news/NewsViewModelTest.kt for
// dispatcher + scope setup) — the assertions below are the contract:

class CalendarViewModelTest {
    // 1) load() with a fake FetchEarningsCalendar returning one AAPL event tomorrow and a
    //    window containing Thanksgiving: state.days holds the event day + the holiday day,
    //    isLoading false after completion, ownSymbols populated from the provider.
    // 2) needsKey=true constructor flag -> state.needsKey true AND days still contain the
    //    holiday rows (fetch returns empty; banners are local).
    // 3) A fetch throwing QuoteError degrades to holiday-only days (no crash).
}
```

Write these three as real tests in the file's idiom (fakes for `EarningsCalendarRepository` are one-liners; construct `FetchEarningsCalendar` around them). Run: FAILS (class missing).

- [ ] **Step 2: Implement `CalendarViewModel.kt`**

```kotlin
package com.aptrade.desktop.calendar

import com.aptrade.shared.application.CalendarDay
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.buildCalendarDays
import com.aptrade.shared.domain.MarketCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CalendarUiState(
    val days: List<CalendarDay> = emptyList(),
    val ownSymbols: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val needsKey: Boolean = false,
)

/** Fourteen days of holidays + S&P-500 earnings. Holiday banners never depend on the
 *  fetch: a failed/keyless fetch still renders the local calendar rows. */
class CalendarViewModel(
    private val fetch: FetchEarningsCalendar,
    private val calendar: MarketCalendar,
    private val ownSymbols: suspend () -> Set<String>,
    private val needsKey: Boolean,
    private val nowEpochSeconds: () -> Long,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CalendarUiState(needsKey = needsKey))
    val state: StateFlow<CalendarUiState> = _state

    fun load() {
        val start = calendar.localEpochDay(nowEpochSeconds())
        _state.value = _state.value.copy(isLoading = true)
        scope.launch {
            val events = fetch.execute(calendar.dayString(start), calendar.dayString(start + 13))
            _state.value = CalendarUiState(
                days = buildCalendarDays(start, 14, calendar, events),
                ownSymbols = ownSymbols(),
                isLoading = false,
                needsKey = needsKey,
            )
        }
    }
}
```

(`fetch.execute` already swallows failures to `emptyList()` — Task 2 — so no try/catch here; `buildCalendarDays` with empty events yields the holiday rows.)

- [ ] **Step 3: Implement `CalendarPane.kt`**

Follow `NewsPane.kt`'s structural idiom (headers, list container, DK styles). Required elements, complete reference implementation:

```kotlin
package com.aptrade.desktop.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.aptrade.shared.application.CalendarDay
import com.aptrade.shared.domain.EarningsEvent
import com.aptrade.shared.domain.EarningsSession
import com.aptrade.shared.domain.USMarketHoliday
import com.aptrade.shared.l10n.L10n
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf

/** USMarketHoliday -> localized name. UI-layer mapping (domain stays L10n-free). */
@Composable
fun holidayName(holiday: USMarketHoliday): String = when (holiday) {
    USMarketHoliday.NewYearsDay -> tr(L10n.Key.HolidayNewYears)
    USMarketHoliday.MartinLutherKingDay -> tr(L10n.Key.HolidayMLK)
    USMarketHoliday.WashingtonsBirthday -> tr(L10n.Key.HolidayWashington)
    USMarketHoliday.GoodFriday -> tr(L10n.Key.HolidayGoodFriday)
    USMarketHoliday.MemorialDay -> tr(L10n.Key.HolidayMemorial)
    USMarketHoliday.Juneteenth -> tr(L10n.Key.HolidayJuneteenth)
    USMarketHoliday.IndependenceDay -> tr(L10n.Key.HolidayIndependence)
    USMarketHoliday.LaborDay -> tr(L10n.Key.HolidayLabor)
    USMarketHoliday.Thanksgiving -> tr(L10n.Key.HolidayThanksgiving)
    USMarketHoliday.Christmas -> tr(L10n.Key.HolidayChristmas)
}

@Composable
fun sessionLabel(session: EarningsSession): String = when (session) {
    EarningsSession.BeforeOpen -> tr(L10n.Key.SessionBeforeOpen)
    EarningsSession.AfterClose -> tr(L10n.Key.SessionAfterClose)
    EarningsSession.DuringMarket -> tr(L10n.Key.SessionDuringMarket)
    EarningsSession.Unknown -> ""
}

@Composable
fun CalendarPane(viewModel: CalendarViewModel) {
    val state by viewModel.state.collectAsState()
    // Structure (implement with the SAME DK components/styles NewsPane uses):
    // LazyColumn {
    //   for each day in state.days:
    //     item: DayHeader(day.day formatted like NewsPane dates)
    //     if (day.holiday != null) item: Banner(trf(L10n.Key.MarketClosedBannerFmt, holidayName(day.holiday)))
    //     else if (day.isHalfDay)  item: Banner(trf(L10n.Key.ClosesEarlyBannerFmt, ...half-day label... ))
    //     items(day.events): EarningsRow(event, owned = event.symbol in state.ownSymbols)
    //   empty & !needsKey -> centered tr(L10n.Key.NoUpcomingEarnings)
    //   needsKey -> the News pane's connect-a-source empty state (reuse its composable if
    //               public; otherwise transcribe it) BELOW any holiday banners
    // }
    // EarningsRow: Row { gold dot if owned; symbol (DK numeric/semibold style); Spacer;
    //   session chip (KindChip visual); epsEstimate as "est $1.52" tnum, omitted when null }
    // Banner: full-width quiet row, DK.surface background, 1dp DK.gold.copy(alpha=.4) border.
}
```

> The commented skeleton is the binding structure; flesh it out with the concrete DK components NewsPane already uses (day header text style, row padding 16/10, hairline dividers). Half-day banner's `%s`: pass the holiday-ish label — for the two float-anchored half-days there is no holiday name; use the day-after-Thanksgiving/Christmas-Eve context by passing the formatted date instead: `trf(L10n.Key.ClosesEarlyBannerFmt, formattedDate)`.

- [ ] **Step 4: Route the tab**

`AppShell.kt`: add `Calendar` to `enum class AppTab` (after `News`) and `AppTab.Calendar -> tr(L10n.Key.CalendarTab)` to `title()`. Update the KDoc "three top-level destinations" → "four".

`Main.kt`: in the `when (tab)` beside `AppTab.News -> ...`, construct once (same remember/scope pattern the news pane uses):

```kotlin
                AppTab.Calendar -> CalendarPane(calendarViewModel)
```

with `calendarViewModel` built beside `newsViewModel` from `AppGraph.fetchEarningsCalendar`, `MarketCalendar()`, the ownSymbols closure, `AppGraph.earningsKeyMissing`, the app's existing `nowEpochSeconds`, and the main scope; call `load()` where news calls its initial load.

- [ ] **Step 5: Detail Next-earnings stat**

`DetailViewModel.kt` (desktop): `DetailUiState` gains `val nextEarnings: EarningsEvent? = null`; in `init` beside the profile fetch launch:

```kotlin
        scope.launch {
            val today = /* MarketCalendar().dayString(localEpochDay(now)) via injected calendar+clock; add ctor params calendar: MarketCalendar, nowEpochSeconds: () -> Long with defaults */
            val next = fetchEarningsCalendar.nextEarnings(symbol, today, plus30(today))
            _state.update { it.copy(nextEarnings = next) }
        }
```

> `plus30`: compute via `calendar.dayString(startEpochDay + 30)`. `fetchEarningsCalendar` is a new constructor param; update the `Main.kt`/detail-host construction site. If injecting into the desktop DetailViewModel is invasive (check its construction sites first), an acceptable alternative: load next-earnings in `DetailPane` via a small `remember`-scoped call — but prefer the VM.

`DetailPane.kt`: extend the stats block (the `StatRow` group from the AssetKind sweep):

```kotlin
            StatRow(
                leftLabel = tr(L10n.Key.NextEarnings),
                leftValue = state.nextEarnings?.let { "${it.day} · ${sessionLabel(it.session)}" } ?: "—",
                rightLabel = "", rightValue = "",
            )
```

(Use the file's single-stat idiom if StatRow demands pairs — pair it with an existing unpaired stat or add a one-sided variant matching the file's style. Import `sessionLabel` from the calendar package.)

- [ ] **Step 6: Run desktop suite + launch check**

```bash
./gradlew :desktopApp:test --rerun-tasks
```
PASS. Optionally launch (`detached Popen recipe from memory`) and eyeball the Calendar tab.

- [ ] **Step 7: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): Calendar tab (holidays + S&P 500 earnings) and Next-earnings detail stat

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 8: Android — coordinator, notifier channels, AppGraph wiring, settings toggle

**Files:**
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/alerts/AndroidMarketActivityCoordinator.kt`
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/alerts/AndroidAlertNotifier.kt` (3 new methods + 3 channels)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/MainActivity.kt` (start coordinator)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/settings/SettingsScreen.kt` (Earnings toggle row)
- Test: `androidApp/src/test/kotlin/com/aptrade/android/alerts/AndroidMarketActivityCoordinatorTest.kt`

**Interfaces:**
- Consumes: shared `MarketActivityPlanner`, `ScheduledNotification.*` (incl. `EarningsCheckDue`), `SchedulerStateStore` + its file store (check `shared/src/jvmCommonMain` for `FileSchedulerStateStore`; if only desktop has one, promote/reuse the same class — it moved to jvmCommonMain in the parity increment, verify), `FetchEarningsCalendar.ownedToday`, `AndroidAlertNotifier`.
- Produces: `AndroidMarketActivityCoordinator(planner, stateStore, loadSettings, notifyMarketStatus, notifyDigest, notifyEarnings, fetchWatchlist, fetchMarketQuotes, fetchTodaysOwnEarnings, scope, nowEpochSeconds, intervalMillis)` — a line-for-line port of `DesktopMarketActivityCoordinator` (which is the reference; transcribe it, then add the earnings branch).
- **Note:** this task turns Android's until-now-decorative `marketOpenClose`/`newsDigest` toggles functional (the coordinator handles all planner events). Record that in the commit message — it is intended, not creep.

- [ ] **Step 1: Failing coordinator test** — port `desktopApp`'s coordinator test file wholesale (package-renamed, notifier closures recorded into lists), plus:

```kotlin
    @Test
    fun earningsCheckDeliversOneNotificationPerOwnedEvent() { /* planner state/settings drive EarningsCheckDue; fake fetchTodaysOwnEarnings returns 2 events -> notifyEarnings called twice with trf-formatted title/body */ }

    @Test
    fun earningsCheckWithNoOwnedEventsStaysSilent() { /* empty list -> zero calls */ }
```

Run `./gradlew :androidApp:testDebugUnitTest --tests '*Coordinator*' --rerun-tasks` — FAILS.

- [ ] **Step 2: Transcribe the coordinator.** Copy `DesktopMarketActivityCoordinator.kt` → `AndroidMarketActivityCoordinator.kt`, package `com.aptrade.android.alerts`, class renamed, desktop-only imports swapped (`com.aptrade.desktop.infra.AppSettings` → `com.aptrade.shared.settings.AppSettings`; `formatPercent` from `com.aptrade.android.ui`). Add the `EarningsCheckDue` branch and the two new constructor params exactly as desktop got in Task 6. KDoc notes it is a transcription and names the source file.

- [ ] **Step 3: Notifier methods.** In `AndroidAlertNotifier.kt` add three channels + methods following the existing `notifyFill` pattern verbatim (permission gate, lazy channel, branded small icon + gold accent):

```kotlin
internal const val MARKET_STATUS_CHANNEL_ID = "market_status"
internal const val DAILY_DIGEST_CHANNEL_ID = "daily_digest"
internal const val EARNINGS_CHANNEL_ID = "earnings_reports"
```

- `suspend fun notifyMarketStatus(opened: Boolean)` — title `tr(L10n.Key.MarketOpenAndClose)`-adjacent: check what desktop's TrayNotifier uses for the open/close title/body strings and reuse those exact keys.
- `suspend fun notifyDigest(summary: String)`
- `suspend fun notifyEarnings(title: String, body: String)`

Channel display names: reuse existing keys (`MarketOpenAndClose`, `DailyNewsDigest`, `EarningsReportsToggle`).

- [ ] **Step 4: Wire in AppGraph + MainActivity.**

AppGraph: `earningsRepository` (Android style — key re-read per access, mirroring `newsRepository`'s key-caching getter exactly, one Ktor client per key), `fetchEarningsCalendar` (ownSymbols = watchlist store load ∪ portfolio store load), `schedulerStateStore` (file store at `configDir().resolve("scheduler.json")` — match desktop's filename, check `resolveConfigDir().resolve(...)` name in desktop AppGraph and use the same), and a `marketActivityCoordinator` factory. MainActivity: start it in the activity scope where the alert-evaluation loop starts (find `EvaluateAlerts` usage) — same lifecycle.

Settings toggle row in `SettingsScreen.kt` `NotificationsPage`, after the Market open & close row:

```kotlin
    ToggleRow(
        title = tr(L10n.Key.EarningsReportsToggle),
        subtitle = tr(L10n.Key.EarningsReportsSubtitle),
        checked = settings.earningsReports,
        onCheckedChange = { checked -> onUpdate { it.copy(earningsReports = checked) } },
    )
```

(Adapt to the page's actual row composable — read the neighboring rows.)

- [ ] **Step 5: Run android suite** — `./gradlew :androidApp:testDebugUnitTest --rerun-tasks`. PASS.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src
git commit -m "feat(android): market-activity coordinator (open/close + digest now functional) with earnings-day notifications

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 9: Android Calendar tab — screen, view model, detail Next-earnings row

**Files:**
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppShell.kt` (ShellTab + icon)
- Create: `androidApp/src/main/kotlin/com/aptrade/android/calendar/CalendarViewModel.kt`
- Create: `androidApp/src/main/kotlin/com/aptrade/android/calendar/CalendarScreen.kt`
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/MainActivity.kt` (tab content routing)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/detail/DetailViewModel.kt` + `DetailScreen.kt`
- Test: `androidApp/src/test/kotlin/com/aptrade/android/calendar/CalendarViewModelTest.kt`

**Interfaces:**
- Consumes: `buildCalendarDays`, `FetchEarningsCalendar`, `MarketCalendar`, L10n keys, `AppGraph.fetchEarningsCalendar` (+ a `needsKey`-equivalent derived from `AppGraph.newsRepository == null`-style key check — mirror how NewsScreen derives it).
- Produces: `CalendarViewModel` — same state/contract as desktop's Task-7 view model but as an `androidx.lifecycle.ViewModel` with `viewModelScope` (mirror `NewsViewModel`'s structure, including the provider-based key re-resolution: `needsKey` recomputed in `load()` so a key saved in Settings applies on next tab entry). Android `DetailUiState` gains `val nextEarnings: EarningsEvent? = null`.

- [ ] **Step 1: Failing VM test** — port the three desktop CalendarViewModel test contracts (Task 7 Step 1) into the android test idiom (`StandardTestDispatcher`, `runTest`, `runCurrent` — copy `NewsViewModelTest`'s harness), plus one android-specific case: `load()` re-resolves the needsKey provider (false after a key appears — the NewsViewModel `startPicksUpAKeyConfiguredAfterConstruction` shape). Run: FAILS.

- [ ] **Step 2: Implement `CalendarViewModel.kt`** — `NewsViewModel`'s structure with Task 7's state shape:

```kotlin
package com.aptrade.android.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aptrade.shared.application.CalendarDay
import com.aptrade.shared.application.FetchEarningsCalendar
import com.aptrade.shared.application.buildCalendarDays
import com.aptrade.shared.domain.MarketCalendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class CalendarUiState(
    val days: List<CalendarDay> = emptyList(),
    val ownSymbols: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val needsKey: Boolean = false,
)

/** 14 days of holidays + S&P-500 earnings. [fetchProvider] and [needsKeyProvider] are
 *  providers (NewsViewModel convention) so a Finnhub key saved in Settings mid-session
 *  applies on the next tab entry; holiday banners render regardless of key state. */
class CalendarViewModel(
    private val fetchProvider: () -> FetchEarningsCalendar,
    private val needsKeyProvider: () -> Boolean,
    private val ownSymbols: suspend () -> Set<String>,
    private val calendar: MarketCalendar = MarketCalendar(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state

    fun load() {
        val start = calendar.localEpochDay(nowEpochSeconds())
        val needsKey = needsKeyProvider()
        _state.value = _state.value.copy(isLoading = true, needsKey = needsKey)
        viewModelScope.launch {
            val events = fetchProvider().execute(calendar.dayString(start), calendar.dayString(start + 13))
            _state.value = CalendarUiState(
                days = buildCalendarDays(start, 14, calendar, events),
                ownSymbols = ownSymbols(),
                isLoading = false,
                needsKey = needsKey,
            )
        }
    }
}
```

- [ ] **Step 3: Implement `CalendarScreen.kt`** — Material 3 rendering of the same structure as desktop's Task-7 pane (transcribe `holidayName`/`sessionLabel` composables into this package with `com.aptrade.android.l10n.tr`; they are UI-layer twins, package-local by design). Screen skeleton mirrors `NewsScreen`: `viewModel { CalendarViewModel(fetchProvider = { AppGraph.fetchEarningsCalendar }, needsKeyProvider = { AppGraph.newsRepository == null }, ownSymbols = { ...watchlist ∪ portfolio... }) }`, `LifecycleStartEffect { viewModel.load() }`, then a `LazyColumn`: day header (`SectionLabel` idiom) → banner card (`Surface` with gold-alpha border) → earnings rows (`ListItem`-style Row: symbol `titleMedium`, session `AssistChip`, `est $x.xx` in `MaterialTheme.typography.bodyMedium` + `FontFamily.Monospace`-free `.monospacedDigit`-equivalent — match how DetailScreen renders numerics), owned rows lead with the gold-tinted dot. Empty → `tr(L10n.Key.NoUpcomingEarnings)` centered; needsKey → the NewsScreen empty-state composable (reuse; it's in NewsScreen.kt — extract to a shared internal composable if private) rendered BELOW banners.

- [ ] **Step 4: Tab + routing.** `AppShell.kt`: `Calendar("calendar", L10n.Key.CalendarTab)` in `ShellTab`; icon `Icons.Filled.DateRange` in the `when (tab)` icon block. `MainActivity`: add the `ShellTab.Calendar -> CalendarScreen(padding)` branch beside News.

- [ ] **Step 5: Detail row.** Android `DetailViewModel`: add `val nextEarnings: EarningsEvent? = null` to `DetailUiState`; in `init`, an isolated coroutine (the priceText pattern — silent failure):

```kotlin
        viewModelScope.launch {
            try {
                val cal = MarketCalendar()
                val start = cal.localEpochDay(System.currentTimeMillis() / 1000)
                val next = fetchEarnings?.nextEarnings(symbol, cal.dayString(start), cal.dayString(start + 30))
                if (next != null) _state.update { it.copy(nextEarnings = next) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { /* stat stays "—" */ }
        }
```

with `fetchEarnings: FetchEarningsCalendar?` as a new constructor param (nullable = keyless; construction site in DetailScreen's `viewModel { }` passes `AppGraph.fetchEarningsCalendar`-or-null following how news use cases are passed; check the real DetailViewModel ctor and thread it the same way its other use cases arrive). `DetailScreen`: below the chart-mode/timeframe block, a quiet stat line:

```kotlin
    state.nextEarnings?.let { next ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(tr(L10n.Key.NextEarnings), style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${next.day} · ${sessionLabel(next.session)}", style = MaterialTheme.typography.bodySmall)
        }
    }
```

- [ ] **Step 6: DetailViewModel test** — add: fake earnings repo with one AAPL event → `state.nextEarnings` populated; throwing repo → stays null, no crash. Run the full android suite `--rerun-tasks`: PASS.

- [ ] **Step 7: Commit**

```bash
git add androidApp/src
git commit -m "feat(android): Calendar tab + Next-earnings detail row

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 10: Swift `USMarketHolidays` + holiday-aware `MarketCalendar`

**Files:**
- Create: `Sources/APTradeDomain/USMarketHolidays.swift`
- Modify: `Sources/APTradeDomain/MarketCalendar.swift`
- Test: `Tests/APTradeDomainTests/USMarketHolidaysTests.swift`
- Test (modify): `Tests/APTradeDomainTests/MarketCalendarTests.swift` (add cases)

**Interfaces — produces:**
- `public enum USMarketHoliday: Sendable, Equatable { case newYearsDay, martinLutherKingDay, washingtonsBirthday, goodFriday, memorialDay, juneteenth, independenceDay, laborDay, thanksgiving, christmas }`
- `public enum USMarketHolidays { public static func fullHoliday(year: Int, month: Int, day: Int) -> USMarketHoliday?; public static func isHalfDay(year: Int, month: Int, day: Int) -> Bool }`
- `MarketCalendar` gains `public func holiday(on date: Date) -> USMarketHoliday?` and `public func isHalfDay(on date: Date) -> Bool`; `status(at:)` becomes holiday/half-day aware.

- [ ] **Step 1: Failing tests.** The Kotlin test table (Task 1 Step 1), transcribed — same dates, `(year:month:day:)` form:

```swift
import XCTest
@testable import APTradeDomain

final class USMarketHolidaysTests: XCTestCase {
    func test_2026Holidays() {
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 1, day: 1), .newYearsDay)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 1, day: 19), .martinLutherKingDay)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 2, day: 16), .washingtonsBirthday)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 4, day: 3), .goodFriday)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 5, day: 25), .memorialDay)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 6, day: 19), .juneteenth)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 7, day: 3), .independenceDay) // Sat Jul 4 observed Fri
        XCTAssertNil(USMarketHolidays.fullHoliday(year: 2026, month: 7, day: 4))
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 9, day: 7), .laborDay)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 11, day: 26), .thanksgiving)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2026, month: 12, day: 25), .christmas)
    }

    func test_observationShifts2027() {
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2027, month: 6, day: 18), .juneteenth)      // Sat -> Fri
        XCTAssertNil(USMarketHolidays.fullHoliday(year: 2027, month: 6, day: 19))
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2027, month: 7, day: 5), .independenceDay)  // Sun -> Mon
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2027, month: 12, day: 24), .christmas)      // Sat -> Fri
    }

    func test_goodFridayAcrossYears() {
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2027, month: 3, day: 26), .goodFriday)
        XCTAssertEqual(USMarketHolidays.fullHoliday(year: 2028, month: 4, day: 14), .goodFriday)
    }

    func test_halfDays() {
        XCTAssertTrue(USMarketHolidays.isHalfDay(year: 2026, month: 11, day: 27))   // day after Thanksgiving
        XCTAssertTrue(USMarketHolidays.isHalfDay(year: 2026, month: 12, day: 24))   // Thu Christmas Eve
        XCTAssertFalse(USMarketHolidays.isHalfDay(year: 2026, month: 7, day: 3))    // observed July 4 -> FULL closure
        XCTAssertTrue(USMarketHolidays.isHalfDay(year: 2025, month: 7, day: 3))     // Thu, Jul 4 2025 = Fri
        XCTAssertFalse(USMarketHolidays.isHalfDay(year: 2027, month: 12, day: 24))  // observed Christmas -> FULL
        XCTAssertFalse(USMarketHolidays.isHalfDay(year: 2026, month: 7, day: 15))
    }

    func test_plainDayIsNoHoliday() {
        XCTAssertNil(USMarketHolidays.fullHoliday(year: 2026, month: 7, day: 15))
    }
}
```

`MarketCalendarTests.swift` additions (build ET dates the way the file's existing tests do — reuse its date-construction helper):

```swift
    func test_thanksgivingMiddayIsClosed() { /* 2026-11-26 12:00 ET -> .closed */ }
    func test_halfDayClosesAtOnePm() { /* 2026-11-27 12:59 ET -> .open; 13:00 ET -> .closed */ }
    func test_plainWednesdayStaysOpen() { /* 2026-07-15 12:00 ET -> .open */ }
    func test_holidayLookup() { /* holiday(on: 2026-11-26 midday) == .thanksgiving; isHalfDay(on: 2026-11-27) == true */ }
```

- [ ] **Step 2: Run** `DEVELOPER_DIR=/Applications/Xcode.app swift test --filter USMarketHolidays 2>&1 | tail -5` — compilation FAILS.

- [ ] **Step 3: Implement `USMarketHolidays.swift`** — the Kotlin twin on pure Int math (no Foundation Calendar; identical algorithms):

```swift
import Foundation

/// The ten NYSE full-closure holidays. Twin of shared/commonMain USMarketHolidays.kt —
/// keep the rule tables in lockstep.
public enum USMarketHoliday: Sendable, Equatable {
    case newYearsDay, martinLutherKingDay, washingtonsBirthday, goodFriday, memorialDay
    case juneteenth, independenceDay, laborDay, thanksgiving, christmas
}

/// Pure, computed US market holiday rules on Hinnant civil-date math — valid for any
/// year. Inputs are civil dates in market-local (ET) wall-clock terms; MarketCalendar
/// supplies those from a Date. See the Kotlin twin for the rule commentary.
public enum USMarketHolidays {

    public static func fullHoliday(year: Int, month: Int, day: Int) -> USMarketHoliday? {
        holidays(year: year)[daysFromCivil(year, month, day)]
    }

    public static func isHalfDay(year: Int, month: Int, day: Int) -> Bool {
        let epochDay = daysFromCivil(year, month, day)
        if holidays(year: year)[epochDay] != nil { return false }
        let weekday = isoWeekday(epochDay)
        guard (1...5).contains(weekday) else { return false }
        let dayAfterThanksgiving = nthWeekday(year: year, month: 11, isoWeekday: 4, n: 4) + 1
        if epochDay == dayAfterThanksgiving { return true }
        return epochDay == daysFromCivil(year, 7, 3) || epochDay == daysFromCivil(year, 12, 24)
    }

    private static func holidays(year: Int) -> [Int: USMarketHoliday] {
        var map: [Int: USMarketHoliday] = [:]
        map[observed(daysFromCivil(year, 1, 1))] = .newYearsDay
        map[nthWeekday(year: year, month: 1, isoWeekday: 1, n: 3)] = .martinLutherKingDay
        map[nthWeekday(year: year, month: 2, isoWeekday: 1, n: 3)] = .washingtonsBirthday
        map[easterSunday(year) - 2] = .goodFriday
        map[lastWeekday(year: year, month: 5, isoWeekday: 1)] = .memorialDay
        map[observed(daysFromCivil(year, 6, 19))] = .juneteenth
        map[observed(daysFromCivil(year, 7, 4))] = .independenceDay
        map[nthWeekday(year: year, month: 9, isoWeekday: 1, n: 1)] = .laborDay
        map[nthWeekday(year: year, month: 11, isoWeekday: 4, n: 4)] = .thanksgiving
        map[observed(daysFromCivil(year, 12, 25))] = .christmas
        return map
    }

    private static func observed(_ epochDay: Int) -> Int {
        switch isoWeekday(epochDay) {
        case 6: return epochDay - 1
        case 7: return epochDay + 1
        default: return epochDay
        }
    }

    private static func nthWeekday(year: Int, month: Int, isoWeekday target: Int, n: Int) -> Int {
        let first = daysFromCivil(year, month, 1)
        let delta = mod(target - isoWeekday(first), 7)
        return first + delta + (n - 1) * 7
    }

    private static func lastWeekday(year: Int, month: Int, isoWeekday target: Int) -> Int {
        let nextFirst = month == 12 ? daysFromCivil(year + 1, 1, 1) : daysFromCivil(year, month + 1, 1)
        let last = nextFirst - 1
        return last - mod(isoWeekday(last) - target, 7)
    }

    private static func easterSunday(_ year: Int) -> Int {
        let a = year % 19, b = year / 100, c = year % 100
        let d = b / 4, e = b % 4
        let f = (b + 8) / 25, g = (b - f + 1) / 3
        let h = (19 * a + b - d - g + 15) % 30
        let i = c / 4, k = c % 4
        let l = (32 + 2 * e + 2 * i - h - k) % 7
        let m = (a + 11 * h + 22 * l) / 451
        let month = (h + l - 7 * m + 114) / 31
        let day = (h + l - 7 * m + 114) % 31 + 1
        return daysFromCivil(year, month, day)
    }

    private static func mod(_ a: Int, _ b: Int) -> Int { ((a % b) + b) % b }

    private static func daysFromCivil(_ y0: Int, _ m: Int, _ d: Int) -> Int {
        let y = m <= 2 ? y0 - 1 : y0
        let era = Int((Double(y) / 400.0).rounded(.down))
        let yoe = y - era * 400
        let mp = m > 2 ? m - 3 : m + 9
        let doy = (153 * mp + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private static func isoWeekday(_ epochDay: Int) -> Int { mod(epochDay + 3, 7) + 1 }
}
```

> Replace the `Double`-based era computation with pure-integer floored division (`let era = y >= 0 ? y / 400 : (y - 399) / 400`) — floating point in date math is a bug farm; the line above is a reminder of the pitfall, not the implementation.

- [ ] **Step 4: Make Swift `MarketCalendar` holiday-aware.** In `status(at:)`, extract year/month/day components alongside the existing weekday/hour/minute pull (`calendar.dateComponents([.year, .month, .day, .weekday, .hour, .minute], from: date)`), then after the weekday guard:

```swift
        if USMarketHolidays.fullHoliday(year: comps.year ?? 0, month: comps.month ?? 0, day: comps.day ?? 0) != nil {
            return .closed
        }
        let closeMinute = USMarketHolidays.isHalfDay(year: comps.year ?? 0, month: comps.month ?? 0, day: comps.day ?? 0)
            ? 13 * 60 : 16 * 60
```

Add the public helpers:

```swift
    /// The full-closure holiday on `date`'s market-local day, or nil on trading days.
    public func holiday(on date: Date) -> USMarketHoliday? {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return USMarketHolidays.fullHoliday(year: c.year ?? 0, month: c.month ?? 0, day: c.day ?? 0)
    }

    /// True when the market closes at 13:00 ET on `date`'s market-local day.
    public func isHalfDay(on date: Date) -> Bool {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return USMarketHolidays.isHalfDay(year: c.year ?? 0, month: c.month ?? 0, day: c.day ?? 0)
    }
```

Update the type's KDoc (delete the "intentionally not modeled" paragraph).

- [ ] **Step 5: Run** `DEVELOPER_DIR=/Applications/Xcode.app swift test 2>&1 | grep -E 'Executed|error|failed' | tail -4` — full macOS suite PASS (planner/coordinator tests use plain weekdays; fix any test DATE that accidentally hits a holiday, never the calendar).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeDomain Tests/APTradeDomainTests
git commit -m "feat(domain): computed US market holidays + holiday-aware MarketCalendar (Swift twin)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 11: Swift earnings application layer + planner + settings

**Files:**
- Create: `Sources/APTradeDomain/EarningsEvent.swift`
- Create: `Sources/APTradeDomain/SP500Symbols.swift` (generated — same script as Task 2 Step 1 with a Swift template: `public enum SP500Symbols { public static let set: Set<String> = [...] }`; reuse the fetched ticker list from Task 2, do NOT refetch)
- Modify: `Sources/APTradeApplication/Ports.swift` (EarningsCalendarRepository protocol)
- Create: `Sources/APTradeApplication/EarningsUseCases.swift`
- Modify: `Sources/APTradeApplication/MarketActivityPlanner.swift` (earningsCheckDue + lastEarningsDay)
- Modify: Swift `AppSettings` (find via `grep -rn 'struct AppSettings' Sources/APTradeDomain`) — add `earningsReports: Bool = true`; check its `Codable` conformance handles missing keys (the `language` field precedent) and mirror that mechanism
- Test: `Tests/APTradeApplicationTests/EarningsUseCasesTests.swift`; modify `Tests/APTradeApplicationTests/MarketActivityPlannerTests.swift`

**Interfaces — produces:**
- `public struct EarningsEvent: Sendable, Equatable { public let symbol, companyName, day: String; public let session: EarningsSession; public let epsEstimate, epsActual: Double? }` + `public enum EarningsSession: Sendable, Equatable { case beforeOpen, afterClose, duringMarket, unknown }` (public memberwise init).
- `public protocol EarningsCalendarRepository: Sendable { func earnings(fromDay: String, toDay: String) async throws -> [EarningsEvent] }`
- `public struct FetchEarningsCalendarUseCase` with `execute(fromDay:toDay:) async -> [EarningsEvent]`, `nextEarnings(symbol:fromDay:toDay:) async -> EarningsEvent?`, `ownedToday(day:) async -> [EarningsEvent]` — same semantics as Kotlin Task 2 (S&P ∪ own filter, own-first ordering, dot/dash normalization, failure → empty). `ownSymbols: @Sendable () async -> Set<String>` injected.
- `ScheduledNotification.earningsCheckDue` + `SchedulerState.lastEarningsDay: String?` + `AppSettings.earningsReports`.

- [ ] **Step 1: Failing tests** — transcribe the six Kotlin `EarningsUseCasesTest` cases (Task 2 Step 2) into XCTest with an inline fake repository, plus the two planner cases (Task 4 Step 1) into `MarketActivityPlannerTests` using its existing fixtures (open-market instant + `SchedulerState(lastStatus: .open)`). Run `swift test --filter Earnings` — FAILS.

- [ ] **Step 2: Implement.** `EarningsEvent.swift` and `EarningsUseCases.swift` are line-for-line semantic twins of Task 2's Kotlin (async/await instead of suspend; `normalized` = `uppercased().replacingOccurrences(of: "-", with: ".")`; sort with `sorted(by:)` composing day, own-membership, symbol). Planner: mirror the digest block —

```swift
        if status == .open {
            let day = calendar.tradingDay(of: now)
            if state.lastEarningsDay != day, settings.earningsReports {
                events.append(.earningsCheckDue)
                newState.lastEarningsDay = day
            }
        }
```

`SchedulerState` gains `public var lastEarningsDay: String?` with the init default; check `UserDefaultsSchedulerStateStore`'s Codable handling for the new optional (the `lastDigestDay` precedent).

- [ ] **Step 3: Run** the Application + Domain suites — PASS. Full `swift test` — PASS.

- [ ] **Step 4: Commit**

```bash
git add Sources Tests
git commit -m "feat(application): Swift earnings use case, SP500 filter, earningsCheckDue planner event

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 12: Swift `FinnhubEarningsRepository` + empty fallback

**Files:**
- Create: `Sources/APTradeInfrastructure/FinnhubEarningsDTO.swift`
- Create: `Sources/APTradeInfrastructure/FinnhubEarningsRepository.swift`
- Create: `Sources/APTradeInfrastructure/EmptyEarningsRepository.swift`
- Test: `Tests/APTradeInfrastructureTests/FinnhubEarningsMapperTests.swift`

**Interfaces:**
- Consumes: `EarningsCalendarRepository` (Task 11); `FinnhubNewsRepository.swift`'s URLSession/request/error idiom (open it, mirror exactly — base URL constant, query building, status-code mapping, `@unchecked Sendable` pattern).
- Produces: `public final class FinnhubEarningsRepository: EarningsCalendarRepository` (init `(apiKey: String, session: URLSession = .shared, now: @escaping () -> Date = Date.init, ttl: TimeInterval = 6 * 60 * 60)`, per-range TTL cache behind a lock — use the locking idiom `CachingMarketDataRepository` already uses); `public struct EmptyEarningsRepository: EarningsCalendarRepository` returning `[]`; `enum FinnhubEarningsMapper { static func events(from data: Data) -> [EarningsEvent] }`.

- [ ] **Step 1: Failing mapper tests** — transcribe Task 3 Step 1's two cases (same JSON sample, XCTest asserts: 4 events, sessions bmo/amc/dmh/unknown, NODATE dropped, empty `{}` → `[]`).
- [ ] **Step 2: Implement DTO + mapper** — `Decodable` structs, all-optional fields, `mapNotNull` equivalent (`compactMap`), identical drop rules to Kotlin.
- [ ] **Step 3: Implement repository + cache + empty fallback**, mirroring `FinnhubNewsRepository`'s request path with `/calendar/earnings?from=&to=&token=`. Add a cache test using the injected `now` + a `URLProtocol` stub only if the news tests already have one — otherwise test the cache by injecting a counting fake through a session-less seam: make the network call a `private let fetchData: (URL) async throws -> Data` closure with a URLSession default, and count closure invocations in the test (two `earnings()` calls, one fetch).
- [ ] **Step 4: Run** infra tests then full `swift test` — PASS.
- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeInfrastructure Tests/APTradeInfrastructureTests
git commit -m "feat(infrastructure): Swift FinnhubEarningsRepository with 6h TTL cache + empty fallback

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 13: Swift L10n + Calendar tab (macOS + iOS)

**Files:**
- Modify: `Sources/APTradeApp/L10n.swift` (22 keys, EN/DE/IT/ES — same names/values as Task 5, camelCased: `calendarTab, marketClosedBannerFmt, closesEarlyBannerFmt, holidayNewYears, holidayMLK, holidayWashington, holidayGoodFriday, holidayMemorial, holidayJuneteenth, holidayIndependence, holidayLabor, holidayThanksgiving, holidayChristmas, sessionBeforeOpen, sessionAfterClose, sessionDuringMarket, nextEarnings, earningsReportsToggle, earningsReportsSubtitle, earningsTodayTitle, earningsTodayBodyFmt, noUpcomingEarnings` — rawValue = the English string; format keys use `%@` per this catalog's convention, check an existing `*Format` key)
- Modify: `Sources/APTradeApp/RootView.swift` (Tab enum + switch + tabTitle)
- Create: `Sources/APTradeApp/CalendarView.swift`
- Create: `Sources/APTradeApp/CalendarViewModel.swift`
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (makeCalendarViewModel)
- Test: `Tests/APTradeAppTests/CalendarViewModelTests.swift`

**Interfaces:**
- Produces: `@MainActor @Observable final class CalendarViewModel` with `struct DayGroup: Identifiable { let day: String; let holiday: USMarketHoliday?; let isHalfDay: Bool; let events: [EarningsEvent] }`, `var days: [DayGroup]`, `var ownSymbols: Set<String>`, `var isLoading: Bool`, `let keyMissing: Bool`, `func load() async`; `CompositionRoot.makeCalendarViewModel() -> CalendarViewModel`.

- [ ] **Step 1: L10n keys.** Add the enum cases + the four-language dictionary entries (translate values identical to Task 5's tables; German/Italian/Spanish strings byte-equal to the Kotlin catalog). The existing `test_everyKeyHasAllFourLanguages_nonEmpty` covers them automatically.

- [ ] **Step 2: Failing ViewModel test** — three cases from Task 7 Step 1's contract, XCTest + fakes (`EarningsCalendarRepository` fake; the grouping logic lives IN this view model on the Swift side since `buildCalendarDays` is Kotlin-only — port its ~20 lines into `CalendarViewModel.load()` and test the same behaviors: holiday/half-day/eventful days survive, plain days collapse, failure → holiday-only).

- [ ] **Step 3: Implement `CalendarViewModel.swift`:**

```swift
import Foundation
import APTradeApplication
import APTradeDomain

@MainActor
@Observable
final class CalendarViewModel {
    struct DayGroup: Identifiable {
        let day: String
        let date: Date
        let holiday: USMarketHoliday?
        let isHalfDay: Bool
        let events: [EarningsEvent]
        var id: String { day }
    }

    private(set) var days: [DayGroup] = []
    private(set) var ownSymbols: Set<String> = []
    private(set) var isLoading = false
    let keyMissing: Bool

    private let fetchEarnings: FetchEarningsCalendarUseCase
    private let calendar: MarketCalendar
    private let loadOwnSymbols: @Sendable () async -> Set<String>
    private let now: () -> Date

    init(fetchEarnings: FetchEarningsCalendarUseCase,
         calendar: MarketCalendar = MarketCalendar(),
         loadOwnSymbols: @escaping @Sendable () async -> Set<String>,
         keyMissing: Bool,
         now: @escaping () -> Date = Date.init) {
        self.fetchEarnings = fetchEarnings
        self.calendar = calendar
        self.loadOwnSymbols = loadOwnSymbols
        self.keyMissing = keyMissing
        self.now = now
    }

    func load() async {
        isLoading = true
        defer { isLoading = false }
        let start = now()
        let window = (0..<14).map { start.addingTimeInterval(Double($0) * 86_400) }
        let events = await fetchEarnings.execute(
            fromDay: calendar.tradingDay(of: window[0]),
            toDay: calendar.tradingDay(of: window[13]))
        let byDay = Dictionary(grouping: events, by: \.day)
        ownSymbols = await loadOwnSymbols()
        days = window.compactMap { date in
            let day = calendar.tradingDay(of: date)
            let holiday = calendar.holiday(on: date)
            let half = calendar.isHalfDay(on: date)
            let dayEvents = byDay[day] ?? []
            guard holiday != nil || half || !dayEvents.isEmpty else { return nil }
            return DayGroup(day: day, date: date, holiday: holiday, isHalfDay: half, events: dayEvents)
        }
    }
}
```

> The 86 400-second stride is safe here because the day KEY comes from `calendar.tradingDay(of:)` (ET wall clock) each step — a DST hour shift cannot skip or double a calendar day over a 14-day window. Note this in a comment.

- [ ] **Step 4: Implement `CalendarView.swift`** — mirror `NewsView.swift`'s scaffold (takes `switcher: AnyView`, `@State private var viewModel = CompositionRoot.makeCalendarViewModel()`, `.task { await viewModel.load() }`). Structure per day group: date header (`sectionLabel` style), holiday banner (`RoundedRectangle` stroke `Theme.gold.opacity(0.4)`, `Theme.surface` fill, text `String(format: tr(.marketClosedBannerFmt), holidayName(holiday))`), earnings rows (`HStack`: gold `Circle().frame(width: 6)` when owned, symbol semibold, company/symbol secondary, `Spacer`, session chip via the `ChangePill`-adjacent quiet chip style, `est` value `.monospacedDigit()`). `holidayName(_:)`/`sessionLabel(_:)` are private `tr`-mapping funcs in this file (Swift twin of Task 7's). Key-missing → reuse `NewsView`'s empty-state block with `tr(.finnhubKeyInstructionsIOS)` on iOS / `tr(.finnhubKeyInstructions)` on macOS (exactly how NewsView chooses); holidays still listed above it. Empty-but-keyed → `tr(.noUpcomingEarnings)` centered.

- [ ] **Step 5: Tab.** `RootView.swift`: `enum Tab` gains `case calendar = "Calendar"`; the content `switch` gains `case .calendar: CalendarView(switcher: AnyView(switcher))`; `tabTitle` gains `case .calendar: return tr(.calendarTab)`. Check `PlatformLayout.swift`/the switcher pill row sizes four items on iPhone (the segmented-pill switcher was fitted for 3 tabs on iOS last increment — verify it accommodates 4 within 375 pt; if tight, apply the same minimal fix pattern used for the tablet segmented rows: allow the pills to compress with `.lineLimit(1)`/`minimumScaleFactor(0.8)` on the pill labels).

- [ ] **Step 6: CompositionRoot factory:**

```swift
    static func makeCalendarViewModel() -> CalendarViewModel {
        let key = AppConfig.finnhubAPIKey()
        let repo: EarningsCalendarRepository = key.map { FinnhubEarningsRepository(apiKey: $0) } ?? EmptyEarningsRepository()
        return CalendarViewModel(
            fetchEarnings: FetchEarningsCalendarUseCase(repository: repo, ownSymbols: ownSymbolsProvider()),
            loadOwnSymbols: ownSymbolsProvider(),
            keyMissing: key == nil)
    }

    /// watchlist ∪ portfolio symbols, read fresh per call.
    private static func ownSymbolsProvider() -> @Sendable () async -> Set<String> {
        { Set(LoadWatchlistUseCase(store: makeStore())().map(\.symbol))
            .union(FetchPortfolioUseCase(store: portfolioStore)().positions.map(\.asset.symbol)) }
    }
```

> Adapt the two use-case invocations to their real call shapes (check how `makeMarketActivityCoordinator` reads the watchlist and how portfolio VMs load positions — reuse those exact expressions; if `FetchPortfolioUseCase` is async, await it).

- [ ] **Step 7: Run** full `swift test` — PASS. **Commit:**

```bash
git add Sources Tests
git commit -m "feat(app): Calendar tab on macOS + iOS — holidays and S&P 500 earnings, 22 L10n keys

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 14: Swift detail stat + earnings notifications + settings toggle

**Files:**
- Modify: `Sources/APTradeApp/AssetDetailViewModel.swift` + `AssetDetailView.swift` (Next-earnings StatTile)
- Modify: `Sources/APTradeApplication/Ports.swift` (`MarketEventNotifier` gains `notifyEarnings`)
- Modify: `Sources/APTradeInfrastructure/UserNotificationAlertNotifier.swift` (find the type implementing `MarketEventNotifier` — `grep -rln 'MarketEventNotifier' Sources/APTradeInfrastructure`) — implement `notifyEarnings`
- Modify: `Sources/APTradeApp/MarketActivityCoordinator.swift` (earningsCheckDue handling)
- Modify: `Sources/APTradeApp/CompositionRoot.swift` (coordinator factory gains earnings use case)
- Modify: `Sources/APTradeApp/RootView.swift` (notifications page toggle row)
- Test: extend `Tests/APTradeAppTests/AssetDetailViewModelTests.swift` (or the VM's test home) + the coordinator/planner test that covers event dispatch

**Interfaces:**
- `MarketEventNotifier` gains `func notifyEarnings(title: String, body: String) async`.
- `MarketActivityCoordinator.init` gains `fetchOwnedEarningsToday: @Sendable (String) async -> [EarningsEvent]` (arg = today's `yyyy-MM-dd`); on `.earningsCheckDue` it calls it with `MarketCalendar().tradingDay(of: Date())`-equivalent (reuse the planner's calendar — add `calendar` param if not present) and notifies per event with `String(format: tr(.earningsTodayBodyFmt), event.symbol, sessionText)`.
- `AssetDetailViewModel` gains `private(set) var nextEarnings: EarningsEvent?` loaded in its init/`load()` via an injected `FetchEarningsCalendarUseCase?` (nil = keyless; stat shows "—").

- [ ] **Step 1: Failing tests** — (a) detail VM: fake repo with one AAPL event → `nextEarnings` populated; nil use case → stays nil. (b) coordinator: `.earningsCheckDue` from the planner (drive via state/settings) → `notifyEarnings` once per owned event; toggle off → planner never emits (already covered in Task 11 planner tests — the coordinator test only needs the dispatch wiring). Follow the existing coordinator test file's fake-notifier idiom.
- [ ] **Step 2: Implement** the four production changes. The detail StatTile goes in `AssetDetailView`'s key-stats grid beside its existing tiles:

```swift
            StatTile(label: tr(.nextEarnings),
                     value: viewModel.nextEarnings.map { "\($0.day) · \(sessionLabel($0.session))" } ?? "—")
```

(`sessionLabel` shared from CalendarView — move it to a small `EarningsLabels.swift` in APTradeApp if visibility demands.) The RootView notifications page gains, after the market open/close `toggleRow`:

```swift
                    toggleRow(icon: "chart.bar.doc.horizontal", title: tr(.earningsReportsToggle),
                              subtitle: tr(.earningsReportsSubtitle), isOn: $settingsVM.settings.earningsReports)
```

- [ ] **Step 3: Run** full `swift test` — PASS.
- [ ] **Step 4: Commit**

```bash
git add Sources Tests
git commit -m "feat(app): Next-earnings detail stat, earnings-day notifications, settings toggle (macOS + iOS)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 15: Close-out — all five suites, iOS verification, README, merge

- [ ] **Step 1: Rebuild the xcframework** (shared Kotlin changed — Swift links the new symbols even though it doesn't use them, the binary must be current):

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:assembleSharedReleaseXCFramework
```

- [ ] **Step 2: All Kotlin suites with forced rerun + XML-verified counts:**

```bash
./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest :androidApp:assembleDebug --rerun-tasks
for d in shared/build/test-results/jvmTest desktopApp/build/test-results/test androidApp/build/test-results/testDebugUnitTest; do
  grep -ho 'tests="[0-9]*"' $d/*.xml | grep -o '[0-9]*' | awk -v d=$d '{s+=$1} END {print d": "s}'
  grep -ho 'failures="[0-9]*"' $d/*.xml | grep -o '[0-9]*' | awk '{s+=$1} END {if (s>0) print "FAILURES: "s}'
done
```
Expected: counts strictly greater than the pre-branch baselines (shared 306 / desktop 212 / android 127), zero failures.

- [ ] **Step 3: macOS suite:** `DEVELOPER_DIR=/Applications/Xcode.app swift test` — expected: > 209 tests, 0 failures.

- [ ] **Step 4: iOS suite:** park `APTrade.xcodeproj` → `xcodebuild test -scheme APTradeLite-Package -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -skipPackagePluginValidation ARCHS=arm64` with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` → `** TEST SUCCEEDED **` → restore the xcodeproj.

- [ ] **Step 5: README.** New "Recently shipped" paragraph (market-holiday calendar closes the roadmap bullet — DELETE the "Market-holiday calendar for the scheduler" roadmap line per the roadmap-closeout convention; demote the previous recently-shipped to "Before that:"). Mention: computed NYSE holidays + half-days on both codebases, Calendar tab (S&P 500 + your symbols), Next-earnings stat, earnings-day notifications, Android's open/close + digest toggles now functional.

- [ ] **Step 6: Merge + push:**

```bash
git checkout main && git merge --no-ff feature/market-earnings-calendar -m "Merge feature/market-earnings-calendar: holiday-aware MarketCalendar + S&P 500 earnings calendar across all four platforms

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
git push origin main && git push gitlab main
git branch -d feature/market-earnings-calendar
```

- [ ] **Step 7: UAT notes for the user** (report, don't automate): Calendar tab on each platform; the July 3/Thanksgiving banner rows (temporarily fake the date range if outside a holiday window); a held symbol's earnings notification (inject a key, hold AAPL, set device clock — or trust the gating tests); half-day close notification appears at 13:00 ET only on the three half-days.

## Plan Self-Review (recorded)

- **Spec coverage:** holiday rules (T1/T10), earnings port+adapter (T2-3/T11-12), S&P filter (T2/T11), Calendar tab ×4 (T7/T9/T13), detail stat ×4 (T7/T9/T14), notifications + toggle ×4 (T6/T8/T14), L10n (T5/T13), suites+README+merge (T15). No spec section is uncovered.
- **Known intentional divergence:** Android gains a full coordinator (open/close + digest become functional) — spec section 4 implied it; T8 records it in the commit message.
- **Type consistency:** `EarningsEvent`/`EarningsSession`/`FetchEarningsCalendar(.execute/.nextEarnings/.ownedToday)`/`CalendarDay`/`buildCalendarDays`/`EarningsCheckDue`/`earningsReports` used identically across tasks; Swift twins use the camelCase forms declared in T10-T11.
- **Deliberate adapt-to-reality points** (not placeholders — the target files' idioms are authoritative and each names its source): coordinator string-localization split (T6), `trf` placeholder convention (T5), commonMain `floorDiv` (T1), Codable missing-key mechanism (T11), row-composable names in settings pages (T6/T8), NewsPane/NewsScreen empty-state reuse (T7/T9/T13).

