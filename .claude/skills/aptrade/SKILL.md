---
name: aptrade
description: Use when building, restyling, or extending the APTrade macOS app — captures the gold-on-black design system, the reusable DesignKit components, the Clean Architecture rules, the live-data polling pattern, and the build/run/test commands (including the toolchain gotchas).
---

# APTrade — Project Conventions

A native macOS SwiftUI investing app (watchlist + asset detail) on strict Clean
Architecture, Swift 6. This skill records how the app is built so new work stays
consistent with what already ships.

## Skills used to build this

- **`frontend-design`** (`claude-plugins-official`) — drove the visual direction.
  The decisions it produced are encoded below; follow them rather than re-deriving a
  new look each time. Re-invoke `frontend-design` only for genuinely new surfaces.

## Design system — "gold on black"

Identity is sampled from the APTrade logo. **Always pull colors and components from the
existing files — never hard-code hex or rebuild a primitive.**

- **Palette** lives in [`Theme.swift`](../../../Sources/APTradeApp/Theme.swift): warm
  near-black ground, a champagne-gold gradient (`Theme.goldGradient`,
  `#A9772A → #D4A94E → #F2DDA0`), silver for the secondary wordmark, warm-white text.
- **The one hard rule:** gold owns the brand and every interactive accent (selected
  toggle, timeframe underline, Add button, live badge). **Gains stay green
  (`Theme.up`), losses stay red (`Theme.down`).** Price-direction color is data, not
  decoration — never spend it on branding. Use `Theme.changeColor(_:)` for any
  direction-driven color.
- **Numerics:** prices use the superscript-cents treatment (`$131`⁶³) via
  `SuperscriptPrice`; percentage changes use `ChangePill`; all figures use
  `.monospacedDigit()`.
- Spend boldness in one place (the sparklines / superscript prices); keep surrounding
  surfaces quiet and dark.

### Reusable components — `DesignKit.swift`

Before adding UI, check [`DesignKit.swift`](../../../Sources/APTradeApp/DesignKit.swift):

| Component | Use for |
|-----------|---------|
| `BrandMark` | the AP/Trade wordmark |
| `LiveBadge` | pulsing gold "LIVE" indicator for live-updating prices |
| `SuperscriptPrice` | any money value with raised cents |
| `ChangePill` | a bordered percentage-change chip |
| `KindToggle` | the Stocks / ETFs / Crypto segmented control |
| `PulseBar` | advancers / decliners split bar |
| `TimeframeBar` | underline-selected 1D / 1W / 1M / 1Y row |
| `StatTile` | one labeled figure in a key-stats grid |

## Architecture rules

Clean Architecture; dependencies point inward only. One SwiftPM target per layer.

- **`APTradeDomain`** — entities & value objects (`Asset`, `Quote`, `Money`,
  `Percentage`, `PricePoint`, `Timeframe`). **No framework imports, no networking, no
  persistence.** Pure Swift.
- **`APTradeApplication`** — use cases over protocol *ports*
  (`MarketDataRepository`, `WatchlistStore`). Business orchestration only.
- **`APTradeInfrastructure`** — adapters: `YahooMarketDataRepository`,
  `CachingMarketDataRepository`, `UserDefaultsWatchlistStore`.
- **`APTradeApp`** — declarative SwiftUI views + thin `@Observable` view models (MVVM).
  Keep logic out of views and views out of the view models' networking.

**Dependency injection:** everything is wired in
[`CompositionRoot.swift`](../../../Sources/APTradeApp/CompositionRoot.swift). Add new
dependencies there, behind a port — don't reach for singletons.

Swift 6 concurrency throughout: `async/await`, `actor` isolation, `Sendable` types.

## Live data pattern

- Quotes are polled on a **15s** cadence via a `runLiveUpdates()` loop started from the
  view's `.task { … }`, so SwiftUI cancels it on disappear. Quotes refresh every tick;
  heavier intraday sparklines every ~4th tick.
- The cache (`CachingMarketDataRepository`) has a **15s TTL** and coalesces concurrent
  same-symbol requests. Keep the poll interval ≥ the TTL so each tick is genuinely
  fresh, not a cached repeat.
- `1D` is already intraday (5-minute bars) and is the detail view's default timeframe.

## Build · Run · Test

```bash
swift build                                   # Swift 6, macOS 14+
```

**Run** — the app is a bare SwiftPM executable; launch the built binary directly
(an `AppDelegate` sets `.regular` activation policy so a window appears):

```bash
"$(swift build --show-bin-path)/APTradeApp"
```

> `swift run APTradeApp` is flaky for the GUI — prefer the binary path above.

**Test** — `DEVELOPER_DIR` must point at a full Xcode so XCTest is present:

```bash
DEVELOPER_DIR=/Applications/Xcode.app swift test
```

> Without `DEVELOPER_DIR`, the Command Line Tools lack XCTest and tests won't run.

Keep changes green: build clean and all tests passing before committing.
