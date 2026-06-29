# In-App Language Switcher — Design Spec

**Date:** 2026-06-29
**Status:** Approved (design), pending spec review → plan

## Goal

Let the user switch the app's interface language at runtime — **English, Deutsch (German), Italiano (Italian), Español (Spanish)** — from a picker in the Settings menu, with the whole UI re-rendering live (no restart), and the choice persisted across launches.

## Scope

**In scope — "Core UI chrome":** the app's own user-facing strings — tab names, section headers, buttons, field labels, settings, empty states, alert/notification copy, command-palette labels.

**Out of scope (explicitly):**
- Live market data in its source language: Finnhub news headlines/summaries/sources, company names, ticker symbols.
- Number/date/currency **formatting** per locale. Prices keep their current presentation (e.g. `$131`⁶³, `1,234.56`). Switching language does **not** change how numbers, dates, or currencies are formatted.
- Full ICU pluralization rules. Interpolated/count strings use simple format strings (acceptable for these four languages and the small counts involved); flagged as a known limitation.
- Native `.lproj` / `.xcstrings` / `AppleLanguages` localization (rejected — see Approach).

## Approach (decided)

**Option A — custom `LocalizationManager` + typed string catalog.** Chosen over native Apple localization because:
- Native localization resolves the bundle at launch (`AppleLanguages`); in-app switching would require a restart or a fragile bundle-swizzle, and SPM resource-bundle wiring this executable doesn't have.
- Option A mirrors the app's existing `ThemeManager` pattern, which already hot-swaps dark mode and accent through Observation. Same mental model, same persistence store, fully testable, Clean-Architecture-clean.

## Architecture

Strict Clean Architecture, matching the existing `AccentTheme` / `ThemeManager` split:

```
Domain          AppLanguage (enum)  +  AppSettings.language (new field)
   ↑
Application      (unchanged — settings use cases already persist AppSettings)
   ↑
Presentation     LocalizationManager (@Observable global)  +  L10n catalog  +  tr(_:)  +  Settings picker
```

The language **value type** lives in the domain (like `AccentTheme`); the **string table** and the **manager** live in presentation (the domain never holds UI copy). The key is no different from how `AccentTheme` is domain but its `Color` ramp is presentation.

### 1. Domain — `AppLanguage` + `AppSettings.language`

`Sources/APTradeDomain/AppLanguage.swift` (no imports needed — pure, like `NewsCategory`):

```swift
public enum AppLanguage: String, CaseIterable, Codable, Sendable {
    case english = "en"
    case german  = "de"
    case italian = "it"
    case spanish = "es"

    /// The language's own name (endonym), shown in the picker regardless of the
    /// currently active language — standard localization UX.
    public var displayName: String {
        switch self {
        case .english: return "English"
        case .german:  return "Deutsch"
        case .italian: return "Italiano"
        case .spanish: return "Español"
        }
    }
}
```

`Sources/APTradeDomain/AppSettings.swift` — add one stored property + lenient decode:
- `public var language: AppLanguage` in the struct and memberwise `init` (default `.english`).
- In the custom `init(from:)`: `language = try c.decodeIfPresent(AppLanguage.self, forKey: .language) ?? d.language`.

This guarantees an existing persisted settings payload (without a `language` key) loads as `.english` and never resets any other preference — identical to how `accent`/`isDarkMode` are handled.

### 2. Presentation — `LocalizationManager` (mirrors `ThemeManager`)

`Sources/APTradeApp/LocalizationManager.swift`:

```swift
@MainActor
@Observable
final class LocalizationManager {
    static let shared = LocalizationManager()

    var language: AppLanguage {
        didSet { if language != oldValue { persist() } }
    }

    private init() {
        language = CompositionRoot.settingsStore.load().language
    }

    /// Fresh load-modify-save so we only ever write `language`, never clobbering
    /// theme/accent that `ThemeManager` owns in the same store.
    private func persist() {
        var settings = CompositionRoot.settingsStore.load()
        settings.language = language
        CompositionRoot.settingsStore.save(settings)
    }
}
```

`LocalizationManager` and `ThemeManager` both persist to `CompositionRoot.settingsStore`. Both are `@MainActor`, so their load-modify-save cycles are serialized; each writes only its own fields after a fresh load, so they cannot clobber each other.

### 3. Presentation — the string catalog

`Sources/APTradeApp/L10n.swift`:

- `enum L10n` with a nested `enum Key: String, CaseIterable` — **one case per UI string** (`.watchlist`, `.portfolio`, `.news`, `.saved`, `.totalReturn`, `.noSavedArticles`, `.language`, …). The `String` raw value is the readable English text (e.g. `case totalReturn = "Total Return"`), so it doubles as a last-resort fallback that is never empty. Organized in the file by feature area with `// MARK:` comments.
- A `static let table: [Key: [AppLanguage: String]]` holding all four translations per key.
- A global free function:

```swift
@MainActor
func tr(_ key: L10n.Key) -> String {
    let lang = LocalizationManager.shared.language
    return L10n.table[key]?[lang]
        ?? L10n.table[key]?[.english]   // fall back to English if a translation is missing
        ?? key.rawValue                 // last resort: the key's English raw value, never empty
}
```

The completeness test (below) makes the two fallbacks unreachable in practice; they exist only as defensive guards.

Because `tr` reads `LocalizationManager.shared.language`, **any view whose `body` calls `tr(...)` re-renders when the language changes** — the same Observation mechanism that makes `Theme` colors live-update. Call sites: `Text(tr(.watchlist))`.

