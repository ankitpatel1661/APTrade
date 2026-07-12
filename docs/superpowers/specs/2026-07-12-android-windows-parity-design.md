# Android → Windows Parity — Design Spec (Workstream A)

**Date:** 2026-07-12
**Status:** Approved design — ready for implementation planning
**Owner workstream:** A (Android) of the cross-platform catch-up program
**Predecessor:** Workstream B (iOS closeout) merged to main 2026-07-12 (8922c09)

---

## Context

The Windows desktop app (`desktopApp`, Compose Desktop, ~15k LOC) is the feature-complete reference: watchlist + detail + palette, portfolio + performance + export, news + bookmarks, alerts + notifications, light/dark themes + accent picker, and a 4-language (EN/DE/IT/ES) localization catalog. The Android app (`androidApp`, ~3.2k LOC) covers only quotes (hard-coded 4 symbols, no persistence), search, detail, and portfolio.

Measured gaps on `main` (2026-07-12):

- **No watchlist**: `QuotesScreen` renders `AppGraph.defaultSymbols = listOf("AAPL","SPY","BTC-USD","ETH-USD")` — no add/remove, no persistence.
- **No navigation chrome**: bare `NavHost` routes (quotes/search/detail/portfolio); no bottom bar.
- **No news, no alerts, no settings, no localization**: `AppGraph` wires only market + portfolio use cases.
- **Dark-only theme**: `Theme.kt` has a single `darkColorScheme` (champagne-gold-on-black); desktop shipped light theme in 6d.2 and accents.

What already exists and is reusable:

- `shared/commonMain` has the domain + application logic for everything missing: `WatchlistEntry`, `AddToWatchlist`/`FetchWatchlist`/`RemoveFromWatchlist`, `WatchlistStore` (port), `PriceAlert` + `AlertUseCases` + `AlertPorts`, `NewsArticle`/`NewsCategory` + `NewsUseCases` + `NewsPorts`, `FinnhubNewsRepository`.
- `shared/jvmCommonMain` already hosts `FilePortfolioStore`, which Android consumes with its `filesDir` — the proven pattern for sharing JVM file-stores.
- `desktopApp` has fully-tested JVM implementations trapped in the wrong module: `FileWatchlistStore` (53 lines), `FileAlertStore` (127), `FileSettingsStore` (140), `FinnhubKeyConfig`, plus the 1,029-line `L10n` catalog (217 keys × 4 languages) and `AppLanguage`.

## Decisions (locked with user)

1. **Approach A — promote & reuse.** Desktop's file stores + L10n catalog move into `shared`; Android consumes them. No Android-native reimplementation (no DataStore, no strings.xml for these).
2. **Full settings parity including light theme and accent picker** — not a trimmed core-settings scope.
3. Program-level (from the 2026-07-12 program decision): native per platform; Windows is the parity reference; Android is the last remaining gap.

## Goal

The Android app ships every Windows-desktop feature on the shared KMP core: persisted watchlist, news with bookmarks, price alerts with system notifications, and full settings (dark/light theme, accent, language EN/DE/IT/ES, notification toggles, profile/security/about pages).

## Non-goals

