# M11.2 — Goals & Income Depth: Kotlin Shared Core + Windows Desktop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the three M11 features — a configurable starting balance, portfolio-level value/income goals, and a dividend calendar plus multi-year DRIP income forecast — on the Kotlin shared core and Windows desktop, transcribing SEMANTICS from the Swift as-built on `main` at `6faac85` while honouring every ruling in the M11 carry-notes.

**Architecture:** All domain math, goal persistence ports, and use cases land in `shared/commonMain` (adapters in `shared/jvmCommonMain`) so M11.3 Android reuses them verbatim; only view models and Compose panes are desktop-local. Swift `Sources/` is the semantic reference and MUST NOT be edited. Two deliberate, recorded divergences from Swift are introduced (a "Since inception" return that finally gives `startingCash` a reader, and an account-age history floor) — both must be comment-documented in Kotlin source following the M10.2 recorded-divergence precedent.

**Tech Stack:** Kotlin 2.1.0 Multiplatform, ionspin `bignum` BigDecimal, kotlinx-serialization, kotlinx-coroutines, Compose Desktop, JUnit/kotlin.test.

## Global Constraints (carry-notes, binding)

1. **`JAVA_HOME` is required and unset by default on this machine.** The verified working value is `/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home`. Do **not** use `/usr/libexec/java_home` — it only knows Corretto 11, which Gradle rejects. Every command in this plan writes it inline.
2. **Test commands:** `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest` and `JAVA_HOME=… ./gradlew :desktopApp:test`. Android must stay green where shared changes: `JAVA_HOME=… ./gradlew :androidApp:testDebugUnitTest`.
3. **Baselines measured from JUnit XML 2026-07-25: shared 612 · desktop 359 · android 282, all 0 failures.** Every task reports the real count it observed, not a copied number.
4. **`pricesBySymbol` is a REQUIRED parameter on `DividendMath.incomeForecast`, positioned immediately after `positions`, never defaulted and never trailing.** Omitting it reverts DRIP reinvestment to cost basis — a ~66% overstatement of year-30 income that no compiler will ever flag (carry-notes §1.1, §5).
5. **Both goal cards render unconditionally.** Neither the income-goal card nor the value-goal card may sit behind an empty-ledger, `isLoading`, or "report loaded" gate (carry-notes §1.3).
6. **The DRIP toggle rebuilds the forecast AND refreshes the income-goal projection.** A fix that rebuilds only the chart leaves the ETA stale against a curve that just changed (carry-notes §2.2). Task 12 owns this wire explicitly.
7. **The value-goal "current value" is never a fabricated zero.** When the equity curve is empty — which happens on any offline/rate-limited session, not just for an all-cash portfolio — it is cash + cost basis of all positions (carry-notes §2.3). Note `FetchPortfolioPerformance.execute` returns `emptyList()` for a position-less portfolio.
8. **The income-goal "current" equals forecast year 1** — the same `DividendMath.projectedAnnualIncome` sum (carry-notes §3.1).
9. **`incomeProjection` distinguishes "no data" from "not on track"** — the insufficient-history case is checked *before* the not-on-track fallthrough (carry-notes §2.4).
10. **The new calendar card is titled "Dividend Calendar"** — the pre-existing `L10n.Key.IncomeUpcomingTitle` ("Upcoming Dividends") stays on the existing next-payout list and must not be collided with (carry-notes §1.4).
11. **Goal targets validate against per-kind ranges** (income 100…1,000,000; value 1,000…100,000,000), never the starting-balance range. One shared parser, parameterized range (carry-notes §1.5).
12. **Reuse the cadence-interval constants** currently inlined in `DividendMath.nextProjected` (30/91/182/365 days). Extract to ONE private helper; do not add a second copy (carry-notes §3.8).
13. **Extend `IncomeSummaryMath`/`DividendMath` rather than deriving dividend math a fourth way** — the M10.3 hoist exists precisely to stop that.
14. Every new L10n key supplies all four languages (English, German, Italian, Spanish) with real idiomatic financial-register translations — not placeholders. A reviewer will judge the DE/IT/ES.
15. **`L10nCatalogTest` pins the key count at `assertEquals(389, L10n.Key.entries.size)` — it MUST be bumped**, along with the test-name parenthetical math and the running-tally header comment above it.
16. Kotlin has **no `Quantity` type** — use raw `com.ionspin.kotlin.bignum.decimal.BigDecimal` wherever Swift uses `Quantity`.
17. Kotlin has **no `SettingsStore` interface** — settings go through the concrete `FileSettingsStore`. A step saying "add a method to the SettingsStore interface" is invalid.
18. **ionspin `BigDecimal` implements `Comparable<Any>`, not `Comparable<BigDecimal>`** (verified by `javap` on `bignum-jvm-0.3.10.jar`). Consequence: `kotlin.ranges.rangeTo` and `kotlin.comparisons.coerceIn` do **not** resolve for it — `min..max` and `.coerceIn(a, b)` will not compile. Use an explicit `AmountRange(min, max)` value type and hand-written clamp helpers. The `<`/`>` operators DO work.
19. **`BigDecimal.pow` only accepts `Int`/`Long`** — fractional exponentiation must route through `Double` (`kotlin.math.pow` / `kotlin.math.ln`), exactly as Swift does. Comment it where it happens (carry-notes §4).
20. **`Portfolio.starting`'s parameter default and `AppSettings.defaultStartingCash`'s default are the ONLY two permitted `100000` literals.** Carry-notes §2.7: defaulted parameters and no-op test doubles are where hardcoded balances hide — Task 14 greps for both by content.
21. **No no-op goal-store defaults.** Goal use cases and `GoalStore` are required constructor parameters everywhere; a construction site that forgets one must fail to compile, not silently discard saves (carry-notes §4).
22. House rules: `tr()`/`trf()` for all copy; DK tokens only; gains green/losses red = data only; every mutating portfolio use case shares the ONE `portfolioMutex`; Compose UI composition ships without unit tests (standing waiver) — view models and math carry the behaviour. USD only. No new dependencies.
23. Branch: `feature/m11-2-goals-income-kotlin-desktop`. Every task ends with a commit. Do NOT touch `Sources/` (Swift) or `androidApp/` beyond the single compile-fix named in Task 4.
24. **Budget a top-tier whole-branch review at close** (carry-notes §5) — every one of §2.2/§2.3/§2.4/§3.4 lived *between* two tasks and no per-task review could have seen them.

---

## Task 1: `Portfolio.startingCash`, `Portfolio.starting(cash)`, and the inception helper

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/Portfolio.kt` (add `startingCash` constructor property; thread it through `buying`/`receivingDividend`/`selling`; add `inceptionEpochSeconds()`; add `DEFAULT_STARTING_CASH` + `starting(cash)`)
- Modify: `shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/FilePortfolioStore.kt` (`PortfolioDTO.startingCash` nullable + lenient decode + save)
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PortfolioStartingCashTest.kt` (new)
- Test: `shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/FilePortfolioStoreTest.kt` (extend)

**Interfaces:**
- Produces: `Portfolio(cash, positions, transactions, startingCash: Money = cash)`; `Portfolio.DEFAULT_STARTING_CASH: Money`; `Portfolio.starting(cash: Money = DEFAULT_STARTING_CASH): Portfolio`; `fun Portfolio.inceptionEpochSeconds(): Long?` (member function).

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/aptrade/shared/domain/PortfolioStartingCashTest.kt`:

```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M11.2 Task 1. Transcribed from the Swift as-built (`Sources/APTradeDomain/Portfolio.swift`),
 * plus the Kotlin-only `inceptionEpochSeconds` helper that Tasks 6 and 7 both consume so the
 * account-age floor and the since-inception metric cannot drift apart.
 */
class PortfolioStartingCashTest {
    private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

    @Test
    fun startingDefaultsToTheOnePermittedLiteral() {
        assertEquals(Money.usd("100000"), Portfolio.starting().cash)
        assertEquals(Money.usd("100000"), Portfolio.starting().startingCash)
        assertEquals(Money.usd("100000"), Portfolio.DEFAULT_STARTING_CASH)
    }

    @Test
    fun startingRecordsTheCallerSuppliedAmountAsBothCashAndStartingCash() {
        val portfolio = Portfolio.starting(Money.usd("25000"))
        assertEquals(Money.usd("25000"), portfolio.cash)
        assertEquals(Money.usd("25000"), portfolio.startingCash)
    }

    @Test
    fun startingCashDefaultsToCashWhenNotSuppliedToTheConstructor() {
        assertEquals(Money.usd("7500"), Portfolio(Money.usd("7500")).startingCash)
    }

