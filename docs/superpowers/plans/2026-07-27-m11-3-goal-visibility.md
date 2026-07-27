# M11.3 — Goal visibility in the Portfolio header, all four platforms

**Branch:** `feature/m11-3-goal-visibility` from `main` (`abc70ea`)
**Design:** approved 2026-07-27, Option B — a goal strip between the header metrics and the section
switcher. **Value goal only**; the income goal's discoverability is a separate, unanswered question.

Closes the last open M11.3 item as well: **carry-notes §4b**, Android's reset ignoring
`AppSettings.defaultStartingCash`.

---

## The design, in one place

A full-width strip, rendered **only when a value goal is set**, showing:

```
GOAL   ▬▬▬▬▬▬▬▬░░░░   $120,000   833%
```

- **No current value.** The header already shows TOTAL VALUE in display type directly above; the
  strip would be repeating a number two lines up. Target + percentage is the whole payload, and it
  is what makes this fit at 375pt.
- **Hidden entirely when no goal is set.** No empty bar, no "Set a goal" prompt. A permanent
  call-to-action in the app's most-viewed chrome taxes every user who doesn't want the feature.
  Setting a goal stays on the Performance card, where the affordance already exists. **The header is
  a readout, not a control** — no tap target, no menu.
- **The bar clamps at 100%; the percentage does not.** This is not a detail to smooth over — it is
  the existing `GoalCard` behaviour (`GoalCard.swift:76,88`: `min(fraction, 1.0)` on the
  `ProgressView` only), and a real portfolio reading **833%** is the case that found this milestone.

---

## Global Constraints

### GC1 — Four platforms, one behaviour

The strip must appear, and be hidden, under identical conditions everywhere. Where a platform's
header already diverges (Android has no TOTAL VALUE label and unlocalized metric labels — Task 8),
fix the divergence rather than building the new row on top of it.

### GC2 — Reuse `GoalMath.progress`; never re-derive the fraction

Swift's `GoalCard.progressContent` computes `GoalMath.progress(current:target:)`. Kotlin computes it
once in `goalCardUi(...)` and stores it on `GoalCardUi.fraction`. **The strip must consume the same
value, not compute a second one.** Two formulas that agree today will drift.

Percentage rendering must match the card exactly: `Int((fraction * 100).rounded())` in Swift, and
the Kotlin equivalent. A strip reading 832% beside a card reading 833% is a defect.

### GC3 — Discrimination-proven tests (carry-notes §4e)

Twelve tests were caught in the preceding two branches passing in correct code because the fixture
made right and wrong implementations produce identical output — **three of them written to guard
those very fixes**. For every test pinning a decision:

1. Name the specific wrong implementation it rejects, in a comment.
2. Introduce it, run, **confirm RED**. Revert, confirm GREEN.
3. **Paste both transcripts in the task report.**

Two shapes are forbidden outright:
- `assert(before != after || before == someCase)` — passes unconditionally once the fixture makes
  the second disjunct true.
- A test that performs the action it is supposed to prove happens automatically. (A reset-propagation
  test that calls the refresh by hand proves the view model behaves *when asked*, not that anything
  asks.)

**The trap specific to this milestone:** the strip is *hidden* when no goal is set. A test asserting
"no strip" passes trivially if the fixture failed to build a header at all. Assert the visible case
and the hidden case with the **same** fixture, changing only the goal.

### GC4 — No new literals; reuse existing constants and formatters

No compact-currency formatter exists on any platform and this design does not need one. Do not add
`$1.2M`-style abbreviation. Use each platform's existing money formatting
(`Money.formatted` / `formatMoney`).

### GC5 — Every suite, every time

```
DEVELOPER_DIR=/Applications/Xcode.app swift test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :shared:jvmTest :desktopApp:test :androidApp:testDebugUnitTest
```

Baseline at `abc70ea`: **Swift 720 · shared 712 · desktop 391 · Android 282**, all 0 failures.
Use `:shared:jvmTest`, never `:shared:test` (a different, Android-target suite).
Known-red, out of scope: `:shared:compileTestKotlinMacosArm64`.

---

## Task 1 — The `GOAL` label key, both catalogs

**Files:** `Sources/APTradeApp/L10n.swift`, `shared/src/commonMain/kotlin/com/aptrade/shared/l10n/L10n.kt`

One new key. English **"Goal"** — deliberately distinct from the existing `valueGoal` ("Value Goal"),
which titles the card; the strip's label sits beside three other uppercase metric labels and must
read as one of them.

- **Swift:** add `case goalShort = "Goal"` to `Key`, and a `table` row with **all four** languages
  (English included — Swift's table stores English explicitly, unlike Kotlin's).
- **Kotlin:** add `GoalShort(english = "Goal")` to `Key`, plus a `Key.GoalShort to "…"` line in
  **each** of the German, Italian and Spanish blocks. English resolves via `Key.english` and must
  **not** get a table row.
