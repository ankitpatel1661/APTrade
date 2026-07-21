# M9 — Technical Screener

**Date:** 2026-07-21
**Status:** Approved design
**Scope:** All four platforms (macOS, iPhone, Windows desktop, Android)
**Follows:** M8's twin-transcription discipline; builds on the existing per-stack
`TechnicalIndicators` (SMA/EMA/RSI/VWAP/Bollinger/MACD) and `SP500Symbols` (503 tickers).

## Summary

A whole-market technical screener: scan the S&P 500 on demand, cache one per-symbol
metrics snapshot for the trading day, and run preset or user-built screens as instant
pure predicates over it. Fifth top-level tab. View-only in M9 (no match notifications).

## Product decisions (locked)

| Decision | Choice |
| --- | --- |
| Screener type | **Presets + custom builder** — 9 curated signal screens plus a save-your-own condition builder (AND-combined). |
| Scan model | **On-demand scan + daily cache** — user-triggered, throttled batches, progress bar, snapshot persisted per trading day; no background scanning. |
| Placement | **Fifth top-level tab** (Watchlist / Portfolio / News / Calendar / Screener) on all four platforms. |
| Notifications | **View-only in M9.** Screen-match alerts are a possible later milestone (they would require the background-scan machinery deliberately excluded here). |
| Architecture | **Per-stack twin engines** (Swift + Kotlin), each on its own existing candles pipeline. No new Kotlin→Swift bridge surface. |

## Domain

### ScreenerSnapshotRow (one per symbol, computed once per scan)

- Identity/price: `symbol`, `name` (SP500Names), `close`, `dayChangePercent`
- Momentum: `rsi14`, `macd`, `macdSignal`, `macdHistogram`
- Trend: `sma50`, `sma200`, `ema20`, `pctVsSma50`, `pctVsSma200`
- Volatility: `bollingerPercentB`, `bollingerBandwidth` ((upper − lower) / middle)
- Range: `week52High`, `week52Low`, `pctTo52wHigh`, `pctTo52wLow`
- Volume: `relativeVolume` (today ÷ 20-day average)
- **Cross flags (booleans, computed at scan time from yesterday+today values):**
  `macdCrossedUp`, `macdCrossedDown`, `goldenCross`, `deathCross`
- All numeric metrics nullable — insufficient history excludes the row from screens
  that need that metric, never crashes.

### Screens

- `ScreenCondition` = metric (enum) + comparison (above/below) + threshold (Double).
- `Screen` = id, name, conditions — AND-combined. No OR in M9 (YAGNI).
- Presets are code (not storage), identified by preset id; custom screens persist.
- Evaluation is pure: `evaluate(screen, rows) → matching rows`; a condition on a null
  metric excludes that row.

### Preset library (9)

RSI Oversold (rsi14 < 30) · RSI Overbought (rsi14 > 70) · MACD Bullish Cross (flag) ·
MACD Bearish Cross (flag) · Golden Cross (flag) · Death Cross (flag) ·
Bollinger Squeeze (bandwidth < 0.05) · Near 52-Week High (pctTo52wHigh within 3%) ·
Near 52-Week Low (pctTo52wLow within 3%).

### Custom builder metrics

price, dayChangePercent, rsi14, bollingerPercentB, bollingerBandwidth, pctTo52wHigh,
pctTo52wLow, relativeVolume, pctVsSma50, pctVsSma200 — each with above/below + value.

## Engine (application layer, twin per stack)

`ScreenerScanEngine`:
- Fetches `candles(symbol, 1Y daily)` for all 503 symbols in throttled batches —
  concurrency 4, small inter-batch delay (named constants; target ≈1–2 min full scan);
  a 429 backs off and retries the batch once.
- Pure `ScreenerMath.snapshot(symbol, name, candles)` builds each row (1Y ≈ 252 bars —
  enough warm-up for SMA-200 and the 52-week range).
- Progress reported as done/total; per-symbol failure skips + counts, scan continues.
- Result: `ScreenerSnapshot(tradingDay, scannedAtEpochSeconds, rows, failedSymbols)`.
- The screener is read-only market data: it NEVER touches the portfolio and stays
  entirely outside TradeSerializer / the portfolio mutex.

## Persistence

- **Snapshot:** file-backed store on BOTH stacks (a few hundred KB of JSON — not
  UserDefaults). Kotlin: `screener-snapshot.json` beside the existing file stores.
  Swift: a small file adapter in Infrastructure (its first file-backed store).
  Keyed by trading day; same-day reopen is instant; new day or Refresh → re-scan;
  old snapshots overwritten, never accumulated.
- **Custom screens:** `ScreenStore` port, PieStore-pattern adapters (UserDefaults on
  Swift, file store on Kotlin). DTOs absent-key tolerant per house rule.

## Error handling

- Scan interrupted (app closed mid-scan) → nothing persisted; next open shows Scan.
- Total network failure → error state with retry.
- Partial failures → snapshot persists with `failedSymbols`; UI notes "scanned N of 503".
- Evaluation with missing metrics → per-condition row exclusion, never a crash.

## UI

### Screener tab (fifth destination, gold-on-black)

- **Screen picker:** chips row — 9 presets + saved custom screens, one active; "+" opens
  the builder.
- **Scan bar:** last-scan time + coverage ("503 scanned · 14:32"); progress bar with
  count during a scan; Scan/Refresh action; muted failed-symbols note.
- **Results table:** sortable — symbol, name, price, day % always, plus the active
  screen's relevant metric columns. Row tap → existing asset detail; per-row
  add-to-watchlist. Distinct empty states: not-scanned-yet, no-matches, scan-failed.
- **Builder sheet:** name + condition rows (metric picker, above/below, value),
  add/remove, live match count against the current snapshot, save/edit/delete.
- **Phone adaptation:** condensed rows (symbol+name stacked, two right-aligned metric
  values) instead of the wide table.
- EN/DE/IT/ES (~30 new keys per catalog).

## Testing (TDD throughout)

- ScreenerMath: hand-computable candle fixtures; every cross flag proven with a
  yesterday/today RED-verifiable fixture; nullability under short histories.
- Evaluation: predicates incl. null-metric exclusion and AND semantics.
- Engine: fake repo — throttling counts, failure isolation, progress sequence,
  429 backoff-and-retry-once.
- Stores: round-trips + hand-written legacy-JSON tolerance.
- ViewModels: sorting, chip switching, builder validation, live match count.
- Catalog completeness; full suites green per platform before merge.

## Rollout

- **M9.1** — Swift domain/engine/UI → macOS + iPhone (may carry the user-gated macOS
  polish backlog: 4-decimal shares, side-by-side allocation)
- **M9.2** — Kotlin twins + Windows desktop
- **M9.3** — Android
- Each increment: spec → plan → subagent-per-task with independent reviews → fable
  whole-branch review → user UAT. Twin transcription rules and recorded-divergence
  discipline carry over from M8.

## Out of scope

- Screen-match notifications / background scanning.
- OR-combined or nested conditions.
- Universes beyond the S&P 500 (crypto/ETF screening).
- Fundamental metrics (P/E, market cap — no data source wired).
