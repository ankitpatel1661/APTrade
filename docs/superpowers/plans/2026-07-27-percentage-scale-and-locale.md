# Percentage scale and locale — make the contract explicit

**Branch:** `fix/percentage-scale-and-locale` from `main` (`b820018`)
**Found:** manual UAT, 2026-07-27, from a single `0,0%` on the iOS Home screen.

Two defects. One is cosmetic; the other has been showing every performance percentage
**100× too small on three of four platforms**.

---

## Defect B — the serious one

`RiskMetrics.totalReturn` returns `last / first - 1` — a **fraction**. Both platforms' own tests
confirm it (`RiskMetricsTest.kt:19` asserts `0.5` for a 100→150 series).

`formatPercent` expects **percentage points** — its test asserts `formatPercent(4.84) == "+4.84%"`.

And the Kotlin view models pass the fraction straight in
(`desktopApp/.../portfolio/PortfolioViewModel.kt:485-493`, `androidApp/.../portfolio/PortfolioViewModel.kt:494-497`):

```kotlin
totalReturn      = formatPercent(report.metrics.totalReturn),
annualizedReturn = formatPercent(report.metrics.annualizedReturn),
volatility       = formatPercent(report.metrics.volatility),
maxDrawdown      = formatPercent(report.metrics.maxDrawdown),
sinceInception   = formatPercent(...)          // desktop only
```

**A 50% gain renders as `+0.50%`.** Both the Home tile and the whole metrics grid on Windows and
Android read the same pre-formatted `MetricTexts` string, so every surface is wrong together.

Swift's Home tile has the same bug via `signedPercent1dp` (`HomeView.swift:86-89`, no `× 100`),
while Swift's **Performance tab is correct** (`PerformanceSection.swift:199` does `v * 100`). So two
screens in the same app disagree by 100×.

**The fix is to make the scale explicit in the field names**, so a call site passing the wrong scale
reads wrong at a glance rather than failing silently. Decided by the user 2026-07-27 over the
cheaper patch-the-call-sites option.

## Defect A — the comma