**Interpolated strings:** keys whose value is a format string (e.g. `"%d results"` / `"%d Ergebnisse"`) are formatted at the call site via `String(format: tr(.nResults), count)`. A small number of these exist in chrome (result counts, etc.).

**Completeness guarantee:** a unit test iterates `L10n.Key.allCases` and asserts each has a non-empty entry for **all four** `AppLanguage` cases. Missing or empty translations fail the build's test step — the safety net for the bulk translation content.

### 4. Presentation — the Settings picker

In `Sources/APTradeApp/RootView.swift`, inside `appearancePage` (the existing **Appearance** subpage of the account drawer, which already hosts the Theme and Accent sections), add a **Language** section beneath Accent:

```swift
sectionLabel(tr(.language))            // "Language" / "Sprache" / "Lingua" / "Idioma"
ForEach(AppLanguage.allCases, id: \.self) { language in
    languageRow(language)
}
```

`languageRow(_:)` mirrors the existing `accentRow(_:)`: shows `language.displayName` (always the endonym), a trailing checkmark when `LocalizationManager.shared.language == language`, and on tap sets `LocalizationManager.shared.language = language` inside `withAnimation(.easeInOut(duration: 0.25))` so the UI cross-fades into the new language. The section label itself is localized (`tr(.language)`) so the picker's own header flips with the language.

Rationale for placement: Dark Mode and Accent already live in the Appearance subpage; Language is the same class of "appearance/interface" preference and belongs alongside them. (Alternative considered: a dedicated top-level row in the account drawer — deferred; the section-within-Appearance is consistent and lighter.)

### 5. String migration (the bulk of the work)

Replace hardcoded English literals with `tr(.key)` across the presentation views. Surface inventory (each file is a migration unit / task):

- `RootView.swift` — tab names, account-drawer rows, every subpage (Profile, Account Settings, Notifications, Appearance, Security & Privacy, Help, About) headers/labels/copy.
- `WatchlistView.swift` — header, search/empty states, column/section labels.
- `PortfolioView.swift` + the Holdings / Allocation / Activity / Performance sub-surfaces — sub-switcher labels, metric titles (Total Return, CAGR, Volatility, Max Drawdown, Sharpe, Beta, Alpha, diversification), empty states.
- `NewsView.swift` — the category pills currently render `NewsCategory.displayName` (domain copy); the view will instead route each category to a localized key (`tr(.newsGeneral)` / `tr(.newsCrypto)` / `tr(.newsMerger)`) so the domain enum stays free of UI copy. Also: Saved toggle, filter placeholder, empty/no-key states.
- `AssetDetailView.swift`, `AssetNewsSection.swift` — section headers, buttons, the "News" label.
- `TradeView` / buy-sell surface — Buy/Sell, quantity, confirm, Max helper.
- Alerts UI — condition labels, create/delete copy.
- `CommandPaletteView` — placeholder, "Go to Watchlist/Portfolio" labels, section headers.
- Shared empty-state / button components.

Each migrated string gets a `L10n.Key` case with all four translations added to the table. `NewsCategory.displayName` is domain copy; for category pills the view will route through a `tr(.newsGeneral/.newsCrypto/.newsMerger)` mapping rather than localizing the domain enum, keeping the domain framework- and copy-policy-clean.

## Translation terminology (locks key finance terms; full table built during migration)

| English | Deutsch | Italiano | Español |
|---|---|---|---|
| Watchlist | Beobachtungsliste | Lista di controllo | Lista de seguimiento |
| Portfolio | Portfolio | Portafoglio | Cartera |
| News | Nachrichten | Notizie | Noticias |
| Saved | Gespeichert | Salvati | Guardados |
| Holdings | Bestände | Posizioni | Posiciones |
| Allocation | Aufteilung | Allocazione | Asignación |
| Activity | Aktivität | Attività | Actividad |
| Performance | Wertentwicklung | Rendimento | Rendimiento |
| Total Return | Gesamtrendite | Rendimento totale | Rentabilidad total |
| Buy / Sell | Kaufen / Verkaufen | Compra / Vendi | Comprar / Vender |
| Settings | Einstellungen | Impostazioni | Ajustes |
| Appearance | Darstellung | Aspetto | Apariencia |
| Language | Sprache | Lingua | Idioma |

These are the anchor terms; the full per-key table is produced during the migration tasks and verified by the completeness test. The user reviews and may adjust any wording.

## Testing

- **Domain:** `AppLanguage` raw values, `displayName` endonyms, `allCases` order/count.
- **Domain:** `AppSettings` round-trips `language`, and a payload **missing** the `language` key decodes to `.english` while preserving all other fields (lenient-decode regression).
- **Presentation:** `LocalizationManager` — setting `language` persists through the settings store; a fresh manager loads the saved language; switching language does not alter `isDarkMode`/`accent` in the store.
- **Catalog completeness:** every `L10n.Key.allCases` entry has a non-empty string for all four `AppLanguage` cases.
- **Resolution:** `tr(.key)` returns the correct per-language string; a key with a (hypothetically) missing translation falls back to English, never to empty.

Tests follow existing style: `UserDefaults(suiteName:)` isolation for store-backed tests, `@MainActor` on manager/VM test classes.

## Non-goals / future passes

- Localized number/date/currency formatting (per-locale grouping/decimal separators, currency symbols).
- Localizing dynamic provider content (news, company names).
- Additional languages beyond the four; right-to-left layouts.
- Full ICU plural/gender handling.

## Out-of-scope improvements noticed

None required for this work. The migration touches many view files but follows the established `tr(.key)` substitution uniformly; no refactoring of view structure is in scope.