- ⚠️ **`L10nCatalogTest` pins an exact key count** (`assertEquals(411, L10n.Key.entries.size)`,
  `shared/src/commonTest/.../L10nCatalogTest.kt:74-77`). Bump it to 412 and update its doc comment
  per that file's own convention of narrating each change. Swift's test pins no count.

German/Italian/Spanish for "Goal": use the existing goal vocabulary already in both catalogs
(`Ziel` / `Obiettivo` / `Objetivo`) rather than inventing new terms.

**Commit:** `feat(l10n): add the short GOAL label for the portfolio header strip`

## Task 2 — Swift: thread the goal into the header and render the strip

**Files:** `Sources/APTradeApp/PortfolioView.swift`, `Sources/APTradeApp/RootView.swift`

`PortfolioSummaryHeader` binds `PortfolioViewModel`, which has no goal state. The goal lives on
`PerformanceViewModel` (`valueGoal`, `valueGoalProjection`, `currentValue`, all `private(set)` and
externally readable).

**The precedent is already there:** `onDidReset: () async -> Void` (`PortfolioView.swift:221`) exists
precisely because this header must talk to the Performance view model's lifecycle, and both call
sites already close over their host's `performanceVM`. Follow it — add
`let performanceVM: PerformanceViewModel` as a stored property and pass it at both sites
(`PortfolioView.swift:51`, `RootView.swift:438`).

**Make it required, not optional.** A defaulted `nil` is how the preceding branch shipped a silent
regression: deleting the argument at one host compiled clean and left the whole suite green. Both
hosts already have the value in scope.

Render the strip after the metric row and before `Simulated · paper trading`, inside both the iOS
and macOS `#if os` branches. Match the surrounding idiom: the label uses the same 10pt/semibold/
1.0-tracking treatment as `metric(...)`'s label.

**Tests** (`Tests/APTradeAppTests/`): the strip's data is a pure function of
`(valueGoal, currentValue)`. Extract that mapping to a testable function rather than asserting on a
SwiftUI body — view hierarchies are untestable here without new tooling, a limitation the previous
branch hit and honestly recorded. Pin: hidden when `valueGoal == nil`; visible with target and
percentage when set; **percentage exceeds 100 while the bar fraction clamps** (the 833% case).

**Commit:** `feat(ios): surface value-goal progress in the portfolio header`

## Task 3 — Windows desktop: the strip

**File:** `desktopApp/src/main/kotlin/com/aptrade/desktop/portfolio/PortfolioPane.kt`

The cheapest platform: `PortfolioUiState.valueGoal: GoalCardUi?` is already the exact struct
`SummaryHeader` reads. No plumbing.

Add the strip inside `SummaryHeader`'s `Column`, after the `StatTile` row. `GoalCardUi` already
carries `targetText`, `fraction` and `projection` — use `targetText` and derive the percentage from
`fraction` per GC2. Reuse the `ProgressBar` composable from
`desktopApp/.../goals/GoalCard.kt:184-190` rather than drawing a second bar.

**Tests** (`desktopApp/src/test/`): same three cases as Task 2.

**Commit:** `feat(desktop): surface value-goal progress in the portfolio header`

## Task 4 — Android: goal state in the view model

**Files:** `androidApp/.../portfolio/PortfolioViewModel.kt`, `androidApp/.../AppGraph.kt`, and the
`PortfolioViewModel` construction site (**find it — it is NOT in `AppGraph.kt`**; recon located
`goalStore` at `AppGraph.kt:161` but the VM is constructed elsewhere, likely the nav host).

Android has **no** goal infrastructure. `AppGraph.kt:161` holds an unwired `FileGoalStore`, kept
deliberately so this task picks up the same file path and format.

Mirror desktop's `PortfolioViewModel` exactly (`desktopApp/.../portfolio/PortfolioViewModel.kt`):
- constructor gains `loadGoals: LoadGoals`, `saveGoal: SaveGoal`, `removeGoal: RemoveGoal`
- `PortfolioUiState` gains `val valueGoal: GoalCardUi? = null`
- `setValueGoal(target: Money)`, `removeValueGoal()`, and a private `refreshValueProjection()` using
  `GoalMath.accountAgeDays(portfolio.inceptionEpochSeconds(), nowEpochSeconds())` and
  `GoalMath.valueProjection(...)`

`GoalCardUi`/`goalCardUi(...)` currently live in `desktopApp`. **Decide and report:** either hoist
them to a shared module both apps consume, or port them into `androidApp`. Hoisting is the better
answer if nothing desktop-specific leaks in — check `formatMoney`'s home before committing to it.
Do not silently duplicate a third copy.

Android uses `viewModelScope`, not an injected `scope` — do not transplant desktop's `scope` param.

**Commit:** `feat(android): wire portfolio value goals into the view model`

## Task 5 — Android: the goal card on Performance

**Files:** `androidApp/.../portfolio/PortfolioScreen.kt`, plus wherever Task 4 put `GoalCardUi`

