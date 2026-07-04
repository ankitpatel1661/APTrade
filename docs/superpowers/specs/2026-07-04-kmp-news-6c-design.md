# Increment 6c — News tab (desktop) on a shared Kotlin news core

Date: 2026-07-04. Authority: user go ("6c (News)") after 6b.2 merged at ac2d5c2.
Parity source: the macOS Swift app (explorer report 2026-07-04, all file:line facts
verified). The deferred-cleanup batch stays parked for 6b.3 — NOT in this increment.

## Goal

Port the macOS News feature to the Windows/desktop Compose app, with the fetching and
domain logic in the shared Kotlin core (`:shared`) so Android can adopt it later.

Scope: News tab (categories, live filter, bookmarks + Saved view, open-in-browser,
loading/empty/no-key states) + per-symbol News section in the detail screen.
Out of scope: Android UI, macOS changes, polling/caching (macOS has none), pull-to-refresh.

## macOS parity facts (binding)

### Finnhub client (FinnhubNewsRepository.swift)
- Base `https://finnhub.io/api/v1`; key as query param `token` (never a header, never logged).
- Market news: `GET /news?category={general|crypto|merger}&token=…`.
- Company news: `GET /company-news?symbol={UPPERCASED}&from={yyyy-MM-dd}&to={yyyy-MM-dd}&token=…`,
  fixed last-7-days window (`to`=now, `from`=now−7d), en_US/UTC `yyyy-MM-dd`.
- Errors: 429 → rate-limited; other non-2xx → network; JSON decode failure → decoding;
  anything else → network. Use cases swallow ALL errors (macOS `try?`) → empty list;
  the UI distinguishes only via the separate keyMissing flag.
- No caching, no polling. Loads on view appear + explicit category switch + manual Refresh.

### Domain (NewsArticle.swift, NewsCategory.swift)
- NewsArticle: id String, headline String, summary String (""-default), source String
  (""-default), url (required valid), imageURL?, publishedAt (Unix seconds → date,
  epoch-default), category String? (raw Finnhub string), relatedSymbol String?.
- Mapper invariants (boundary-enforced): drop articles lacking non-empty headline or
  parseable url; ""→null for image/related; id falls back to the url string.
- NewsCategory enum: general, crypto, merger (declaration order) + displayName.

### News tab UI (NewsView.swift, ArticleRow.swift, NewsViewModel.swift)
- Category pills General · Crypto · Merger + trailing Saved toggle (bookmark icon,
  gold when active) in one row; selecting Saved deselects pills visually.
- Live filter TextField "Filter headlines" (magnifier icon, capsule w/ hairline) —
  case-insensitive substring on headline OR source, applied to the active base list
  (bookmarks when Saved, else articles), recomputed per keystroke, no debounce.
- ArticleRow: 64×64 rounded thumbnail (async; plain surfaceHi rectangle on no/failed
  image), 2-line headline, "{source} · {relative time}" (named relative style, e.g.
  "2 hours ago"), 2-line summary (hidden if empty), trailing bookmark toggle
  (bookmark/bookmark.fill, gold when active). Row-body click opens the article url in
  the DEFAULT BROWSER; the bookmark button does not open.
- Loading: centered spinner only when loading && visible list empty.
- Empty states: Saved → "No saved articles"; else "No headlines right now" + "Refresh"
  button (re-triggers load; no Refresh in the Saved empty state). Newspaper icon both.
- No-key state REPLACES the whole tab (no pills, no filter): "Connect a news source" +
  "Add a Finnhub API key to ~/.config/aptrade/config.json (field \"finnhubAPIKey\")
  and relaunch."

### Per-symbol News (AssetNewsSection.swift)
- Heading "News"; at most 8 articles (prefix), same ArticleRow + divider; bare spinner
  under the heading while loading && empty; renders NOTHING (no heading) when
  keyMissing or empty-and-not-loading — the tab owns the connect-a-source messaging.
- Loads company news (7-day endpoint) on first appearance of the section.

### Bookmarks (UserDefaultsBookmarkStore.swift, ToggleBookmarkUseCase)
- macOS: UserDefaults key "bookmarks", FULL NewsArticle array JSON (not ids).
- Toggle keyed by id: present → remove; absent → insert at index 0; whole list re-saved.
- Decode failure → [] silently (stored bytes untouched).

### API key (AppConfig.swift)
- `~/.config/aptrade/config.json`, shape `{"finnhubAPIKey": "…"}`; trimmed non-empty
  required, else nil. Read lazily at composition time. No-key → Empty repository
  substituted at the composition root; keyMissing flag drives the UI.

## Desktop design decisions (recorded divergences where noted)

1. NEW SHARED SURFACE (`:shared` commonMain): NewsCategory, NewsArticle, NewsRepository
   port, BookmarkStore port, Finnhub DTO+mapper, FinnhubNewsRepository (Ktor; reuses the
   per-platform engine actuals), use cases FetchMarketNews / FetchCompanyNews (swallow ≡
   macOS `try?`, CancellationException-first) / LoadBookmarks / ToggleBookmark
   (insert-at-0, id-keyed). Framework-free commonMain (Ktor is already the sanctioned
   networking layer there). publishedAt carried as epochSeconds Long (no Date in KMP
   domain); url/imageURL as String (validated non-blank url at the mapper — RECORDED
   DIVERGENCE: Swift stores URL types; Kotlin stores validated strings).
2. Desktop bookmarks: FileBookmarkStore → bookmarks.json in the existing ConfigDir
   (atomic temp+ATOMIC_MOVE; full-article DTO list; corrupt → [] without overwriting).
   RECORDED DIVERGENCE: file-based vs macOS UserDefaults (desktop has no UserDefaults;
   matches watchlist/portfolio/settings precedent).
3. Desktop API key: read `config.json` first from the desktop ConfigDir, then FALL BACK
   to `~/.config/aptrade/config.json` (the macOS path) so a mac user's existing key
   just works. Same shape/trim rules. Lazily read at AppGraph news-VM construction.
   No-key → keyMissing flag (no Empty-repository indirection needed on desktop — the
   VM simply never fetches; RECORDED DIVERGENCE, behaviorally identical).
4. Thumbnails: a small desktop async image loader (Ktor GET bytes → Skia
   Image.makeFromEncoded → ImageBitmap) with an in-memory LRU (~64 entries), fallback
   surfaceHi rounded rectangle on any failure. No new dependency (no Coil).
5. Relative time: pure Kotlin formatter over epochSeconds ("just now", "N minutes ago",
   "N hours ago", "N days ago", "MMM d" beyond 7 days) — RECORDED DIVERGENCE: macOS
   uses the system named-relative formatter; ours is a documented approximation with
   pinned tests.
6. Open-in-browser: AWT Desktop.browse (ExportSave precedent for the AWT import).
7. Detail-screen News: DetailPane gains the section fed by a small per-detail news
   state (loaded once per symbol selection, same lifecycle as the existing detail VM).

## Definition of done

1. Shared suites green (122 + new), macOS target compiles, xcframework 3 slices,
   Swift 193 untouched-green, iOS builds (Task 7 re-proof — :shared grows).
2. Desktop suites green (107 + new).
3. News tab on desktop: pills/Saved/filter/rows/bookmark/open-in-browser/loading/
   empty/no-key all per parity facts above.
4. Detail screen News section per parity facts.
5. Bookmarks survive restart (bookmarks.json); key read incl. macOS-path fallback.
6. README News section + roadmap prune (6c out); ledger updated.
7. Human visual gate post-implementation (standing convention).
