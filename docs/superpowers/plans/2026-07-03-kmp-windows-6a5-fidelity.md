# KMP Increment 6a.5 — Windows Desktop Fidelity Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the desktop Watchlist tab read and behave like the macOS app: fixed three-tab shell, full-window detail navigation (replacing the split pane), and the macOS header — average day change with a click-to-expand inline crosshair chart.

**Architecture:** All changes are in `:desktopApp` — no `:shared` changes. Navigation is plain Compose state in `AppRoot` (one-level push: `Watchlist | Detail(symbol)`); the header chart's crosshair math is pure, unit-tested functions in designkit; `WatchlistViewModel` gains two derived fields computed in `publish()`.

**Tech Stack:** Kotlin 2.1.0, Compose Multiplatform 1.7.3 (unchanged pins).

**Spec:** `docs/superpowers/specs/2026-07-03-kmp-windows-6a5-fidelity-design.md`

## Global Constraints

- Before EVERY `./gradlew`: `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`. Never system Gradle. Counts from JUnit XMLs with `--rerun-tasks`.
- No `:shared`, `:androidApp`, or Swift changes. Suites stay: shared **51**, androidApp **13**, Swift **193**. Desktop starts at **40** and grows as stated per task.
- Money display only via designkit (`splitPrice`/`SuperscriptPrice`) on `amountText`. The new header figures are PERCENTAGES (not money) — `Double` is correct for them; format via the existing `formatPercent`.
- Gold = brand/interactive only; green/red via `DK.changeColor` = price direction only.
- ViewModel scopes stay single-thread-confined (documented contract); DetailViewModel's per-selection scope-cancelled-on-dispose lifecycle is preserved verbatim.
- Test money literals must be zero-drop-immune (.25-style cents) — `Money.amountText` drops trailing zeros.

## Design note (refinement over the spec)

The spec says `averageSpark` is the "per-index mean of the row sparklines". Raw price averaging is meaningless across symbols (BTC ~61000 would swamp AAPL ~308), and the card's format is percentage points — so each spark is first **normalized to percent-change-from-its-first-value** (`(v/first − 1) × 100`), then averaged per index across the series that have a value at that index. This matches the macOS card's `percentagePoints` style and the header's percent headline.

---