    @Test
    fun buyingCarriesStartingCashForwardUnchanged() {
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("10"), Money.usd("100"), 1_000L, "txn-1")
        assertEquals(Money.usd("49000"), portfolio.cash)
        assertEquals(Money.usd("50000"), portfolio.startingCash)
    }

    @Test
    fun sellingCarriesStartingCashForwardUnchanged() {
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("10"), Money.usd("100"), 1_000L, "txn-1")
            .selling("AAPL", qty("10"), Money.usd("120"), 2_000L, "txn-2")
        assertEquals(Money.usd("50000"), portfolio.startingCash)
    }

    @Test
    fun receivingDividendCarriesStartingCashForwardUnchanged() {
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("10"), Money.usd("100"), 1_000L, "txn-1")
            .receivingDividend("txn-2", "AAPL", Money.usd("0.25"), qty("10"), 3_000L)
        assertEquals(Money.usd("50000"), portfolio.startingCash)
    }

    @Test
    fun inceptionEpochSecondsIsNullForAnUntradedPortfolio() {
        assertNull(Portfolio.starting().inceptionEpochSeconds())
    }

    @Test
    fun inceptionEpochSecondsIsTheEarliestTransactionEpoch() {
        val portfolio = Portfolio.starting()
            .buying(aapl, qty("1"), Money.usd("100"), 5_000L, "txn-1")
            .buying(aapl, qty("1"), Money.usd("100"), 2_000L, "txn-2")
        assertEquals(2_000L, portfolio.inceptionEpochSeconds())
    }
}
```

Extend `shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/FilePortfolioStoreTest.kt` with these two cases (keep the file's existing temp-file idiom and its existing imports; add `kotlin.io.path.writeText` if absent):

```kotlin
    @Test
    fun startingCashRoundTrips() = runBlocking {
        val file = Files.createTempDirectory("aptrade-portfolio").resolve("portfolio.json")
        val store = FilePortfolioStore(file)
        store.save(Portfolio.starting(Money.usd("25000")))
        assertEquals(Money.usd("25000"), store.load()?.startingCash)
    }

    @Test
    fun aPreM11PayloadWithNoStartingCashKeyDecodesStartingCashAsCash() = runBlocking {
        val file = Files.createTempDirectory("aptrade-portfolio").resolve("portfolio.json")
        // Byte-shape of a payload written before `startingCash` existed: no such key at all.
        file.writeText(
            """{"cash":{"amount":"12345","currency":"USD"},"positions":[],"transactions":[]}""",
        )
        val loaded = FilePortfolioStore(file).load()
        assertEquals(Money.usd("12345"), loaded?.cash)
        assertEquals(Money.usd("12345"), loaded?.startingCash)
    }
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest --tests "*PortfolioStartingCash*" --tests "*FilePortfolioStore*"
```
Expected: **compilation failure** — `startingCash`, `DEFAULT_STARTING_CASH`, `starting(Money)`, and `inceptionEpochSeconds()` do not exist.

- [ ] **Step 3: Implement**

In `Portfolio.kt`, change the data-class header to:

```kotlin
data class Portfolio(
    val cash: Money,
    val positions: List<Position> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    /** The cash this portfolio OPENED with — fixed at reset time, never moved by a trade.
     *
     *  RECORDED DIVERGENCE FROM SWIFT (M11.2 kickoff decision 4a.1, carry-notes §2.1): the Swift
     *  twin carries this field with NO reader — total return there derives from the equity
     *  curve's own first point, and the reset flow reads `AppSettings.defaultStartingCash`.
     *  Kotlin ports it AND gives it a real consumer: `PerformanceMetrics.sinceInceptionReturn`
     *  (see `FetchPerformanceReport`), which measures return against the balance the user
     *  actually chose rather than against whatever value the priced curve happened to open at.
     *  A $10k practice run and a $1M one must not both report return against the same baseline.
     *  This is a Swift BACKPORT CANDIDATE once the metric proves out here.
     *
     *  Defaults to [cash] so every existing three-argument construction site keeps its meaning:
     *  a portfolio built with no explicit opening balance opened at its current cash. */
    val startingCash: Money = cash,
) {
```

Add `startingCash = startingCash` to the `Portfolio(...)` construction inside `buying`, `receivingDividend`, and `selling` (three sites — the Swift twin does the same at its `:92`, `:110`, `:146`).

Add, next to `positionFor`:

```kotlin
    /** Epoch-seconds of the EARLIEST transaction — the account's inception instant — or `null`
     *  when nothing has ever traded.
     *
     *  ONE named derivation, deliberately: `FetchPortfolioPerformance`'s `sinceInception` trim
     *  (M11.2 Task 7) and `GoalMath`'s account-age history floor (Task 6) both need exactly this
     *  signal, and the M11 carry-notes require they cannot drift apart. Every consumer calls
     *  here; nobody re-derives `transactions.minOfOrNull { it.epochSeconds }` locally. */
    fun inceptionEpochSeconds(): Long? = transactions.minOfOrNull { it.epochSeconds }
```

Replace the companion object with:

```kotlin
    companion object {
        /** The ONE hardcoded opening balance in the shared core (carry-notes §2.7 permits exactly
         *  two literals: this one, and `AppSettings.defaultStartingCash`'s default). Named rather
         *  than inlined so a repo grep for a stray balance finds every real call site instead of
         *  a scatter of bare `100000`s. */
        val DEFAULT_STARTING_CASH: Money = Money.usd("100000")

        /** A fresh paper portfolio opened at [cash], with [startingCash] recorded to match. */
        fun starting(cash: Money = DEFAULT_STARTING_CASH): Portfolio =
            Portfolio(cash = cash, startingCash = cash)
    }
```

In `FilePortfolioStore.kt`, extend the DTO and both directions:

```kotlin
    @Serializable
    private data class PortfolioDTO(
        val cash: MoneyDTO,
        val positions: List<PositionDTO>,
        val transactions: List<TransactionDTO>,
        /** Nullable + defaulted so a payload written before M11.2 (no such key) still decodes;
         *  it falls back to `cash` below, matching `Portfolio`'s own constructor default rather
         *  than failing the whole load the way an unknown enum does. */
        val startingCash: MoneyDTO? = null,
    )
```

In `load()`, after `cash` is built:

```kotlin
            val startingCash = dto.startingCash?.let {
                Money(amount = BigDecimal.parseString(it.amount), currencyCode = it.currency)
            } ?: cash
```
and return `Portfolio(cash, positions, transactions, startingCash)`.

In `save()`, add to the `PortfolioDTO(...)` literal:

```kotlin
            startingCash = MoneyDTO(
                portfolio.startingCash.amount.toStringExpanded(),
                portfolio.startingCash.currencyCode,
            ),
```

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test :androidApp:testDebugUnitTest
```
Expected: shared PASS at 612 + 10 = **622**; desktop **359** and android **282** unchanged-green (nothing calls the new members yet, and every existing `Portfolio(...)`/`Portfolio.starting()` call keeps compiling because both additions are defaulted). Report the counts you actually observe.

- [ ] **Step 5: Commit**

```
git checkout -b feature/m11-2-goals-income-kotlin-desktop
git add shared/src/commonMain/kotlin/com/aptrade/shared/domain/Portfolio.kt \
        shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/FilePortfolioStore.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/domain/PortfolioStartingCashTest.kt \
        shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/FilePortfolioStoreTest.kt
git commit -m "feat(shared): record starting cash on Portfolio with lenient decode

Adds Portfolio.startingCash (defaulting to cash), a caller-supplied
Portfolio.starting(cash), and the single named inceptionEpochSeconds()
derivation that Tasks 6 and 7 both consume.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: `AppSettings.defaultStartingCash` + `FileSettingsStore` persistence

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/settings/AppSettings.kt`
- Modify: `shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/FileSettingsStore.kt`
- Test: `shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/FileSettingsStoreTest.kt` (extend)

**Interfaces:**
- Consumes: `Portfolio.DEFAULT_STARTING_CASH` (Task 1).
- Produces: `AppSettings.defaultStartingCash: Money`.

- [ ] **Step 1: Write the failing test**

Append to `FileSettingsStoreTest.kt` (reuse the file's existing temp-dir/`runBlocking` idiom; add `import com.aptrade.shared.domain.Money` and `import kotlin.io.path.writeText` if absent):

```kotlin
    @Test
    fun defaultStartingCashDefaultsToOneHundredThousand() {
        assertEquals(Money.usd("100000"), AppSettings().defaultStartingCash)
    }

    @Test
    fun defaultStartingCashRoundTrips() = runBlocking {
        val file = Files.createTempDirectory("aptrade-settings").resolve("settings.json")
        val store = FileSettingsStore(file)
        store.save(AppSettings(defaultStartingCash = Money.usd("250000")))
        assertEquals(Money.usd("250000"), store.load().defaultStartingCash)
    }

    @Test
    fun aPreM11SettingsFileWithNoStartingCashKeyLoadsTheDefault() = runBlocking {
        val file = Files.createTempDirectory("aptrade-settings").resolve("settings.json")
        file.writeText("""{"accent":"ChampagneGold","dripEnabled":true}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(Money.usd("100000"), loaded.defaultStartingCash)
        assertEquals(true, loaded.dripEnabled)
    }

    @Test
    fun anUnparseableStartingCashDegradesToTheDefaultWithoutFailingTheWholeBlob() = runBlocking {
        val file = Files.createTempDirectory("aptrade-settings").resolve("settings.json")
        file.writeText("""{"accent":"ChampagneGold","defaultStartingCash":"not-a-number","dripEnabled":true}""")
        val loaded = FileSettingsStore(file).load()
        assertEquals(Money.usd("100000"), loaded.defaultStartingCash)
        // Field-level, NOT whole-blob: the neighbouring preference survives.
        assertEquals(true, loaded.dripEnabled)
    }
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest --tests "*FileSettingsStore*"
```
Expected: **compilation failure** — `AppSettings.defaultStartingCash` does not exist.

- [ ] **Step 3: Implement**

In `AppSettings.kt`, add the import `com.aptrade.shared.domain.Money` and `com.aptrade.shared.domain.Portfolio`, extend the KDoc with a paragraph, and add the field as the LAST constructor property:

```kotlin
 *  defaultStartingCash (M11.2 Task 2) mirrors macOS `AppSettings.defaultStartingCash`: the
 *  opening balance the reset flow pre-fills and, when the user does not override it, resets to.
 *  Defaults to [Portfolio.DEFAULT_STARTING_CASH] — one of exactly two permitted hardcoded
 *  balances in this codebase (the other is `Portfolio.starting`'s own parameter default). */
data class AppSettings(
    …
    val dividendNotifications: Boolean = true,
    val defaultStartingCash: Money = Portfolio.DEFAULT_STARTING_CASH,
)
```

In `FileSettingsStore.kt`, add to `SettingsDTO`:

```kotlin
        /** Plain decimal text, the same lossless convention `FilePortfolioStore.MoneyDTO` uses.
         *  USD-only per the milestone constraint, so no currency code is persisted. */
        val defaultStartingCash: String = DEFAULT_STARTING_CASH_TEXT,
```

and above the class body's `json` field:

```kotlin
    private companion object {
        /** Serialized form of [Portfolio.DEFAULT_STARTING_CASH]; kept as text so the DTO stays a
         *  pure @Serializable value with no BigDecimal serializer. */
        const val DEFAULT_STARTING_CASH_TEXT = "100000"
    }
```

In `load()`, inside the `AppSettings(...)` literal:

```kotlin
                // Field-level lenient decode (same family as `language` above, NOT the whole-blob
                // fallback `accent` triggers): an unparseable amount is one bad preference, not a
                // reason to discard the user's notification and security choices.
                defaultStartingCash = runCatching { BigDecimal.parseString(dto.defaultStartingCash) }
                    .getOrNull()
                    ?.let { Money(it, "USD") }
                    ?: Portfolio.DEFAULT_STARTING_CASH,
```

In `save()`, inside the `SettingsDTO(...)` literal:

```kotlin
            defaultStartingCash = settings.defaultStartingCash.amount.toStringExpanded(),
```

Add the imports `com.aptrade.shared.domain.BigDecimal`-free set: `com.aptrade.shared.domain.Money`, `com.aptrade.shared.domain.Portfolio`, `com.ionspin.kotlin.bignum.decimal.BigDecimal`.

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test :androidApp:testDebugUnitTest
```
Expected: shared **626** (622 + 4) PASS; desktop 359 and android 282 unchanged-green. Report observed counts.

- [ ] **Step 5: Commit**

```
git add shared/src/commonMain/kotlin/com/aptrade/shared/settings/AppSettings.kt \
        shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/FileSettingsStore.kt \
        shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/FileSettingsStoreTest.kt
git commit -m "feat(shared): add defaultStartingCash preference with field-level lenient decode

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: Goal domain type, amount parser, `GoalStore` port, use cases, file adapter

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PortfolioGoal.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/AmountInput.kt`
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/application/GoalStore.kt` (port + `LoadGoals`/`SaveGoal`/`RemoveGoal`)
- Create: `shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/FileGoalStore.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/AmountInputTest.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/GoalUseCasesTest.kt`
- Test: `shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/FileGoalStoreTest.kt`

**Interfaces:**
- Produces:
  - `enum class GoalKind { Value, Income }`, `data class PortfolioGoal(kind, target: Money, createdAtEpochSeconds: Long)`, `val GoalKind.targetRange: AmountRange`
  - `data class AmountRange(val min: BigDecimal, val max: BigDecimal)`, `object AmountInput { val STARTING_BALANCE_RANGE: AmountRange; fun parse(text: String, range: AmountRange): Money? }`
  - `interface GoalStore { suspend fun load(): List<PortfolioGoal>; suspend fun save(goals: List<PortfolioGoal>) }`
  - `class LoadGoals(store)` → `suspend fun execute(): List<PortfolioGoal>`; `class SaveGoal(store)` → `suspend fun execute(goal: PortfolioGoal)`; `class RemoveGoal(store)` → `suspend fun execute(kind: GoalKind)`
  - `class FileGoalStore(file: Path) : GoalStore`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/aptrade/shared/domain/AmountInputTest.kt`:

```kotlin
package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M11.2 Task 3. Kotlin twin of `Sources/APTradeApp/StartingBalanceInput.swift`, with TWO
 * deliberate divergences documented on [AmountInput] itself: the range is REQUIRED (Swift
 * defaults it, which is how the starting-balance bounds ended up policing goal targets —
 * carry-notes §1.5), and parsing is locale-INDEPENDENT (Kotlin commonMain has no NumberFormat,
 * and Swift's locale-aware parser has a recorded round-trip gap — carry-notes §4).
 */
class AmountInputTest {
    private val goalIncome = GoalKind.Income.targetRange
    private val goalValue = GoalKind.Value.targetRange
    private val balance = AmountInput.STARTING_BALANCE_RANGE

    @Test
    fun parsesPlainDigits() {
        assertEquals(Money.usd("25000"), AmountInput.parse("25000", balance))
    }

    @Test
    fun stripsGroupingSeparatorsAndWhitespace() {
        assertEquals(Money.usd("1250000"), AmountInput.parse(" 1,250,000 ", balance))
    }

    @Test
    fun acceptsASingleDecimalPoint() {
        assertEquals(Money.usd("1500.50"), AmountInput.parse("1500.50", balance))
    }

    @Test
    fun normalisesALeadingOrTrailingDecimalPoint() {
        val tiny = AmountRange(BigDecimal.parseString("0"), BigDecimal.parseString("1"))
        assertEquals(Money.usd("1500"), AmountInput.parse("1500.", balance))
        assertEquals(Money.usd("0.5"), AmountInput.parse(".5", tiny))
    }

    @Test
    fun rejectsEmptyGarbageAndMultipleDecimalPoints() {
        assertNull(AmountInput.parse("", balance))
        assertNull(AmountInput.parse("   ", balance))
        assertNull(AmountInput.parse("abc", balance))
        assertNull(AmountInput.parse("1.2.3", balance))
        assertNull(AmountInput.parse("-5000", balance))
        assertNull(AmountInput.parse(".", balance))
    }

    @Test
    fun enforcesTheSuppliedRangeInclusively() {
        assertEquals(Money.usd("1000"), AmountInput.parse("1000", balance))
        assertEquals(Money.usd("10000000"), AmountInput.parse("10000000", balance))
        assertNull(AmountInput.parse("999", balance))
        assertNull(AmountInput.parse("10000001", balance))
    }

    /** Carry-notes §1.5: a "$50/month in dividends" goal is $600/yr — an entirely ordinary
     *  first goal that the starting-balance range made unsettable. */
    @Test
    fun anIncomeGoalUnderOneThousandIsSettable() {
        assertEquals(Money.usd("600"), AmountInput.parse("600", goalIncome))
        assertNull(AmountInput.parse("600", balance))
    }

    /** …and the same ruling's other half: a value goal past $10M must be settable. */
    @Test
    fun aValueGoalAboveTenMillionIsSettable() {
        assertEquals(Money.usd("50000000"), AmountInput.parse("50000000", goalValue))
        assertNull(AmountInput.parse("50000000", balance))
    }

    @Test
    fun perKindRangesAreTheExactRuledBounds() {
        assertEquals(Money.usd("100"), AmountInput.parse("100", goalIncome))
        assertEquals(Money.usd("1000000"), AmountInput.parse("1000000", goalIncome))
        assertNull(AmountInput.parse("99", goalIncome))
        assertNull(AmountInput.parse("1000001", goalIncome))
        assertEquals(Money.usd("1000"), AmountInput.parse("1000", goalValue))
        assertEquals(Money.usd("100000000"), AmountInput.parse("100000000", goalValue))
        assertNull(AmountInput.parse("999", goalValue))
        assertNull(AmountInput.parse("100000001", goalValue))
    }
}
```

Add `import com.ionspin.kotlin.bignum.decimal.BigDecimal` to that test file.

Create `shared/src/commonTest/kotlin/com/aptrade/shared/application/GoalUseCasesTest.kt`:

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M11.2 Task 3. Semantics transcribed from `Sources/APTradeApplication/GoalUseCases.swift`. */
class GoalUseCasesTest {

    private class MemoryGoalStore(var goals: List<PortfolioGoal> = emptyList()) : GoalStore {
        var saveCount = 0
        override suspend fun load(): List<PortfolioGoal> = goals
        override suspend fun save(goals: List<PortfolioGoal>) {
            saveCount += 1
            this.goals = goals
        }
    }

    @Test
    fun loadReturnsWhateverTheStoreHolds() = runTest {
        val goal = PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_000L)
        assertEquals(listOf(goal), LoadGoals(MemoryGoalStore(listOf(goal))).execute())
    }

    @Test
    fun saveUpsertsByKindSoOnlyOneGoalPerKindSurvives() = runTest {
        val store = MemoryGoalStore()
        SaveGoal(store).execute(PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_000L))
        SaveGoal(store).execute(PortfolioGoal(GoalKind.Value, Money.usd("750000"), 2_000L))
        assertEquals(1, store.goals.size)
        assertEquals(Money.usd("750000"), store.goals.single().target)
    }

    @Test
    fun saveKeepsTheOtherKindIntact() = runTest {
        val store = MemoryGoalStore()
        SaveGoal(store).execute(PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_000L))
        SaveGoal(store).execute(PortfolioGoal(GoalKind.Income, Money.usd("6000"), 2_000L))
        assertEquals(2, store.goals.size)
        assertTrue(store.goals.any { it.kind == GoalKind.Value })
        assertTrue(store.goals.any { it.kind == GoalKind.Income })
    }

    @Test
    fun removeDropsOnlyTheNamedKind() = runTest {
        val store = MemoryGoalStore(
            listOf(
                PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_000L),
                PortfolioGoal(GoalKind.Income, Money.usd("6000"), 2_000L),
            ),
        )
        RemoveGoal(store).execute(GoalKind.Value)
        assertEquals(GoalKind.Income, store.goals.single().kind)
    }
}
```

Create `shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/FileGoalStoreTest.kt`:

```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M11.2 Task 3. Mirrors `FilePortfolioStore`'s atomic-write / lenient-fallback discipline. */
class FileGoalStoreTest {

    @Test
    fun missingFileLoadsAnEmptyList() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        assertTrue(FileGoalStore(file).load().isEmpty())
    }

    @Test
    fun goalsRoundTrip() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        val store = FileGoalStore(file)
        val goals = listOf(
            PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1_700_000_000L),
            PortfolioGoal(GoalKind.Income, Money.usd("6000"), 1_700_000_100L),
        )
        store.save(goals)
        assertEquals(goals, store.load())
    }

    @Test
    fun corruptFileLoadsAnEmptyListRatherThanThrowing() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        file.writeText("{ not json at all")
        assertTrue(FileGoalStore(file).load().isEmpty())
    }

    @Test
    fun anUnknownGoalKindLoadsAnEmptyList() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        file.writeText("""[{"kind":"Retirement","target":{"amount":"1","currency":"USD"},"createdAtEpochSeconds":1}]""")
        assertTrue(FileGoalStore(file).load().isEmpty())
    }

    @Test
    fun savingAnEmptyListClearsPersistedGoals() = runBlocking {
        val file = Files.createTempDirectory("aptrade-goals").resolve("goals.json")
        val store = FileGoalStore(file)
        store.save(listOf(PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1L)))
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest --tests "*AmountInput*" --tests "*GoalUseCases*" --tests "*FileGoalStore*"
```
Expected: **compilation failure** — none of `GoalKind`, `PortfolioGoal`, `AmountInput`, `AmountRange`, `GoalStore`, `LoadGoals`, `SaveGoal`, `RemoveGoal`, `FileGoalStore` exist.

- [ ] **Step 3: Implement**

Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/PortfolioGoal.kt`:

```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** What a goal measures. */
enum class GoalKind {
    /** Total portfolio value (holdings + cash). */
    Value,

    /** Projected annual dividend income. */
    Income,
}

/** A user-set target for the WHOLE portfolio. At most one per [GoalKind] — enforcing that is
 *  `SaveGoal`'s job, not the adapter's. The user sets an amount; the app projects when it will
 *  be reached. Transcribed from `Sources/APTradeDomain/PortfolioGoal.swift`; Swift's `createdAt:
 *  Date` becomes epoch-seconds, the convention every other date in the Kotlin core uses. */
data class PortfolioGoal(
    val kind: GoalKind,
    val target: Money,
    val createdAtEpochSeconds: Long,
)

/** Per-kind goal-target validation bounds (carry-notes §1.5, BINDING).
 *
 *  [AmountInput.STARTING_BALANCE_RANGE] describes a portfolio's OPENING CASH. The Swift wave
 *  initially reused it for both goal kinds, which made an ordinary income goal like "$50/month
 *  in dividends" ($600/yr) unsettable and capped a value goal at $10M even though a value goal
 *  is meant to run far past a starting balance. Each kind gets a range sized to what it actually
 *  measures, and each supplies its own hint copy so the field never describes another quantity. */
val GoalKind.targetRange: AmountRange
    get() = when (this) {
        GoalKind.Income -> AmountRange(BigDecimal.parseString("100"), BigDecimal.parseString("1000000"))
        GoalKind.Value -> AmountRange(BigDecimal.parseString("1000"), BigDecimal.parseString("100000000"))
    }
```

Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/AmountInput.kt`:

```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal

/** An inclusive amount interval.
 *
 *  Deliberately NOT `ClosedRange<BigDecimal>`: ionspin's `BigDecimal` implements
 *  `Comparable<Any>`, not `Comparable<BigDecimal>`, so Kotlin's `rangeTo`/`coerceIn`
 *  (`<T : Comparable<T>>`) do not resolve for it and `min..max` will not compile. The `<`/`>`
 *  operators DO work, which is all [contains] needs. */
data class AmountRange(val min: BigDecimal, val max: BigDecimal) {
    fun contains(value: BigDecimal): Boolean = value >= min && value <= max
}

/**
 * Parses and range-validates a money amount typed into a field. Pure — no framework, no locale
 * object, usable from every platform's UI layer.
 *
 * Kotlin twin of `Sources/APTradeApp/StartingBalanceInput.swift`, with TWO recorded divergences:
 *
 * 1. **[range] is REQUIRED, not defaulted.** Swift defaults it to the starting-balance bounds,
 *    which is exactly how those bounds ended up policing goal targets (carry-notes §1.5). A
 *    defaulted range whose omission silently validates the wrong quantity is a re-armed bug with
 *    a compiler that will never complain, so every caller names its range.
 * 2. **Parsing is locale-INDEPENDENT.** `commonMain` has no `NumberFormat`, and Swift's
 *    locale-aware parser has a recorded round-trip gap (an amount formatted with an
 *    unconditional "." is re-read as a grouping separator under `de_DE` — carry-notes §4).
 *    Here `,`, spaces, non-breaking spaces and apostrophes are grouping separators and are
 *    stripped; `.` is the one decimal point. USD-only per the milestone constraint.
 */
object AmountInput {
    /** The reset flow's opening-balance bounds — $1,000 … $10,000,000. */
    val STARTING_BALANCE_RANGE: AmountRange = AmountRange(
        BigDecimal.parseString("1000"),
        BigDecimal.parseString("10000000"),
    )

    fun parse(text: String, range: AmountRange): Money? {
        val builder = StringBuilder()
        for (ch in text) {
            when {
                ch in '0'..'9' -> builder.append(ch)
                ch == '.' -> builder.append(ch)
                ch == ',' || ch == ' ' || ch == ' ' || ch == '\'' -> Unit
                else -> return null
            }
        }
        var cleaned = builder.toString()
        if (cleaned.count { it == '.' } > 1) return null
        if (cleaned.endsWith('.')) cleaned = cleaned.dropLast(1)
        if (cleaned.startsWith('.')) cleaned = "0$cleaned"
        if (cleaned.isEmpty()) return null

        val amount = try {
            BigDecimal.parseString(cleaned)
        } catch (e: RuntimeException) {
            return null
        }
        if (!range.contains(amount)) return null
        return Money(amount, "USD")
    }
}
```

Create `shared/src/commonMain/kotlin/com/aptrade/shared/application/GoalStore.kt`:

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.PortfolioGoal

/** Persists the user's portfolio goals. At most one goal per [GoalKind] is stored; enforcing
 *  that is [SaveGoal]'s job, not the adapter's.
 *
 *  Carry-notes §2.5: the design spec says goals live "in the portfolio store alongside portfolio
 *  state"; the Swift AS-BUILT put them behind their own port with their own key, and that turned
 *  out to matter — because a `PortfolioGoal` is never embedded in another serialized payload, a
 *  pre-goals file simply has no goals key and degrades to an empty list, so the lenient-decoding
 *  problem never arises at all. Port the as-built shape, not the spec sentence. */
interface GoalStore {
    suspend fun load(): List<PortfolioGoal>
    suspend fun save(goals: List<PortfolioGoal>)
}

class LoadGoals(private val store: GoalStore) {
    suspend fun execute(): List<PortfolioGoal> = store.load()
}

/** Upserts by kind — one value goal and one income goal at most. */
class SaveGoal(private val store: GoalStore) {
    suspend fun execute(goal: PortfolioGoal) {
        store.save(store.load().filter { it.kind != goal.kind } + goal)
    }
}

class RemoveGoal(private val store: GoalStore) {
    suspend fun execute(kind: GoalKind) {
        store.save(store.load().filter { it.kind != kind })
    }
}
```

Create `shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/FileGoalStore.kt`:

```kotlin
package com.aptrade.shared.infrastructure

import com.aptrade.shared.application.GoalStore
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** JSON-file goal persistence: one array under its own file, beside its sibling stores in
 *  `jvmCommonMain` so desktop AND Android share it (there is no `UserDefaults` equivalent here —
 *  everything JVM-side is file-backed via `ConfigDir`). Writes are atomic (temp file +
 *  ATOMIC_MOVE). A missing file, a corrupt payload, or an unknown [GoalKind] all load as an
 *  EMPTY list rather than throwing: a goal is an aspiration, not accounting state, so losing one
 *  must never take the app down the way a dropped transaction would corrupt cash balances. */
class FileGoalStore(private val file: Path) : GoalStore {

    @Serializable
    private data class MoneyDTO(val amount: String, val currency: String)

    @Serializable
    private data class GoalDTO(val kind: String, val target: MoneyDTO, val createdAtEpochSeconds: Long)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun load(): List<PortfolioGoal> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString<List<GoalDTO>>(file.readText()).map { dto ->
                val kind = GoalKind.entries.firstOrNull { it.name == dto.kind }
                    ?: return@withContext emptyList()
                PortfolioGoal(
                    kind = kind,
                    target = Money(BigDecimal.parseString(dto.target.amount), dto.target.currency),
                    createdAtEpochSeconds = dto.createdAtEpochSeconds,
                )
            }
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    override suspend fun save(goals: List<PortfolioGoal>) = withContext(Dispatchers.IO) {
        file.parent?.createDirectories()
        val dto = goals.map {
            GoalDTO(
                kind = it.kind.name,
                target = MoneyDTO(it.target.amount.toStringExpanded(), it.target.currencyCode),
                createdAtEpochSeconds = it.createdAtEpochSeconds,
            )
        }
        val text = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(GoalDTO.serializer()), dto)
        val temp = Files.createTempFile(file.parent, "goals", ".tmp")
        // Files.write(Path, byte[]) is API 26; Files.writeString is API 33+, so avoid it here —
        // this code runs on Android minSdk 26 as well as desktop JVM.
        Files.write(temp, text.toByteArray(Charsets.UTF_8))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}
```

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test :androidApp:testDebugUnitTest
```
Expected: shared **643** (626 + 9 AmountInput + 4 GoalUseCases + 5 FileGoalStore = 644 — report the exact observed number, the arithmetic here is indicative); desktop 359, android 282 unchanged-green.

- [ ] **Step 5: Commit**

```
git add shared/src/commonMain/kotlin/com/aptrade/shared/domain/PortfolioGoal.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/domain/AmountInput.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/application/GoalStore.kt \
        shared/src/jvmCommonMain/kotlin/com/aptrade/shared/infrastructure/FileGoalStore.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/domain/AmountInputTest.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/application/GoalUseCasesTest.kt \
        shared/src/jvmCommonTest/kotlin/com/aptrade/shared/infrastructure/FileGoalStoreTest.kt
git commit -m "feat(shared): portfolio goals — type, per-kind ranges, port, use cases, file store

Goal targets validate against per-kind ranges (income 100..1,000,000;
value 1,000..100,000,000), never the starting-balance range; the shared
amount parser takes its range as a required argument.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: `ResetPortfolio` takes an amount and clears goals

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/application/ResetPortfolio.kt`
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt` (construct with the goal store)
- Modify: `androidApp/src/main/kotlin/com/aptrade/android/portfolio/PortfolioViewModel.kt` (single call-site compile fix — Android's own reset UI is M11.3)
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/ResetPortfolioTest.kt` (extend if present, otherwise create)

**Interfaces:**
- Consumes: `Portfolio.starting(cash)` (Task 1), `GoalStore` (Task 3).
- Produces: `class ResetPortfolio(store: PortfolioStore, portfolioMutex: Mutex, goalStore: GoalStore)`, `suspend fun execute(startingCash: Money): Portfolio`.

- [ ] **Step 1: Write the failing test**

Create/extend `shared/src/commonTest/kotlin/com/aptrade/shared/application/ResetPortfolioTest.kt`. If the file already exists, keep its cases and its existing fake store, adding these; if it does not, use this whole file:

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioGoal
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResetPortfolioTest {

    private class MemoryPortfolioStore(var portfolio: Portfolio? = null) : PortfolioStore {
        override suspend fun load(): Portfolio? = portfolio
        override suspend fun save(portfolio: Portfolio) { this.portfolio = portfolio }
    }

    private class MemoryGoalStore(var goals: List<PortfolioGoal> = emptyList()) : GoalStore {
        override suspend fun load(): List<PortfolioGoal> = goals
        override suspend fun save(goals: List<PortfolioGoal>) { this.goals = goals }
    }

    @Test
    fun resetsToTheCallerSuppliedBalanceAndRecordsItAsStartingCash() = runTest {
        val store = MemoryPortfolioStore(
            Portfolio.starting().buying(
                Asset("AAPL", "Apple Inc.", AssetKind.Stock),
                BigDecimal.parseString("1"), Money.usd("100"), 1_000L, "txn-1",
            ),
        )
        val fresh = ResetPortfolio(store, Mutex(), MemoryGoalStore()).execute(Money.usd("25000"))
        assertEquals(Money.usd("25000"), fresh.cash)
        assertEquals(Money.usd("25000"), fresh.startingCash)
        assertTrue(fresh.positions.isEmpty())
        assertTrue(fresh.transactions.isEmpty())
    }

    @Test
    fun resetPersistsTheFreshPortfolio() = runTest {
        val store = MemoryPortfolioStore()
        ResetPortfolio(store, Mutex(), MemoryGoalStore()).execute(Money.usd("25000"))
        assertEquals(Money.usd("25000"), store.portfolio?.cash)
    }

    /** A fresh practice run must not inherit targets set against the old balance. */
    @Test
    fun resetClearsEveryGoal() = runTest {
        val goals = MemoryGoalStore(
            listOf(
                PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1L),
                PortfolioGoal(GoalKind.Income, Money.usd("6000"), 2L),
            ),
        )
        ResetPortfolio(MemoryPortfolioStore(), Mutex(), goals).execute(Money.usd("25000"))
        assertTrue(goals.goals.isEmpty())
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest --tests "*ResetPortfolio*"
```
Expected: **compilation failure** — `ResetPortfolio` takes two constructor arguments and `execute()` takes none.

- [ ] **Step 3: Implement**

Replace the body of `ResetPortfolio.kt`:

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Discards the current portfolio and persists a fresh [Portfolio.starting] opened at
 *  [startingCash], then clears every goal.
 *
 *  Serialized like [BuyAsset] — under the SAME [portfolioMutex] instance every other
 *  portfolio/pie writer holds (see [BuyAsset]'s co-holder doc). A reset overwriting the portfolio
 *  while a buy/sell/pie mutation is mid-flight would otherwise silently discard it (or vice
 *  versa: an in-flight catch-up day landing after a reset could leave a fresh portfolio saddled
 *  with stale pie ledger claims). There is no load to guard — the whole body runs inside the lock
 *  — mirroring the Swift twin's `ResetPortfolioUseCase`
 *  (`Sources/APTradeApplication/PortfolioUseCases.swift`), which wraps the same save in its
 *  `TradeSerializer.run`.
 *
 *  [goalStore] is REQUIRED, unlike the Swift twin's `goalStore: GoalStore? = nil` (which exists
 *  there only so pre-goals construction sites kept compiling). Carry-notes §4 records a no-op
 *  goal store silently discarding saves as a live hazard: a construction site that forgets to
 *  inject the real store must fail to COMPILE here, not fail silently at runtime. */
class ResetPortfolio(
    private val store: PortfolioStore,
    private val portfolioMutex: Mutex,
    private val goalStore: GoalStore,
) {
    suspend fun execute(startingCash: Money): Portfolio = portfolioMutex.withLock {
        val fresh = Portfolio.starting(startingCash)
        store.save(fresh)
        // A fresh practice run must not inherit targets set against the old balance.
        goalStore.save(emptyList())
        fresh
    }
}
```

Android compile fix — in `androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt`, the `ResetPortfolio(portfolioStore, portfolioMutex)` construction gains the goal store. Add, beside the other `File*Store` fields in the same graph object, a `val goalStore: GoalStore = FileGoalStore(<the same config-dir resolution its siblings use>.resolve("goals.json"))` and pass it: `ResetPortfolio(portfolioStore, portfolioMutex, goalStore)`. **Read the surrounding lines and match the file's own config-dir idiom exactly — do not invent a path helper.**

In `androidApp/src/main/kotlin/com/aptrade/android/portfolio/PortfolioViewModel.kt`, the single `resetPortfolio.execute()` call becomes:

```kotlin
            // M11.3 wires Android's own amount field here; until then the reset opens at the
            // named default rather than a bare literal, so the M11.2 hardcoded-balance grep
            // finds this call site instead of a hidden number.
            portfolio = resetPortfolio.execute(Portfolio.DEFAULT_STARTING_CASH)
```
adding `import com.aptrade.shared.domain.Portfolio` if absent.

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :androidApp:testDebugUnitTest
```
Expected: shared PASS (3 new/changed cases). Android **282** green. `:desktopApp:test` will NOT compile yet — the desktop `AppGraph`'s `ResetPortfolio(portfolioStore, portfolioMutex)` and `PortfolioViewModel.reset()` are Task 9's work. That is the one deliberate red window in this plan; note it in the commit and close it in Task 9.

**If you prefer a green tree at every commit**, apply the same two-line desktop fix now (`AppGraph`: add `val goalStore = FileGoalStore(resolveConfigDir().resolve("goals.json"))` and pass it; `PortfolioViewModel.reset()`: `resetPortfolio.execute(Portfolio.DEFAULT_STARTING_CASH)`), then run `:desktopApp:test` and expect 359 green. Task 9 replaces that placeholder with the real amount. **Do this** — a red baseline hides regressions from every later task.

- [ ] **Step 5: Commit**

```
git add shared/src/commonMain/kotlin/com/aptrade/shared/application/ResetPortfolio.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/application/ResetPortfolioTest.kt \
        androidApp/src/main/kotlin/com/aptrade/android/AppGraph.kt \
        androidApp/src/main/kotlin/com/aptrade/android/portfolio/PortfolioViewModel.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModel.kt
git commit -m "feat(shared): reset portfolio at a caller-supplied balance and clear goals

GoalStore is a required constructor argument so a construction site that
forgets it cannot silently discard saves (carry-notes section 4).

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: `DividendMath` — growth rate, DRIP income forecast, projected schedule

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/DividendMath.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/DividendForecastTest.kt` (new — keeps the 15-fixture `DividendMathTest` intact)

**Interfaces:**
- Produces (all top-level in `DividendMath.kt`, NOT nested — carry-notes §2.6 records the nested-type mistake costing an implementer a turn):
  - `data class ForecastYear(val yearOffset: Int, val income: Money)`
  - `data class ScheduledDividend(val symbol: String, val exDateEpochSeconds: Long, val perShare: Money, val estimatedAmount: Money)`
  - `DividendMath.MIN_DIVIDEND_GROWTH: BigDecimal` (−0.20), `DividendMath.MAX_DIVIDEND_GROWTH: BigDecimal` (0.25)
  - `DividendMath.dividendGrowthRate(events: List<DividendEvent>, asOfEpochSeconds: Long): BigDecimal`
  - `DividendMath.incomeForecast(positions: List<Position>, pricesBySymbol: Map<String, Money>, eventsBySymbol: Map<String, List<DividendEvent>>, years: Int, dripEnabled: Boolean, asOfEpochSeconds: Long): List<ForecastYear>`
  - `DividendMath.projectedSchedule(positions: List<Position>, eventsBySymbol: Map<String, List<DividendEvent>>, throughEpochSeconds: Long, asOfEpochSeconds: Long): List<ScheduledDividend>`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/aptrade/shared/domain/DividendForecastTest.kt`:

```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M11.2 Task 5. Semantics transcribed from `Sources/APTradeDomain/DividendMath.swift` AS-BUILT
 * (post-fix-wave), including the two invariants the Swift wave nearly lost:
 *  - forecast year 1 carries NO growth (carry-notes §3.2), and
 *  - DRIP reinvests at the QUOTED price, falling back to cost basis only per-symbol
 *    (carry-notes §1.1 — reinvesting at cost basis overstated year 30 by ~66%).
 */
class DividendForecastTest {
    private val day = 86_400L
    private fun usd(s: String): Money = Money.usd(s)
    private fun qty(s: String): BigDecimal = BigDecimal.parseString(s)

    private fun asset(symbol: String) = Asset(symbol, symbol, AssetKind.Stock)
    private fun position(symbol: String, shares: String, cost: String) =
        Position(asset(symbol), qty(shares), usd(cost), usd("0"))

    private fun event(symbol: String, atEpochSeconds: Long, amount: String) =
        DividendEvent(symbol, atEpochSeconds, usd(amount))

    /** A fixed "now" so every window is deterministic: 2026-07-01T00:00:00Z. */
    private val now = 1_782_000_000L

    /** Four quarterly $0.25 payments over the trailing year → $1.00/share/yr, flat history. */
    private fun flatQuarterly(symbol: String): List<DividendEvent> = listOf(
        event(symbol, now - 300 * day, "0.25"),
        event(symbol, now - 209 * day, "0.25"),
        event(symbol, now - 118 * day, "0.25"),
        event(symbol, now - 27 * day, "0.25"),
    )

    // MARK: dividendGrowthRate

    @Test
    fun growthRateIsZeroWithFewerThanTwoEvents() {
        assertEquals(BigDecimal.ZERO, DividendMath.dividendGrowthRate(emptyList(), now))
        assertEquals(BigDecimal.ZERO, DividendMath.dividendGrowthRate(listOf(event("A", now, "1")), now))
    }

    @Test
    fun growthRateIsZeroWhenTheWindowSpansUnderTwoYears() {
        assertEquals(BigDecimal.ZERO, DividendMath.dividendGrowthRate(flatQuarterly("A"), now))
    }

    @Test
    fun aFlatThreeYearHistoryHasZeroGrowth() {
        val events = (0 until 12).map { i -> event("A", now - (11 - i) * 91 * day, "0.25") }
        assertEquals(0.0, DividendMath.dividendGrowthRate(events, now).doubleValue(false), 1e-9)
    }

    @Test
    fun aDoublingOverTwoYearsAnnualizesAndClampsToTheUpperBound() {
        // Year -3 quarterly $0.25 ($1.00/yr) -> year 0 quarterly $0.50 ($2.00/yr).
        val early = (0 until 4).map { i -> event("A", now - (1095 - i * 91) * day, "0.25") }
        val late = (0 until 4).map { i -> event("A", now - (300 - i * 91) * day, "0.50") }
        val rate = DividendMath.dividendGrowthRate(early + late, now)
        assertEquals(DividendMath.MAX_DIVIDEND_GROWTH, rate)
    }

    @Test
    fun aCollapsingHistoryClampsToTheLowerBound() {
        val early = (0 until 4).map { i -> event("A", now - (1095 - i * 91) * day, "1.00") }
        val late = (0 until 4).map { i -> event("A", now - (300 - i * 91) * day, "0.05") }
        assertEquals(DividendMath.MIN_DIVIDEND_GROWTH, DividendMath.dividendGrowthRate(early + late, now))
    }

    /** Carry-notes §3.6: the per-symbol clamp is −0.20 … 0.25 and is INDEPENDENT of GoalMath's
     *  −0.50 … 1.00 portfolio clamp. Pinned by exact equality so the two cannot be conflated. */
    @Test
    fun perSymbolClampBoundsAreExact() {
        assertEquals(BigDecimal.parseString("-0.20"), DividendMath.MIN_DIVIDEND_GROWTH)
        assertEquals(BigDecimal.parseString("0.25"), DividendMath.MAX_DIVIDEND_GROWTH)
    }

    // MARK: incomeForecast

    @Test
    fun forecastOfZeroOrFewerYearsIsEmpty() {
        assertTrue(
            DividendMath.incomeForecast(
                positions = listOf(position("A", "100", "50")),
                pricesBySymbol = mapOf("A" to usd("150")),
                eventsBySymbol = mapOf("A" to flatQuarterly("A")),
                years = 0,
                dripEnabled = false,
                asOfEpochSeconds = now,
            ).isEmpty(),
        )
    }

    /** Carry-notes §3.2: yearOffset 1 is the trailing twelve months, growth applied ZERO times. */
    @Test
    fun yearOneIsTheTrailingRateWithNoGrowthApplied() {
        val events = (0 until 12).map { i -> event("A", now - (11 - i) * 91 * day, "0.25") }
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = mapOf("A" to usd("150")),
            eventsBySymbol = mapOf("A" to events),
            years = 3,
            dripEnabled = false,
            asOfEpochSeconds = now,
        )
        assertEquals(1, forecast.first().yearOffset)
        assertEquals(usd("100"), forecast.first().income)   // 100 shares x $1.00 trailing
    }

    /** Carry-notes §3.1: year 1 must equal `projectedAnnualIncome` EXACTLY, or the goal card's
     *  progress % and ETA disagree with the forecast chart rendered beside them. */
    @Test
    fun yearOneEqualsProjectedAnnualIncome() {
        val positions = listOf(position("A", "100", "50"), position("B", "40", "20"))
        val events = mapOf("A" to flatQuarterly("A"), "B" to flatQuarterly("B"))
        val forecast = DividendMath.incomeForecast(
            positions = positions,
            pricesBySymbol = mapOf("A" to usd("150"), "B" to usd("30")),
            eventsBySymbol = events,
            years = 5,
            dripEnabled = true,
            asOfEpochSeconds = now,
        )
        val current = DividendMath.projectedAnnualIncome(positions, events, now)
        assertEquals(current.amount.doubleValue(false), forecast.first().income.amount.doubleValue(false), 1e-9)
    }

    @Test
    fun holdingsWithNoDividendHistoryContributeNothing() {
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50"), position("Z", "100", "10")),
            pricesBySymbol = mapOf("A" to usd("150"), "Z" to usd("12")),
            eventsBySymbol = mapOf("A" to flatQuarterly("A")),
            years = 1,
            dripEnabled = false,
            asOfEpochSeconds = now,
        )
        assertEquals(usd("100"), forecast.single().income)
    }

    @Test
    fun withoutDripAFlatPayerProducesAFlatCurve() {
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = mapOf("A" to usd("150")),
            eventsBySymbol = mapOf("A" to (0 until 12).map { i -> event("A", now - (11 - i) * 91 * day, "0.25") }),
            years = 5,
            dripEnabled = false,
            asOfEpochSeconds = now,
        )
        assertEquals(listOf(1, 2, 3, 4, 5), forecast.map { it.yearOffset })
        for (year in forecast) assertEquals(100.0, year.income.amount.doubleValue(false), 1e-9)
    }

    /** THE §1.1 REGRESSION. A holding bought at $50 now trading at $150 must reinvest at $150.
     *  Reinvesting at the $50 cost basis buys 3x the shares per year and overstates the curve. */
    @Test
    fun dripReinvestsAtTheQuotedPriceNotCostBasis() {
        val events = (0 until 12).map { i -> event("A", now - (11 - i) * 91 * day, "0.25") }
        fun run(price: Money) = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = mapOf("A" to price),
            eventsBySymbol = mapOf("A" to events),
            years = 2,
            dripEnabled = true,
            asOfEpochSeconds = now,
        )
        // Year 1 income $100 buys 100/150 shares at the quote -> year 2 = 100.6667 x $1.00.
        assertEquals(100.0 + 100.0 / 150.0, run(usd("150")).last().income.amount.doubleValue(false), 1e-6)
        // At cost basis it would be 100 + 100/50 = 102 — the overstatement this test forbids.
        assertTrue(run(usd("150")).last().income.amount < run(usd("50")).last().income.amount)
    }

    @Test
    fun aSymbolMissingFromPricesBySymbolFallsBackToItsOwnCostBasis() {
        val events = (0 until 12).map { i -> event("A", now - (11 - i) * 91 * day, "0.25") }
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = emptyMap(),
            eventsBySymbol = mapOf("A" to events),
            years = 2,
            dripEnabled = true,
            asOfEpochSeconds = now,
        )
        assertEquals(102.0, forecast.last().income.amount.doubleValue(false), 1e-6)
    }

    @Test
    fun growthCompoundsFromYearTwoOnward() {
        // 25%-clamped grower: year1 = trailing, year2 = trailing x 1.25.
        val early = (0 until 4).map { i -> event("A", now - (1095 - i * 91) * day, "0.25") }
        val late = (0 until 4).map { i -> event("A", now - (300 - i * 91) * day, "0.50") }
        val forecast = DividendMath.incomeForecast(
            positions = listOf(position("A", "100", "50")),
            pricesBySymbol = mapOf("A" to usd("150")),
            eventsBySymbol = mapOf("A" to (early + late)),
            years = 2,
            dripEnabled = false,
            asOfEpochSeconds = now,
        )
        assertEquals(200.0, forecast.first().income.amount.doubleValue(false), 1e-9)
        assertEquals(250.0, forecast.last().income.amount.doubleValue(false), 1e-9)
    }

    // MARK: projectedSchedule

    @Test
    fun scheduleRollsCadenceForwardWithinTheWindowAscending() {
        val rows = DividendMath.projectedSchedule(
            positions = listOf(position("A", "100", "50")),
            eventsBySymbol = mapOf("A" to flatQuarterly("A")),
            throughEpochSeconds = now + 365 * day,
            asOfEpochSeconds = now,
        )
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.exDateEpochSeconds > now && it.exDateEpochSeconds <= now + 365 * day })
        assertEquals(rows.map { it.exDateEpochSeconds }.sorted(), rows.map { it.exDateEpochSeconds })
        assertEquals(usd("25"), rows.first().estimatedAmount)   // 100 shares x $0.25
    }

    @Test
    fun aStaleProjectionIsRolledForwardUntilItIsGenuinelyInTheFuture() {
        // Last payment two years ago: nextProjected lands in the past and must be rolled.
        val stale = listOf(
            event("A", now - 820 * day, "0.25"),
            event("A", now - 729 * day, "0.25"),
        )
        val rows = DividendMath.projectedSchedule(
            positions = listOf(position("A", "100", "50")),
            eventsBySymbol = mapOf("A" to stale),
            throughEpochSeconds = now + 365 * day,
            asOfEpochSeconds = now,
        )
        assertTrue(rows.all { it.exDateEpochSeconds > now })
    }

    @Test
    fun aHoldingWithNoInferableCadenceContributesNoRows() {
        val rows = DividendMath.projectedSchedule(
            positions = listOf(position("A", "100", "50")),
            eventsBySymbol = mapOf("A" to listOf(event("A", now - 30 * day, "0.25"))),
            throughEpochSeconds = now + 365 * day,
            asOfEpochSeconds = now,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun everyScheduledRowCarriesItsPerShareRateSoTheUiCanLabelItAnEstimate() {
        val rows = DividendMath.projectedSchedule(
            positions = listOf(position("A", "100", "50")),
            eventsBySymbol = mapOf("A" to flatQuarterly("A")),
            throughEpochSeconds = now + 200 * day,
            asOfEpochSeconds = now,
        )
        assertTrue(rows.all { it.perShare == usd("0.25") })
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest --tests "*DividendForecast*"
```
Expected: **compilation failure** — `ForecastYear`, `ScheduledDividend`, `dividendGrowthRate`, `incomeForecast`, `projectedSchedule`, `MIN_DIVIDEND_GROWTH`, `MAX_DIVIDEND_GROWTH` do not exist.

- [ ] **Step 3: Implement**

Add these imports at the top of `DividendMath.kt`: `import kotlin.math.pow`.

Add these two TOP-LEVEL types below the existing `DividendCadence` enum (top-level, matching `DividendEvent`'s own placement — carry-notes §2.6):

```kotlin
/** One projected year of dividend income.
 *
 *  [yearOffset] 1 is the trailing twelve-month rate carried forward with NO growth applied — a
 *  deliberately conservative first year. Growth compounds from [yearOffset] 2 onward. (The Swift
 *  wave shipped a comment claiming the opposite and had to correct it; carry-notes §3.2.) */
data class ForecastYear(val yearOffset: Int, val income: Money)

/** One projected dividend payment for a held symbol. ESTIMATED, never declared — the upstream
 *  data contains historical ex-dates only, so nothing in naming, comments, or UI copy derived
 *  from this type may imply an announced date (carry-notes §3.7). */
data class ScheduledDividend(
    val symbol: String,
    val exDateEpochSeconds: Long,
    val perShare: Money,
    val estimatedAmount: Money,
)
```

Inside `object DividendMath`, replace the inline `when (cadence)` block in `nextProjected` with a call to a new shared helper (carry-notes §3.8 — ONE copy of the step sizes):

```kotlin
    /** Calendar interval in DAYS for a cadence bucket (Monthly 30, Quarterly 91, SemiAnnual 182,
     *  Annual 365). Shared by [nextProjected] and [projectedSchedule] so the step sizes are
     *  defined in exactly one place — the M8 "next projected" path already owned these constants
     *  inline and a second copy is explicitly forbidden. */
    private fun cadenceIntervalDays(cadence: DividendCadence): Long = when (cadence) {
        DividendCadence.Monthly -> 30L
        DividendCadence.Quarterly -> 91L
        DividendCadence.SemiAnnual -> 182L
        DividendCadence.Annual -> 365L
    }
```
so `nextProjected`'s body becomes `val intervalDays = cadenceIntervalDays(cadence)` with the rest unchanged.

Append to `object DividendMath`:

```kotlin
    /** Lower bound on per-symbol annual dividend growth used by forecasts. */
    val MIN_DIVIDEND_GROWTH: BigDecimal = BigDecimal.parseString("-0.20")

    /** Upper bound on per-symbol annual dividend growth used by forecasts.
     *
     *  NOTE (carry-notes §3.6): these two are INDEPENDENT of `GoalMath.MIN/MAX_ANNUAL_GROWTH`
     *  (−0.50 … 1.00), which clamp a whole portfolio's value growth. They are easy to conflate
     *  and were deliberately kept separate; both pairs are pinned by exact-equality tests. */
    val MAX_DIVIDEND_GROWTH: BigDecimal = BigDecimal.parseString("0.25")

    private const val SECONDS_PER_YEAR = 365.25 * 86_400.0

    /**
     * Annualized growth of a symbol's dividend, measured over at most the last five years of
     * history and clamped to [MIN_DIVIDEND_GROWTH] … [MAX_DIVIDEND_GROWTH]. Returns 0 when there
     * is too little history to measure honestly (fewer than two years spanned, or fewer than two
     * payments).
     *
     * The trailing-year rate is compared at EACH END of the window rather than raw per-payment
     * amounts, so a cadence change (e.g. quarterly -> monthly) doesn't read as growth.
     *
     * FRACTIONAL EXPONENTIATION (carry-notes §4): ionspin's `BigDecimal.pow` takes Int/Long only,
     * so the n-th root routes through Double exactly as the Swift twin does. Tolerance-covered by
     * the tests; the clamp bounds the blast radius of any Double drift.
     */
    fun dividendGrowthRate(events: List<DividendEvent>, asOfEpochSeconds: Long): BigDecimal {
        val windowStart = asOfEpochSeconds - (5.0 * SECONDS_PER_YEAR).toLong()
        val window = events
            .filter { it.exDateEpochSeconds in windowStart..asOfEpochSeconds }
            .sortedBy { it.exDateEpochSeconds }
        if (window.size < 2) return BigDecimal.ZERO

        val years = (window.last().exDateEpochSeconds - window.first().exDateEpochSeconds).toDouble() /
            SECONDS_PER_YEAR
        if (years < 2.0) return BigDecimal.ZERO

        val early = trailingAnnualPerShare(window, window.first().exDateEpochSeconds + SECONDS_PER_YEAR.toLong())
        val late = trailingAnnualPerShare(window, asOfEpochSeconds)
        if (early.amount <= BigDecimal.ZERO || late.amount <= BigDecimal.ZERO) return BigDecimal.ZERO

        val spanYears = years - 1.0
        if (spanYears < 1.0) return BigDecimal.ZERO

        val ratio = late.amount.divide(early.amount, MONEY_MATH).doubleValue(false)
        if (ratio <= 0.0) return BigDecimal.ZERO
        val rate = ratio.pow(1.0 / spanYears) - 1.0
        if (!rate.isFinite()) return BigDecimal.ZERO

        return clampGrowth(BigDecimal.fromDouble(rate))
    }

    private fun clampGrowth(value: BigDecimal): BigDecimal = when {
        value < MIN_DIVIDEND_GROWTH -> MIN_DIVIDEND_GROWTH
        value > MAX_DIVIDEND_GROWTH -> MAX_DIVIDEND_GROWTH
        else -> value
    }

    /**
     * Projects annual dividend income forward, per holding, summed.
     *
     * Year 1 ([ForecastYear.yearOffset] 1) holds each symbol at its current trailing
     * twelve-month per-share rate — NO growth applied yet, a deliberately conservative choice, and
     * numerically identical to [projectedAnnualIncome] so a goal card's progress and a forecast
     * chart never disagree (carry-notes §3.1/§3.2). From year 2 each symbol grows at its clamped
     * historical [dividendGrowthRate].
     *
     * With DRIP on, each year's dividends buy more shares at that symbol's QUOTED price
     * ([pricesBySymbol]), with the assumed price itself growing at the same rate — a stated
     * simplification the UI surfaces as a caption.
     *
     * [pricesBySymbol] IS REQUIRED AND SITS SECOND, BESIDE [positions] — BINDING (carry-notes
     * §1.1/§5). It is not defaulted and never trails: with it omitted, every symbol would silently
     * reinvest at cost basis, so a holding bought at $50 and now trading at $150 would buy THREE
     * TIMES too many shares per year and overstate year-30 income by roughly 66%. The per-symbol
     * `?: averageCost` fallback below is for a symbol genuinely missing a quote — it must never
     * become the behaviour for every symbol via an omitted argument. A defaulted parameter whose
     * omission is a correctness bug is not a default; it is a re-armed bug with a compiler that
     * will never complain.
     */
    fun incomeForecast(
        positions: List<Position>,
        pricesBySymbol: Map<String, Money>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        years: Int,
        dripEnabled: Boolean,
        asOfEpochSeconds: Long,
    ): List<ForecastYear> {
        if (years <= 0) return emptyList()

        class Projection(
            var shares: BigDecimal,
            var perShare: BigDecimal,
            var price: BigDecimal,
            val growth: BigDecimal,
        )

        val projections = mutableListOf<Projection>()
        var currency = "USD"
        for (position in positions) {
            val events = eventsBySymbol[position.asset.symbol] ?: emptyList()
            val trailing = trailingAnnualPerShare(events, asOfEpochSeconds)
            // The `quantity > 0` guard is inert while the portfolio model forbids a zero-quantity
            // position (selling removes the position at zero; overselling is rejected) — which is
            // exactly what makes year 1 equal projectedAnnualIncome. Keep it: if that model ever
            // changes, this is where the divergence would start (carry-notes §3.1).
            if (trailing.amount <= BigDecimal.ZERO || position.quantity <= BigDecimal.ZERO) continue
            currency = trailing.currencyCode
            projections += Projection(
                shares = position.quantity,
                perShare = trailing.amount,
                price = pricesBySymbol[position.asset.symbol]?.amount ?: position.averageCost.amount,
                growth = dividendGrowthRate(events, asOfEpochSeconds),
            )
        }

        val out = mutableListOf<ForecastYear>()
        for (offset in 1..years) {
            var total = BigDecimal.ZERO
            for (projection in projections) {
                if (offset > 1) {
                    val factor = BigDecimal.ONE + projection.growth
                    projection.perShare = projection.perShare * factor
                    projection.price = projection.price * factor
                }
                val income = projection.shares * projection.perShare
                total += income
                if (dripEnabled && projection.price > BigDecimal.ZERO) {
                    projection.shares += income.divide(projection.price, MONEY_MATH)
                }
            }
            out += ForecastYear(offset, Money(total, currency))
        }
        return out
    }

    /**
     * Rolls each holding's inferred payment cadence forward from its last real event, emitting
     * ESTIMATED payments in `(asOf, through]`, ascending by ex-date.
     *
     * Every emitted row is a projection: the upstream feed exposes no forward-declared dividend
     * dates, so the UI must label each row an estimate (carry-notes §3.7).
     */
    fun projectedSchedule(
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        throughEpochSeconds: Long,
        asOfEpochSeconds: Long,
    ): List<ScheduledDividend> {
        val out = mutableListOf<ScheduledDividend>()
        for (position in positions) {
            if (position.quantity <= BigDecimal.ZERO) continue
            val events = eventsBySymbol[position.asset.symbol] ?: emptyList()
            val seed = nextProjected(events) ?: continue
            val cadence = inferredCadence(events) ?: continue

            val step = cadenceIntervalDays(cadence) * SECONDS_PER_DAY
            var next = seed.exDateEpochSeconds
            // Roll a stale projection forward until it is genuinely in the future.
            while (next <= asOfEpochSeconds) next += step

            while (next <= throughEpochSeconds) {
                out += ScheduledDividend(
                    symbol = position.asset.symbol,
                    exDateEpochSeconds = next,
                    perShare = seed.amountPerShare,
                    estimatedAmount = Money(
                        seed.amountPerShare.amount * position.quantity,
                        seed.amountPerShare.currencyCode,
                    ),
                )
                next += step
            }
        }
        return out.sortedBy { it.exDateEpochSeconds }
    }
```

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test :androidApp:testDebugUnitTest
```
Expected: shared up by 19; existing `DividendMathTest` still green (the `cadenceIntervalDays` extraction is behaviour-preserving — if any `nextProjected` fixture reddens, the extraction changed a constant, so fix the helper rather than the test). Desktop 359 and android 282 unchanged-green.

- [ ] **Step 5: Commit**

```
git add shared/src/commonMain/kotlin/com/aptrade/shared/domain/DividendMath.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/domain/DividendForecastTest.kt
git commit -m "feat(shared): multi-year dividend forecast with DRIP at quoted prices

pricesBySymbol is required and sits beside positions: reinvesting at cost
basis overstates year-30 income by ~66% and no compiler would catch an
omitted trailing default. Cadence step sizes are now defined once and
shared by nextProjected and projectedSchedule.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: `GoalMath` — progress, account-age growth floor, value/income projections

**Files:**
- Create: `shared/src/commonMain/kotlin/com/aptrade/shared/domain/GoalMath.kt`
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/domain/GoalMathTest.kt`

**Interfaces:**
- Consumes: `ForecastYear` (Task 5), `Portfolio.inceptionEpochSeconds()` (Task 1), `PortfolioPerformancePoint`.
- Produces:
  - `sealed class GoalProjection { data object Reached; data class Years(val value: Double); data object BeyondHorizon; data object NotOnTrack; data object InsufficientHistory }`
  - `GoalMath.MINIMUM_HISTORY_DAYS: Int` (180), `GoalMath.HORIZON_YEARS: Double` (30.0), `GoalMath.MIN_ANNUAL_GROWTH`/`MAX_ANNUAL_GROWTH: BigDecimal`
  - `GoalMath.progress(current: Money, target: Money): Double`
  - `GoalMath.accountAgeDays(inceptionEpochSeconds: Long?, asOfEpochSeconds: Long): Double?`
  - `GoalMath.annualGrowthRate(curve: List<PortfolioPerformancePoint>, accountAgeDays: Double?): BigDecimal?`
  - `GoalMath.valueProjection(current: Money, target: Money, curve: List<PortfolioPerformancePoint>, accountAgeDays: Double?): GoalProjection`
  - `GoalMath.incomeProjection(current: Money, target: Money, forecast: List<ForecastYear>): GoalProjection`
  - `fun Portfolio.goalCurrentValueFloor(): Money`

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/aptrade/shared/domain/GoalMathTest.kt`:

```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M11.2 Task 6. Semantics transcribed from `Sources/APTradeDomain/GoalMath.swift` AS-BUILT, with
 * ONE recorded behavioural divergence pinned below: the minimum-history floor measures ACCOUNT
 * AGE (first transaction -> now), not the price window's span (M11.2 kickoff decision 4a.2).
 */
class GoalMathTest {
    private val day = 86_400L
    private val now = 1_782_000_000L
    private fun usd(s: String): Money = Money.usd(s)

    private fun point(epochSeconds: Long, value: String) =
        PortfolioPerformancePoint(epochSeconds, usd(value), usd("0"))

    /** A curve spanning [days] days that grows from [from] to [to]. */
    private fun curve(days: Long, from: String, to: String) =
        listOf(point(now - days * day, from), point(now, to))

    // MARK: progress

    @Test
    fun progressIsTheFractionOfTarget() {
        assertEquals(0.5, GoalMath.progress(usd("50000"), usd("100000")), 1e-9)
    }

    @Test
    fun progressMayExceedOne() {
        assertTrue(GoalMath.progress(usd("150000"), usd("100000")) > 1.0)
    }

    @Test
    fun progressIsZeroForANonPositiveTargetAndNeverNegative() {
        assertEquals(0.0, GoalMath.progress(usd("50000"), usd("0")), 1e-9)
        assertEquals(0.0, GoalMath.progress(usd("-50000"), usd("100000")), 1e-9)
    }

    // MARK: account-age floor (RECORDED DIVERGENCE)

    @Test
    fun accountAgeIsNullWithNoTransactions() {
        assertNull(GoalMath.accountAgeDays(null, now))
    }

    @Test
    fun accountAgeIsTheSpanFromTheFirstTransaction() {
        assertEquals(200.0, GoalMath.accountAgeDays(now - 200 * day, now)!!, 1e-9)
    }

    @Test
    fun minimumHistoryFloorIsOneHundredEightyDays() {
        assertEquals(180, GoalMath.MINIMUM_HISTORY_DAYS)
    }

    /** THE DIVERGENCE, pinned: a brand-new account holding a SEASONED symbol has a full 365-day
     *  price curve, which is exactly the situation Swift's price-span floor waves through. Kotlin
     *  measures the account, so it honestly reports insufficient history. */
    @Test
    fun aNewAccountHoldingASeasonedSymbolStillReportsInsufficientHistory() {
        val seasonedCurve = curve(365, "100000", "130000")
        val accountAge = GoalMath.accountAgeDays(now - 20 * day, now)
        assertNull(GoalMath.annualGrowthRate(seasonedCurve, accountAge))
        assertEquals(
            GoalProjection.InsufficientHistory,
            GoalMath.valueProjection(usd("130000"), usd("500000"), seasonedCurve, accountAge),
        )
    }

    @Test
    fun anAccountOlderThanTheFloorMeasuresGrowthOffTheCurve() {
        val rate = GoalMath.annualGrowthRate(curve(365, "100000", "110000"), 400.0)!!
        assertEquals(0.10, rate.doubleValue(false), 1e-3)
    }

    @Test
    fun growthRateIsNullForADegenerateCurve() {
        assertNull(GoalMath.annualGrowthRate(emptyList(), 400.0))
        assertNull(GoalMath.annualGrowthRate(listOf(point(now, "100000")), 400.0))
        assertNull(GoalMath.annualGrowthRate(curve(365, "0", "110000"), 400.0))
    }

    /** Carry-notes §3.6: the portfolio clamp is −0.50 … 1.00 and is INDEPENDENT of DividendMath's
     *  −0.20 … 0.25 per-symbol clamp. Both boundaries pinned by exact equality. */
    @Test
    fun portfolioClampBoundsAreExactAndDifferFromThePerSymbolPair() {
        assertEquals(BigDecimal.parseString("-0.5"), GoalMath.MIN_ANNUAL_GROWTH)
        assertEquals(BigDecimal.parseString("1.0"), GoalMath.MAX_ANNUAL_GROWTH)
        assertEquals(GoalMath.MAX_ANNUAL_GROWTH, GoalMath.annualGrowthRate(curve(365, "10000", "100000"), 400.0))
        assertEquals(GoalMath.MIN_ANNUAL_GROWTH, GoalMath.annualGrowthRate(curve(365, "100000", "1000"), 400.0))
    }

    // MARK: valueProjection

    @Test
    fun aMetTargetReportsReached() {
        assertEquals(
            GoalProjection.Reached,
            GoalMath.valueProjection(usd("120000"), usd("100000"), curve(365, "100000", "120000"), 400.0),
        )
    }

    @Test
    fun aNonPositiveTargetReportsNotOnTrackNotReached() {
        assertEquals(
            GoalProjection.NotOnTrack,
            GoalMath.valueProjection(usd("120000"), usd("0"), curve(365, "100000", "120000"), 400.0),
        )
    }

    @Test
    fun aReachableTargetReportsAConcreteEta() {
        val projection = GoalMath.valueProjection(
            usd("110000"), usd("220000"), curve(365, "100000", "110000"), 400.0,
        )
        assertTrue(projection is GoalProjection.Years)
        // ln(2)/ln(1.10) ~= 7.27 years.
        assertEquals(7.27, (projection as GoalProjection.Years).value, 0.1)
    }

    @Test
    fun aFlatCurveReportsNotOnTrackRatherThanAnInfiniteEta() {
        assertEquals(
            GoalProjection.NotOnTrack,
            GoalMath.valueProjection(usd("100000"), usd("200000"), curve(365, "100000", "100000"), 400.0),
        )
    }

    @Test
    fun aTargetFurtherOutThanTheHorizonReportsBeyondHorizon() {
        assertEquals(
            GoalProjection.BeyondHorizon,
            GoalMath.valueProjection(
                usd("100100"), usd("100000000"), curve(365, "100000", "100100"), 400.0,
            ),
        )
    }

    // MARK: incomeProjection

    private fun forecast(vararg incomes: String): List<ForecastYear> =
        incomes.mapIndexed { i, v -> ForecastYear(i + 1, usd(v)) }

    @Test
    fun incomeProjectionReportsTheCrossingYear() {
        val projection = GoalMath.incomeProjection(usd("1000"), usd("3000"), forecast("1000", "2000", "3200"))
        assertEquals(GoalProjection.Years(3.0), projection)
    }

    @Test
    fun incomeProjectionReportsReachedWhenCurrentAlreadyMeetsTarget() {
        assertEquals(
            GoalProjection.Reached,
            GoalMath.incomeProjection(usd("5000"), usd("3000"), forecast("5000", "5200")),
        )
    }

    /** Carry-notes §2.4, BINDING: a brand-new user with NO holdings has an all-zero forecast. That
     *  is an ABSENCE of data, not a failing rate — and the value goal in the identical situation
     *  says "needs more history". The two cards are deliberately symmetric and always visible, so
     *  the copy must agree. Checked BEFORE the not-on-track fallthrough. */
    @Test
    fun anAllZeroForecastReportsInsufficientHistoryNotNotOnTrack() {
        assertEquals(
            GoalProjection.InsufficientHistory,
            GoalMath.incomeProjection(usd("0"), usd("6000"), forecast("0", "0", "0")),
        )
    }

    @Test
    fun anEmptyForecastReportsInsufficientHistory() {
        assertEquals(
            GoalProjection.InsufficientHistory,
            GoalMath.incomeProjection(usd("0"), usd("6000"), emptyList()),
        )
    }

    @Test
    fun aGrowingButUncrossedForecastReportsBeyondHorizon() {
        assertEquals(
            GoalProjection.BeyondHorizon,
            GoalMath.incomeProjection(usd("1000"), usd("999999"), forecast("1000", "1100", "1200")),
        )
    }

    @Test
    fun aShrinkingForecastReportsNotOnTrack() {
        assertEquals(
            GoalProjection.NotOnTrack,
            GoalMath.incomeProjection(usd("1000"), usd("5000"), forecast("1000", "900", "800")),
        )
    }

    /** Carry-notes §3.5: `beyondHorizon` renders by INTERPOLATING the horizon constant. This test
     *  derives its expectation from the same constant so the two cannot drift. */
    @Test
    fun horizonConstantIsThirtyYears() {
        assertEquals(30.0, GoalMath.HORIZON_YEARS, 1e-9)
        val justOver = GoalMath.yearsToTarget(
            current = BigDecimal.parseString("1"),
            target = BigDecimal.parseString("2"),
            annualRate = BigDecimal.fromDouble(
                Math.pow(2.0, 1.0 / (GoalMath.HORIZON_YEARS + 1.0)) - 1.0,
            ),
        )
        assertEquals(GoalProjection.BeyondHorizon, justOver)
    }

    // MARK: current-value floor

    /** Carry-notes §2.3: never a fabricated $0. Collapses to exactly `cash` when there are no
     *  positions, so the all-cash reading is preserved exactly. */
    @Test
    fun currentValueFloorIsCashPlusCostBasis() {
        val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
        val portfolio = Portfolio.starting(usd("50000"))
            .buying(aapl, BigDecimal.parseString("100"), usd("100"), 1_000L, "txn-1")
        assertEquals(usd("50000"), portfolio.goalCurrentValueFloor())
        assertEquals(usd("50000"), Portfolio.starting(usd("50000")).goalCurrentValueFloor())
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest --tests "*GoalMath*"
```
Expected: **compilation failure** — `GoalProjection`, `GoalMath`, and `goalCurrentValueFloor` do not exist.

- [ ] **Step 3: Implement**

Create `shared/src/commonMain/kotlin/com/aptrade/shared/domain/GoalMath.kt`:

```kotlin
package com.aptrade.shared.domain

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * How a goal is tracking. Never fabricates an ETA it cannot support.
 *
 * All FIVE outcomes are distinct cases so a caller can tell "already met" from "unreachable" from
 * "too far out" from "no data at all" from a concrete ETA WITHOUT string-matching a rendered
 * sentence (carry-notes §3.5).
 */
sealed class GoalProjection {
    data object Reached : GoalProjection()
    data class Years(val value: Double) : GoalProjection()
    data object BeyondHorizon : GoalProjection()
    data object NotOnTrack : GoalProjection()
    data object InsufficientHistory : GoalProjection()
}

/** Progress and honest time-to-target math for portfolio goals. Pure. Transcribed from
 *  `Sources/APTradeDomain/GoalMath.swift` AS-BUILT, with one recorded divergence at
 *  [MINIMUM_HISTORY_DAYS]. */
object GoalMath {

    /**
     * Minimum days of ACCOUNT history before a growth rate is trustworthy.
     *
     * RECORDED DIVERGENCE FROM SWIFT (M11.2 kickoff decision 4a.2, carry-notes §1.2/§4a.2):
     * Swift's twin measures the span of the equity curve it is handed — but that curve is the
     * one-year PRICE window with a flat pre-inception cash point, so its span is ~365 days for
     * anyone holding any seasoned symbol and the floor is very nearly inert there. Kotlin
     * measures instead from the account's FIRST TRANSACTION (see
     * [Portfolio.inceptionEpochSeconds] — the one named derivation, shared with
     * `FetchPortfolioPerformance`'s sinceInception trim so the metric and this floor cannot
     * drift apart).
     *
     * Consequence, accepted deliberately: a genuinely new account reports insufficient history
     * regardless of how seasoned its holdings are. That is the honest reading, because the
     * projection extrapolates THE ACCOUNT'S growth rate; a user who transfers in a long-held
     * position still waits until the account itself has 180 days behind it.
     *
     * This is a Swift BACKPORT CANDIDATE, not a transcription slip.
     *
     * Why 180 and not 30: a 30-day window annualized by 365.25/days is a 12.175x extrapolation —
     * a portfolio up 5% in its first month reads as +80%/yr, sails under the +100% clamp, and
     * renders a confident multi-year ETA, contradicting this type's promise never to fabricate
     * an ETA it cannot support.
     */
    const val MINIMUM_HISTORY_DAYS = 180

    /** Projections longer than this report [GoalProjection.BeyondHorizon]. */
    const val HORIZON_YEARS = 30.0

    /** Portfolio-value growth clamp, lower bound.
     *
     *  INDEPENDENT of `DividendMath.MIN/MAX_DIVIDEND_GROWTH` (−0.20 … 0.25), which clamp a single
     *  symbol's dividend growth. The two pairs are easy to conflate and are deliberately kept
     *  separate; both are pinned by exact-equality tests (carry-notes §3.6). */
    val MIN_ANNUAL_GROWTH: BigDecimal = BigDecimal.parseString("-0.5")

    /** Portfolio-value growth clamp, upper bound. See [MIN_ANNUAL_GROWTH]. */
    val MAX_ANNUAL_GROWTH: BigDecimal = BigDecimal.parseString("1.0")

    private const val SECONDS_PER_DAY = 86_400.0

    /** Fraction of the target achieved. May exceed 1. A zero or negative target yields 0. The
     *  result is always finite and non-negative. */
    fun progress(current: Money, target: Money): Double {
        if (target.amount <= BigDecimal.ZERO) return 0.0
        val value = current.amount.divide(target.amount, MONEY_MATH).doubleValue(false)
        if (!value.isFinite()) return 0.0
        return max(value, 0.0)
    }

    /** Days between the account's inception and [asOfEpochSeconds]; `null` when the account has
     *  never traded. Feed it [Portfolio.inceptionEpochSeconds]'s result — never a locally
     *  re-derived minimum. */
    fun accountAgeDays(inceptionEpochSeconds: Long?, asOfEpochSeconds: Long): Double? {
        if (inceptionEpochSeconds == null) return null
        val days = (asOfEpochSeconds - inceptionEpochSeconds).toDouble() / SECONDS_PER_DAY
        return max(days, 0.0)
    }

    /**
     * Annualized growth of the equity curve, clamped to [MIN_ANNUAL_GROWTH] … [MAX_ANNUAL_GROWTH].
     * `null` when the account is younger than [MINIMUM_HISTORY_DAYS], or when the curve itself is
     * too degenerate to measure (fewer than two points, under a day of span, or a non-positive
     * endpoint).
     *
     * The GATE is account age; the MEASUREMENT is still the curve's own endpoints over the curve's
     * own span, because that is what actually changed over what actually elapsed. The one-day span
     * guard plus the clamp bound the exponent for the residual case of an old account with a very
     * short priced curve.
     *
     * FRACTIONAL EXPONENTIATION (carry-notes §4): ionspin's `BigDecimal.pow` takes Int/Long only,
     * so the annualization routes through Double, as the Swift twin does.
     */
    fun annualGrowthRate(curve: List<PortfolioPerformancePoint>, accountAgeDays: Double?): BigDecimal? {
        if (accountAgeDays == null || accountAgeDays < MINIMUM_HISTORY_DAYS.toDouble()) return null
        val sorted = curve.sortedBy { it.epochSeconds }
        val first = sorted.firstOrNull() ?: return null
        val last = sorted.lastOrNull() ?: return null
        val spanDays = (last.epochSeconds - first.epochSeconds).toDouble() / SECONDS_PER_DAY
        if (spanDays < 1.0) return null
        if (first.value.amount <= BigDecimal.ZERO || last.value.amount <= BigDecimal.ZERO) return null

        val ratio = last.value.amount.divide(first.value.amount, MONEY_MATH).doubleValue(false)
        if (ratio <= 0.0) return null
        val rate = ratio.pow(365.25 / spanDays) - 1.0
        if (!rate.isFinite()) return null

        val asDecimal = BigDecimal.fromDouble(rate)
        return when {
            asDecimal < MIN_ANNUAL_GROWTH -> MIN_ANNUAL_GROWTH
            asDecimal > MAX_ANNUAL_GROWTH -> MAX_ANNUAL_GROWTH
            else -> asDecimal
        }
    }

    /** Degenerate-input guard shared by [valueProjection]/[incomeProjection]: a non-positive
     *  target reads as 0% progress (mirroring [progress]) and is reported as
     *  [GoalProjection.NotOnTrack] rather than a misleading [GoalProjection.Reached]; a `current`
     *  already at or past `target` reports [GoalProjection.Reached]. `null` means neither shortcut
     *  applies and the caller must consult its own projection data. */
    private fun degenerateOrReached(current: Money, target: Money): GoalProjection? = when {
        target.amount <= BigDecimal.ZERO -> GoalProjection.NotOnTrack
        current.amount >= target.amount -> GoalProjection.Reached
        else -> null
    }

    /** When the portfolio's value reaches [target] at its historical growth rate. */
    fun valueProjection(
        current: Money,
        target: Money,
        curve: List<PortfolioPerformancePoint>,
        accountAgeDays: Double?,
    ): GoalProjection {
        degenerateOrReached(current, target)?.let { return it }
        val rate = annualGrowthRate(curve, accountAgeDays) ?: return GoalProjection.InsufficientHistory
        return yearsToTarget(current.amount, target.amount, rate)
    }

    /**
     * When projected annual income reaches [target], read off the forecast curve.
     *
     * A forecast that never grows reports [GoalProjection.NotOnTrack] rather than a fake ETA. A
     * forecast carrying NO positive income at all — a brand-new portfolio holding nothing, whose
     * forecast is 30 zero years — reports [GoalProjection.InsufficientHistory], checked BEFORE the
     * not-on-track fallthrough (carry-notes §2.4, BINDING). Nothing is off track; there is simply
     * no data, and the symmetric value-goal card says exactly that in the identical situation.
     *
     * [forecast] must always be exactly `HORIZON_YEARS` long, independent of whatever horizon the
     * chart beside it is displaying (carry-notes §3.3) — a truncated forecast makes an
     * unreachable goal indistinguishable from one reached in year 31.
     */
    fun incomeProjection(current: Money, target: Money, forecast: List<ForecastYear>): GoalProjection {
        degenerateOrReached(current, target)?.let { return it }
        val last = forecast.lastOrNull() ?: return GoalProjection.InsufficientHistory
        val crossing = forecast.firstOrNull { it.income.amount >= target.amount }
        if (crossing != null) return GoalProjection.Years(crossing.yearOffset.toDouble())
        if (forecast.none { it.income.amount > BigDecimal.ZERO }) return GoalProjection.InsufficientHistory
        return if (last.income.amount > current.amount) {
            GoalProjection.BeyondHorizon
        } else {
            GoalProjection.NotOnTrack
        }
    }

    /** Solves `current * (1 + rate)^t >= target`, honestly. Internal-by-convention (the two
     *  projections above are the intended entry points); public so the horizon constant can be
     *  pinned directly by test. */
    fun yearsToTarget(current: BigDecimal, target: BigDecimal, annualRate: BigDecimal): GoalProjection {
        if (current <= BigDecimal.ZERO) return GoalProjection.NotOnTrack
        val rate = annualRate.doubleValue(false)
        if (rate <= 0.0) return GoalProjection.NotOnTrack
        val ratio = target.divide(current, MONEY_MATH).doubleValue(false)
        if (ratio <= 0.0) return GoalProjection.NotOnTrack
        val years = ln(ratio) / ln(1.0 + rate)
        if (!years.isFinite() || years <= 0.0) return GoalProjection.NotOnTrack
        return if (years > HORIZON_YEARS) GoalProjection.BeyondHorizon else GoalProjection.Years(years)
    }
}

/**
 * The value-goal card's "current value" when no priced equity curve is available.
 *
 * Cash plus every position's OWN cost basis — never a fabricated zero (carry-notes §2.3, BINDING).
 * The equity curve comes back empty in TWO distinct situations, and only one of them is exotic:
 *  1. genuinely all-cash (`positions.isEmpty()` — `FetchPortfolioPerformance.execute` returns
 *     `emptyList()` immediately), which is durable, and
 *  2. positions exist but their history fetch failed or was too thin — every offline, rate-limited
 *     or upstream-error session lands here, and that is common.
 *
 * A hardcoded `0` would show a user holding real positions "$0 / $500,000 · 0%": a specific, wrong
 * dollar figure for their own portfolio. This collapses to exactly `cash` when there are no
 * positions, preserving the all-cash reading precisely.
 */
fun Portfolio.goalCurrentValueFloor(): Money =
    positions.fold(cash) { acc, position -> acc + position.marketValue(at = position.averageCost) }
```

Note for the implementer: the test's `Math.pow` is JVM-only but the test source set is `commonTest` compiled for `jvmTest` here — if the multiplatform compiler rejects it for another target, swap to `kotlin.math.pow` (`2.0.pow(...)`) and re-run.

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test :androidApp:testDebugUnitTest
```
Expected: shared up by 22; desktop 359 and android 282 unchanged-green. Report observed counts.

- [ ] **Step 5: Commit**

```
git add shared/src/commonMain/kotlin/com/aptrade/shared/domain/GoalMath.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/domain/GoalMathTest.kt
git commit -m "feat(shared): goal progress and honest projections with an account-age floor

Recorded divergence from Swift: the 180-day minimum-history floor measures
the account's age from its first transaction, not the price window's span,
so a new account holding a seasoned symbol reports insufficient history.
Backport candidate.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: "Since inception" return — the consumer that makes `startingCash` earn its place

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchPortfolioPerformance.kt` (reuse the ONE inception derivation)
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchPerformanceReport.kt` (`PerformanceMetrics.sinceInceptionReturn`; forward `sinceInception`)
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/SinceInceptionReturnTest.kt` (new)
- Test: `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchPerformanceReportTest.kt` (fix every `PerformanceMetrics(...)` construction, if any)

**Interfaces:**
- Consumes: `Portfolio.startingCash`, `Portfolio.inceptionEpochSeconds()` (Task 1).
- Produces: `PerformanceMetrics(totalReturn, annualizedReturn, volatility, maxDrawdown, sharpe, beta, alpha, sinceInceptionReturn: Double?)` (new field, REQUIRED, last); `FetchPerformanceReport.execute(timeframe, benchmark, portfolio, riskFree = 0.04, sinceInception: Boolean = false)`.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/aptrade/shared/application/SinceInceptionReturnTest.kt`. Reuse the fake repository/store shapes that `FetchPerformanceReportTest.kt` already defines — **read that file first and mirror its doubles rather than inventing new ones**; the skeleton below names them `SiFakeRepository`/`SiFakeStore` so it compiles standalone if the existing doubles are private.

```kotlin
package com.aptrade.shared.application

import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PricePoint
import com.aptrade.shared.domain.Quote
import com.aptrade.shared.domain.Timeframe
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M11.2 Task 7 — the "since inception" return (kickoff decision 4a.1). This metric is the reader
 * that stops `Portfolio.startingCash` from being dead persisted state: return is measured against
 * the balance the USER CHOSE, not against whatever value the priced curve happened to open at.
 */
class SinceInceptionReturnTest {
    private val day = 86_400L
    private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)
    private fun qty(s: String) = BigDecimal.parseString(s)

    private class SiFakeStore(private val portfolio: Portfolio) : PortfolioStore {
        override suspend fun load(): Portfolio = portfolio
        override suspend fun save(portfolio: Portfolio) = Unit
    }

    private class SiFakeRepository(
        private val historyBySymbol: Map<String, List<PricePoint>>,
    ) : MarketDataRepository by ThrowingMarketDataRepository() {
        override suspend fun history(symbol: String, timeframe: Timeframe): List<PricePoint> =
            historyBySymbol[symbol] ?: emptyList()
    }

    private fun report(
        portfolio: Portfolio,
        history: List<PricePoint>,
        sinceInception: Boolean = false,
    ) = runTest {
        val repository = SiFakeRepository(mapOf("AAPL" to history))
        val performance = FetchPortfolioPerformance(repository, SiFakeStore(portfolio))
        FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio, sinceInception = sinceInception)
    }

    @Test
    fun sinceInceptionMeasuresAgainstStartingCashNotTheCurvesFirstPoint() = runTest {
        // Opened at $50,000; bought 100 AAPL at $100 (cash 40,000). Latest close $200 →
        // total value 40,000 + 20,000 = 60,000 → since-inception return = 60,000/50,000 - 1 = 0.20.
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("100"), Money.usd("100"), 1_000_000L, "txn-1")
        val history = listOf(
            PricePoint(1_000_000L, Money.usd("100")),
            PricePoint(1_000_000L + 200 * day, Money.usd("200")),
        )
        val repository = SiFakeRepository(mapOf("AAPL" to history))
        val performance = FetchPortfolioPerformance(repository, SiFakeStore(portfolio))
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        assertEquals(0.20, result.metrics.sinceInceptionReturn!!, 1e-9)
    }

    @Test
    fun theSameCurveAgainstADifferentOpeningBalanceGivesADifferentReturn() = runTest {
        val portfolio = Portfolio.starting(Money.usd("100000"))
            .buying(aapl, qty("100"), Money.usd("100"), 1_000_000L, "txn-1")
        val history = listOf(
            PricePoint(1_000_000L, Money.usd("100")),
            PricePoint(1_000_000L + 200 * day, Money.usd("200")),
        )
        val repository = SiFakeRepository(mapOf("AAPL" to history))
        val performance = FetchPortfolioPerformance(repository, SiFakeStore(portfolio))
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        // 100,000 cash - 10,000 spent + 20,000 holdings = 110,000 → +10%.
        assertEquals(0.10, result.metrics.sinceInceptionReturn!!, 1e-9)
    }

    @Test
    fun sinceInceptionIsNullWhenThereIsNoCurveToReadTheLatestValueFrom() = runTest {
        val portfolio = Portfolio.starting(Money.usd("50000"))
        val repository = SiFakeRepository(emptyMap())
        val performance = FetchPortfolioPerformance(repository, SiFakeStore(portfolio))
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        assertNull(result.metrics.sinceInceptionReturn)
    }

    @Test
    fun sinceInceptionIsNullForANonPositiveStartingBalance() = runTest {
        // Only reachable through a hand-built Portfolio — `starting(cash)` always records a
        // matching startingCash — but the guard must exist so no division ever fabricates a figure.
        val portfolio = Portfolio(cash = Money.usd("10000"), startingCash = Money.usd("0"))
            .buying(aapl, qty("10"), Money.usd("100"), 1_000_000L, "txn-1")
        val history = listOf(
            PricePoint(1_000_000L, Money.usd("100")),
            PricePoint(1_000_000L + 200 * day, Money.usd("120")),
        )
        val repository = SiFakeRepository(mapOf("AAPL" to history))
        val performance = FetchPortfolioPerformance(repository, SiFakeStore(portfolio))
        val result = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio)
        assertNull(result.metrics.sinceInceptionReturn)
    }

    /** The `sinceInception` flag was implemented but had ZERO callers passing `true`. This is its
     *  first real caller: the trim drops the pre-inception lead-in from the curve. */
    @Test
    fun forwardingSinceInceptionTrimsTheCurveToTheFirstTransactionDay() = runTest {
        val portfolio = Portfolio.starting(Money.usd("50000"))
            .buying(aapl, qty("100"), Money.usd("100"), 1_000_000L + 100 * day, "txn-1")
        val history = listOf(
            PricePoint(1_000_000L, Money.usd("100")),
            PricePoint(1_000_000L + 50 * day, Money.usd("110")),
            PricePoint(1_000_000L + 150 * day, Money.usd("120")),
        )
        val repository = SiFakeRepository(mapOf("AAPL" to history))
        val performance = FetchPortfolioPerformance(repository, SiFakeStore(portfolio))
        val untrimmed = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio, sinceInception = false)
        val trimmed = FetchPerformanceReport(repository, performance)
            .execute(Timeframe.OneYear, "SPY", portfolio, sinceInception = true)
        assertTrue(trimmed.points.size < untrimmed.points.size)
        assertTrue(trimmed.points.all { it.epochSeconds >= (1_000_000L + 100 * day) / day * day })
        // The metric is span-independent: it reads the LATEST value either way.
        assertEquals(untrimmed.metrics.sinceInceptionReturn, trimmed.metrics.sinceInceptionReturn)
    }
}
```

**`ThrowingMarketDataRepository` is a name this plan has NOT verified.** Before writing the fixture, open `shared/src/commonTest/kotlin/com/aptrade/shared/application/FetchPerformanceReportTest.kt` and use whatever `MarketDataRepository` double it already defines. If that double is private to the file, promote it to `internal` in place rather than declaring a second one — carry-notes §4 lists test-double duplication as live debt. Do not invent a name.

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest --tests "*SinceInception*"
```
Expected: **compilation failure** — `PerformanceMetrics.sinceInceptionReturn` and `execute(..., sinceInception = …)` do not exist.

- [ ] **Step 3: Implement**

In `FetchPortfolioPerformance.kt`, replace the inline first-transaction derivation with the shared helper — behaviour-preserving, and the whole point of Task 1's helper:

```kotlin
        var series = portfolio.performanceSeries(histories)
        if (sinceInception) {
            // ONE named derivation, shared with GoalMath's account-age history floor
            // (M11.2 kickoff decision 4a.2) so the trim and the floor cannot drift apart.
            val firstEpoch = portfolio.inceptionEpochSeconds()
            if (firstEpoch != null) {
                val inceptionDay = (firstEpoch / 86_400) * 86_400
                val trimmed = series.filter { it.epochSeconds >= inceptionDay }
                if (trimmed.isNotEmpty()) series = trimmed
            }
        }
```

In `FetchPerformanceReport.kt`, extend the metrics type:

```kotlin
data class PerformanceMetrics(
    val totalReturn: Double,
    val annualizedReturn: Double,
    val volatility: Double,
    val maxDrawdown: Double,
    val sharpe: Double?,
    val beta: Double?,
    val alpha: Double?,
    /** Total return measured from the portfolio's ACTUAL opening balance
     *  ([Portfolio.startingCash]) to its latest curve value, rather than from the curve's own
     *  first point — the consumer that makes `startingCash` earn its place (M11.2 kickoff
     *  decision 4a.1, carry-notes §2.1). Swift currently carries the field with no reader; this
     *  is a BACKPORT CANDIDATE.
     *
     *  Span-INDEPENDENT by construction: it reads the latest point's value, which is "now"
     *  regardless of which timeframe produced the curve. `null` when there is no curve to read a
     *  latest value from, or when the opening balance is non-positive (division would be
     *  meaningless, and a fabricated number here is exactly what carry-notes §2.3 forbids).
     *
     *  REQUIRED, deliberately not defaulted: every construction site must decide what it means. */
    val sinceInceptionReturn: Double?,
)
```

Add `import com.aptrade.shared.domain.MONEY_MATH` and `import com.ionspin.kotlin.bignum.decimal.BigDecimal`, then extend `execute`:

```kotlin
    @Throws(CancellationException::class)
    suspend fun execute(
        timeframe: Timeframe,
        benchmark: String,
        portfolio: Portfolio,
        riskFree: Double = 0.04,
        /** Forwarded to [FetchPortfolioPerformance]: trims the curve to the account's first
         *  transaction day. Opt-in, so omitting it preserves the previous behaviour exactly. */
        sinceInception: Boolean = false,
    ): PerformanceReport {
        val points = fetchPortfolioPerformance.execute(timeframe, sinceInception)
        val sinceInceptionReturn = sinceInceptionReturn(portfolio, points)
        if (points.isEmpty()) {
            return PerformanceReport(
                points,
                null,
                PerformanceMetrics(0.0, 0.0, 0.0, 0.0, null, null, null, sinceInceptionReturn),
            )
        }
        …
        val metrics = PerformanceMetrics(
            totalReturn = RiskMetrics.totalReturn(values),
            annualizedReturn = RiskMetrics.annualizedReturn(values),
            volatility = RiskMetrics.annualizedVolatility(values),
            maxDrawdown = RiskMetrics.maxDrawdown(values),
            sharpe = RiskMetrics.sharpe(values, riskFree),
            beta = benchmarkCloses?.let { RiskMetrics.beta(values, it) },
            alpha = benchmarkCloses?.let { RiskMetrics.alpha(values, it, riskFree) },
            sinceInceptionReturn = sinceInceptionReturn,
        )
```

and add the private helper to the class:

```kotlin
    /** See [PerformanceMetrics.sinceInceptionReturn]. */
    private fun sinceInceptionReturn(
        portfolio: Portfolio,
        points: List<PortfolioPerformancePoint>,
    ): Double? {
        val opening = portfolio.startingCash.amount
        if (opening <= BigDecimal.ZERO) return null
        val latest = points.lastOrNull()?.value?.amount ?: return null
        return latest.divide(opening, MONEY_MATH).doubleValue(false) - 1.0
    }
```

Then grep by CONTENT for every other `PerformanceMetrics(` construction and add the argument:

```
grep -rn "PerformanceMetrics(" shared/src desktopApp/src androidApp/src
```
Each hit either passes a real value or an explicit `null` with a one-line reason.

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test :androidApp:testDebugUnitTest
```
Expected: shared up by 5; desktop 359 and android 282 green (the flag defaults to `false`, so no existing caller changes behaviour). If a desktop or Android test constructs `PerformanceMetrics` positionally, it needs the new argument — that is a compile fix, not a behaviour change.

- [ ] **Step 5: Commit**

```
git add shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchPortfolioPerformance.kt \
        shared/src/commonMain/kotlin/com/aptrade/shared/application/FetchPerformanceReport.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/application/SinceInceptionReturnTest.kt
git commit -m "feat(shared): since-inception return measured from the chosen opening balance

Gives Portfolio.startingCash a real reader and the long-dead sinceInception
flag its first caller. Recorded divergence from Swift; backport candidate.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 8: L10n — 21 new keys × 4 languages, catalog pin 389 → 410

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/aptrade/shared/l10n/L10n.kt`
- Modify: `shared/src/commonTest/kotlin/com/aptrade/shared/l10n/L10nCatalogTest.kt`

**Interfaces:**
- Produces the 21 `L10n.Key` entries listed below; every later desktop task consumes them.

**Deliberate reuse (do NOT add near-duplicates):**
- The lowercase "est." disclaimer on the Dividend Calendar reuses the EXISTING `IncomeEstimatedBadge` (English "Est.", DE "ca.", IT "Stim.", ES "Est." — byte-identical to Swift's separate `estimatedShort` key). Same word, one key; the M10.2 `SessionAfterClose` precedent.
- "Save"/"Cancel"/"Reset" reuse the existing `SaveAction`/`Cancel`/`Reset`.
- The Dividend Calendar's title is a NEW key. It must NOT reuse or overwrite `IncomeUpcomingTitle` ("Upcoming Dividends"), which stays on the pre-existing next-payout list (carry-notes §1.4 — two identically-titled cards shipped briefly on Swift before this was caught).

- [ ] **Step 1: Write the failing test**

In `L10nCatalogTest.kt`, extend the header KDoc's running tally by appending this sentence to the final paragraph:

```
 * M11.2 Task 8 (goals, dividend calendar, income forecast, configurable starting balance) added
 * 21 more: the calendar/forecast/goal block, the two reset-sheet strings, the starting-balance
 * range hint, and `SinceInception` (Kotlin-first — no Swift counterpart, it names the metric
 * introduced by M11.2 kickoff decision 4a.1). Swift's `estimatedShort` was NOT transcribed — the
 * existing `IncomeEstimatedBadge` already carries exactly that word in all four languages, so it
 * is reused rather than near-duplicated. That brings the total to 410.
```

and replace the count test:

```kotlin
    @Test
    fun `catalog has exactly 410 keys (389 pre-M11_2 + 21 goals_calendar_forecast_reset keys from the M11_2 Task 8 Kotlin L10n port)`() {
        assertEquals(410, L10n.Key.entries.size)
    }
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest --tests "*L10nCatalog*"
```
Expected: FAIL — `expected:<410> but was:<389>`.

- [ ] **Step 3: Implement**

Append to `L10n.Key`, immediately before the terminating `;`:

```kotlin
        // MARK: M11.2 — goals, dividend calendar, income forecast, configurable starting balance
        DividendCalendarTitle(english = "Dividend Calendar"),
        NoDividendPayersHeld(english = "No dividend payers held yet."),
        IncomeForecastTitle(english = "Income Forecast"),
        ForecastCaption(english = "Assumes historical dividend growth continues; DRIP compounding where enabled."),
        IncomeGoal(english = "Income Goal"),
        ValueGoal(english = "Value Goal"),
        SetGoal(english = "Set a goal"),
        EditGoal(english = "Edit goal"),
        RemoveGoal(english = "Remove goal"),
        GoalTarget(english = "Target"),
        IncomeGoalRange(english = "Between \$100 and \$1,000,000 per year"),
        ValueGoalRange(english = "Between \$1,000 and \$100,000,000"),
        GoalReached(english = "Goal reached"),
        GoalNotOnTrack(english = "Not on track at current rate"),
        GoalNeedsHistory(english = "Tracking — needs more history"),
        GoalBeyondHorizonFmt(english = "More than %@ yrs at this rate"),
        GoalYearsFmt(english = "About %@ yrs at this rate"),
        StartingBalanceRange(english = "Between \$1,000 and \$10,000,000"),
        ResetPortfolioTitle(english = "Reset Portfolio"),
        ResetPortfolioBody(english = "This clears all holdings and history, then opens a fresh portfolio with the cash below."),
        SinceInception(english = "Since Inception"),
```

Append to the **German** map (before its closing `),`):

```kotlin
            Key.DividendCalendarTitle to "Dividendenkalender",
            Key.NoDividendPayersHeld to "Noch keine Dividendenzahler im Bestand.",
            Key.IncomeForecastTitle to "Einkommensprognose",
            Key.ForecastCaption to "Annahme: historisches Dividendenwachstum setzt sich fort; DRIP-Verzinsung wo aktiviert.",
            Key.IncomeGoal to "Einkommensziel",
            Key.ValueGoal to "Wertziel",
            Key.SetGoal to "Ziel festlegen",
            Key.EditGoal to "Ziel bearbeiten",
            Key.RemoveGoal to "Ziel entfernen",
            Key.GoalTarget to "Ziel",
            Key.IncomeGoalRange to "Zwischen 100 \$ und 1.000.000 \$ pro Jahr",
            Key.ValueGoalRange to "Zwischen 1.000 \$ und 100.000.000 \$",
            Key.GoalReached to "Ziel erreicht",
            Key.GoalNotOnTrack to "Beim aktuellen Tempo nicht erreichbar",
            Key.GoalNeedsHistory to "Wird verfolgt – benötigt mehr Verlauf",
            Key.GoalBeyondHorizonFmt to "Mehr als %@ Jahre bei diesem Tempo",
            Key.GoalYearsFmt to "Etwa %@ Jahre bei diesem Tempo",
            Key.StartingBalanceRange to "Zwischen 1.000 \$ und 10.000.000 \$",
            Key.ResetPortfolioTitle to "Portfolio zurücksetzen",
            Key.ResetPortfolioBody to "Dadurch werden alle Bestände und der Verlauf gelöscht und ein neues Portfolio mit dem unten angegebenen Bargeld eröffnet.",
            Key.SinceInception to "Seit Beginn",
```

Append to the **Italian** map:

```kotlin
            Key.DividendCalendarTitle to "Calendario dei dividendi",
            Key.NoDividendPayersHeld to "Nessun titolo con dividendi in portafoglio.",
            Key.IncomeForecastTitle to "Previsione di reddito",
            Key.ForecastCaption to "Presuppone che la crescita storica dei dividendi continui; capitalizzazione DRIP dove attiva.",
            Key.IncomeGoal to "Obiettivo di reddito",
            Key.ValueGoal to "Obiettivo di valore",
            Key.SetGoal to "Imposta un obiettivo",
            Key.EditGoal to "Modifica obiettivo",
            Key.RemoveGoal to "Rimuovi obiettivo",
            Key.GoalTarget to "Obiettivo",
            Key.IncomeGoalRange to "Tra 100 \$ e 1.000.000 \$ all'anno",
            Key.ValueGoalRange to "Tra 1.000 \$ e 100.000.000 \$",
            Key.GoalReached to "Obiettivo raggiunto",
            Key.GoalNotOnTrack to "Non in linea con il ritmo attuale",
            Key.GoalNeedsHistory to "In monitoraggio — serve più storico",
            Key.GoalBeyondHorizonFmt to "Più di %@ anni a questo ritmo",
            Key.GoalYearsFmt to "Circa %@ anni a questo ritmo",
            Key.StartingBalanceRange to "Tra 1.000 \$ e 10.000.000 \$",
            Key.ResetPortfolioTitle to "Reimposta portafoglio",
            Key.ResetPortfolioBody to "Questa operazione cancella tutte le posizioni e lo storico, quindi apre un nuovo portafoglio con la liquidità indicata di seguito.",
            Key.SinceInception to "Dall'inizio",
```

Append to the **Spanish** map:

```kotlin
            Key.DividendCalendarTitle to "Calendario de dividendos",
            Key.NoDividendPayersHeld to "Aún no tienes valores que paguen dividendos.",
            Key.IncomeForecastTitle to "Previsión de ingresos",
            Key.ForecastCaption to "Supone que continúa el crecimiento histórico de dividendos; capitalización DRIP donde esté activada.",
            Key.IncomeGoal to "Objetivo de ingresos",
            Key.ValueGoal to "Objetivo de valor",
            Key.SetGoal to "Fijar un objetivo",
            Key.EditGoal to "Editar objetivo",
            Key.RemoveGoal to "Eliminar objetivo",
            Key.GoalTarget to "Objetivo",
            Key.IncomeGoalRange to "Entre 100 \$ y 1.000.000 \$ al año",
            Key.ValueGoalRange to "Entre 1.000 \$ y 100.000.000 \$",
            Key.GoalReached to "Objetivo alcanzado",
            Key.GoalNotOnTrack to "No va por buen camino al ritmo actual",
            Key.GoalNeedsHistory to "En seguimiento: falta historial",
            Key.GoalBeyondHorizonFmt to "Más de %@ años a este ritmo",
            Key.GoalYearsFmt to "Unos %@ años a este ritmo",
            Key.StartingBalanceRange to "Entre 1.000 \$ y 10.000.000 \$",
            Key.ResetPortfolioTitle to "Restablecer cartera",
            Key.ResetPortfolioBody to "Esto borra todas las posiciones y el historial, y abre una cartera nueva con el efectivo indicado abajo.",
            Key.SinceInception to "Desde el inicio",
```

Note on escaping: this catalog already writes `\$` inside Kotlin string literals for currency symbols (see the existing `ResetPortfolioConfirm` rows) — keep that convention. `%@` is the Swift-style placeholder `trf()` rewrites to `%s`.

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test :androidApp:testDebugUnitTest
```
Expected: `L10nCatalogTest` green — the count test passes at 410 AND "every key resolves to a non-blank string for all four languages" passes, which is what proves no row was missed in any of the three maps. Desktop 359, android 282 green.

- [ ] **Step 5: Commit**

```
git add shared/src/commonMain/kotlin/com/aptrade/shared/l10n/L10n.kt \
        shared/src/commonTest/kotlin/com/aptrade/shared/l10n/L10nCatalogTest.kt
git commit -m "feat(shared): 21 L10n keys for goals, dividend calendar, forecast and reset

Catalog 389 -> 410, all four languages. The new calendar card is titled
'Dividend Calendar' so it cannot collide with the pre-existing
'Upcoming Dividends' list on the same scroll view.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 9: Desktop reset flow takes a validated amount

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModel.kt` (`reset(startingCash: Money)`)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioPane.kt` (`onReset: (Money) -> Unit`, `defaultStartingCash` param, amount field in `ResetConfirmDialog`)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (thread `notificationSettings.defaultStartingCash` and the amount through)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt` (replace Task 4's placeholder wiring — the goal store is a real graph field)
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/PortfolioResetAmountTest.kt` (new)

**Interfaces:**
- Consumes: `AmountInput.parse`, `AmountInput.STARTING_BALANCE_RANGE` (Task 3); `ResetPortfolio.execute(Money)` (Task 4); `AppSettings.defaultStartingCash` (Task 2); `L10n.Key.ResetPortfolioTitle/ResetPortfolioBody/StartingBalanceRange` (Task 8).
- Produces: `PortfolioViewModel.reset(startingCash: Money)`; `PortfolioPane(..., defaultStartingCash: Money, onReset: (Money) -> Unit, …)`.

- [ ] **Step 1: Write the failing test**

Create `desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/PortfolioResetAmountTest.kt`. **Read the existing `desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModelTest.kt` first and reuse its VM-construction helper verbatim** — this file only adds cases; do not fork a second fixture.

```kotlin
package com.aptrade.desktop.portfolio

import com.aptrade.shared.domain.AmountInput
import com.aptrade.shared.domain.Money
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M11.2 Task 9. The reset flow now opens the portfolio at a user-chosen, range-validated amount
 * instead of a hardcoded $100,000.
 */
class PortfolioResetAmountTest {

    @Test
    fun resetOpensThePortfolioAtTheSuppliedAmount() = runTest {
        // Build the VM with this package's existing test fixture (see PortfolioViewModelTest).
        val fixture = portfolioViewModelFixture(scope = this)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.reset(Money.usd("25000"))
        runCurrent()
        assertEquals(Money.usd("25000"), fixture.portfolioStore.portfolio?.cash)
        assertEquals(Money.usd("25000"), fixture.portfolioStore.portfolio?.startingCash)
    }

    @Test
    fun resetClearsTheValueGoalSoAStaleTargetCannotSurvive() = runTest {
        val fixture = portfolioViewModelFixture(scope = this)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.setValueGoal(Money.usd("500000"))
        runCurrent()
        fixture.viewModel.reset(Money.usd("25000"))
        runCurrent()
        assertNull(fixture.viewModel.state.value.valueGoal)
    }

    /** The dialog's Confirm button is gated on exactly this parse, so pin the seam the UI uses
     *  rather than the (untested, waived) composition itself. */
    @Test
    fun theResetFieldRejectsOutOfRangeAmounts() {
        assertNull(AmountInput.parse("999", AmountInput.STARTING_BALANCE_RANGE))
        assertNull(AmountInput.parse("10000001", AmountInput.STARTING_BALANCE_RANGE))
        assertEquals(Money.usd("1000"), AmountInput.parse("1,000", AmountInput.STARTING_BALANCE_RANGE))
    }
}
```

If `portfolioViewModelFixture` does not exist, extract the existing test's inline construction into exactly one such helper in `PortfolioViewModelTest.kt` (marked `internal`) and use it from both files. The second case depends on Task 13's `setValueGoal`/`state.valueGoal` — **if you are running tasks strictly in order, write that case now and leave it failing until Task 13, or move it into Task 13's suite. Do not delete it.**

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test --tests "*PortfolioResetAmount*"
```
Expected: **compilation failure** — `reset` takes no argument.

- [ ] **Step 3: Implement**

`AppGraph.kt` — replace Task 4's placeholder with the real graph field, beside its sibling file stores:

```kotlin
    /** Portfolio goals (M11.2). Public like `settingsStore`/`pieStore` because the goal use cases
     *  below and the reset use case all read from this ONE instance. */
    val goalStore: GoalStore = FileGoalStore(resolveConfigDir().resolve("goals.json"))
    val loadGoals = LoadGoals(goalStore)
    val saveGoal = SaveGoal(goalStore)
    val removeGoal = RemoveGoal(goalStore)
```
and `val resetPortfolio = ResetPortfolio(portfolioStore, portfolioMutex, goalStore)`. Add the imports (`com.aptrade.shared.application.GoalStore`, `LoadGoals`, `SaveGoal`, `RemoveGoal`, `com.aptrade.shared.infrastructure.FileGoalStore`).

`PortfolioViewModel.kt` — change `reset()`:

```kotlin
    /** Opens a fresh portfolio at [startingCash] (the reset dialog's validated amount) and clears
     *  every goal — `ResetPortfolio` does the clearing; this re-reads so Performance's value-goal
     *  card can't keep rendering a deleted goal with a progress bar and ETA computed against the
     *  pre-reset curve (carry-notes §3.4). */
    fun reset(startingCash: Money) {
        scope.launch {
            portfolio = resetPortfolio.execute(startingCash)
            quotes = emptyMap()
            _state.update {
                it.copy(
                    performanceValues = emptyList(),
                    performancePoints = emptyList(),
                    benchmarkTwinValues = null,
                    metrics = null,
                    valueGoal = null,     // added by Task 13; drop this line until then
                )
            }
            publish(loading = false)
```
keeping the remainder of the existing body unchanged. Add `import com.aptrade.shared.domain.Money` if absent.

`PortfolioPane.kt` — the pane signature gains `defaultStartingCash: Money`, `onReset` becomes `(Money) -> Unit`, and the dialog owns the field:

```kotlin
    if (showResetConfirm) {
        ResetConfirmDialog(
            defaultStartingCash = defaultStartingCash,
            onConfirm = { amount -> showResetConfirm = false; onReset(amount) },
            onCancel = { showResetConfirm = false },
        )
    }
```

Replace `ResetConfirmDialog`'s body content (keep its scrim, Esc handling, focus requester and button chrome exactly as they are — only the inner column content and the confirm gating change):

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResetConfirmDialog(
    defaultStartingCash: Money,
    onConfirm: (Money) -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Seeded from the persisted preference, so the common case is "press Reset" and the
    // uncommon case is "type a different balance" — never "retype your usual number".
    var amountText by remember { mutableStateOf(defaultStartingCash.amount.toStringExpanded()) }
    val parsed = AmountInput.parse(amountText, AmountInput.STARTING_BALANCE_RANGE)
    …
            Text(
                tr(L10n.Key.ResetPortfolioTitle),
                style = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
            )
            Text(
                tr(L10n.Key.ResetPortfolioBody),
                style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.Normal, color = DK.textSecondary),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    tr(L10n.Key.StartingBalance).uppercase(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
                )
                WizardTextField(value = amountText, onValueChange = { amountText = it }, placeholder = "0", fontSize = 20.sp)
                Text(
                    tr(L10n.Key.StartingBalanceRange),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                        // The hint turns red only once the user has typed something unusable —
                        // an empty field on open is not an error state.
                        color = if (parsed == null && amountText.isNotBlank()) DK.down else DK.textTertiary,
                    ),
                )
            }
```
and the destructive button becomes:

```kotlin
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f).clip(RoundedCornerShape(50))
                        .background(DK.down.copy(alpha = if (parsed == null) 0.06f else 0.16f))
                        .border(1.dp, DK.down.copy(alpha = if (parsed == null) 0.18f else 0.4f), RoundedCornerShape(50))
                        .clickable(
                            enabled = parsed != null,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { parsed?.let(onConfirm) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        tr(L10n.Key.Reset),
                        style = TextStyle(
                            fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = if (parsed == null) DK.down.copy(alpha = 0.45f) else DK.down,
                        ),
                    )
                }
```

The old `trf(L10n.Key.StartOverWithFormat, "$100,000")` line is DELETED — it was the pane's hardcoded balance. `WizardTextField` is `internal` in `com.aptrade.desktop.plans`; import it (`com.aptrade.desktop.plans.WizardTextField`) rather than writing a second text field.

`Main.kt` — at the `PortfolioPane(...)` call site: `defaultStartingCash = notificationSettings.defaultStartingCash` and `onReset = { amount -> portfolioViewModel.reset(amount) }` (the method reference no longer type-checks). `notificationSettings` is already in scope at that call site.

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
```
Expected: desktop green at 359 + the new cases; shared and android unchanged. Then boot once and eyeball the dialog:
```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:run
```
(Portfolio → "Reset portfolio…" → the sheet shows a seeded, editable amount with a range hint; Confirm is dimmed for "999". Kill the process after.)

- [ ] **Step 5: Commit**

```
git add desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioPane.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModel.kt \
        desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/PortfolioResetAmountTest.kt
git commit -m "feat(desktop): choose the starting balance when resetting the portfolio

The reset sheet seeds from the persisted default, validates against
\$1,000..\$10,000,000 with a live hint, and gates Confirm on a parse.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 10: Desktop `GoalCard` — one card, one edit dialog, both surfaces

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/goals/GoalCard.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/goals/GoalCardUiTest.kt`

**Interfaces:**
- Consumes: `PortfolioGoal`, `GoalKind`, `GoalProjection`, `GoalMath`, `AmountInput`, `GoalKind.targetRange`, the Task 8 keys.
- Produces:
  - `data class GoalCardUi(val currentText: String, val targetText: String, val targetAmountText: String, val fraction: Double, val projection: GoalProjection)`
  - `fun goalCardUi(goal: PortfolioGoal?, current: Money, projection: GoalProjection?): GoalCardUi?`
  - `@Composable fun GoalCard(title: String, kind: GoalKind, ui: GoalCardUi?, onSet: (Money) -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun goalProjectionText(projection: GoalProjection?): String`

- [ ] **Step 1: Write the failing test**

Create `desktopApp/src/test/kotlin/com/aptrade/desktop/goals/GoalCardUiTest.kt`:

```kotlin
package com.aptrade.desktop.goals

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M11.2 Task 10. The card composition itself ships under the standing UI waiver; the VM→UI
 * mapping does not — it is where a wrong money string or a fabricated progress bar would come
 * from, and both goal surfaces share it.
 */
class GoalCardUiTest {

    @Test
    fun anUnsetGoalMapsToNullSoTheCardRendersItsSetAGoalAffordance() {
        assertNull(goalCardUi(goal = null, current = Money.usd("1000"), projection = null))
    }

    @Test
    fun aSetGoalCarriesFormattedMoneyAndAFraction() {
        val ui = goalCardUi(
            goal = PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1L),
            current = Money.usd("125000"),
            projection = GoalProjection.Years(7.3),
        )!!
        assertEquals("\$125,000.00", ui.currentText)
        assertEquals("\$500,000.00", ui.targetText)
        assertEquals(0.25, ui.fraction, 1e-9)
        assertEquals(GoalProjection.Years(7.3), ui.projection)
    }

    /** The edit field must round-trip the RAW amount, never the grouped display string — feeding
     *  "$500,000.00" back into the parser would fail on the "$". */
    @Test
    fun targetAmountTextIsRawForRefillingTheEditField() {
        val ui = goalCardUi(
            goal = PortfolioGoal(GoalKind.Income, Money.usd("6000"), 1L),
            current = Money.usd("0"),
            projection = null,
        )!!
        assertEquals("6000", ui.targetAmountText)
    }

    /** Carry-notes §3.5: a null projection is "not computed yet", which reads as the same honest
     *  "needs more history" sentence — never silence, never a fabricated ETA. */
    @Test
    fun aNullProjectionMapsToInsufficientHistory() {
        val ui = goalCardUi(
            goal = PortfolioGoal(GoalKind.Value, Money.usd("500000"), 1L),
            current = Money.usd("125000"),
            projection = null,
        )!!
        assertEquals(GoalProjection.InsufficientHistory, ui.projection)
    }

    @Test
    fun fractionIsClampedAtZeroButMayExceedOne() {
        val over = goalCardUi(
            goal = PortfolioGoal(GoalKind.Value, Money.usd("100000"), 1L),
            current = Money.usd("150000"),
            projection = GoalProjection.Reached,
        )!!
        assertEquals(1.5, over.fraction, 1e-9)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test --tests "*GoalCardUi*"
```
Expected: **compilation failure** — package `com.aptrade.desktop.goals` does not exist.

- [ ] **Step 3: Implement**

Create `desktopApp/src/main/kotlin/com/aptrade/desktop/goals/GoalCard.kt`:

```kotlin
package com.aptrade.desktop.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aptrade.desktop.designkit.DK
import com.aptrade.desktop.designkit.InterFamily
import com.aptrade.desktop.designkit.formatMoney
import com.aptrade.desktop.l10n.tr
import com.aptrade.desktop.l10n.trf
import com.aptrade.desktop.plans.WizardTextField
import com.aptrade.shared.domain.AmountInput
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalMath
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.PortfolioGoal
import com.aptrade.shared.domain.targetRange
import com.aptrade.shared.l10n.L10n
import kotlin.math.roundToInt

/**
 * One goal's worth of card state, pre-formatted per this codebase's VM→UI contract.
 *
 * [projection] stays a raw [GoalProjection] rather than a rendered sentence so the copy resolves
 * inside composition and re-renders when the user changes language — and so no caller has to
 * string-match to tell "already met" from "unreachable" (carry-notes §3.5).
 *
 * [targetAmountText] is the RAW `Money.amountText`, used ONLY to refill the edit field. Never
 * feed [currentText]/[targetText] into a parser — they carry "$" and grouping separators.
 */
data class GoalCardUi(
    val currentText: String,
    val targetText: String,
    val targetAmountText: String,
    val fraction: Double,
    val projection: GoalProjection,
)

/** Maps a (possibly absent) goal to card state. `null` means "no goal set" — the card still
 *  RENDERS, showing its "Set a goal" affordance; it is never hidden (carry-notes §1.3). */
fun goalCardUi(goal: PortfolioGoal?, current: Money, projection: GoalProjection?): GoalCardUi? {
    if (goal == null) return null
    return GoalCardUi(
        currentText = formatMoney(current.amountText),
        targetText = formatMoney(goal.target.amountText),
        targetAmountText = goal.target.amountText,
        fraction = GoalMath.progress(current, goal.target),
        // "Not computed yet" and "not enough history" read identically to a user, and both are
        // honest; collapsing them here keeps every downstream branch exhaustive over five cases.
        projection = projection ?: GoalProjection.InsufficientHistory,
    )
}

/**
 * Progress + honest projection for one portfolio goal. Shared by the Income surface (income goal)
 * and the Performance surface (value goal) — same card, same edit dialog, different
 * `title`/`kind`/`ui`.
 *
 * BINDING (carry-notes §1.3): every caller renders this card UNCONDITIONALLY — never inside an
 * empty-ledger, loading, or "report loaded" branch. A goal is a plan; it is most useful before you
 * hold anything, and the Swift wave shipped both cards behind exactly those gates before the
 * ruling. Follow the DRIP-card precedent: hoist above the state switch.
 */
@Composable
fun GoalCard(
    title: String,
    kind: GoalKind,
    ui: GoalCardUi?,
    onSet: (Money) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isEditing by remember { mutableStateOf(false) }
    var seedText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.textPrimary),
            )
            Spacer(Modifier.weight(1f))
            if (ui != null) {
                TextAction(tr(L10n.Key.EditGoal), DK.textSecondary) { seedText = ui.targetAmountText; isEditing = true }
                Spacer(Modifier.width(14.dp))
                TextAction(tr(L10n.Key.RemoveGoal), DK.down, onRemove)
            }
        }
        if (ui == null) {
            TextAction(tr(L10n.Key.SetGoal), DK.gold) { seedText = ""; isEditing = true }
        } else {
            ProgressBar(ui.fraction)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    ui.currentText,
                    style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary, fontFeatureSettings = "tnum"),
                )
                Text("/", style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary))
                Text(
                    ui.targetText,
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary, fontFeatureSettings = "tnum"),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${(ui.fraction * 100).roundToInt()}%",
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DK.gold, fontFeatureSettings = "tnum"),
                )
            }
            Text(
                goalProjectionText(ui.projection),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
            )
        }
    }

    if (isEditing) {
        GoalEditDialog(
            title = title,
            kind = kind,
            initialText = seedText,
            onSave = { amount -> isEditing = false; onSet(amount) },
            onCancel = { isEditing = false },
        )
    }
}

/** Renders every [GoalProjection] case as its own honest sentence — never collapsed into a generic
 *  on-track/off-track binary, and never a fabricated ETA (carry-notes §3.5).
 *
 *  `BeyondHorizon` INTERPOLATES [GoalMath.HORIZON_YEARS] rather than baking "30" into the copy, so
 *  the string cannot silently go stale if the constant moves. */
@Composable
fun goalProjectionText(projection: GoalProjection?): String = when (projection) {
    GoalProjection.Reached -> tr(L10n.Key.GoalReached)
    GoalProjection.NotOnTrack -> tr(L10n.Key.GoalNotOnTrack)
    GoalProjection.InsufficientHistory, null -> tr(L10n.Key.GoalNeedsHistory)
    GoalProjection.BeyondHorizon -> trf(L10n.Key.GoalBeyondHorizonFmt, GoalMath.HORIZON_YEARS.toInt().toString())
    is GoalProjection.Years -> {
        val rounded = if (projection.value < 10.0) {
            String.format(java.util.Locale.ROOT, "%.1f", projection.value)
        } else {
            projection.value.roundToInt().toString()
        }
        trf(L10n.Key.GoalYearsFmt, rounded)
    }
}

@Composable
private fun ProgressBar(fraction: Double) {
    val clamped = fraction.coerceIn(0.0, 1.0).toFloat()
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(DK.hairline)) {
        Box(Modifier.fillMaxWidth(clamped).height(6.dp).clip(RoundedCornerShape(50)).background(DK.gold))
    }
}

@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color),
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    )
}

