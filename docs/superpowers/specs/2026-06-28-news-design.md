# News — Design

**Date:** 2026-06-28
**Status:** Approved (design); pending implementation plan
**Feature 3 of 3** in the "elevate the experience" arc (Risk/Perf ✅ → Command Palette ✅ → News).

## Goal

Add a News experience to APTrade backed by the Finnhub API: a top-level **News tab** with
market headlines (General / Crypto / Merger categories), and a **News section on each asset's
detail view** with company news for that symbol. Plus a client-side filter and persisted
bookmarks. Turns the app from "numbers" into "numbers with context."

## Scope

In scope:

- **News tab** (new third top-level tab): market headlines via Finnhub's market-news endpoint,
  with a segmented category switcher — **General**, **Crypto**, **Merger** (Forex deliberately
  dropped; it doesn't fit the app's stock/ETF/crypto scope).
- **Asset-detail News section**: company news for the symbol being viewed, via Finnhub's
  company-news endpoint (last 7 days).
- **Client-side filter**: a text field that narrows the currently-loaded headlines by
  headline/source substring. (Finnhub has no news-search API — this is local filtering of the
  fetched feed, not a server query.)
- **Bookmarks**: save/unsave articles to a persisted list (UserDefaults store mirroring the
  existing watchlist/alert stores), with a "Saved" toggle on the News tab to view them.
- **Article open**: tapping an article opens its URL in the **default browser** via
  `NSWorkspace.shared.open`. The 2-line summary shown inline is the "preview"; the app never
  auto-fetches article pages, only hands the URL to the browser.
- **Finnhub key** read from a local config file (`~/.config/aptrade/config.json`,
  `finnhubAPIKey`), with a graceful empty state when the key is absent.

Explicitly out of scope:

