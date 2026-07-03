# KMP Increment 6a — Windows Desktop App: Design Kit, Shell, Watchlist (Design)

**Date:** 2026-07-03
**Status:** Approved
**Predecessors:** `2026-07-01-kmp-walking-skeleton-design.md` (increments 1–5 all merged;
the shared Kotlin core serves all five `MarketDataRepository` methods on macOS, iOS, and
Android).

## Context

Increment 6 of the KMP program is the Windows front end. Two decisions were deferred
until now and are resolved here:

1. **Stack: Compose Desktop, not WinUI.** The user has **no Windows machine or VM**.
   WinUI 3 can only be compiled on Windows with Visual Studio — it could never be
   built, run, or debugged in this environment. Compose Desktop is a JVM app that is
   developed and verified live on this Mac and runs identically on Windows;
   `:shared`'s existing `jvm()` target and Ktor CIO engine are consumed
   Kotlin-to-Kotlin with no bridging. Windows ground truth comes from CI
   (see §10).
2. **Parity goal: the Windows app must look and eventually work like the macOS app.**
   The user chose **full feature parity** as the destination. Since the macOS app's
   portfolio, news, and alerts logic exists only in Swift today, parity is a
   multi-increment program, decomposed below. **This spec designs only increment 6a.**

## The Windows parity program (roadmap, not designed here)

| Increment | Delivers | New shared-core work |
|---|---|---|
| **6a (this spec)** | Compose design kit + app shell + Watchlist tab + asset detail + Ctrl+K palette, CI `.msi` | Watchlist persistence port |
| 6b | Portfolio tab: paper trading, holdings, PnL, portfolio charts, export | Port portfolio/transaction domain to Kotlin |
| 6c | News tab + asset-news section | Port Finnhub news repository to Kotlin |
| 6d | Alerts, account panel, settings, light theme, localization, performance/risk | Port alerts + settings seams |

Each of 6b–6d also benefits macOS: once those domains live in the shared core, the
Swift app can adopt them the way it adopted market data (increments 3–4b).

## Goal of 6a

A `:desktopApp` Compose Desktop module that recreates the macOS app's visual identity
and shell, with a fully working Watchlist tab (live prices, add/remove, persistence),
asset detail (charts + stat tiles), and Ctrl+K search palette — verified live on this
Mac against real Yahoo data, with a GitHub Actions Windows job producing an `.msi`
installer artifact as the Windows build proof.

## Scope decisions (locked)

1. **Compose Desktop; WinUI eliminated** (no Windows access).
2. **Approach A+**: separate `:desktopApp` module; NO Compose Multiplatform
   restructure of `:androidApp`. Desktop UI code is written extraction-ready (pure
   Compose APIs, no Android imports) so a later shared-UI module remains cheap, but
   extraction happens only when a real second consumer appears (rule of three).
3. **Visual identity = the macOS app**, recreated component-by-component from
   `Sources/APTradeApp/DesignKit.swift` / `Theme.swift`, verified side-by-side
   against the running macOS app on this machine.
4. **Feature scope of 6a** = Watchlist tab + detail + palette. Portfolio and News
   tabs are present in the tab switcher but show a branded "coming soon" placeholder
   (they are 6b/6c).
5. **The one new shared-core seam is watchlist persistence** (§7). No other
   shared-core changes.
6. **Done =** Mac-verified live + all suites green + CI Windows build with `.msi`
   artifact and desktop tests running on the Windows runner.

## Module & build

- New `:desktopApp` Gradle module: Kotlin JVM plugin + `org.jetbrains.compose` +
  `org.jetbrains.kotlin.plugin.compose` (the Kotlin-2.1.0-bundled compose compiler).
- Compose Multiplatform pinned to a version from the 1.7.x line tested against
  Kotlin 2.1.0 — exact pin verified during planning against the official
  compatibility table (same care as the AGP 8.7.2 pin).
- `settings.gradle.kts` gains `include(":desktopApp")`.
- `:shared` is consumed via its existing `jvm()` target; the Ktor CIO engine actual
  already exists. **No `:shared` target changes.**
- Dev loop: `./gradlew :desktopApp:run` opens the window on macOS.
- JAVA_HOME/Gradle-wrapper gotchas per `kmp-toolchain-gotchas` memory apply
  unchanged.

## Architecture & DI

Clean Architecture as everywhere else. One deliberate difference from Android
(demanded by the increment-5 final review): **no `object` singleton**. A plain
`AppGraph` class is constructed once in `main()`, owns the single
`YahooMarketDataRepository`/Ktor client and the watchlist store, and is passed to
the UI explicitly — explicit lifetime, closeable client, testable. MVVM with
StateFlow ViewModels; CancellationException is always rethrown before domain-error
catches (uniform with Android).

## Compose DesignKit — the visual foundation for 6a–6d

Recreated 1:1 from the Swift originals, in a dedicated package
(`com.aptrade.desktop.designkit`), one composable per Swift component:

- **Palette/Theme**: exact colors from `Theme.swift` — warm near-black ground, gold
  gradient `#A9772A → #D4A94E → #F2DDA0`, silver secondary, warm-white text.
  Gains green / losses red (`changeColor` equivalent): price-direction color is
  data, never branding. Dark-only in 6a (light theme is 6d).