/** Goal-target entry. Reuses the shared [AmountInput] parser rather than inventing a second
 *  validator, but validates against [GoalKind.targetRange] — an income target and a value target
 *  are different quantities at different scales, so the field's hint must describe the range it is
 *  actually enforcing (carry-notes §1.5). */
@Composable
private fun GoalEditDialog(
    title: String,
    kind: GoalKind,
    initialText: String,
    onSave: (Money) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val parsed = AmountInput.parse(text, kind.targetRange)
    val hintKey = when (kind) {
        GoalKind.Income -> L10n.Key.IncomeGoalRange
        GoalKind.Value -> L10n.Key.ValueGoalRange
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(DK.surface)
                .border(1.dp, DK.hairline, RoundedCornerShape(14.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    tr(L10n.Key.GoalTarget).uppercase(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 1.sp),
                )
                WizardTextField(value = text, onValueChange = { text = it }, placeholder = "0", fontSize = 20.sp)
                Text(
                    tr(hintKey),
                    style = TextStyle(
                        fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                        color = if (parsed == null && text.isNotBlank()) DK.down else DK.textTertiary,
                    ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                DialogButton(tr(L10n.Key.Cancel), DK.textSecondary, enabled = true, onClick = onCancel)
                DialogButton(tr(L10n.Key.SaveAction), DK.gold, enabled = parsed != null) { parsed?.let(onSave) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DialogButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f).clip(RoundedCornerShape(50))
            .background(color.copy(alpha = if (enabled) 0.16f else 0.06f))
            .border(1.dp, color.copy(alpha = if (enabled) 0.4f else 0.18f), RoundedCornerShape(50))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = if (enabled) color else color.copy(alpha = 0.45f),
            ),
        )
    }
}
```

If `WizardTextField`'s `internal` visibility does not reach `com.aptrade.desktop.goals` (it will — same Gradle module), promote it rather than copying it.

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test
```
Expected: green, up by 5. Report the observed count.

- [ ] **Step 5: Commit**

```
git add desktopApp/src/main/kotlin/com/aptrade/desktop/goals/GoalCard.kt \
        desktopApp/src/test/kotlin/com/aptrade/desktop/goals/GoalCardUiTest.kt
git commit -m "feat(desktop): shared goal card with per-kind target validation

One card and one edit dialog for both the income and value goals; the
projection stays a typed value so callers never string-match, and the
beyond-horizon copy interpolates GoalMath.HORIZON_YEARS.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 11: Desktop `IncomeViewModel` — calendar, forecast, income goal, DRIP rebuild

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/income/IncomeViewModel.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt` (`makeIncomeViewModel` gains the goal use cases and the live DRIP reader)
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/income/IncomeForecastGoalTest.kt` (new)
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/income/IncomeViewModelTest.kt` (update its `makeVm` for the new required constructor arguments)

**Interfaces:**
- Consumes: `DividendMath.incomeForecast/projectedSchedule/projectedAnnualIncome`, `ForecastYear`, `ScheduledDividend`, `GoalMath.incomeProjection`, `GoalMath.HORIZON_YEARS`, `LoadGoals`/`SaveGoal`/`RemoveGoal`, `goalCardUi` (Task 10).
- Produces:
  - `enum class ForecastHorizon(val years: Int) { Five(5), Ten(10), Twenty(20), Thirty(30) }` with `val label: String get() = "${years}y"`
  - `data class CalendarMonth(val id: String, val rows: List<ScheduledDividend>, val total: Money)`
  - `State` gains `calendarMonths`, `forecast`, `horizon`, `hasForecastIncome`, `incomeGoal: GoalCardUi?`
  - `IncomeViewModel(portfolioStore, marketDataRepository, calendar, scope, nowEpochSeconds, loadGoals, saveGoal, removeGoal, isDripEnabled: suspend () -> Boolean)`
  - `fun setHorizon(horizon: ForecastHorizon)`, `fun dripDidChange(enabled: Boolean)`, `fun setIncomeGoal(target: Money)`, `fun removeIncomeGoal()`

- [ ] **Step 1: Write the failing test**

Create `desktopApp/src/test/kotlin/com/aptrade/desktop/income/IncomeForecastGoalTest.kt`. Reuse `IncomeViewModelTest`'s `FakePortfolioStore`, `usd`, `qty`, `fixedNow`, `utc`, `quote` helpers by making them `internal` in that file rather than duplicating them (carry-notes §4 flags test-helper duplication as live debt — do not add a fifth copy).

```kotlin
package com.aptrade.desktop.income

import com.aptrade.desktop.FakeMarketDataRepository
import com.aptrade.shared.application.GoalStore
import com.aptrade.shared.application.LoadGoals
import com.aptrade.shared.application.RemoveGoal
import com.aptrade.shared.application.SaveGoal
import com.aptrade.shared.domain.Asset
import com.aptrade.shared.domain.AssetKind
import com.aptrade.shared.domain.DividendEvent
import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalMath
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import com.aptrade.shared.domain.Portfolio
import com.aptrade.shared.domain.PortfolioGoal
import com.aptrade.shared.domain.Quote
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M11.2 Task 11. Forecast, dividend calendar, income goal, and the DRIP-toggle wire. */
class IncomeForecastGoalTest {
    private val day = 86_400L
    private fun usd(s: String) = Money.usd(s)
    private fun qty(s: String) = BigDecimal.parseString(s)
    private val aapl = Asset("AAPL", "Apple Inc.", AssetKind.Stock)

    /** 2026-07-20T12:00:00Z — the same fixed "now" IncomeViewModelTest uses. */
    private val now = 1_784_548_800L

    private class MemoryGoalStore(var goals: List<PortfolioGoal> = emptyList()) : GoalStore {
        override suspend fun load(): List<PortfolioGoal> = goals
        override suspend fun save(goals: List<PortfolioGoal>) { this.goals = goals }
    }

    /** Twelve quarterly $0.25 payments — three years of flat history, cadence inferable. */
    private fun events(): List<DividendEvent> =
        (0 until 12).map { i -> DividendEvent("AAPL", now - (11 - i) * 91 * day, usd("0.25")) }

    private class Fixture(
        val viewModel: IncomeViewModel,
        val goals: MemoryGoalStore,
    )

    private fun fixture(
        scope: CoroutineScope,
        drip: Boolean = false,
        goals: MemoryGoalStore = MemoryGoalStore(),
        shares: String = "100",
        quotePrice: String? = "150",
    ): Fixture {
        val portfolio = Portfolio.starting(usd("50000"))
            .buying(aapl, qty(shares), usd("50"), now - 800 * day, "txn-1")
        val market = FakeMarketDataRepository()
        market.quotesImpl = { symbols ->
            if (quotePrice == null) emptyList()
            else symbols.map { Quote(it, usd(quotePrice), usd(quotePrice), 0.0) }
        }
        market.dividendEventsImpl = { _, _ -> events() }
        val vm = IncomeViewModel(
            portfolioStore = object : com.aptrade.shared.application.PortfolioStore {
                override suspend fun load(): Portfolio = portfolio
                override suspend fun save(portfolio: Portfolio) = Unit
            },
            marketDataRepository = market,
            scope = scope,
            nowEpochSeconds = { now },
            loadGoals = LoadGoals(goals),
            saveGoal = SaveGoal(goals),
            removeGoal = RemoveGoal(goals),
            isDripEnabled = { drip },
        )
        return Fixture(vm, goals)
    }

    // MARK: forecast

    @Test
    fun loadPublishesAForecastOfTheSelectedHorizonLength() = runTest {
        val f = fixture(this)
        f.viewModel.load(); runCurrent()
        assertEquals(ForecastHorizon.Ten, f.viewModel.state.value.horizon)
        assertEquals(10, f.viewModel.state.value.forecast.size)
        assertTrue(f.viewModel.state.value.hasForecastIncome)
    }

    @Test
    fun changingTheHorizonRebuildsWithoutAReload() = runTest {
        val f = fixture(this)
        f.viewModel.load(); runCurrent()
        f.viewModel.setHorizon(ForecastHorizon.Thirty); runCurrent()
        assertEquals(30, f.viewModel.state.value.forecast.size)
    }

    /** BINDING (carry-notes §1.1). The DRIP forecast must compound at the QUOTED $150, not the
     *  $50 cost basis — pinned by comparing against a run with no quotes at all. */
    @Test
    fun dripCompoundsAtTheQuotedPriceNotCostBasis() = runTest {
        val quoted = fixture(this, drip = true)
        quoted.viewModel.load(); runCurrent()
        val costBasisOnly = fixture(this, drip = true, quotePrice = null)
        costBasisOnly.viewModel.load(); runCurrent()
        val quotedYear10 = quoted.viewModel.state.value.forecast.last().income.amount
        val costBasisYear10 = costBasisOnly.viewModel.state.value.forecast.last().income.amount
        assertTrue(quotedYear10 < costBasisYear10, "cost-basis DRIP must overstate the quoted run")
    }

    /** BINDING (carry-notes §2.2). Flipping DRIP must rebuild the chart AND refresh the ETA. */
    @Test
    fun dripDidChangeRebuildsTheForecastAndRefreshesTheGoalProjection() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("50000"), 1L)))
        val f = fixture(this, drip = false, goals = goals)
        f.viewModel.load(); runCurrent()
        val before = f.viewModel.state.value.forecast.last().income.amount
        val beforeProjection = f.viewModel.state.value.incomeGoal!!.projection

        f.viewModel.dripDidChange(enabled = true); runCurrent()
        val after = f.viewModel.state.value.forecast.last().income.amount
        assertTrue(after > before, "DRIP on must raise the far-year forecast")
        // The projection is recomputed off the same rebuilt assumption — not left stale.
        assertNotNull(f.viewModel.state.value.incomeGoal)
        assertTrue(
            beforeProjection != f.viewModel.state.value.incomeGoal!!.projection ||
                beforeProjection is GoalProjection.BeyondHorizon,
            "the ETA must be recomputed against the curve that just changed",
        )
    }

    // MARK: income goal

    /** BINDING (carry-notes §3.1): the goal's "current" is forecast year 1's income exactly, so
     *  the progress % and ETA agree with the chart rendered beside them. */
    @Test
    fun theGoalsCurrentEqualsForecastYearOne() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("400"), 1L)))
        val f = fixture(this, goals = goals)
        f.viewModel.load(); runCurrent()
        val yearOne = f.viewModel.state.value.forecast.first().income
        // 100 shares x $1.00 trailing = $100 against a $400 target = 25%.
        assertEquals(usd("100"), yearOne)
        assertEquals(0.25, f.viewModel.state.value.incomeGoal!!.fraction, 1e-9)
    }

    /** BINDING (carry-notes §3.3): the ETA must not move when the chart horizon changes. */
    @Test
    fun theGoalEtaIsIndependentOfTheChartHorizon() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("400"), 1L)))
        val f = fixture(this, drip = true, goals = goals)
        f.viewModel.load(); runCurrent()
        val atTen = f.viewModel.state.value.incomeGoal!!.projection
        f.viewModel.setHorizon(ForecastHorizon.Five); runCurrent()
        assertEquals(atTen, f.viewModel.state.value.incomeGoal!!.projection)
        f.viewModel.setHorizon(ForecastHorizon.Thirty); runCurrent()
        assertEquals(atTen, f.viewModel.state.value.incomeGoal!!.projection)
    }

    @Test
    fun settingAGoalPersistsItAndPublishesCardState() = runTest {
        val f = fixture(this)
        f.viewModel.load(); runCurrent()
        assertNull(f.viewModel.state.value.incomeGoal)
        f.viewModel.setIncomeGoal(usd("6000")); runCurrent()
        assertEquals(usd("6000"), f.goals.goals.single().target)
        assertEquals(GoalKind.Income, f.goals.goals.single().kind)
        assertNotNull(f.viewModel.state.value.incomeGoal)
    }

    @Test
    fun removingAGoalClearsBothStoreAndState() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("6000"), 1L)))
        val f = fixture(this, goals = goals)
        f.viewModel.load(); runCurrent()
        f.viewModel.removeIncomeGoal(); runCurrent()
        assertTrue(f.goals.goals.isEmpty())
        assertNull(f.viewModel.state.value.incomeGoal)
    }

    /** Carry-notes §3.4: reset clears goals, so every appearance must re-read. */
    @Test
    fun aSecondLoadRereadsGoalStateRatherThanTrustingTheFirst() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("6000"), 1L)))
        val f = fixture(this, goals = goals)
        f.viewModel.load(); runCurrent()
        assertNotNull(f.viewModel.state.value.incomeGoal)
        goals.goals = emptyList()   // an external reset
        f.viewModel.load(); runCurrent()
        assertNull(f.viewModel.state.value.incomeGoal)
    }

    /** Carry-notes §2.4: a portfolio holding no dividend payer has an all-zero forecast — that is
     *  an absence of data, not a failing rate. */
    @Test
    fun aPortfolioWithNoDividendIncomeReportsInsufficientHistoryNotNotOnTrack() = runTest {
        val goals = MemoryGoalStore(listOf(PortfolioGoal(GoalKind.Income, usd("6000"), 1L)))
        val market = FakeMarketDataRepository()
        market.quotesImpl = { emptyList() }
        market.dividendEventsImpl = { _, _ -> emptyList() }
        val vm = IncomeViewModel(
            portfolioStore = object : com.aptrade.shared.application.PortfolioStore {
                override suspend fun load(): Portfolio = Portfolio.starting(usd("50000"))
                override suspend fun save(portfolio: Portfolio) = Unit
            },
            marketDataRepository = market,
            scope = this,
            nowEpochSeconds = { now },
            loadGoals = LoadGoals(goals),
            saveGoal = SaveGoal(goals),
            removeGoal = RemoveGoal(goals),
            isDripEnabled = { false },
        )
        vm.load(); runCurrent()
        assertFalse(vm.state.value.hasForecastIncome)
        assertEquals(GoalProjection.InsufficientHistory, vm.state.value.incomeGoal!!.projection)
    }

    // MARK: dividend calendar

    @Test
    fun theCalendarGroupsTwelveMonthsOfEstimatedPayoutsAscendingWithMonthTotals() = runTest {
        val f = fixture(this)
        f.viewModel.load(); runCurrent()
        val months = f.viewModel.state.value.calendarMonths
        assertTrue(months.isNotEmpty())
        assertEquals(months.map { it.id }.sorted(), months.map { it.id })
        for (month in months) {
            assertTrue(month.rows.isNotEmpty())
            val expected = month.rows.fold(BigDecimal.ZERO) { acc, row -> acc + row.estimatedAmount.amount }
            assertEquals(expected, month.total.amount)
            assertTrue(month.rows.all { it.exDateEpochSeconds > now && it.exDateEpochSeconds <= now + 365 * day })
        }
    }

    @Test
    fun aPortfolioWithNoProjectablePayoutsHasAnEmptyCalendar() = runTest {
        val market = FakeMarketDataRepository()
        market.quotesImpl = { emptyList() }
        market.dividendEventsImpl = { _, _ -> emptyList() }
        val vm = IncomeViewModel(
            portfolioStore = object : com.aptrade.shared.application.PortfolioStore {
                override suspend fun load(): Portfolio = Portfolio.starting(usd("50000"))
                override suspend fun save(portfolio: Portfolio) = Unit
            },
            marketDataRepository = market,
            scope = this,
            nowEpochSeconds = { now },
            loadGoals = LoadGoals(MemoryGoalStore()),
            saveGoal = SaveGoal(MemoryGoalStore()),
            removeGoal = RemoveGoal(MemoryGoalStore()),
            isDripEnabled = { false },
        )
        vm.load(); runCurrent()
        assertTrue(vm.state.value.calendarMonths.isEmpty())
    }

    @Test
    fun theGoalProjectionAlwaysReadsAFullHorizonForecast() {
        // Documents the contract the ETA test above exercises: 30 entries, not the pill's length.
        assertEquals(30, GoalMath.HORIZON_YEARS.toInt())
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test --tests "*IncomeForecastGoal*"
```
Expected: **compilation failure** — `ForecastHorizon`, `CalendarMonth`, the new `State` fields, and the four new constructor parameters do not exist.

- [ ] **Step 3: Implement**

In `IncomeViewModel.kt`, add these imports: `com.aptrade.desktop.goals.GoalCardUi`, `com.aptrade.desktop.goals.goalCardUi`, `com.aptrade.shared.application.LoadGoals`, `SaveGoal`, `RemoveGoal`, `com.aptrade.shared.domain.ForecastYear`, `GoalKind`, `GoalMath`, `PortfolioGoal`, `ScheduledDividend`.

Add above `State`:

```kotlin
/** Forecast chart horizon. Presented as pills (5/10/20/30) — no free slider. */
enum class ForecastHorizon(val years: Int) {
    Five(5), Ten(10), Twenty(20), Thirty(30);

    val label: String get() = "${years}y"
}

/** One month's worth of estimated upcoming dividend payments.
 *
 *  [rows] are [ScheduledDividend]s — cadence projections rolled forward from each holding's last
 *  REAL event, never announced ex-dates (carry-notes §3.7). Every row the UI renders from this
 *  must be labeled an estimate.
 *
 *  [id] is the UTC `"yyyy-MM"` bucket key; the pane formats the human month title from it, the
 *  same division of labour `MonthBar.id`/`monthLabel` already uses. There can be up to THIRTEEN
 *  entries: the window is a fixed 365 days, so with "now" mid-month the first and last buckets are
 *  both partial — and across a year boundary they share a month NAME with different years. The UI
 *  must therefore render the year too and must not assume a 12-item grid. */
data class CalendarMonth(
    val id: String,
    val rows: List<ScheduledDividend>,
    val total: Money,
)
```

Extend `State`:

```kotlin
    /** Estimated payouts over the next 365 days, grouped by UTC month, ascending. */
    val calendarMonths: List<CalendarMonth> = emptyList(),
    /** Per-holding-summed annual income projected [horizon] years forward. `forecast[0]`
     *  (yearOffset 1) is the trailing-twelve-month rate with NO growth — and is also exactly what
     *  the income goal's progress is measured against. */
    val forecast: List<ForecastYear> = emptyList(),
    val horizon: ForecastHorizon = ForecastHorizon.Ten,
    /** Whether [forecast] holds any actual income. `incomeForecast` always returns `horizon`
     *  entries — for a portfolio holding no dividend payer, every entry is a zero `Money` — so
     *  `forecast.isNotEmpty()` can never answer "is there anything to chart?". Exposed here rather
     *  than recomputed in the view so no pane has to reason about DividendMath's internals. */
    val hasForecastIncome: Boolean = false,
    /** `null` when no income goal is set — the card still RENDERS (carry-notes §1.3), showing its
     *  "Set a goal" affordance. */
    val incomeGoal: GoalCardUi? = null,
```

Extend the constructor (all four REQUIRED — carry-notes §4 records a no-op goal store silently discarding saves):

```kotlin
class IncomeViewModel(
    private val portfolioStore: PortfolioStore,
    private val marketDataRepository: MarketDataRepository,
    private val calendar: MarketCalendar = MarketCalendar(),
    private val scope: CoroutineScope,
    private val nowEpochSeconds: () -> Long,
    private val loadGoals: LoadGoals,
    private val saveGoal: SaveGoal,
    private val removeGoal: RemoveGoal,
    /** Reads the persisted DRIP toggle LIVE on each load — never captured once at construction,
     *  since the user can flip it at any point during a run. Suspend because Kotlin's settings
     *  store is real file I/O (the same recorded divergence `ProcessDueDividends` documents). */
    private val isDripEnabled: suspend () -> Boolean,
) {
```

Add the cached-input fields beside `_state`:

```kotlin
    // Inputs the forecast rebuild needs, cached during load() so changing the horizon or flipping
    // DRIP recomputes without a network round trip.
    private var lastPositions: List<Position> = emptyList()
    private var lastEventsBySymbol: Map<String, List<DividendEvent>> = emptyMap()

    /** Quotes from the last load, as `[symbol: price]`. Passed to `DividendMath.incomeForecast` so
     *  DRIP reinvestment compounds at the REAL quoted price rather than silently falling back to
     *  cost basis for every symbol. That fallback is `incomeForecast`'s own per-symbol behaviour
     *  for a symbol genuinely missing a quote — it must never become the behaviour for ALL symbols
     *  via an omitted argument (carry-notes §1.1). */
    private var lastPricesBySymbol: Map<String, Money> = emptyMap()

    private var dripEnabled: Boolean = false
    private var incomeGoal: PortfolioGoal? = null
```

At the end of `load()`'s `try` block — after the existing `_state.update { … }` — append:

```kotlin
                lastPositions = portfolio.positions
                lastEventsBySymbol = eventsBySymbol
                lastPricesBySymbol = quotes.mapValues { (_, quote) -> quote.price }
                dripEnabled = isDripEnabled()
                // Re-read on EVERY load, never gated on a first-load flag: a portfolio reset
                // clears goals as a side effect, and a screen that trusted its first read would
                // keep showing a deleted goal with a progress bar and an ETA computed against the
                // pre-reset curve (carry-notes §3.4).
                incomeGoal = loadGoals.execute().firstOrNull { it.kind == GoalKind.Income }
                _state.update { it.copy(calendarMonths = buildCalendar(portfolio.positions, eventsBySymbol, asOf)) }
                rebuildForecast()
```

Add the forecast/goal section:

```kotlin
    // MARK: - Forecast & income goal

    fun setHorizon(horizon: ForecastHorizon) {
        if (_state.value.horizon == horizon) return
        _state.update { it.copy(horizon = horizon) }
        rebuildForecast()
    }

    /** Call after the persisted DRIP setting changes.
     *
     *  BINDING (carry-notes §2.2): the toggle and the forecast chart live on the SAME screen, and
     *  the chart's caption actively promises DRIP compounding. Without this, flipping the toggle
     *  left every displayed year understated until the user happened to tap a horizon pill. It
     *  refreshes the income-goal projection too — that ETA reads the same curve, so rebuilding
     *  only the chart would leave it stale against a curve that just changed.
     *
     *  Takes the new value directly rather than re-reading the suspend settings store, so the
     *  rebuild cannot race the persist. */
    fun dripDidChange(enabled: Boolean) {
        dripEnabled = enabled
        rebuildForecast()
    }

    fun setIncomeGoal(target: Money) {
        val goal = PortfolioGoal(GoalKind.Income, target, nowEpochSeconds())
        scope.launch {
            saveGoal.execute(goal)
            incomeGoal = goal
            refreshGoalProjection()
        }
    }

    fun removeIncomeGoal() {
        scope.launch {
            removeGoal.execute(GoalKind.Income)
            incomeGoal = null
            _state.update { it.copy(incomeGoal = null) }
        }
    }

    private fun rebuildForecast() {
        val forecast = DividendMath.incomeForecast(
            positions = lastPositions,
            pricesBySymbol = lastPricesBySymbol,
            eventsBySymbol = lastEventsBySymbol,
            years = _state.value.horizon.years,
            dripEnabled = dripEnabled,
            asOfEpochSeconds = nowEpochSeconds(),
        )
        _state.update {
            it.copy(
                forecast = forecast,
                hasForecastIncome = forecast.any { year -> year.income.amount > BigDecimal.ZERO },
            )
        }
        refreshGoalProjection()
    }

    private fun refreshGoalProjection() {
        val goal = incomeGoal
        if (goal == null) {
            _state.update { it.copy(incomeGoal = null) }
            return
        }
        val asOf = nowEpochSeconds()
        // ALWAYS a full-horizon curve, independent of the chart's 5/10/20/30 pill (carry-notes
        // §3.3): a truncated forecast makes an unreachable goal indistinguishable from one reached
        // in year 31, i.e. "not on track" where "beyond horizon" is correct.
        val fullHorizon = DividendMath.incomeForecast(
            positions = lastPositions,
            pricesBySymbol = lastPricesBySymbol,
            eventsBySymbol = lastEventsBySymbol,
            years = GoalMath.HORIZON_YEARS.toInt(),
            dripEnabled = dripEnabled,
            asOfEpochSeconds = asOf,
        )
        // The SAME measure as forecast year 1 — both are projectedAnnualIncome's sum of
        // trailingAnnualPerShare x shares — so the card's progress % and ETA agree with what the
        // chart beside it shows for year 1 (carry-notes §3.1).
        val current = DividendMath.projectedAnnualIncome(lastPositions, lastEventsBySymbol, asOf)
        val projection = GoalMath.incomeProjection(current, goal.target, fullHorizon)
        _state.update { it.copy(incomeGoal = goalCardUi(goal, current, projection)) }
    }

    // MARK: - Dividend calendar

    /** Groups the next 365 days of ESTIMATED payouts into ascending UTC month buckets. */
    private fun buildCalendar(
        positions: List<Position>,
        eventsBySymbol: Map<String, List<DividendEvent>>,
        asOfEpochSeconds: Long,
    ): List<CalendarMonth> {
        val scheduled = DividendMath.projectedSchedule(
            positions = positions,
            eventsBySymbol = eventsBySymbol,
            throughEpochSeconds = asOfEpochSeconds + 365 * SECONDS_PER_DAY,
            asOfEpochSeconds = asOfEpochSeconds,
        )
        if (scheduled.isEmpty()) return emptyList()

        val grouped = linkedMapOf<String, MutableList<ScheduledDividend>>()
        for (row in scheduled) {
            grouped.getOrPut(monthKey(row.exDateEpochSeconds)) { mutableListOf() } += row
        }
        return grouped.entries.sortedBy { it.key }.map { (key, rows) ->
            val currency = rows.first().estimatedAmount.currencyCode
            CalendarMonth(
                id = key,
                rows = rows,
                total = Money(rows.fold(BigDecimal.ZERO) { acc, r -> acc + r.estimatedAmount.amount }, currency),
            )
        }
    }
```

(`monthKey` and `SECONDS_PER_DAY` already exist privately in this file — reuse them; do not add a copy.)

`AppGraph.kt` — extend the factory:

```kotlin
    fun makeIncomeViewModel(
        scope: CoroutineScope,
        nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): IncomeViewModel = IncomeViewModel(
        portfolioStore = portfolioStore,
        marketDataRepository = repository,
        calendar = marketCalendar,
        scope = scope,
        nowEpochSeconds = nowEpochSeconds,
        loadGoals = loadGoals,
        saveGoal = saveGoal,
        removeGoal = removeGoal,
        // Reads the live toggle at load time — the same `{ settingsStore.load().dripEnabled }`
        // seam `processDueDividends` already uses, so there is still exactly ONE persisted field.
        isDripEnabled = { settingsStore.load().dripEnabled },
    )
```

Update `IncomeViewModelTest`'s `makeVm` to pass the four new arguments (a local `MemoryGoalStore` and `isDripEnabled = { false }`) and make its helpers `internal` for reuse.

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
```
Expected: desktop green, up by 12; the 15 pre-existing `IncomeViewModelTest` cases still green. Report observed counts.

- [ ] **Step 5: Commit**

```
git add desktopApp/src/main/kotlin/com/aptrade/desktop/income/IncomeViewModel.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt \
        desktopApp/src/test/kotlin/com/aptrade/desktop/income/IncomeForecastGoalTest.kt \
        desktopApp/src/test/kotlin/com/aptrade/desktop/income/IncomeViewModelTest.kt
git commit -m "feat(desktop): income forecast, dividend calendar and income goal state

The goal ETA always reads a full 30-year forecast so it cannot move when
the chart horizon changes, and its 'current' is forecast year 1 exactly.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 12: Desktop Income UI — goal card, Dividend Calendar, forecast chart, DRIP wire

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/income/IncomePane.kt`

**Interfaces:**
- Consumes: everything Task 11 publishes plus `GoalCard` (Task 10) and the Task 8 keys.
- Produces: no new public API. UI composition ships under the standing waiver (Global Constraint 22) — Tasks 10 and 11 carry the behaviour.

- [ ] **Step 1: Write the failing test**

None — UI composition is covered by the standing waiver. **Instead, write down the four acceptance checks you will run by eye in Step 4, and do not tick this task without running them.**

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test
```
Expected: still green at Task 11's count — this task adds no test. The "failure" it closes is behavioural: today the pane renders no goal card, no calendar, no forecast, and its DRIP toggle is inert with respect to both.

- [ ] **Step 3: Implement**

In `IncomePane.kt`, the pane body becomes:

```kotlin
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        DripCard(
            checked = notificationSettings.dripEnabled,
            onCheckedChange = { checked ->
                onUpdateNotificationSettings { it.copy(dripEnabled = checked) }
                // BINDING (carry-notes §2.2): the persist above is fire-and-forget and nothing
                // observes the setting, so without this call the forecast chart directly below —
                // whose caption promises DRIP compounding — would keep the old assumption until
                // the user happened to tap a horizon pill, understating every displayed year. It
                // refreshes the income-goal projection too, which reads the same curve.
                viewModel.dripDidChange(enabled = checked)
            },
        )
        // The DRIP card and the income-goal card are this pane's reachability floor: BOTH render
        // above the state switch, unconditionally (carry-notes §1.3). Turning DRIP on, or setting
        // an income goal, BEFORE a first payout is the common case, not an edge case — the Swift
        // wave shipped the goal card inside the ledger branch and a user holding no dividend payer
        // could never set an income goal at all.
        GoalCard(
            title = tr(L10n.Key.IncomeGoal),
            kind = GoalKind.Income,
            ui = state.incomeGoal,
            onSet = { amount -> viewModel.setIncomeGoal(amount) },
            onRemove = { viewModel.removeIncomeGoal() },
        )
        when {
            state.isLoading && state.cards == null -> LoadingState()
            state.history.isEmpty() && state.upcoming.isEmpty() -> EmptyIncomeState()
            else -> IncomeContent(state, onSetHorizon = { viewModel.setHorizon(it) })
        }
    }
```

`IncomeContent` gains the two new cards between the monthly chart and the Upcoming/Holdings row:

```kotlin
@Composable
private fun IncomeContent(state: IncomeState, onSetHorizon: (ForecastHorizon) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        state.cards?.let { SummaryCardsGrid(it) }
        if (state.months.isNotEmpty()) MonthlyChart(state.months)
        DividendCalendarCard(state.calendarMonths)
        ForecastCard(state.forecast, state.hasForecastIncome, state.horizon, onSetHorizon)
        // The existing three-branch Upcoming/Holdings `when` block is copied through verbatim —
        // side-by-side when both lists are non-empty, otherwise whichever one has rows.
        when {
            state.upcoming.isNotEmpty() && state.holdings.isNotEmpty() -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    UpcomingSection(state.upcoming, Modifier.weight(1f))
                    HoldingsSection(state.holdings, Modifier.weight(1f))
                }
            }
            state.upcoming.isNotEmpty() -> UpcomingSection(state.upcoming)
            state.holdings.isNotEmpty() -> HoldingsSection(state.holdings)
        }
        if (state.history.isNotEmpty()) HistorySection(state.history)
    }
}
```

Add the two cards and the month-title formatter:

```kotlin
private val calendarMonthTitleFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

/** Full "August 2026" title for a `"yyyy-MM"` bucket key. The YEAR is always rendered: the
 *  underlying window is a fixed 365 days, so across a year boundary two partial buckets can share
 *  a month name, and the year is what disambiguates them. Falls back to the raw key on a malformed
 *  bucket rather than crashing the whole card over one bad group — same idiom as [monthLabel]. */
private fun calendarMonthTitle(key: String): String = try {
    YearMonth.parse(key).format(calendarMonthTitleFormatter)
} catch (e: Exception) {
    key
}

/** The 12-month projected payout calendar.
 *
 *  TITLED "Dividend Calendar" (carry-notes §1.4, BINDING) — deliberately NOT
 *  `L10n.Key.IncomeUpcomingTitle` ("Upcoming Dividends"), which belongs to the pre-existing
 *  next-payout list further down this same scroll view. Two identically-titled cards shipped
 *  briefly on Swift before this was caught.
 *
 *  EVERY row is an estimate (carry-notes §3.7): the upstream feed exposes no forward-declared
 *  ex-dates, so the card carries the disclaimer in its header and again per row. */
@Composable
private fun DividendCalendarCard(months: List<CalendarMonth>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(tr(L10n.Key.DividendCalendarTitle))
            Spacer(Modifier.weight(1f))
            Text(
                tr(L10n.Key.IncomeEstimatedBadge).lowercase(),
                style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = DK.textTertiary),
            )
        }
        if (months.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    tr(L10n.Key.NoDividendPayersHeld),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (month in months) CalendarMonthGroup(month)
            }
        }
    }
}

@Composable
private fun CalendarMonthGroup(month: CalendarMonth) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                calendarMonthTitle(month.id),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DK.textTertiary, letterSpacing = 0.4.sp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatMoney(month.total.amountText),
                style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = DK.textSecondary, fontFeatureSettings = "tnum"),
            )
        }
        // Two holdings can legitimately project onto the exact same ex-date within a month, so
        // rows are rendered in list order — never keyed by date, which could collide.
        for (row in month.rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.symbol,
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DK.textPrimary),
                )
                Text(
                    dateText(row.exDateEpochSeconds),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatMoney(row.estimatedAmount.amountText),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary, fontFeatureSettings = "tnum"),
                )
                Text(
                    tr(L10n.Key.IncomeEstimatedBadge).lowercase(),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = DK.textTertiary),
                )
            }
        }
    }
}

private const val FORECAST_CHART_HEIGHT_DP = 160

/** Multi-year income forecast with 5/10/20/30 horizon pills.
 *
 *  The header WRAPS rather than sharing one fixed-width row with the pills: carry-notes §4 records
 *  the Swift picker's narrow-width behaviour as unverified at 375pt, and the instruction for
 *  Kotlin is to design for narrow width from the start rather than inherit an unconfirmed
 *  fallback. Title on its own line, pills below, scrollable if the window is genuinely tiny. */
@Composable
private fun ForecastCard(
    forecast: List<ForecastYear>,
    hasIncome: Boolean,
    horizon: ForecastHorizon,
    onSetHorizon: (ForecastHorizon) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(tr(L10n.Key.IncomeForecastTitle))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (option in ForecastHorizon.entries) {
                HorizonPill(option.label, option == horizon) { onSetHorizon(option) }
            }
        }
        if (!hasIncome) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(
                    tr(L10n.Key.NoDividendPayersHeld),
                    style = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DK.textSecondary),
                )
            }
        } else {
            ForecastChart(forecast)
            Text(
                tr(L10n.Key.ForecastCaption),
                style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = DK.textTertiary),
            )
        }
    }
}

@Composable
private fun HorizonPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(
            fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) DK.gold else DK.textTertiary, fontFeatureSettings = "tnum",
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) DK.gold.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) DK.gold.copy(alpha = 0.4f) else DK.hairline, RoundedCornerShape(50))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Gold area+line over the forecast years, scaled to the largest year. Same Canvas idiom the
 *  monthly chart already uses — no new chart dependency. */
@Composable
private fun ForecastChart(forecast: List<ForecastYear>) {
    val values = forecast.map { it.income.amount.doubleValue(false) }
    val maxValue = values.maxOrNull() ?: 0.0
    if (values.size < 2 || maxValue <= 0.0) return
    Canvas(Modifier.fillMaxWidth().height(FORECAST_CHART_HEIGHT_DP.dp)) {
        val stepX = size.width / (values.size - 1).toFloat()
        fun y(v: Double) = (size.height - (v / maxValue * size.height)).toFloat()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, y(values.first()))
            values.forEachIndexed { i, v -> if (i > 0) lineTo(i * stepX, y(v)) }
        }
        val filled = androidx.compose.ui.graphics.Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(filled, DK.gold.copy(alpha = 0.14f))
        drawPath(path, DK.gold, style = Stroke(width = 2.dp.toPx()))
    }
}
```

Add the imports these need: `androidx.compose.foundation.clickable`, `androidx.compose.foundation.interaction.MutableInteractionSource`, `com.aptrade.desktop.goals.GoalCard`, `com.aptrade.shared.domain.ForecastYear`, `com.aptrade.shared.domain.GoalKind`.

- [ ] **Step 4: Run tests, then the four eyeball checks**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:run
```

Acceptance (all four must hold; kill the app afterwards):
1. **On a brand-new, never-traded portfolio**, Invest → Income shows the DRIP card AND the "Income Goal" card with "Set a goal" — neither hidden by the empty state.
2. **The Dividend Calendar's title differs from the Upcoming Dividends list's title** — scroll the whole pane and confirm no two cards share a heading.
3. **Flipping the DRIP toggle changes the forecast chart immediately**, without touching a horizon pill; if a goal is set, its ETA line updates in the same frame.
4. **Narrow the window as far as it goes** — the horizon pills scroll rather than clipping the section title.

Also run a content grep (no line numbers) to prove the calendar did not steal the existing title:
```
grep -rn "IncomeUpcomingTitle\|DividendCalendarTitle" desktopApp/src/main/kotlin
```
Expected: `IncomeUpcomingTitle` appears exactly once (in `UpcomingSection`), `DividendCalendarTitle` exactly once (in `DividendCalendarCard`).

- [ ] **Step 5: Commit**

```
git add desktopApp/src/main/kotlin/com/aptrade/desktop/income/IncomePane.kt
git commit -m "feat(desktop): Dividend Calendar, income forecast chart and income-goal card

The goal card renders above the loading/empty switch so it is reachable
before a first payout, and the DRIP toggle now rebuilds the forecast and
refreshes the goal projection instead of shipping inert.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 13: Desktop Performance — value goal + the "Since Inception" tile

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModel.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PerformanceSection.kt`
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt` (VM factory / construction gains the goal use cases)
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/ValueGoalTest.kt` (new)

**Interfaces:**
- Consumes: `GoalMath.valueProjection/accountAgeDays`, `Portfolio.inceptionEpochSeconds`, `goalCurrentValueFloor`, `goalCardUi`/`GoalCard`, `PerformanceMetrics.sinceInceptionReturn`, `LoadGoals`/`SaveGoal`/`RemoveGoal`, `L10n.Key.ValueGoal`/`SinceInception`.
- Produces: `PortfolioUiState.valueGoal: GoalCardUi?`; `MetricTexts.sinceInception: String`; `PortfolioViewModel.setValueGoal(target: Money)`, `PortfolioViewModel.removeValueGoal()`.

- [ ] **Step 1: Write the failing test**

Create `desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/ValueGoalTest.kt`, reusing the `portfolioViewModelFixture` helper Task 9 established:

```kotlin
package com.aptrade.desktop.portfolio

import com.aptrade.shared.domain.GoalKind
import com.aptrade.shared.domain.GoalProjection
import com.aptrade.shared.domain.Money
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M11.2 Task 13. The value goal on Performance, plus the since-inception metric tile. */
class ValueGoalTest {

    @Test
    fun settingAValueGoalPersistsItAndPublishesCardState() = runTest {
        val f = portfolioViewModelFixture(scope = this)
        f.viewModel.start(); runCurrent()
        assertNull(f.viewModel.state.value.valueGoal)
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        assertEquals(GoalKind.Value, f.goalStore.goals.single().kind)
        assertNotNull(f.viewModel.state.value.valueGoal)
    }

    @Test
    fun removingAValueGoalClearsBothStoreAndState() = runTest {
        val f = portfolioViewModelFixture(scope = this)
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        f.viewModel.removeValueGoal(); runCurrent()
        assertTrue(f.goalStore.goals.isEmpty())
        assertNull(f.viewModel.state.value.valueGoal)
    }

    /** BINDING (carry-notes §2.3): an EMPTY equity curve — which any offline or rate-limited
     *  session produces, not just an all-cash portfolio — must never render a fabricated $0
     *  against a real target. It is cash + cost basis of every position. */
    @Test
    fun anEmptyCurveFallsBackToCashPlusCostBasisNotZero() = runTest {
        // Fixture configured so the history fetch fails for every symbol.
        val f = portfolioViewModelFixture(scope = this, failHistory = true)
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        val card = f.viewModel.state.value.valueGoal!!
        assertTrue(card.fraction > 0.0, "a portfolio holding real assets must not read 0%")
        assertEquals(f.expectedCostBasisFloorText, card.currentText)
    }

    /** The all-cash reading is preserved exactly by the same expression. */
    @Test
    fun anAllCashPortfolioReadsItsCashAsCurrentValue() = runTest {
        val f = portfolioViewModelFixture(scope = this, holdings = emptyList())
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        assertEquals("\$100,000.00", f.viewModel.state.value.valueGoal!!.currentText)
    }

    /** THE §4a.2 DIVERGENCE, at the view-model seam: a brand-new account holding a seasoned
     *  symbol has a full priced curve but no account history, so the card says "needs more
     *  history" rather than projecting off a 20-day-old account. */
    @Test
    fun aNewAccountHoldingASeasonedSymbolReportsInsufficientHistory() = runTest {
        val f = portfolioViewModelFixture(scope = this, accountAgeDays = 20)
        f.viewModel.start(); runCurrent()
        f.viewModel.setValueGoal(Money.usd("500000")); runCurrent()
        assertEquals(GoalProjection.InsufficientHistory, f.viewModel.state.value.valueGoal!!.projection)
    }

    @Test
    fun theSinceInceptionTileRendersThePercentFromTheReport() = runTest {
        val f = portfolioViewModelFixture(scope = this)
        f.viewModel.start(); runCurrent()
        val metrics = f.viewModel.state.value.metrics
        assertNotNull(metrics)
        assertTrue(metrics.sinceInception.endsWith("%"))
    }

    @Test
    fun theSinceInceptionTileShowsAnEmDashWhenTheMetricIsUnavailable() = runTest {
        val f = portfolioViewModelFixture(scope = this, holdings = emptyList())
        f.viewModel.start(); runCurrent()
        assertEquals("—", f.viewModel.state.value.metrics?.sinceInception ?: "—")
    }
}
```

Extend `portfolioViewModelFixture` with the `failHistory`, `holdings`, and `accountAgeDays` knobs plus a `goalStore` field and an `expectedCostBasisFloorText` computed from the same holdings the fixture built. **Keep it in ONE place** — carry-notes §4 lists test-helper duplication as live debt.

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test --tests "*ValueGoal*"
```
Expected: **compilation failure** — `setValueGoal`, `state.valueGoal`, and `MetricTexts.sinceInception` do not exist.

- [ ] **Step 3: Implement**

`PortfolioViewModel.kt`:

```kotlin
data class MetricTexts(
    val totalReturn: String,
    val annualizedReturn: String,
    val volatility: String,
    val maxDrawdown: String,
    val sharpe: String,
    val beta: String,
    val alpha: String,
    /** Return measured from the balance the user actually opened at, not from the curve's first
     *  point — the reader that makes `Portfolio.startingCash` earn its place (M11.2 kickoff
     *  decision 4a.1). "—" when the metric is unavailable (no curve, or a non-positive opening
     *  balance); never a fabricated 0%. */
    val sinceInception: String,
)
```

`PortfolioUiState` gains, next to `metrics`:

```kotlin
    /** `null` when no value goal is set. The card still RENDERS (carry-notes §1.3) — this only
     *  selects between its progress body and its "Set a goal" affordance. */
    val valueGoal: GoalCardUi? = null,
```

Constructor gains three REQUIRED parameters (no no-op defaults — carry-notes §4):

```kotlin
    private val loadGoals: LoadGoals,
    private val saveGoal: SaveGoal,
    private val removeGoal: RemoveGoal,
```

Add private state and the goal section:

```kotlin
    private var equityCurve: List<PortfolioPerformancePoint> = emptyList()
    private var currentValue: Money = Money.usd("0")
    private var valueGoal: PortfolioGoal? = null

    fun setValueGoal(target: Money) {
        val goal = PortfolioGoal(GoalKind.Value, target, nowEpochSeconds())
        scope.launch {
            saveGoal.execute(goal)
            valueGoal = goal
            refreshValueProjection()
        }
    }

    fun removeValueGoal() {
        scope.launch {
            removeGoal.execute(GoalKind.Value)
            valueGoal = null
            _state.update { it.copy(valueGoal = null) }
        }
    }

    private fun refreshValueProjection() {
        val goal = valueGoal
        if (goal == null) {
            _state.update { it.copy(valueGoal = null) }
            return
        }
        // ACCOUNT AGE, not the price window's span (M11.2 kickoff decision 4a.2): fed from the ONE
        // named derivation `Portfolio.inceptionEpochSeconds()`, the same signal
        // FetchPortfolioPerformance's sinceInception trim uses, so the metric and the floor cannot
        // drift apart. A brand-new account holding a seasoned symbol therefore honestly reports
        // insufficient history instead of extrapolating three weeks of price movement.
        val accountAgeDays = GoalMath.accountAgeDays(portfolio.inceptionEpochSeconds(), nowEpochSeconds())
        val projection = GoalMath.valueProjection(currentValue, goal.target, equityCurve, accountAgeDays)
        _state.update { it.copy(valueGoal = goalCardUi(goal, currentValue, projection)) }
    }
```

In `loadPerformanceReport()`'s success branch, before the `_state.update`:

```kotlin
                equityCurve = report.points
                // The curve's LAST point is the true current total account value (cash +
                // holdings). When there is no curve at all, fall back to cash + every position's
                // OWN cost basis — never a hardcoded zero (carry-notes §2.3). The curve is empty
                // in two distinct situations and only one is exotic: genuinely all-cash
                // (FetchPortfolioPerformance returns emptyList() for a position-less portfolio),
                // and positions-exist-but-history-failed, which every offline or rate-limited
                // session hits. Neither may fabricate a dollar figure nobody's portfolio holds.
                currentValue = report.points.lastOrNull()?.value ?: portfolioSnapshot.goalCurrentValueFloor()
                valueGoal = loadGoals.execute().firstOrNull { it.kind == GoalKind.Value }
```
and add to the `metrics` literal:

```kotlin
                    sinceInception = report.metrics.sinceInceptionReturn
                        ?.let { formatPercent(it) } ?: "—",
```
and after the `_state.update { … }` call, `refreshValueProjection()`.

Also add the same two lines to the `catch (e: QuoteError)` branch — a portfolio-side history failure must still populate `currentValue` from the cost-basis floor and still re-read the goal, or the card would sit blank exactly when the fallback matters most:

```kotlin
            } catch (e: QuoteError) {
                // Portfolio-side history failure: leave prior report state as last-good, but the
                // goal card must still show an honest current value rather than nothing.
                if (equityCurve.isEmpty()) currentValue = portfolioSnapshot.goalCurrentValueFloor()
                valueGoal = loadGoals.execute().firstOrNull { it.kind == GoalKind.Value }
                refreshValueProjection()
            }
```
(the `catch` block must become able to suspend — it already sits inside `scope.launch`, so this compiles.)

In `reset(startingCash)` (Task 9), replace the placeholder `valueGoal = null` state copy with both the field reset and the state reset:

```kotlin
            valueGoal = null
            equityCurve = emptyList()
            currentValue = portfolio.goalCurrentValueFloor()
```
inside the launch, before `publish(loading = false)`.

`PerformanceSection.kt` — render the card FIRST inside the section's `Column`, above the header row, and extend the tile grid:

```kotlin
fun PerformanceSection(
    state: PortfolioUiState,
    onSetSpan: (PortfolioSpan) -> Unit,
    onSetBenchmark: (String) -> Unit,
    onSetValueGoal: (Money) -> Unit,
    onRemoveValueGoal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    …
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // UNCONDITIONAL (carry-notes §1.3): the value-goal card sits ABOVE everything the report's
        // load state governs. The Swift wave put it inside the `loaded` branch, so an all-cash
        // portfolio — money deposited, nothing bought — collapsed the section and the card
        // vanished. A goal is a plan; it is most useful before you hold anything.
        GoalCard(
            title = tr(L10n.Key.ValueGoal),
            kind = GoalKind.Value,
            ui = state.valueGoal,
            onSet = onSetValueGoal,
            onRemove = onRemoveValueGoal,
        )
        Row(verticalAlignment = Alignment.CenterVertically) { …unchanged header… }
```

and in `MetricGrid`, append one tile (the grid already chunks by 4 and pads the trailing row, so 8 tiles becomes two full rows with no layout change needed):

```kotlin
        tr(L10n.Key.Alpha) to (metrics?.alpha ?: "—"),
        tr(L10n.Key.SinceInception) to (metrics?.sinceInception ?: "—"),
```
Update the KDoc's "7-tile risk grid" to "8-tile".

`PortfolioPane.kt` — forward the two new lambdas at its `PerformanceSection(...)` call; `Main.kt` — forward them from `portfolioViewModel::setValueGoal` / `::removeValueGoal` through the existing pane parameter chain.

`AppGraph.kt` — wherever `PortfolioViewModel` is constructed, pass `loadGoals = loadGoals, saveGoal = saveGoal, removeGoal = removeGoal`.

- [ ] **Step 4: Run tests**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest :androidApp:testDebugUnitTest
```
Expected: desktop green, up by 7 plus Task 9's deferred goal-clearing case; shared and android unchanged. Then:
```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:run
```
Acceptance: Portfolio → Performance shows the "Value Goal" card **on a portfolio with zero holdings**, and the metric grid shows a "Since Inception" tile. Reset the portfolio and confirm the goal card returns to "Set a goal" without restarting the app. Kill after.

- [ ] **Step 5: Commit**

```
git add desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModel.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PerformanceSection.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioPane.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt \
        desktopApp/src/main/kotlin/com/aptrade/desktop/AppGraph.kt \
        desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/ValueGoalTest.kt \
        desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModelTest.kt
git commit -m "feat(desktop): value goal on Performance plus a Since Inception tile

The card renders above the report's load state so an all-cash portfolio
can still set a goal, its current value falls back to cash + cost basis
rather than a fabricated \$0, and the history floor measures account age.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 14: Close-out — hardcoded-balance sweep, seam audit, full verification

**Files:**
- Modify: whatever the sweep finds (expect: none, or a stray default)
- Modify: `README.md` if its Roadmap lists any of these three features as pending

**Interfaces:** none — this task adds no API.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/aptrade/shared/domain/StartingBalanceLiteralTest.kt`:

```kotlin
package com.aptrade.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M11.2 Task 14. Carry-notes §2.7: the Swift wave found TWO hardcoded balances that neither the
 * spec nor the plan anticipated — a defaulted `seedCash` closure and a no-op test double returning
 * a fabricated $100,000. Both hiding places are structural, not textual, so a grep alone will not
 * find them. This pins the ONE value every legitimate default must agree on, so a second literal
 * that drifts shows up as a failing assertion rather than as a user's wrong opening balance.
 */
class StartingBalanceLiteralTest {

    @Test
    fun theOnePermittedLiteralIsSharedByBothSanctionedDefaults() {
        assertEquals(Portfolio.DEFAULT_STARTING_CASH, Portfolio.starting().cash)
        assertEquals(
            Portfolio.DEFAULT_STARTING_CASH,
            com.aptrade.shared.settings.AppSettings().defaultStartingCash,
        )
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest --tests "*StartingBalanceLiteral*"
```
Expected: it should PASS immediately if Tasks 1 and 2 are correct. **If it fails, a second literal has already drifted — fix that, not the test.** (A test that passes on first run is acceptable here precisely because its job is to freeze an invariant against future drift.)

- [ ] **Step 3: Run the sweep and fix what it finds**

Content-based greps (no line numbers — line numbers move):

```
# 1. Every remaining opening-balance literal. Expect exactly two production hits:
#    Portfolio.DEFAULT_STARTING_CASH and FileSettingsStore's DEFAULT_STARTING_CASH_TEXT.
grep -rn '100000\|100_000\|"100000"' shared/src/commonMain shared/src/jvmCommonMain \
    desktopApp/src/main androidApp/src/main

# 2. Defaulted parameters that could hide a balance — the first Swift hiding place.
grep -rniE '(cash|balance|seed)[A-Za-z]* *: *Money *=' shared/src desktopApp/src androidApp/src

# 3. No-op / stub stores — the second Swift hiding place. Anything implementing GoalStore or
#    PortfolioStore with an empty or constant body outside a test fixture is a defect.
grep -rn ': *GoalStore\|: *PortfolioStore' shared/src desktopApp/src androidApp/src

# 4. The forecast's required argument is never omitted anywhere.
grep -rn "incomeForecast(" shared/src desktopApp/src androidApp/src
#    Every hit must pass pricesBySymbol explicitly.

# 5. Neither goal card sits behind a state gate.
grep -rn "GoalCard(" desktopApp/src/main
#    Read each call's surrounding block: it must not be inside an `if (loaded)`,
#    `if (!isEmptyLedger)`, or equivalent branch.

# 6. Both kickoff divergences are comment-documented in source.
grep -rn "RECORDED DIVERGENCE\|BACKPORT CANDIDATE" shared/src/commonMain
#    Expect at least: Portfolio.startingCash, GoalMath.MINIMUM_HISTORY_DAYS,
#    PerformanceMetrics.sinceInceptionReturn.
```

Fix anything the sweep turns up. Then reconcile the README: per the house rule, at feature merge PRUNE shipped items from the Roadmap rather than only adding to the feature list. If the README's Roadmap names configurable starting balance, portfolio goals, or the dividend calendar/forecast, move them into the shipped-features list and delete the Roadmap entries; update any test-count figures it quotes.

- [ ] **Step 4: Full verification**

```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest
```
Expected: all three green, 0 failures. Record the three real counts against the 612 / 359 / 282 baselines and state the delta per suite.

Because `commonMain` changed, also run the native gate so the macOS/iOS xcframework still compiles:
```
./scripts/build-shared.sh
```

Then boot once more and walk all three features end to end (kill after):
```
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :desktopApp:run
```
1. Reset the portfolio at a non-default amount; confirm Performance's "Since Inception" tile reflects THAT balance, not $100,000.
2. Set an income goal and a value goal; confirm both cards show progress, and that resetting clears both without an app restart.
3. Flip DRIP; confirm the forecast chart and the income goal's ETA line both move.

- [ ] **Step 5: Commit and request the whole-branch review**

```
git add -A
git commit -m "chore: M11.2 close-out — hardcoded-balance sweep and README roadmap prune

Co-Authored-By: Claude <noreply@anthropic.com>"
```

Then run a **top-tier whole-branch review** (carry-notes §5). Per-task reviews are structurally blind to seam defects: on the Swift wave, every one of the DRIP-toggle wire, the `currentValue` fallback, the income/value empty-case disagreement, and the goal-state re-read lived BETWEEN two tasks, and only the whole-branch review found them. Point the reviewer at this plan's Global Constraints as the checklist.

---

## Coverage map (spec + carry-notes → task)

| Requirement | Task |
|---|---|
| F1 · `Portfolio.starting(cash)` + `startingCash` | 1 |
| F1 · settings-persisted default | 2 |
| F1 · reset takes an amount | 4, 9 |
| F1 · validated amount field on the desktop reset flow | 3 (parser), 9 (field) |
| F2 · goal value type | 3 |
| F2 · goal math (progress, value projection, income projection) | 6 |
| F2 · goal store port + file adapter | 3 |
| F2 · goals cleared on reset | 4 (use case), 9 + 13 (re-read) |
| F2 · value-goal card on Performance | 10, 13 |
| F2 · income-goal card on Income | 10, 11, 12 |
| F3 · per-symbol dividend growth rate | 5 |
| F3 · multi-year DRIP income forecast | 5, 11 |
| F3 · 12-month projected schedule → "Dividend Calendar" card, every row "est." | 5, 11, 12 |
| F3 · forecast chart with 5/10/20/30 pills | 11, 12 |
| §1.1 DRIP at quoted price, `pricesBySymbol` required and second | 5 (signature + test), 11 (VM passes it) |
| §1.2 180-day floor | 6 |
| §1.3 both cards unconditional | 12, 13 |
| §1.4 "Dividend Calendar" title, no collision | 8, 12 (grep in 12 + 14) |
| §1.5 per-kind target ranges | 3, 10 |
| §2.1 → 4a.1 `startingCash` gets a real consumer | 7, 13 |
| §2.2 DRIP toggle rebuilds forecast AND goal projection | 11 (VM), 12 (wire) |
| §2.3 `currentValue` never a fabricated zero | 6 (floor), 13 (both branches) |
| §2.4 income projection distinguishes no-data from off-track | 6 |
| §2.5 separate goal store, not embedded in the portfolio payload | 3 |
| §2.6 verified symbol names only | plan-wide; every name traced to source |
| §2.7 hidden-literal sweep incl. defaults and no-op doubles | 14 |
| §3.1 income goal's current = forecast year 1 | 5, 11 |
| §3.2 year 1 carries no growth | 5 |
| §3.3 ETA independent of the chart horizon | 11 |
| §3.4 goal state re-read on every appearance | 11 (income), 13 (value) |
| §3.5 five distinct projection cases, horizon interpolated | 6, 10 |
| §3.6 the two clamps are exact and independent | 5, 6 |
| §3.7 every calendar row labeled an estimate | 5 (type doc), 12 (per-row + header) |
| §3.8 reuse the cadence constants | 5 |
| §4a.2 floor measures account age; new-account test | 6, 13 |
| §4 fractional-exponent route checked, not blindly transcribed | 5, 6 (both documented; `BigDecimal.pow` is Int/Long only) |
| §4 locale round-trip gap avoided | 3 (locale-independent parser, documented) |
| §4 test-helper duplication not worsened | 9, 11, 13 (single shared fixtures) |
| §4 no-op goal store hazard | 3, 4, 11, 13 (every injection required) |
| §4 narrow-width forecast picker designed for, not inherited | 12 |
| §5 whole-branch review budgeted | 14 |

## Known gaps, recorded deliberately

- **`AppSettings.defaultStartingCash` has no settings-screen editor.** Task 2 persists it and Task 9 seeds the reset field from it, but nothing yet writes it — the reset dialog does not save the typed amount back as the new default. Swift is the same. If the reviewer wants it, it is a two-line addition to Task 9's confirm handler (`onUpdateNotificationSettings { it.copy(defaultStartingCash = amount) }`); it is left out here because the Swift as-built is the reference and it does not do this.
- **Android is untouched beyond one compile fix** (Task 4). Its Income/Performance surfaces gain nothing this milestone; M11.3 owns them. Every piece they will need is already in `commonMain`/`jvmCommonMain`.
- **Currency codes are still dropped at a few `Money` construction sites** (carry-notes §4). Inert under the USD-only constraint; the existing backlog chip covers it.