- Server-side news search (Finnhub doesn't offer it).
- In-app web/article reader (WKWebView) — open in the system browser instead.
- Realtime/streaming news, push notifications for news, sentiment analysis.

## Key architectural decision: config/secret boundary

The Finnhub API key must reach the repository without leaking into the domain or git.

**Decision (Approach A):** a small `AppConfig` reader in `APTradeInfrastructure` reads
`~/.config/aptrade/config.json` once and exposes `finnhubAPIKey: String?`. `CompositionRoot`
reads the key and constructs a `FinnhubNewsRepository(apiKey:)` when it's present, or a stub
`NewsRepository` returning `[]` when it's absent — so the News surfaces show a clean
"no news source connected" empty state rather than erroring. The domain and application layers
never know a key exists; they depend only on the `NewsRepository` port.

Rejected: (B) always construct `FinnhubNewsRepository` and let it throw when the key is missing
— conflates "no key configured" with "network failure," muddying the empty-vs-error states.
(C) environment variable — the user chose a config file.

The config file lives **outside the repo** (`~/.config/aptrade/`), so the key cannot be
committed by accident and the path is stable regardless of where the bare dev binary runs from.
The key is never logged or printed.

## Architecture

### Domain (`Sources/APTradeDomain/`, imports only `Foundation`)

- **`NewsArticle.swift`** — `Identifiable, Equatable, Codable, Sendable`:
  - `id: String` (Finnhub numeric id → String), `headline: String`, `summary: String`,
    `source: String`, `url: URL`, `imageURL: URL?`, `publishedAt: Date`, `category: String?`,
    `relatedSymbol: String?`.
- **`NewsCategory.swift`** — pure enum `case general, crypto, merger`, with `displayName`
  ("General"/"Crypto"/"Merger"). The Finnhub query-string mapping lives in infrastructure (the
  way `Timeframe.yahooRange` is an infrastructure extension, not a domain concern).

### Application (`Sources/APTradeApplication/`)

- **Port `NewsRepository: Sendable`**:
  - `func marketNews(category: NewsCategory) async throws -> [NewsArticle]`
  - `func companyNews(symbol: String) async throws -> [NewsArticle]`
- **Port `BookmarkStore: Sendable`**: `func load() -> [NewsArticle]`, `func save(_ articles: [NewsArticle])`
  (mirrors `WatchlistStore`).
- **Use cases:**
  - `FetchMarketNewsUseCase(repository:)` → `callAsFunction(category:) async -> [NewsArticle]`
    (best-effort: returns `[]` on failure, like the existing autocomplete pattern — the view
    model distinguishes empty-vs-error separately where needed; see Error handling).
  - `FetchCompanyNewsUseCase(repository:)` → `callAsFunction(symbol:) async -> [NewsArticle]`.
  - `LoadBookmarksUseCase(store:)` → `callAsFunction() -> [NewsArticle]`.
  - `ToggleBookmarkUseCase(store:)` → `callAsFunction(_ article: NewsArticle) -> [NewsArticle]`
    (adds if absent by `id`, removes if present; persists; returns the new list).
  - Filtering is NOT a use case — it's a presentation-layer computed property.

  Note on best-effort vs. error surfacing: the `NewsRepository` port methods are `async throws`
  (the `FinnhubNewsRepository` throws `AppError` exactly like `YahooMarketDataRepository`), but
  the two fetch use cases catch with `try?` and return `[NewsArticle]` (`?? []`), so the view
  model's states are derived from (key-present?, isLoading, articles.isEmpty). This
  keeps the use-case surface simple and matches the existing `SearchAssetsUseCase` convention.
  A distinct "error" visual state is therefore only shown for the no-key case; transient fetch
  failures present as the empty "No headlines right now" state with a Refresh affordance.

### Infrastructure (`Sources/APTradeInfrastructure/`)

- **`FinnhubNewsRepository.swift`** — conforms to `NewsRepository`; `URLSession`-based;
  init `(apiKey: String, session: URLSession = .shared)`. Mirrors `YahooMarketDataRepository`'s
  error handling exactly: `429 → AppError.rateLimited`, non-2xx → `AppError.network`,
  decode/other → `AppError.network`. URLs (key passed as the `token` query item):
  - market: `https://finnhub.io/api/v1/news?category={finnhubValue}&token={key}`
  - company: `https://finnhub.io/api/v1/company-news?symbol={SYMBOL}&from={yyyy-MM-dd}&to={yyyy-MM-dd}&token={key}`
    where the window is the last 7 days (UTC).
- **`FinnhubNewsDTO.swift` + mapper** — decodes the JSON array (fields confirmed live:
  `id, headline, summary, source, url, image, datetime, category, related`). Maps Unix
  `datetime` (seconds) → `Date`, `id` (Int) → String, `image` → `imageURL` (nil if empty),
  `related` → `relatedSymbol` (nil if empty). Skips any article missing a non-empty `headline`
  or a parseable `url`.
- **`NewsCategoryMapping.swift`** — `NewsCategory.finnhubValue` extension
  (`general`/`crypto`/`merger`).
- **`AppConfig.swift`** — reads `~/.config/aptrade/config.json` via `FileManager`/`Data`,
  decodes `{ "finnhubAPIKey": String }`; exposes `finnhubAPIKey: String?` (nil when the file is
  absent, unreadable, malformed, or the key is empty/whitespace). Never logs the value.
- **`UserDefaultsBookmarkStore.swift`** — conforms to `BookmarkStore`; Codable `[NewsArticle]`
  under a dedicated key; corrupt-data decode failure returns `[]` without overwriting (mirrors
  the post-fix `UserDefaultsPortfolioStore` behavior).

### Presentation (`Sources/APTradeApp/`)

- **`NewsViewModel.swift`** (`@MainActor @Observable`): `category: NewsCategory`,
  `articles: [NewsArticle]`, `filter: String`, `bookmarkedIDs: Set<String>`, `showingSaved: Bool`,
  `isLoading: Bool`, `keyMissing: Bool`. Computed `visibleArticles` applies the filter (and the
  Saved toggle). Methods: `onAppear()`, `load()`, `setCategory(_:)` (reloads),
  `updateFilter(_:)`, `toggleBookmark(_:)`, `toggleSaved()`. Built via DI with the fetch use
  cases, `LoadBookmarksUseCase`, `ToggleBookmarkUseCase`, and a `keyMissing` flag from
  `CompositionRoot`.
- **`NewsView.swift`** — the tab: category segmented control (KindToggle-style), filter field,
  a "Saved" toggle, a scrollable list of `ArticleRow`s, and the empty / no-key / loading states.
- **`ArticleRow.swift`** (new shared view) — `AsyncImage` thumbnail (placeholder on failure),
  headline (2 lines), `source · relative-time`, 2-line summary, and a bookmark toggle button.
  Tapping the row calls an `onOpen` closure; the bookmark button calls `onToggleBookmark`.
  Reused by both the News tab and the asset-detail section.
- **`AssetNewsViewModel.swift`** (`@MainActor @Observable`) + **`AssetNewsSection`** view —
  company news for a symbol, added to `AssetDetailView` (its own `.task`-driven load), reusing
  `ArticleRow` and the same bookmark toggle.
- **`RootView.swift`** — add `.news` to the `Tab` enum (the switcher is built from
  `Tab.allCases`, so a "News" pill appears automatically); render `NewsView` for `.news`.
  Article opening uses `NSWorkspace.shared.open(url)` (AppKit, already imported in this layer).

## Data flow

News tab appears → `NewsViewModel.onAppear` → (key present?) → `FetchMarketNewsUseCase(.general)`
→ `FinnhubNewsRepository.marketNews` → URLSession → decode → `[NewsArticle]` → render. Category
switch → reload. Filter / Saved toggle → recompute `visibleArticles` (no network). Bookmark tap
→ `ToggleBookmarkUseCase` → `BookmarkStore` persists → `bookmarkedIDs` updates. Article tap →
`NSWorkspace.shared.open(article.url)`.

Asset detail → `AssetNewsSection.task` → `AssetNewsViewModel.load(symbol)` →
`FetchCompanyNewsUseCase` → company news → render rows.

## Error handling

- **No key** → `keyMissing` true → "Connect a news source" empty state that names the config
  path (`~/.config/aptrade/config.json`) and the `finnhubAPIKey` field. No value leaked; no
  network call attempted.
- **Network / rate-limit** → the repository throws `AppError`, the use case catches it with
  `try?` and yields `[]`; the surface shows
  the "No headlines right now" empty state with a Refresh affordance, keeping any
  previously-loaded list visible rather than blanking it.
- **Empty category result** → same "No headlines right now" empty state.
- **Malformed articles** (missing headline or unparseable url) → skipped by the mapper.
- **Bookmark-store decode failure** → graceful empty list, no overwrite.

## Testing

- **Domain:** `NewsArticle` Codable round-trip; `NewsCategory.displayName`.
- **Infrastructure (bulk):** `FinnhubNewsMapper` against a real captured Finnhub fixture
  (decodes the expected `[NewsArticle]`, maps datetime/id/image/related correctly, skips a
  malformed entry); `NewsCategory.finnhubValue` mapping; `AppConfig` reading a temp JSON file
  (present key, absent file, malformed JSON, empty key → all the right `finnhubAPIKey` result);
  `UserDefaultsBookmarkStore` round-trip + corrupt-data fallback.
- **Application:** `FetchMarketNewsUseCase` / `FetchCompanyNewsUseCase` against a stub
  `NewsRepository`; `ToggleBookmarkUseCase` add/remove + persistence with a memory store;
  `LoadBookmarksUseCase`.
- **Presentation:** `NewsViewModel` — load → loaded/empty/no-key, category switch reloads,
  filter narrows `visibleArticles`, Saved toggle shows bookmarks, `toggleBookmark` updates the
  set; `AssetNewsViewModel` load. Stub repo + memory bookmark store, matching the existing VM
  test style.
- Live Finnhub networking is not unit-tested (same as `YahooMarketDataRepository`); the mapper
  is tested against a captured fixture instead.

## Build/run/test notes

- `swift test` requires `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` (project
  memory) or XCTest is missing.
- Manual UI verification falls to the user — computer-use can't target the bare SwiftPM dev
  binary (project memory); the implementer should build + code-trace and the user clicks through.
- The Finnhub key is already created and verified live (both endpoints returned HTTP 200) at
  `~/.config/aptrade/config.json`.

## Out-of-scope follow-ups (tracked for later)

- In-app article reader (WKWebView) with reader mode.
- News-driven price-alert correlation; sentiment tagging.
- Caching news responses (the existing `CachingMarketDataRepository` pattern could be mirrored
  for news if rate limits become a concern).
