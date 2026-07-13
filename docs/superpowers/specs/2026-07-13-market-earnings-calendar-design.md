# Market-Holiday + Earnings Calendar — Design

**Date:** 2026-07-13
**Status:** Approved for planning
**Platforms:** all four in one increment — Windows (`desktopApp`) + Android (`androidApp`) on the shared Kotlin core, macOS + iOS from the shared SwiftPM presentation code. Parity holds at merge; desktop remains the behavioral reference.
**Out of scope (explicit):** real authentication (parked by user), non-US markets, historical earnings browsing, revenue estimates beyond what the chosen endpoint returns per row.

## Goal

Two user-facing capabilities:

1. **Market-holiday calendar** — the existing `MarketCalendar` (deliberately weekday-only until now; the long-standing roadmap item) becomes holiday-aware, so market open/close notifications stop firing on Thanksgiving and half-days close at 13:00 ET.
2. **Earnings calendar** — quarterly-report dates: a whole-market upcoming-events Calendar tab (user's held/watched symbols highlighted and pinned), a "Next earnings" stat on every asset detail screen, and a settings-gated notification on the morning a held/watched company reports.

## Decisions locked with the user

| Question | Decision |
|---|---|
| Earnings UI surfaces | Both: detail-screen stat row + dedicated calendar screen |
| Calendar coverage | S&P 500 constituents ∪ user's watchlist/portfolio symbols (revised 2026-07-13 from "whole market" — "otherwise it will be a mess"; user chose S&P 500 as the working definition of "Fortune 500 companies"). User's symbols highlighted/pinned and always shown even when not in the index |
| Notifications | Earnings-day notification for held/watched symbols only, settings-gated; holidays silently correct open/close notifications (no holiday notices) |
| Rollout | All four platforms in one increment |
| Holiday data source | Computed rules in the domain layer (no network, no expiry) |
| Calendar placement | Fourth top-level tab beside Watchlist / Portfolio / News |

## 1. Domain — holiday-aware `MarketCalendar`

Twin implementations, as today: `Sources/APTradeDomain/MarketCalendar.swift` and `shared/src/commonMain/.../domain/MarketCalendar.kt` (the Kotlin file is a transcription of the Swift one; both carry the "roadmap" comment this design deletes).

New pure rule set `USMarketHolidays` (one per codebase, identical tables):

- **Full-day closures (10):** New Year's Day, Martin Luther King Jr. Day (3rd Monday of January), Washington's Birthday (3rd Monday of February), Good Friday (Easter algorithm − 2 days), Memorial Day (last Monday of May), Juneteenth (June 19), Independence Day (July 4), Labor Day (1st Monday of September), Thanksgiving (4th Thursday of November), Christmas (December 25).
- **Observation shifts** for fixed-date holidays: Saturday → observed Friday before; Sunday → observed Monday after.
- **Half-days (13:00 ET close):** July 3 (when a weekday and July 4 is not observed on it), the day after Thanksgiving, Christmas Eve (when a weekday).
- Good Friday via the anonymous Gregorian Easter algorithm — computed, valid for any year; nothing in this feature expires.

API surface added to `MarketCalendar` (names indicative; final naming at plan time follows each codebase's conventions):

- `status(at:)` — now returns `closed` on full holidays and after 13:00 on half-days (existing weekday/session logic otherwise unchanged).
- `holidayName(on:) -> String?` — the L10n-keyed holiday for a market-local date, `nil` on trading days. Drives the calendar screen's banner rows and is what tests pin.
- `isHalfDay(on:) -> Bool` — drives the "closes early" banner variant.

Purity rules hold: no framework imports, no network, no persistence. Holiday names are returned as enum cases / L10n keys, not display strings — the UI resolves them through the catalogs.

## 2. Application + Infrastructure — earnings data

**Port** (`EarningsCalendarRepository`, one per codebase, in the Application layer next to `NewsRepository`):

```
fetchEarnings(from: Date, to: Date) -> [EarningsEvent]
```

**Domain value `EarningsEvent`:** symbol, company name (may be empty from the API — UI falls back to symbol), date (market-local day), session (`beforeOpen` / `afterClose` / `duringMarket` / `unknown`), EPS estimate?, EPS actual? (populated once reported).

**Adapter** `FinnhubEarningsRepository` — `GET /calendar/earnings?from=&to=&token=` — mirrors `FinnhubNewsRepository` exactly: same key plumbing (`AppConfig` / `FinnhubKeyConfig`), same DTO-file + mapper split, all fields optional in the DTO, unparseable rows dropped not thrown. Kotlin adapter lives in `shared/commonMain` (Ktor, per-platform client seam as news); Swift adapter in `APTradeInfrastructure` (URLSession).

**No-key fallback:** `EmptyEarningsRepository` returning `[]`, plus the same `keyMissing`/`needsKey` gate news uses. Wiring goes through the *existing* key-refresh paths: Android `AppGraph`'s per-access key re-read, Swift per-view-model factory read — a key saved in Settings applies the next time the Calendar tab opens, matching News.

**Large-cap filter:** a bundled static constituent set `SP500Symbols` (~503 tickers) in each codebase's Domain layer (Swift + shared `commonMain`) — a plain `Set<String>` constant, no network. The calendar use case keeps only events whose symbol is in `SP500Symbols ∪ watchlist ∪ portfolio`, so the screen stays readable and the user's own names never disappear even when outside the index. Constituents drift a few times a year; the set is refreshed opportunistically at maintenance time (acceptable staleness — the user's own symbols are always exact).

**Use case** `FetchEarningsCalendar` serves both surfaces:

- Calendar screen: one ranged fetch, today → +14 days, filtered per the large-cap rule above.
- Detail screen "next earnings": ranged fetch today → +30 days filtered to the symbol, earliest hit; absent → the UI shows "—". (Same use case, symbol filtering in the use case, not the view.)

**Caching:** in-memory TTL cache of the ranged response (hours-scale TTL — earnings dates don't move intraday), keyed by range, shared by both surfaces so opening three detail screens doesn't refetch. Follows the existing caching-repository idiom per codebase.

## 3. Presentation — Calendar tab + detail stat row

**Fourth tab "Calendar"** added to: desktop `AppTab` + tab row, Android `ShellTab` + `NavigationBar`, macOS/iOS `RootView.Tab` + switcher pills. Localized tab title in all four languages.

**Calendar screen** (one design, four renderings in each platform's established idiom):

- A scrolling list of the next 14 days grouped by day; day headers use the existing section-header treatment.
- **Banner row** under a day header when the market is closed or closes early: "Market closed — Independence Day" / "Closes 1:00 PM — Christmas Eve". Gold-bordered quiet banner (DesignKit chip/border idiom, not price-direction colors). Weekends show no banner — only holidays and half-days are called out.
- **Earnings rows:** symbol, company name, before/after-market chip (reuses the kind-chip visual), EPS estimate in `.monospacedDigit()`/`tnum` when present. Days with no earnings and no banner collapse (not rendered as empty headers).
- **My-symbols treatment:** rows whose symbol is in watchlist ∪ portfolio pin to the top of their day group and get the gold accent treatment; the remaining S&P-500 rows render quiet. No coverage toggle (user decision).
- **No-key state:** the News tab's empty state, verbatim pattern — "Connect a news source" + platform-appropriate key instructions (in-app-field text on iOS/Android, file-drop text on macOS/desktop). Holiday banners still render above it: they're computed locally and need no key.
- **Loading/error:** same idioms as News (spinner; error pane with Retry).

**Detail screens** add one row to the key-stats block: label "Next earnings", value "Jul 24 · After close" (localized date formatting; "—" when none in the 30-day window or no key). macOS/iOS `StatRow`-style, desktop `StatRow`, Android detail stats list.

**L10n:** new keys — tab title, banner formats, the 10 holiday names, session labels (Before open / After close / During market), "Next earnings", earnings-notification title/body formats, settings toggle title + subtitle — in EN/DE/IT/ES in both catalogs. Both catalog count tests bumped.

## 4. Notifications

- New `AppSettings` field `earningsReports: Bool` (default **on**), surfaced as a toggle on the Notifications settings page in both codebases (same row anatomy as Price Alerts / Order Fills).
- The existing once-per-trading-day scheduler gate (`MarketActivityCoordinator` on Swift, desktop coordinator, Android's equivalent path) gains one job: on the first tick of each trading day, fetch today's earnings, intersect with watchlist ∪ portfolio symbols, and post one notification per match — "AAPL reports today · After close" — through the platform notifiers already in place (UserNotification / Tray / Android). Android gets one new notification channel (`earnings_reports`), created lazily like the existing two, using the branded small icon.
- Once-per-day delivery is idempotent via the existing trading-day gate persistence; a symbol never notifies twice in a day.
- No key → the fetch returns empty → no notifications, silently.
- **Holiday correction is free:** `status()` now returns `closed` on holidays, so the market-open/close notifications skip them with no scheduler changes; half-day close notifications fire at 13:00 because `status()` flips there.

## 5. Testing & parity discipline

- **Holiday rules (the test-rich core):** identical fixed-date test tables in Swift (`MarketCalendarTests`) and Kotlin: all ten 2026 and 2027 holidays; observation shifts (July 4 2026 = Saturday → observed Friday July 3 2026; June 19 2027 = Saturday → observed Friday June 18 2027); Easter/Good Friday across ≥3 years; half-day 12:59 open vs 13:00 closed boundary; a plain Wednesday stays open; `tradingDay` unaffected.
- **Earnings:** DTO mapping from captured Finnhub JSON (missing fields, unknown session hours, malformed rows dropped); use-case range + symbol filtering; large-cap filtering (non-index symbol dropped, non-index *held* symbol kept, index symbol kept); TTL cache behavior (second call within TTL doesn't refetch).
- **ViewModels:** day grouping, my-symbols pinning order, banner emission for holiday/half-day days, no-key `needsKey`, empty-day collapsing.
- **Notification gating:** toggle off → nothing; symbol not held/watched → nothing; held symbol reporting today → exactly one; second tick same day → nothing.
- **Suites:** all five green before merge (macOS `swift test`, iOS sim `APTradeLite-Package`, `:shared:jvmTest`, `:desktopApp:test`, `:androidApp:testDebugUnitTest`), counts verified from JUnit XML with `--rerun-tasks` per the recorded gotcha. Desktop suite guards that existing behavior (stores, settings JSON) is unchanged apart from the new settings field.

## Risks / notes

- **Finnhub free tier** limits the earnings-calendar range (roughly one month around today). The 14-day screen window and 30-day detail window sit inside it. If a range request fails or comes back truncated, surfaces degrade to "—"/empty rather than erroring loudly.
- **`AppSettings` gains a field** in both codebases — the load-merge-save seams and settings round-trip tests already cover unknown/new-field tolerance; the new field defaults on for existing users.
- **Half-day 13:00 close** changes when the "market closed" notification fires on those three days — that is the intended fix, called out here so UAT doesn't read it as a regression.
- Company names from `/calendar/earnings` are sometimes absent; the row falls back to the symbol alone by design.
- **S&P 500 constituent drift:** the bundled set goes stale as the index rebalances (a handful of changes per year). Accepted: banner/holiday data is unaffected, held/watched symbols are always shown regardless, and the set is a one-constant maintenance update.