### Task 1: Fix the tab row (all three tabs render)

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/ui/AppShell.kt` (TabRow, lines ~87–124)

**Interfaces:**
- Consumes: nothing new. Produces: no API change — visual fix only.

**Root cause (diagnosed):** each tab `Column`'s underline `Box` uses `fillMaxWidth()`; inside an unweighted `Row` child that propagates an unbounded max-width, so the first tab column expands to the full row and pushes Portfolio/News off-screen.

- [ ] **Step 1: Constrain each tab column to its intrinsic width**

In `TabRow`, change the tab `Column`'s modifier chain to start with an intrinsic width constraint:

```kotlin
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(IntrinsicSize.Min)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTabSelect(tab) }
                    .padding(horizontal = 18.dp),
            ) {
```

Add the imports `androidx.compose.foundation.layout.IntrinsicSize` and `androidx.compose.foundation.layout.width` (keep existing imports; `width` may already be imported). Everything else in `TabRow` stays byte-identical — with the column now intrinsically sized, the underline's `fillMaxWidth()` correctly spans just the label.

- [ ] **Step 2: Build + suite**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: BUILD SUCCESSFUL, XML count **40** (no test changes).

- [ ] **Step 3: Visual check**

Launch `./gradlew :desktopApp:run` in the background, wait ~20s, confirm the process is alive with no exceptions, then screenshot-free check is deferred to the human — but if the environment allows, note the tab row now lays out three labels. Kill the run.

- [ ] **Step 4: Commit**

```bash
git add desktopApp/src/main/kotlin/com/aptrade/desktop/ui/AppShell.kt
git commit -m "fix(desktop): tab row renders all three tabs — intrinsic width instead of greedy fillMaxWidth"
```

---

### Task 2: `averageChange` + `averageSpark` in WatchlistViewModel

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/watchlist/WatchlistViewModel.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/watchlist/WatchlistViewModelTest.kt` (append)

**Interfaces:**
- Consumes: existing `WatchlistUiState`, `publish()`, `sparks`/`quotes`/`entries` internals.
- Produces (Task 4 renders these): `WatchlistUiState.averageChange: Double?` (mean change% across ALL entries with quotes; null when none) and `WatchlistUiState.averageSpark: List<Double>` (normalized percent series, empty when < 2 usable points).

- [ ] **Step 1: Write the failing tests** (append to `WatchlistViewModelTest.kt`; reuse the file's existing `vm(...)`, `quote(...)`, `defaults`, `InMemoryStore` helpers exactly as the existing tests do):

```kotlin
    @Test
    fun averageChangeIsMeanAcrossAllKinds() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols ->
            symbols.map { quote(it, "100.25", when (it) { "AAPL" -> 2.0; "SPY" -> -1.0; else -> 5.0 }) }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(2.0, vm.state.value.averageChange)   // (2 - 1 + 5) / 3
    }

    @Test
    fun averageChangeIsNullWithoutQuotes() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { throw QuoteError.Network("down") }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertNull(vm.state.value.averageChange)
    }

    @Test
    fun averageSparkNormalizesEachSeriesToPercentThenAverages() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.25", 1.0) } }
        repo.historyImpl = { symbol, _ ->
            when (symbol) {
                "AAPL" -> listOf(PricePoint(1, Money.usd("100.25")), PricePoint(2, Money.usd("110.27")))  // +10%
                "SPY" -> listOf(PricePoint(1, Money.usd("200.50")), PricePoint(2, Money.usd("210.52")))   // +5%
                else -> emptyList()
            }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        val spark = vm.state.value.averageSpark
        assertEquals(2, spark.size)
        assertEquals(0.0, spark[0], 1e-9)
        assertEquals(7.5, spark[1], 1e-6)   // mean of +10% and +5%
    }

    @Test
    fun averageSparkToleratesLengthMismatch() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.25", 1.0) } }
        repo.historyImpl = { symbol, _ ->
            when (symbol) {
                "AAPL" -> listOf(PricePoint(1, Money.usd("100.00")), PricePoint(2, Money.usd("110.00")),
                                 PricePoint(3, Money.usd("121.00")))                                       // 0, +10, +21
                "SPY" -> listOf(PricePoint(1, Money.usd("200.00")), PricePoint(2, Money.usd("220.00")))   // 0, +10
                else -> emptyList()
            }
        }
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(listOf(0.0, 10.0, 21.0), vm.state.value.averageSpark)  // idx2 has only AAPL
    }

    @Test
    fun averageSparkEmptyWhenFewerThanTwoPoints() = runTest {
        val repo = FakeMarketDataRepository()
        repo.quotesImpl = { symbols -> symbols.map { quote(it, "100.25", 1.0) } }
        repo.historyImpl = { _, _ -> listOf(PricePoint(1, Money.usd("100.25"))) }  // single point each
        val vm = vm(repo, InMemoryStore(), backgroundScope)
        vm.start(); runCurrent()
        assertEquals(emptyList(), vm.state.value.averageSpark)
    }
```

(The `PricePoint` values feed Double pixel/percent math, so round `"100.00"`-style literals are fine here — only `amountText` assertions need `.25` cents. Add any missing imports the file doesn't already have: `assertNull` is already imported.)

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :desktopApp:test --console=plain
```
Expected: compilation FAILS — `averageChange`/`averageSpark` unresolved on `WatchlistUiState`.

- [ ] **Step 3: Implement**

In `WatchlistUiState`, add after `decliners: Int = 0`:

```kotlin
    val averageChange: Double? = null,        // mean change% across all entries; null when no quotes
    val averageSpark: List<Double> = emptyList(),  // per-index mean of percent-normalized row sparks
```

In `WatchlistViewModel`, add a private helper and extend `publish()`:

```kotlin
    /** Each spark normalized to percent-change-from-first, then averaged per index across
     *  the series that reach that index. Raw prices can't be averaged across symbols. */
    private fun averageSpark(): List<Double> {
        val normalized = entries.mapNotNull { e ->
            val s = sparks[e.symbol] ?: return@mapNotNull null
            val first = s.firstOrNull()?.takeIf { it != 0.0 } ?: return@mapNotNull null
            s.map { (it / first - 1) * 100 }
        }
        val maxLen = normalized.maxOfOrNull { it.size } ?: 0
        if (maxLen < 2) return emptyList()
        return (0 until maxLen).map { i -> normalized.mapNotNull { it.getOrNull(i) }.average() }
    }
```

and in `publish()`'s `_state.update`, after `decliners = ...`:

```kotlin
                averageChange = changes.takeIf { c -> c.isNotEmpty() }?.average(),
                averageSpark = averageSpark(),
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: PASS, XML count **45** (40 + 5).

- [ ] **Step 5: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): averageChange and percent-normalized averageSpark in WatchlistViewModel"
```

---

### Task 3: Crosshair math + ExpandedValueCard in designkit

**Files:**
- Create: `desktopApp/src/main/kotlin/com/aptrade/desktop/designkit/ValueCard.kt`
- Test: `desktopApp/src/test/kotlin/com/aptrade/desktop/designkit/ValueCardMathTest.kt`

**Interfaces:**
- Consumes: `DK`, `InterFamily`, `formatPercent`, `changeColor` (existing designkit).
- Produces (Task 4 uses): `fun crosshairIndex(pointerX: Float, chartWidth: Float, pointCount: Int): Int`; `fun percentPointDelta(values: List<Double>, index: Int): Double`; `@Composable fun ExpandedValueCard(title: String, values: List<Double>, onClose: () -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the failing math tests** (`ValueCardMathTest.kt`):

```kotlin
package com.aptrade.desktop.designkit

import kotlin.test.Test
import kotlin.test.assertEquals

class ValueCardMathTest {
    @Test fun midpointMapsToNearestIndex() = assertEquals(2, crosshairIndex(50f, 100f, 5))
    @Test fun leftEdgeIsFirstIndex() = assertEquals(0, crosshairIndex(0f, 100f, 5))
    @Test fun rightEdgeIsLastIndex() = assertEquals(4, crosshairIndex(100f, 100f, 5))
    @Test fun overshootClampsToLast() = assertEquals(4, crosshairIndex(250f, 100f, 5))
    @Test fun negativeClampsToFirst() = assertEquals(0, crosshairIndex(-10f, 100f, 5))
    @Test fun degenerateCountFallsBackToLast() = assertEquals(0, crosshairIndex(50f, 100f, 1))
    @Test fun zeroWidthFallsBackToLast() = assertEquals(4, crosshairIndex(50f, 0f, 5))

    @Test fun deltaIsValueMinusStart() = assertEquals(7.5, percentPointDelta(listOf(0.0, 3.0, 7.5), 2))
    @Test fun deltaAtStartIsZero() = assertEquals(0.0, percentPointDelta(listOf(2.0, 3.0), 0))
    @Test fun deltaOnEmptyIsZero() = assertEquals(0.0, percentPointDelta(emptyList(), 3))
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :desktopApp:test --console=plain
```
Expected: compilation FAILS — `crosshairIndex`/`percentPointDelta` unresolved.

- [ ] **Step 3: Implement `ValueCard.kt`**

```kotlin
package com.aptrade.desktop.designkit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Pointer x → nearest point index; clamped, degenerate inputs fall back sensibly. */
fun crosshairIndex(pointerX: Float, chartWidth: Float, pointCount: Int): Int {
    if (pointCount < 2) return 0
    if (chartWidth <= 0f) return pointCount - 1
    val raw = (pointerX / chartWidth * (pointCount - 1)).roundToInt()
    return raw.coerceIn(0, pointCount - 1)
}

/** Percentage-point change from the series start to `index` (0 when out of range/empty). */
fun percentPointDelta(values: List<Double>, index: Int): Double {
    val v = values.getOrNull(index) ?: return 0.0
    val start = values.firstOrNull() ?: return 0.0
    return v - start
}

/** The macOS ExpandedValueCard: an inline (in-flow, full-width) chart card grown from the
 *  header sparkline. Hover drives a crosshair; the headline shows the value under the
 *  cursor and its percentage-point delta from the period start. Percent data, not money. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ExpandedValueCard(
    title: String,
    values: List<Double>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hoverIndex by remember { mutableStateOf<Int?>(null) }
    var chartWidthPx by remember { mutableStateOf(0f) }
    val activeIndex = hoverIndex ?: (values.size - 1).coerceAtLeast(0)
    val activeValue = values.getOrNull(activeIndex) ?: 0.0
    val delta = percentPointDelta(values, activeIndex)
    val color = DK.changeColor(activeValue.takeIf { values.isNotEmpty() })

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DK.surface)
            .border(1.dp, DK.hairline, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title.uppercase(), style = TextStyle(fontFamily = InterFamily, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold, color = DK.textTertiary, letterSpacing = 1.sp))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatPercent(activeValue), style = TextStyle(fontFamily = InterFamily,
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = color,
                        fontFeatureSettings = "tnum"))
                    Text("${formatPercent(delta)} from start", style = TextStyle(fontFamily = InterFamily,
                        fontSize = 12.sp, color = DK.textSecondary, fontFeatureSettings = "tnum"))
                }
            }
            Spacer(Modifier.weight(1f))
            Text("✕", style = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, color = DK.textSecondary),
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = onClose)
                    .padding(4.dp))
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .onPointerEvent(PointerEventType.Move) { event ->
                    val x = event.changes.firstOrNull()?.position?.x ?: return@onPointerEvent
                    hoverIndex = crosshairIndex(x, chartWidthPx, values.size)
                }
                .onPointerEvent(PointerEventType.Exit) { hoverIndex = null },
        ) {
            chartWidthPx = size.width
            if (values.size < 2) return@Canvas
            val min = values.min(); val max = values.max()
            val span = (max - min).takeIf { it > 0.0 } ?: 1.0
            val stepX = size.width / (values.size - 1)
            fun y(v: Double) = size.height - ((v - min) / span * size.height).toFloat()
            val path = Path()
            values.forEachIndexed { i, v ->
                if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * stepX, y(v))
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            // crosshair: vertical hairline + dot at the active point
            val cx = activeIndex * stepX
            drawLine(DK.hairline, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx())
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(cx, y(activeValue)))
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: PASS, XML count **55** (45 + 10).

- [ ] **Step 5: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): ExpandedValueCard with unit-tested crosshair math in designkit"
```

---

### Task 4: Header parity + full-window detail navigation

**Files:**
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/watchlist/WatchlistPane.kt` (drop the split; add the macOS header)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/detail/DetailPane.kt` (rehost as `DetailScreen(symbol, onBack)`)
- Modify: `desktopApp/src/main/kotlin/com/aptrade/desktop/Main.kt` (`AppRoot` navigation state, Esc handling)

**Interfaces:**
- Consumes: Task 2's `averageChange`/`averageSpark`; Task 3's `ExpandedValueCard`; existing designkit (`Sparkline`, `PulseBar`, `numericStyle`-equivalents via `TextStyle`, `formatPercent`, `DK.changeColor`); existing `DetailContent` internals.
- Produces: `@Composable fun DetailScreen(symbol: String, onBack: () -> Unit)` (exported from DetailPane.kt; the old `DetailPane(selectedSymbol: String?)` is DELETED); `WatchlistPane` loses the `Row` split (signature otherwise unchanged, still takes `onSelect: (String) -> Unit` — now meaning "open detail").

This task is UI composition (no unit tests — 6a waiver pattern; the behavior lives in tested VMs and pure functions). Fidelity targets:

- **WatchlistPane:** delete the master–detail `Row`/hairline/`DetailPane` host; `MasterPane`'s content becomes the whole pane, full window width, list insets 16dp, header block horizontal padding 24dp. Header (mirrors macOS `pulse`, top-to-bottom): a row with the 34sp average-change figure (`formatPercent(state.averageChange)`, color `DK.changeColor(state.averageChange)`, tnum, "—" when null) + "Avg day change" 13sp `textSecondary` beside it + `Spacer(weight)` + a 140×36dp `Sparkline(state.averageSpark, DK.changeColor(state.averageChange))` that is clickable (no indication) to toggle `var chartExpanded by remember { mutableStateOf(false) }` — only rendered when `averageSpark.size > 1`. Below: `AnimatedVisibility(chartExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut())` hosting `ExpandedValueCard(title = "Avg day change", values = state.averageSpark, onClose = { chartExpanded = false })` with bottom padding 16dp. Below that: `PulseBar` at `width(180.dp)` (macOS `.frame(width: 180)`), then the existing KindToggle/LiveBadge row, AddField, and the list — all existing composition preserved.
- **DetailPane.kt → DetailScreen:** replace `fun DetailPane(selectedSymbol: String?)` (and its "Select a symbol" empty state, which no longer has a caller) with `fun DetailScreen(symbol: String, onBack: () -> Unit)`: a `Column` with a top bar (48dp height, horizontal padding 16dp): back affordance `"‹  Back"` (18sp, `DK.textSecondary`, clickable-no-indication → `onBack`) left-aligned, then the existing `DetailContent` filling the rest. The per-selection `remember(symbol) { CoroutineScope(...) }` + `DisposableEffect(symbol) { onDispose { scope.cancel() } }` + `DetailViewModel` creation move into `DetailScreen` unchanged (they currently live in `DetailPane`) — same lifecycle, keyed on `symbol`.
- **Main.kt `AppRoot`:** add `var openSymbol by remember { mutableStateOf<String?>(null) }`. The Watchlist tab branch becomes: `if (openSymbol != null) DetailScreen(symbol = openSymbol!!, onBack = { openSymbol = null }) else WatchlistPane(...)` with `onSelect = { symbol -> watchlistViewModel.onSelect(symbol); openSymbol = symbol }`. Palette `onAdd` additionally sets `openSymbol = asset.symbol` (keeps existing add+select calls). Switching to Portfolio/News tabs does NOT clear `openSymbol` (returning to Watchlist restores the open detail — matches a nav-stack feel).
- **Esc:** in `Main.kt`'s `Window(onPreviewKeyEvent = ...)`, extend: on `KeyDown` of `Key.Escape` — if the palette is open, close it (call `closePalette()`), else if a detail is open, go back. This needs `paletteOpen`/`openSymbol` visible at the Window level: hoist `openSymbol` state from `AppRoot` into `main()` alongside `paletteOpen`, passing `openSymbol`/`onOpenDetail`/`onBack` into `AppRoot` (keep `AppRoot`'s parameter list tidy — pass a small `nav` lambda pair, or individual params; follow the existing flat-parameter style).

- [ ] **Step 1: Implement the three files per the targets above.** No business logic in composables; nothing money-formatted outside designkit.

- [ ] **Step 2: Suite stays green**

```bash
./gradlew :desktopApp:test --console=plain --rerun-tasks
```
Expected: PASS, XML count **55** (no test changes this task).

- [ ] **Step 3: Live check (as far as the environment allows)**

`./gradlew :desktopApp:run` in background; confirm ≥60s alive with no exceptions while polling real data; kill cleanly. Interactive/visual verification (tabs, row→detail→back via chevron AND Esc, header expand/collapse, crosshair hover, palette→detail) falls to the human — say so in the report; do NOT claim it verified.

- [ ] **Step 4: Commit**

```bash
git add desktopApp/src
git commit -m "feat(desktop): full-window detail navigation and macOS header with expandable average chart"
```

---

### Task 5: Regression sweep + ledger evidence

**Files:** none created (verification only; report to the task report file).

- [ ] **Step 1: Full regression**

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest :desktopApp:test --console=plain --rerun-tasks
```
Expected (XML counts): shared **51**, androidApp **13**, desktopApp **55**. No Swift/iOS/xcframework re-proof needed — `:shared` is untouched this increment (verify with `git diff --stat main.. -- shared/ Sources/ Tests/` → empty).

- [ ] **Step 2: Report** the counts + the diff-scope proof. The human then does the live visual pass; merge flow (suites on main, push, CI-on-main watch) follows the 6a precedent.

---

## Self-review notes (already applied)

- Spec coverage: tab fix (T1), averageChange/averageSpark incl. normalization refinement (T2), ExpandedValueCard + pure crosshair math (T3), full-window navigation + Esc + header composition + sweep paddings (T4), regression + human gate (T5). No `:shared` changes anywhere — T5 proves it.
- Type consistency: `averageChange: Double?`/`averageSpark: List<Double>` (T2) match T4's renders; `crosshairIndex/percentPointDelta/ExpandedValueCard` signatures (T3) match T4's calls; `DetailScreen(symbol, onBack)` produced in T4 and consumed only in T4 (AppRoot).
- Deliberate cuts: no UI tests (waiver precedent), no nav library, Portfolio/News content untouched, alerts/account panel out of scope per spec.
