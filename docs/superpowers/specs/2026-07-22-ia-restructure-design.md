# M10 — IA Restructure: "Four destinations and a Home"

**Status:** Approved by user 2026-07-22 (interactive mockup review).
**Visual reference:** `2026-07-22-ia-restructure-mockup.html` (same directory — open in a browser; every destination is clickable). The mockup is authoritative for STRUCTURE and PLACEMENT; existing app components remain authoritative for visual rendering.

## North star

The visual language does not change — tokens, ChangePill, SuperscriptPrice, sparklines, pill switchers, charts, trade flow all stay as shipped. **Only the information architecture changes**: where features live, how they're reached, and a new Home dashboard. The problem being solved: features accreted as equal-weight tabs and buried Portfolio sections ("junk drawer" — 6 sections doing 4 unrelated jobs), no opening summary surface, tools misfiled (Export in the account menu, DRIP under Account Settings, alerts write-only with no overview), and ~180pt of macOS header chrome.

## The IA (all four platforms, identical section ORDER everywhere)

| Destination | Sections (first = default) |
|---|---|
| **Home** (app opens here) | — (dashboard) |
| **Markets** | Watchlist · Screener · Calendar · News |
| **Portfolio** | Holdings · Allocation · Activity · Performance |
| **Invest** | Plans · Income |

- **Phone (iPhone / Android):** 4 bottom tabs; sections are the existing pill-row idiom inside each tab. Markets shows a visible search field above the pills (opens the existing search/palette flow).
- **Desktop (macOS / Windows):** the top pill switcher and the 108pt wordmark header are REPLACED by a left sidebar (~208pt): compact brand mark at top, `Home`, then labeled groups MARKETS / PORTFOLIO / INVEST listing their sections as direct items (sections are one click, always visible), footer pinned: `Search ⌘K` and `Settings`. Custom-styled per the mockup (surface-hi selection with gold inset ring) — NOT a stock NavigationSplitView look.
- **Desktop master–detail:** Watchlist and Screener render as list column + detail pane side-by-side (selecting a row opens detail IN the content area, Buy included; the full-window replace navigation is retired for these two views). Other views stay full-pane. Detail opened from palette/Home keeps the existing sheet presentation.

## Home (new, all platforms)

Data comes from existing engines only — no new domain logic beyond aggregation:

1. **Hero:** portfolio total value (market value + cash), day change ($ and %, ChangePill), sparkline of the trade-aware equity curve (last ~30 days of the series Performance already computes). Tap/click → Portfolio.
2. **Quick stats row:** Total return % (Performance), Cash, Income YTD (dividend engine).
3. **"Today" feed** (each row navigates to its home surface):
   - Market status via MarketCalendar: "Market open · closes 4:00 PM" / "Market closed · opens Mon 9:30".
   - Top gainer and top loser across holdings ∪ watchlist (live quotes).
   - Next earnings among owned+watched symbols (earnings calendar): "MSFT reports earnings · after close".
   - Next upcoming dividend (income engine): "AAPL dividend · est. $12.40 · Thu".
   - Screener freshness: "Screener: <last screen> · N matches · today" only when the snapshot is fresh (isSnapshotFresh).
4. **Quick cards** (phone) / side cards (desktop): Screener, Alerts, Calendar, News with a one-line live status; tap navigates.
5. **Alerts entry:** bell in the phone top bar (badged when alerts exist) and an Alerts card on desktop Home → Alerts center.

Failure isolation:每 feed row degrades independently (a failed quote or missing Finnhub key hides that row, never blocks Home).

## Alerts center (new, all platforms)

A list of ALL price alerts across symbols: symbol, condition summary (reuse the PriceAlertSheet's condition-description helper), remove (swipe/context/✕ + confirm-free — creation is cheap), tap → symbol detail. Reached from Home (bell/card). Creation remains per-symbol (row bell / detail), unchanged. Uses existing LoadAlerts/RemovePriceAlert (shared) and their Swift equivalents.

## Tool re-homing

| Thing | From | To |
|---|---|---|
| Export portfolio | Account "⋯" menu | Portfolio · Holdings header button (same export flow) |
| DRIP toggle | Account Settings page | Income view header card ("Reinvest dividends (DRIP)" + subtitle) |
| Settings ordering | mixed | App settings first (Appearance, Language, Notifications), then Profile / Account / Security / Help / About |
| macOS wordmark | 108pt header every screen | compact mark in the sidebar header (full brand stays in About) |
| Command palette navigate targets | Watchlist / Portfolio | updated to the four new destinations (at minimum Home / Markets / Portfolio / Invest) |

Account "⋯" panel itself survives (profile, security, help, about, language, sign-out) — it just stops hosting Export and DRIP.

## Explicit non-goals

No new data sources; no visual-token changes; no chart changes; no change to News/Calendar/Screener/Plans/Income internals beyond their hosting; no Android/desktop settings-page redesign beyond the ordering pass; no persistence-format changes. The DRIP toggle keeps writing the same settings field.

## L10n

New keys in BOTH catalogs (Kotlin commonMain L10n + Swift L10n), EN/DE/IT/ES, incl.: HomeTab, MarketsTab, InvestTab, Today section title, market open/closed + closes/opens-at formats, "leads your holdings"/"biggest faller" lines, quick-card status formats, Alerts center title/empty state, "reports earnings" line, upcoming-dividend line, screener-fresh line. Section names reuse existing keys (Watchlist, ScreenerTab, CalendarTab, News, Holdings/Allocation/Activity/Performance/Plans/Income section keys). Catalog-count pin tests must be bumped in the same task that adds keys.

## Increments

- **M10.1 Swift** — macOS (sidebar + master-detail + Home + Alerts center + re-homing) and iPhone (4-tab shell + section hosts + Home + Alerts center). Reference implementation.
- **M10.2 Kotlin Windows desktop** — transcribe from Swift as-built; shared aggregation logic lands in commonMain where it buys the twins (HomeFeed assembly), desktop adds sidebar/panes.
- **M10.3 Android** — final increment; 4-tab NavigationBar + section pills + Home + Alerts center from desktop as-built.

Per-increment: user UAT after merge; push at milestone end per house rule. At M10 close: prune from README roadmap.

## Open questions ratified by mockup approval

News lives under Markets (not top-level) · the tab is named "Invest" · desktop sidebar is full-label (no icon-collapse mode).