`Double.formatted(.number…)` defaults to `Locale.autoupdatingCurrent`. The device is `en_DE`
(English language, German region — the developer's own machine), so that one formatter emits a
comma while every neighbour pins `en_US` via `NumberFormatter`.

---

## ⛔ Global Constraint 1 — the rename is SCOPED. Read this before touching anything.

**Rename ONLY these, which are provably not persisted:**

| Type | Where | Evidence it is safe |
|---|---|---|
| `RiskMetrics`' return values | `Sources/APTradeDomain/RiskMetrics.swift`, `shared/.../domain/RiskMetrics.kt` | pure functions, nothing stored |
| `PerformanceMetrics` fields | `Sources/APTradeDomain/RiskMetrics.swift:89-108`, `shared/.../application/FetchPerformanceReport.kt:14-35` | Swift: no `Codable`. Kotlin: plain `data class`, not `@Serializable` |
| `MetricTexts` fields | `desktopApp/`, `androidApp/` | pre-formatted `String` UI DTOs, never serialized |

**NEVER rename these — a rename here is a MIGRATION and would destroy user data:**

- **`Percentage.value`** (`Sources/APTradeDomain/Percentage.swift:3`). It is `Codable` with **no
  custom `CodingKeys`**, and `PieSlice.targetWeight: Percentage` is persisted inside `[Pie]` by
  `UserDefaultsPieStore.swift:17-26` via a bare `JSONEncoder`. A decode failure is swallowed by
  `try?` and **returns an empty list**, so renaming `value` would make **every user's saved Pies
  silently vanish** on next launch. Out of scope. Do not touch it.
- **`ScreenerSnapshotRow`'s percent fields** (`ScreenerMath.swift:5`) — `Codable` and persisted by
  `FileScreenerSnapshotStore.swift:25,32`. Lower stakes (a re-fetchable scan cache) but still a
  compatibility break. Out of scope.

If you believe a rename outside the safe list is required, **stop and report** rather than doing it.

## Global Constraint 2 — one name trap

`ScreenerMath.bollingerPercentB` (`ScreenerMath.swift:146-150`) has "Percent" in its name but
returns a bare **fraction** — `(close - l) / (u - l)`, no `× 100` — unlike its siblings
`dayChangePercent`/`pctVsSma50`, which really are points. Any pattern-matching pass over
"percent-named things" must not touch it. It is also out of scope per GC1 (persisted).

## Global Constraint 3 — discrimination-proven tests

Thirteen tests have been caught in this codebase passing in correct code because the fixture made
right and wrong implementations produce identical output. **This defect is guarded by the
fourteenth, and it is the first one guarding a genuinely broken behaviour:**

```kotlin
// desktopApp/src/test/kotlin/com/aptrade/desktop/portfolio/PortfolioViewModelTest.kt:523
assertTrue(s.metrics!!.totalReturn.endsWith("%"))
```

`"0.12%"` and `"12.00%"` both end with `%`. It cannot fail for any scaling or separator error.
`ValueGoalTest.kt:71-78` has the same shape for `sinceInception`.

For every test you add or change: name the wrong implementation it rejects, introduce it, confirm
**RED**, revert, confirm **GREEN**, paste both transcripts. **Assert the full string** — never a
suffix, never `endsWith`.

Note: every suite runs under the CI host's default locale, so a locale-sensitive formatter is
invisible to tests by construction. A locale test must set the locale explicitly.

## Global Constraint 4 — display vs. parse have opposite policies

This branch fixes **display** only. Do **not** touch `StartingBalanceInput.swift:16-31` or
`ScreenBuilderSheet.swift:55,126` — those are locale-**aware parse** paths (the carry-notes'
recorded "locale round-trip gap"), and pinning them to `en_US` would break input for a user typing
`1.234,56`. Display pins to `en_US`; parse follows the device. They are separate changes.

## Global Constraint 5 — suites

```
DEVELOPER_DIR=/Applications/Xcode.app swift test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest
```

Baseline at `b820018`: **Swift 727 · shared 712 · desktop 394 · Android 298**, 0 failures.
Use `:shared:jvmTest`, never `:shared:test`. Known-red, out of scope: `:shared:compileTestKotlinMacosArm64`.

---

## Task 1 — Swift: rename to carry the scale, and fix the Home tile

**Files:** `Sources/APTradeDomain/RiskMetrics.swift`, `Sources/APTradeApp/HomeView.swift`,
`Sources/APTradeApp/HomeViewModel.swift`, `Sources/APTradeApp/PerformanceSection.swift`, and the
tests referencing the renamed fields.

1. Rename `PerformanceMetrics`' fraction-bearing fields so the scale is unmissable — suggested
   `totalReturnFraction`, `annualizedReturnFraction`, `volatilityFraction`, `maxDrawdownFraction`,
   `alphaFraction`. **Leave `sharpe` and `beta` alone** — they are dimensionless ratios, not
   percentages, and must never go through a percent formatter.
2. Document on the type, in one line each, that these are fractions where `0.5` means 50%.
3. Fix `HomeViewModel.swift:214` — the property is named `totalReturnPercent` but stores a
   fraction. Rename it to match what it holds.
4. Fix `HomeView.swift:86-89` `signedPercent1dp`. It is fed a fraction and does no `× 100`.
   **Prefer deleting it** and routing through the existing pinned `Percentage.formatted`, which
   fixes the scale, the separator, and the 1dp-vs-2dp inconsistency with the neighbouring pill in
   one move. If you keep a bespoke helper, say why.
5. `PerformanceSection.swift:199,120-126` is already correct — update it for the renamed fields
   only, and **do not change its arithmetic**.

**Test:** assert the **full rendered string** for a known fraction on both the Home tile path and
the Performance path, and prove they agree. The wrong implementation to reject is the missing
`× 100`. A test asserting only that the string ends in `%` is exactly what let this ship.

**Commit:** `fix(domain): name performance metrics by their scale; correct the Home return tile`

## Task 2 — Kotlin: the same rename, and fix both view models

**Files:** `shared/.../domain/RiskMetrics.kt`, `shared/.../application/FetchPerformanceReport.kt`,
`desktopApp/.../portfolio/PortfolioViewModel.kt`, `androidApp/.../portfolio/PortfolioViewModel.kt`,
plus tests.

Mirror Task 1's names exactly — these are twins and must stay aligned. Note Kotlin's
`PerformanceMetrics` has an **8th field**, `sinceInceptionReturn`, with no Swift counterpart; rename
it on the same pattern.

Then fix every call site listed above so the fraction is scaled before it reaches `formatPercent`.
**Do not change `formatPercent` itself** — its points contract is correct, matches
`Percentage.formatted`, and is relied on by the Pie wizard (`totalReturnPP`), which is already right
and must not regress.

Kotlin already has a **`…PP`** suffix convention for percentage points (`PieSlice.targetWeightPP`,
`BacktestReport.totalReturnPP`). Renaming fractions to `…Fraction` reads correctly against it — the
two suffixes then say exactly what each carries.

**Test:** replace `PortfolioViewModelTest.kt:523`'s `endsWith("%")` and `ValueGoalTest.kt:71-78`'s
equivalent with **full-string** assertions on a known fixture. Android has no `formatPercent`
coverage at all — add it.

**Commit:** `fix(kotlin): scale performance metrics into points before formatting`

## Task 3 — Pin the five locale-sensitive Swift formatters

**Files:** `Sources/APTradeApp/ExpandableValueChart.swift:162,164`,
`Sources/APTradeApp/WatchlistView.swift:38,113`, and `HomeView.swift:88` if Task 1 has not already
removed it.

All five call `Double.formatted(.number…)` with no `.locale(...)`, so they follow the device region.
Every neighbouring number uses `NumberFormatter` pinned to `Locale(identifier: "en_US")` —
`Money.formatted` (`Money.swift:22-29`) and `Percentage.formatted` (`Percentage.swift:11-20`) are
the established idiom, used in 22 other places.

Match the idiom rather than adding a sixth ad-hoc variant. Note `ExpandableValueChart.swift:162`
contains **two** unlocalized sub-expressions on one line.

**Test:** a test that renders under a **non-US locale** and asserts a period. Without setting the
locale explicitly the test passes on any implementation, since CI runs `en_US`.

**Commit:** `fix(ios): pin the remaining number formatters to en_US`

---

## Verification

Beyond GC5's suites, this is done when a portfolio that has genuinely gained 50% shows **`+50.00%`**
— not `+0.50%` — on all four platforms, on both the Home tile and the Performance grid, and shows a
**period** on an `en_DE` device.

The Swift Home tile and Swift Performance tab must agree with each other; they currently differ by
100×, and that disagreement is the cheapest possible regression test to run by eye.