- **Components**: `BrandMark` (wordmark; reuse the logo assets under `logo/`),
  `LiveBadge` (pulsing gold), `SuperscriptPrice` (raised cents), `ChangePill`,
  `KindToggle` (Stocks/ETFs/Crypto), `PulseBar`, `TimeframeBar`
  (underline-selected), `StatTile`, `Sparkline`, plus the line/candlestick chart
  Canvas (adapted from `androidApp`'s `Charts.kt`, which is already pure Compose).
- **Typography**: SF Pro is not redistributable to Windows; bundle **Inter** as the
  closest stack (Linear/Stripe lineage, matches the design philosophy). Monospaced
  digits (tabular numerals) for every figure.
- **Fidelity check**: each component compared side-by-side against the running macOS
  app on this machine. Pixel-exact is asymptotic (font metrics differ); "reads as
  the same app" is the bar.

## UI — same layout as the macOS app

- **Shell** (mirrors `RootView.macBody`): wordmark header (~108pt, centered) with
  palette button top-right; tab switcher Watchlist / Portfolio / News; min window
  size ~560×680, resizable. Portfolio/News render a branded placeholder pane.
- **Watchlist tab** (mirrors `WatchlistView`): `KindToggle`, rows with symbol,
  sparkline, `SuperscriptPrice`, `ChangePill`; add-ticker search field with live
  debounced suggestions; remove; empty/error/loading states via an `ErrorPane`
  equivalent.
- **Asset detail** (mirrors `AssetDetailView` core): profile header, timeframe bar
  (1D default), line/candlestick toggle, key-stats `StatTile` grid. Opened from a
  watchlist row or palette selection.
- **Ctrl+K command palette** (mirrors `CommandPaletteView`): overlay with dimmed
  scrim, 300ms-debounced shared `FetchSearch`, Enter opens/adds, Esc closes.

## The new shared seam — watchlist persistence

In `:shared` commonMain:

- `WatchlistStore` port: load/save/observe the ordered symbol list.
- Use cases `FetchWatchlist`, `AddToWatchlist`, `RemoveFromWatchlist` (Kotlin
  `Fetch*` naming per the 4b precedent).
- Unit-tested against an in-memory fake in `jvmTest`.

`:desktopApp` provides the JVM implementation: a JSON file in the platform config
dir (`%APPDATA%/APTrade` on Windows, `~/Library/Application Support/APTrade` on
macOS/dev) with atomic write (temp file + rename). Android keeps its hard-coded
list this increment; it and the Swift app can adopt the port later.

**Interface-broadening rule (4b lesson):** adding the store touches no existing
interface, but any change that does must grep for ALL implementers including
anonymous test doubles before task briefs are written.

## Live data

- Watchlist ViewModel polls quotes every **15s** (the macOS cadence), loop started
  in the UI scope and cancelled on scope teardown; manual refresh available.
  Sparkline/intraday data refreshed every ~4th tick (macOS pattern).
- Detail fetches on selection/timeframe/mode change with the snapshot-locals
  pattern (the Android `loadChart` fix) so a stale response can never render over a
  newer selection.

## Testing

TDD throughout, mirroring the Android increment:

- ViewModel unit tests over a fake `MarketDataRepository` + fake `WatchlistStore`:
  watchlist load/add/remove/poll-tick, search debounce + race safety, detail
  selection/timeframe/mode + stale-response protection, palette state.
- `:shared` jvmTest: the three watchlist use cases.
- `:desktopApp` store test: JSON round-trip, missing/corrupt file → empty list,
  atomic rewrite.
- Existing suites stay green: 44 shared + 13 androidApp Kotlin, 193 Swift,
  xcframework + iOS scheme build.
- Gradle test-count verification via JUnit XMLs (`kmp-toolchain-gotchas`), not
  console output.

## CI — the Windows proof

GitHub Actions workflow (`.github/workflows/windows-desktop.yml`):

- `windows-latest`, Temurin JDK 17, Gradle wrapper.
- `:desktopApp:test` (desktop suite runs on real Windows — cheap, high signal).
- `packageMsi` → upload the `.msi` as a build artifact.
- jpackage needs the WiX toolset for `.msi`; it is preinstalled on GitHub Windows
  runners — the plan verifies and pins an install step only if needed.
- Trigger: pushes to `main` and the increment branch, plus manual dispatch.

This is the only Windows-ground-truth check available (no local Windows).

## Error handling

Same taxonomy as Android: shared-core `QuoteError` cases mapped to user-facing
error panes with retry; CancellationException always rethrown; polling loop
survives transient network failures (shows last-good data + error banner rather
than clearing the list).

## Out of scope (YAGNI for 6a)

Portfolio/news/alerts/trading (6b–6d); light theme; localization; macOS/Linux
packaging & signing; Windows code-signing; Compose Multiplatform restructure of
`:androidApp`; any Swift-app changes; auto-update.

## Risks & mitigations

- **CMP/Kotlin version mismatch** → pin from the official compatibility table
  during planning; treat like the AGP pin.
- **WiX/jpackage on CI** → verified in the CI task; fallback is an explicit WiX
  install step or `packageAppImage` (zip) artifact while `.msi` is fixed.
- **Font fidelity** → bundle Inter; side-by-side review is the acceptance test,
  "reads as the same app" is the bar.
- **CI iteration latency** (only Windows feedback channel) → keep the workflow
  minimal and cache Gradle; everything behavioral is verified locally on macOS
  first.