- Background (app-closed) alert monitoring — desktop evaluates alerts only while running; Android matches (foreground evaluation in the poll). WorkManager-based background alerts are a separate future feature.
- Command palette on Android (desktop's ⌘K concept has no mobile idiom; search screen covers the need).
- Portfolio export on Android beyond what exists (`ExportShare` already ships).
- iOS/macOS changes of any kind.
- Play-store/release packaging.

## Architecture

### Shared promotions (foundation — everything else depends on this)

Move, verbatim where possible:

| From `desktopApp` | To | Notes |
|---|---|---|
| `infra/FileWatchlistStore.kt` | `shared/jvmCommonMain` | implements shared `WatchlistStore` port |
| `infra/FileAlertStore.kt` | `shared/jvmCommonMain` | implements shared alert port |
| `infra/FileSettingsStore.kt` | `shared/jvmCommonMain` | settings model may need to move with it |
| `infra/FinnhubKeyConfig.kt` | `shared/jvmCommonMain` | key resolution for news |
| `infra/FileBookmarkStore.kt` | `shared/jvmCommonMain` | news bookmarks (required by A3) |
| `l10n/L10n.kt` (catalog + keys) | `shared/commonMain` | pure Kotlin data; 217 keys × 4 languages, moved verbatim |
| `l10n/AppLanguage.kt` | `shared/commonMain` | enum + display names |

Rules:
- `desktopApp` keeps thin re-exports (type aliases / forwarding declarations) so existing desktop call-sites and its 80+ test files compile **unchanged**. Desktop behavior must be byte-identical after the move; the existing desktop test suite is the guard (notably `L10nCatalogTest`, `FileWatchlistStoreTest`, `FileAlertStoreTest`, `FileSettingsStoreTest`, `FinnhubKeyConfigTest`).
- Store file formats do not change — a desktop user's existing config/data files must load identically.
- Tests move with the code they test (re-homed under `shared/src/jvmCommonTest`), or remain in desktop against the re-exports if moving them is disruptive — implementer's choice, but coverage must not drop.
- The desktop `LocalizationManager` (Compose-state holder) stays in `desktopApp`; Android gets its own thin Compose-state holder over the same shared catalog. Only the *data* is shared; per-platform UI state holders stay per-platform.

### Android navigation shell

Material 3 `Scaffold` with:
- Bottom `NavigationBar`: **Watchlist / Portfolio / News** (mirrors iPhone tabs and Windows panes).
- Top app bar: search action (→ existing search screen) and account action (→ settings).
- Existing `NavHost` destinations (search, detail/{symbol}) layered above the tabs; `QuotesScreen` is retired once the watchlist screen lands (its ViewModel test conventions carry over).

### Feature increments

- **A1 — Watchlist screen**: persisted via shared `FileWatchlistStore` (+`AddToWatchlist`/`FetchWatchlist`/`RemoveFromWatchlist`), add-from-search, swipe-to-remove, 15s live prices with daily %, per-row alert bell. Desktop `WatchlistPane` (511 lines) is the reference anatomy; `WatchlistViewModel` mirrors desktop's.
- **A2 — Alerts**: `PriceAlertSheet` analog (price above / price below / % daily move) reusing shared `AlertUseCases` + promoted `FileAlertStore`; evaluation folded into the watchlist poll (foreground, matching desktop); delivery via `NotificationManager` with a dedicated channel + `POST_NOTIFICATIONS` runtime permission flow (Android 13+); order-fill notifications behind the settings toggle (desktop `AppGraphNotifyOrderFill` pattern).
- **A3 — News**: News tab with market/category news and per-symbol company news via shared `NewsUseCases`/`FinnhubNewsRepository`, bookmarks via the promoted `FileBookmarkStore`, Finnhub key via promoted `FinnhubKeyConfig`, article open in a Chrome Custom Tab. Desktop `NewsPane` (334 lines) is the reference.
- **A4 — Settings + localization + theme**: account/settings screens (profile, notifications, appearance, security, language, about) mirroring desktop `AccountPanel` anatomy; language switcher live-re-rendering through an Android Compose-state `LocalizationManager` over the shared catalog, persisted via promoted `FileSettingsStore`; **light theme** — lift desktop's 6d.2 light color table into a Material 3 `lightColorScheme` alongside the existing dark scheme — plus the accent picker (desktop `AccentTheme` ramp).
- **A5 — Verification**: desktop + shared + android Gradle test suites green (proves the promotions didn't regress desktop); emulator UAT walk performed by the user (same protocol as the iOS closeout: agent builds/installs/launches, user drives and reports).

Sequencing: **promotions → shell → A1 → A2 → A3 → A4 → A5.** A1 depends on the shell and promoted watchlist store; A2 on A1's rows; A3/A4 depend on promotions only (A3 and A4 are order-flexible but planned in this order).

## Error handling

Follows the desktop patterns: repository failures surface as retryable error panes (`ErrorPane` exists on Android already); missing Finnhub key renders the connect-a-news-source empty state (localized); notification permission denial degrades gracefully (alerts still evaluate and show in-app, system notification skipped).

## Testing strategy

- Promoted stores: their existing unit tests move with them (or run against re-exports); the desktop suite must stay green throughout — it is the primary regression guard for the promotion step.
- Every new Android ViewModel gets JVM unit tests mirroring the desktop equivalents (`WatchlistViewModelTest`, `AlertFormStateTest`, `NewsViewModelTest`, settings persistence) using the existing `FakeMarketDataRepository`/fake-store patterns already present in `androidApp/src/test`.
- The shared L10n catalog gets a completeness test in shared (every key × 4 languages non-empty) — desktop's `L10nCatalogTest` either moves or is mirrored.
- Visual/UAT on the Android emulator is user-performed (computer-use cannot drive the dev build).

## Toolchain facts (bind every implementation task)

- `JAVA_HOME` must point at JDK 17 explicitly: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`; always use `./gradlew` (wrapper pins Gradle 8.9 — system Gradle 9 breaks the KMP plugin).
- Android SDK + emulator port collisions with BlueStacks have occurred on this machine — kill stray adb servers if the emulator misbehaves.
- Swift/macOS side is untouched by this workstream; no DEVELOPER_DIR concerns.

## Definition of done

All Windows-desktop features listed in the increments are usable on the Android emulator; desktop + shared + android test suites green; desktop app behavior byte-identical (its suite + a desktop smoke run prove it); README feature/roadmap notes updated at merge per repo convention.