Port desktop's `GoalCard` to Compose Material 3, rendered as the **first** element of Android's
Performance section, unconditionally — matching desktop (`PerformanceSection.kt:93`) and Swift
(`PerformanceSection.swift:70`). Carry-notes §1.3: the card renders even with no holdings, because
setting a goal before your first trade is the common case.

Reuse the existing L10n keys (`ValueGoal`, `SetGoal`, `EditGoal`, `RemoveGoal`, `GoalTarget`,
`GoalReached`, `GoalNotOnTrack`, `GoalNeedsHistory`, `GoalBeyondHorizonFmt`, `GoalYearsFmt`) — all
already exist in the shared catalog. No new keys.

Per-kind validation bounds are already in the shared core (`PortfolioGoal.targetRange`) — use it;
do not re-declare bounds. Carry-notes §1.5: a $600/yr income goal was unsettable because bounds were
borrowed from the wrong quantity.

**Commit:** `feat(android): render the value-goal card on the Performance section`

## Task 6 — Android: the strip

**File:** `androidApp/.../portfolio/PortfolioScreen.kt`

Add the strip to `SummaryHeader` after the two metric rows. Same rules as Tasks 2 and 3.

**Commit:** `feat(android): surface value-goal progress in the portfolio header`

## Task 7 — Android: reset honours the configured starting balance (carry-notes §4b)

**Files:** `androidApp/.../portfolio/PortfolioViewModel.kt`, `androidApp/.../portfolio/PortfolioScreen.kt`,
`androidApp/.../settings/SettingsScreen.kt`

The last open M11.3 item, and a live cross-platform inconsistency: **Windows honours a chosen
starting balance and Android silently uses $100,000.**

Today: `reset()` takes no argument and calls `resetPortfolio.execute(Portfolio.DEFAULT_STARTING_CASH)`
(`PortfolioViewModel.kt:321-339`). The confirm dialog has no amount field and its body is the literal
`"Start over with $100,000?"`. `SettingsScreen.kt:485` renders Starting Balance as the hardcoded
string `"$100,000.00"`, never reading `AppSettings.defaultStartingCash`.

1. `reset(startingCash: Money)` — mirror desktop's signature (`desktopApp/.../PortfolioViewModel.kt:388`),
   including that it **re-reads the goal** from `loadGoals` afterwards rather than clearing it
   (M11.1 UAT F1: a reset must not clear goals).
2. Give the dialog an amount field pre-filled from `AppSettings.defaultStartingCash`, validated
   against the same $1,000–$10,000,000 bounds the other platforms use. Reuse the shared bounds; do
   not restate them. Replace the hardcoded dialog strings with L10n keys — the reset copy already
   exists in the catalog (`ResetPortfolio`, `ResetPortfolioTitle`, `StartingBalance`,
   `StartingBalanceRange`).
3. Make the Settings row read the real `AppSettings.defaultStartingCash` instead of a literal.

**Pin it:** a test that a configured non-default balance actually reaches `resetPortfolio.execute` —
carry-notes §4b names this exact assertion. The wrong implementation it rejects is the current
`Portfolio.DEFAULT_STARTING_CASH` hardcode.

**Commit:** `fix(android): honour the configured starting balance on reset`

## Task 8 — Android: localize the header and restore its TOTAL VALUE label

**File:** `androidApp/.../portfolio/PortfolioScreen.kt`

Two pre-existing divergences, both visible the moment Task 6's localized strip lands beside them:

1. `SummaryMetric` is called with **hardcoded English literals** — `"Cash"`, `"Holdings"`,
   `"Unrealized"`, `"Realized"` (`PortfolioScreen.kt:378-386`) — while desktop's `StatTile` uses
   `tr(L10n.Key.…)`. In German the strip would read "ZIEL" beside "CASH". Keys already exist
   (`CashLabel`, `HoldingsSection`, `UnrealizedPnL`, `RealizedPnL`).
2. Android's header renders the total value with **no "TOTAL VALUE" label** at all, unlike the other
   three (`PortfolioScreen.kt:369-372`). Add it, using the existing `TotalValue` key.

Also replace the hardcoded `"Reset portfolio…"` button literal (`:270`) if Task 7 has not already.

**No new L10n keys.** If you find yourself adding one, stop and report — every string here already
exists in the shared catalog.

**Commit:** `fix(android): localize the portfolio header and restore its total-value label`

---

## Verification

Beyond GC5's suites, the milestone is done when, on **each** of the four platforms:

| Case | Expected |
|---|---|
| No goal set | No strip. Header identical to today. |
| Goal set, 42% of $100,000 | `GOAL ▬▬▬░░░ $100,000 42%` |
| Goal exceeded — $1,000,000 against $120,000 | `833%`, bar full but not overflowing |
| Goal removed from the Performance card | Strip disappears without leaving the screen |
| Android only: reset with a configured $250,000 | Portfolio opens at $250,000, goal survives |

The last two are seam cases across tasks, and per-task reviews are structurally blind to them —
budget a whole-branch review at close.
