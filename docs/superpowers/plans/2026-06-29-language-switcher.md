# In-App Language Switcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a runtime language switcher (English / Deutsch / Italiano / Español) reachable from a dedicated account-drawer row, that re-renders the whole UI live (no restart) and persists the choice.

**Architecture:** A pure-domain `AppLanguage` enum + a new `AppSettings.language` field; a presentation-layer `LocalizationManager` (`@MainActor @Observable` singleton, mirroring `ThemeManager`) that persists through the existing settings store; a typed `L10n` string catalog with a `tr(_:)` resolver that reads the manager (so Observation re-renders every view on switch); a dedicated drawer row + subpage picker; then a per-file migration of hardcoded English literals to `tr(.key)`.

**Tech Stack:** Swift, SwiftUI, AppKit, XCTest. Four SPM modules: `APTradeDomain` → `APTradeApplication` → {`APTradeInfrastructure`, `APTradeApp`}. Reference design: `docs/superpowers/specs/2026-06-29-language-switcher-design.md`.

## Global Constraints

- **Scope = "Core UI chrome":** localize the app's own user-facing strings only. Do **not** localize: Finnhub news headlines/summaries/sources, company/ticker names, or number/date/currency **formatting** (prices stay `$1,234.56` etc.). Do **not** localize non-user-facing literals: SF Symbol names, image/asset keys, format specifiers, `UserDefaults` keys. Accessibility labels **are** user-facing — localize them.
- **Build/test toolchain:** prefix every `swift test` with `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` or XCTest is missing. `swift build` needs no override.
- **Domain purity:** `Sources/APTradeDomain/` imports only `Foundation` (or nothing). `AppLanguage` holds **no UI copy beyond its own endonym `displayName`**; all translated UI strings live in the presentation `L10n` catalog.
- **Languages:** exactly four — `english="en"`, `german="de"`, `italian="it"`, `spanish="es"`. Endonyms in the picker: English, Deutsch, Italiano, Español.
- **Lenient settings decode:** the new `language` field MUST use `decodeIfPresent ?? default` so existing persisted settings load unchanged (default `.english`).
- **`LocalizationManager` mirrors `ThemeManager`:** `@MainActor @Observable`, `static let shared`, persists via `CompositionRoot.settingsStore` with a fresh load-modify-save that writes only `language` (never clobbering `isDarkMode`/`accent`).
- **`tr(_:)` is `@MainActor`** and reads `LocalizationManager.shared.language`; call sites in view bodies therefore re-render live on switch (same Observation mechanism as `Theme` colors).
- **Translation production:** the implementer produces de/it/es translations for every migrated key, using the locked terminology table in Task 3 for finance terms and standard fluency elsewhere. This is required deliverable content, not a placeholder; the **catalog-completeness test** (Task 3) enforces that every key has a non-empty string in all four languages.
- **Anchor terminology (use verbatim):** Watchlist→Beobachtungsliste/Lista di controllo/Lista de seguimiento; Portfolio→Portfolio/Portafoglio/Cartera; News→Nachrichten/Notizie/Noticias; Saved→Gespeichert/Salvati/Guardados; Holdings→Bestände/Posizioni/Posiciones; Allocation→Aufteilung/Allocazione/Asignación; Activity→Aktivität/Attività/Actividad; Performance→Wertentwicklung/Rendimento/Rendimiento; Total Return→Gesamtrendite/Rendimento totale/Rentabilidad total; Buy/Sell→Kaufen/Verkaufen · Compra/Vendi · Comprar/Vender; Settings→Einstellungen/Impostazioni/Ajustes; Appearance→Darstellung/Aspetto/Apariencia; Language→Sprache/Lingua/Idioma.
- Follow existing test style: `UserDefaults(suiteName:)` isolation for store-backed tests; `@MainActor` on manager/VM test classes.

---

### Task 1: AppLanguage enum (domain)

**Files:**
- Create: `Sources/APTradeDomain/AppLanguage.swift`
- Test: `Tests/APTradeDomainTests/AppLanguageTests.swift`

**Interfaces:**
- Consumes: nothing (pure).
- Produces: `enum AppLanguage: String, CaseIterable, Codable, Sendable { case english="en", german="de", italian="it", spanish="es" }` with `var displayName: String`.

- [ ] **Step 1: Write the failing test**

Create `Tests/APTradeDomainTests/AppLanguageTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class AppLanguageTests: XCTestCase {
    func test_rawValues_areBCP47Codes() {
        XCTAssertEqual(AppLanguage.english.rawValue, "en")
        XCTAssertEqual(AppLanguage.german.rawValue, "de")
        XCTAssertEqual(AppLanguage.italian.rawValue, "it")
        XCTAssertEqual(AppLanguage.spanish.rawValue, "es")
    }

    func test_displayName_usesEndonyms() {
        XCTAssertEqual(AppLanguage.english.displayName, "English")
        XCTAssertEqual(AppLanguage.german.displayName, "Deutsch")
        XCTAssertEqual(AppLanguage.italian.displayName, "Italiano")
        XCTAssertEqual(AppLanguage.spanish.displayName, "Español")
    }

    func test_allCases_orderAndCount() {
        XCTAssertEqual(AppLanguage.allCases, [.english, .german, .italian, .spanish])
    }

    func test_codableRoundTrip() throws {
        let data = try JSONEncoder().encode(AppLanguage.german)
        XCTAssertEqual(try JSONDecoder().decode(AppLanguage.self, from: data), .german)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter AppLanguageTests`
Expected: FAIL — `cannot find 'AppLanguage' in scope`.

- [ ] **Step 3: Write minimal implementation**

Create `Sources/APTradeDomain/AppLanguage.swift`:

```swift
/// The app's interface language. Pure value type — the actual translated strings live in
/// the presentation `L10n` catalog (the way `AccentTheme` is domain but its color ramp is
/// presentation). Only the endonym `displayName` is carried here, for the picker.
public enum AppLanguage: String, CaseIterable, Codable, Sendable {
    case english = "en"
    case german  = "de"
    case italian = "it"
    case spanish = "es"

    /// The language's own name, shown in the picker regardless of the active language.
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

- [ ] **Step 4: Run test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter AppLanguageTests`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeDomain/AppLanguage.swift Tests/APTradeDomainTests/AppLanguageTests.swift
git commit -m "feat(domain): AppLanguage enum (en/de/it/es)"
```

---

### Task 2: AppSettings.language field + lenient decode (domain)

**Files:**
- Modify: `Sources/APTradeDomain/AppSettings.swift`
- Test: `Tests/APTradeDomainTests/AppSettingsLanguageTests.swift`

**Interfaces:**
- Consumes: `AppLanguage` (Task 1).
- Produces: `AppSettings.language: AppLanguage` (stored property; memberwise-init param `language: AppLanguage = .english`; lenient-decoded).

`AppSettings` has a custom `init(from:)` using **synthesized** `CodingKeys` (no explicit enum). Adding the stored property auto-extends `CodingKeys` with `.language`, so the decoder line below compiles.

- [ ] **Step 1: Write the failing test**

Create `Tests/APTradeDomainTests/AppSettingsLanguageTests.swift`:

```swift
import XCTest
@testable import APTradeDomain

final class AppSettingsLanguageTests: XCTestCase {
    func test_default_languageIsEnglish() {
        XCTAssertEqual(AppSettings.default.language, .english)
    }

    func test_codableRoundTrip_preservesLanguage() throws {
        var s = AppSettings.default
        s.language = .spanish
        let data = try JSONEncoder().encode(s)
        XCTAssertEqual(try JSONDecoder().decode(AppSettings.self, from: data).language, .spanish)
    }

    func test_legacyPayloadWithoutLanguage_decodesToEnglishAndKeepsOtherFields() throws {
        // A payload saved before `language` existed: omits the key, sets a non-default elsewhere.
        let legacy = #"{ "isDarkMode": false, "confirmTrades": false }"#.data(using: .utf8)!
        let decoded = try JSONDecoder().decode(AppSettings.self, from: legacy)
        XCTAssertEqual(decoded.language, .english)      // absent → default
        XCTAssertFalse(decoded.isDarkMode)              // existing field preserved
        XCTAssertFalse(decoded.confirmTrades)           // existing field preserved
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter AppSettingsLanguageTests`
Expected: FAIL — `value of type 'AppSettings' has no member 'language'`.

- [ ] **Step 3: Write the implementation**

In `Sources/APTradeDomain/AppSettings.swift`:

1. Add the stored property in the `// Appearance` group, after `accent`:
```swift
    public var accent: AccentTheme

    // Language
    public var language: AppLanguage
```

2. Add the memberwise-init parameter (after `accent: AccentTheme = .champagneGold`) and its assignment:
```swift
        accent: AccentTheme = .champagneGold,
        language: AppLanguage = .english
    ) {
```
```swift
        self.accent = accent
        self.language = language
    }
```

3. Add the lenient decode line in `init(from:)`, after the `accent` line:
```swift
        accent = try c.decodeIfPresent(AccentTheme.self, forKey: .accent) ?? d.accent
        language = try c.decodeIfPresent(AppLanguage.self, forKey: .language) ?? d.language
```

- [ ] **Step 4: Run test to verify it passes**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter AppSettingsLanguageTests`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full suite (no regressions)**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: all pass (existing settings-decode tests still green).

- [ ] **Step 6: Commit**

```bash
git add Sources/APTradeDomain/AppSettings.swift Tests/APTradeDomainTests/AppSettingsLanguageTests.swift
git commit -m "feat(domain): persist language in AppSettings (lenient decode)"
```

---

### Task 3: Localization infrastructure — LocalizationManager + L10n catalog + tr() (presentation)

**Files:**
- Create: `Sources/APTradeApp/LocalizationManager.swift`
- Create: `Sources/APTradeApp/L10n.swift`
- Test: `Tests/APTradeAppTests/LocalizationManagerTests.swift`
- Test: `Tests/APTradeAppTests/L10nTests.swift`

**Interfaces:**
- Consumes: `AppLanguage` (Task 1), `AppSettings`/`CompositionRoot.settingsStore` (existing).
- Produces:
  - `final class LocalizationManager` (`@MainActor @Observable`): `static let shared`, `var language: AppLanguage`.
  - `enum L10n` with `enum Key: String, CaseIterable` and `static let table: [Key: [AppLanguage: String]]`.
  - `@MainActor func tr(_ key: L10n.Key) -> String`.

This task seeds the catalog with the **core navigation keys** (used by Task 4). Later migration tasks (5–10) add their own `Key` cases + table rows; the completeness test guards every addition.

- [ ] **Step 1: Write the failing tests**

Create `Tests/APTradeAppTests/L10nTests.swift`:

```swift
import XCTest
@testable import APTradeApp
import APTradeDomain

@MainActor
final class L10nTests: XCTestCase {
    func test_everyKeyHasAllFourLanguages_nonEmpty() {
        for key in L10n.Key.allCases {
            let row = L10n.table[key]
            XCTAssertNotNil(row, "missing table row for \(key)")
            for lang in AppLanguage.allCases {
                let value = row?[lang]
                XCTAssertNotNil(value, "missing \(lang) for \(key)")
                XCTAssertFalse((value ?? "").isEmpty, "empty \(lang) for \(key)")
            }
        }
    }

    func test_tr_resolvesActiveLanguage() {
        LocalizationManager.shared.language = .german
        XCTAssertEqual(tr(.watchlist), "Beobachtungsliste")
        LocalizationManager.shared.language = .spanish
        XCTAssertEqual(tr(.watchlist), "Lista de seguimiento")
        LocalizationManager.shared.language = .english
        XCTAssertEqual(tr(.watchlist), "Watchlist")
    }
}
```

Create `Tests/APTradeAppTests/LocalizationManagerTests.swift`:

```swift
import XCTest
@testable import APTradeApp
@testable import APTradeInfrastructure
import APTradeDomain

@MainActor
final class LocalizationManagerTests: XCTestCase {
    // LocalizationManager.shared persists through CompositionRoot.settingsStore (UserDefaults.standard).
    // Save/restore that key around each test so we don't leak state.
    private var saved: AppSettings!
    override func setUp() { saved = CompositionRoot.settingsStore.load() }
    override func tearDown() { CompositionRoot.settingsStore.save(saved) }

    func test_settingLanguage_persistsThroughStore() {
        LocalizationManager.shared.language = .italian
        XCTAssertEqual(CompositionRoot.settingsStore.load().language, .italian)
    }

    func test_switchingLanguage_doesNotClobberThemeOrAccent() {
        var s = CompositionRoot.settingsStore.load()
        s.isDarkMode = false
        s.accent = .roseGold
        CompositionRoot.settingsStore.save(s)

        LocalizationManager.shared.language = .german

        let after = CompositionRoot.settingsStore.load()
        XCTAssertEqual(after.language, .german)
        XCTAssertFalse(after.isDarkMode)        // untouched
        XCTAssertEqual(after.accent, .roseGold) // untouched
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter L10nTests`
Then: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter LocalizationManagerTests`
Expected: both FAIL — `cannot find 'L10n'` / `cannot find 'LocalizationManager'`.

- [ ] **Step 3: Write LocalizationManager**

Create `Sources/APTradeApp/LocalizationManager.swift`:

```swift
import APTradeDomain

/// Drives the app's interface language for `tr(_:)`. Mirrors `ThemeManager`: Observation
/// tracks `language`, so any view whose body calls `tr(...)` re-renders when it changes,
/// with no environment plumbing. Persists through the shared settings store.
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

    /// Fresh load-modify-save so only `language` is written — `ThemeManager` owns
    /// `isDarkMode`/`accent` in the same store and must not be clobbered.
    private func persist() {
        var settings = CompositionRoot.settingsStore.load()
        settings.language = language
        CompositionRoot.settingsStore.save(settings)
    }
}
```

- [ ] **Step 4: Write the L10n catalog (seed keys) + tr()**

Create `Sources/APTradeApp/L10n.swift`:

```swift
import APTradeDomain

/// Typed UI-string catalog. Each `Key`'s `String` raw value is the English source text,
/// so it doubles as a never-empty last-resort fallback. Translations for all four
/// languages live in `table`; the catalog-completeness test enforces full coverage.
/// Migration tasks add `Key` cases + `table` rows per view file.
enum L10n {
    enum Key: String, CaseIterable {
        // MARK: Navigation / core
        case watchlist = "Watchlist"
        case portfolio = "Portfolio"
        case news = "News"
        case language = "Language"
    }

    static let table: [Key: [AppLanguage: String]] = [
        .watchlist: [.english: "Watchlist", .german: "Beobachtungsliste",
                     .italian: "Lista di controllo", .spanish: "Lista de seguimiento"],
        .portfolio: [.english: "Portfolio", .german: "Portfolio",
                     .italian: "Portafoglio", .spanish: "Cartera"],
        .news:      [.english: "News", .german: "Nachrichten",
                     .italian: "Notizie", .spanish: "Noticias"],
        .language:  [.english: "Language", .german: "Sprache",
                     .italian: "Lingua", .spanish: "Idioma"],
    ]
}

/// Resolves `key` against the active language. Falls back to English, then the key's
/// English raw value — both unreachable while the completeness test passes.
@MainActor
func tr(_ key: L10n.Key) -> String {
    let lang = LocalizationManager.shared.language
    return L10n.table[key]?[lang]
        ?? L10n.table[key]?[.english]
        ?? key.rawValue
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter L10nTests`
Then: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter LocalizationManagerTests`
Expected: PASS.

- [ ] **Step 6: Run the full suite + commit**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: all pass.

```bash
git add Sources/APTradeApp/LocalizationManager.swift Sources/APTradeApp/L10n.swift Tests/APTradeAppTests/LocalizationManagerTests.swift Tests/APTradeAppTests/L10nTests.swift
git commit -m "feat(presentation): LocalizationManager + L10n catalog + tr() resolver"
```

---

### Task 4: Language picker (drawer row + subpage) + tab-bar localization (presentation)

**Files:**
- Modify: `Sources/APTradeApp/RootView.swift`

**Interfaces:**
- Consumes: `tr(_:)` + `L10n.Key` (Task 3), `LocalizationManager` (Task 3), `AppLanguage` (Task 1), existing `PanelRoute`, `accountRow`, `subpageHeader`, `Theme`, `Tab`.
- Produces: a working, live-switching language picker; the top tab switcher labels read through `tr(_:)`.

This is the first visible slice: after this task, switching language in the picker instantly relabels the tab bar. No automated test (presentation glue); deliverable is a clean build + full suite + the live switch traced in code.

- [ ] **Step 1: Add `.language` to PanelRoute**

In `Sources/APTradeApp/RootView.swift`, add a case to the private `PanelRoute` enum (around line 9):

```swift
    private enum PanelRoute {
        case menu, profile, accountSettings, notifications, appearance, security, help, about, language
    }
```
(Match the enum's existing case list; append `language`.)

- [ ] **Step 2: Add the drawer row**

In the account-drawer menu list, after the Appearance row (around line 270):

```swift
                accountRow(icon: "paintpalette", title: "Appearance") { panelRoute = .appearance }
                accountRow(icon: "globe", title: tr(.language)) { panelRoute = .language }
```

- [ ] **Step 3: Route to the subpage**

In the `switch panelRoute` dispatch (around line 232–237), add:

```swift
        case .language: languagePage
```

- [ ] **Step 4: Add the subpage + row builder**

Add these two members near `appearancePage` / `accentRow`:

```swift
    private var languagePage: some View {
        VStack(alignment: .leading, spacing: 0) {
            subpageHeader(title: tr(.language))
            Divider().overlay(Theme.hairline)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(AppLanguage.allCases, id: \.self) { language in
                    languageRow(language)
                }
            }
            .padding(.top, 6)
            Spacer()
        }
        .padding(.top, 4)
    }

    private func languageRow(_ language: AppLanguage) -> some View {
        let selected = LocalizationManager.shared.language == language
        return Button {
            withAnimation(.easeInOut(duration: 0.25)) {
                LocalizationManager.shared.language = language
            }
        } label: {
            HStack(spacing: 12) {
                Text(language.displayName)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 16)).foregroundStyle(Theme.gold)
                }
            }
            .padding(.horizontal, 4).padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
```

If `RootView.swift` imports `APTradeDomain` already (it references `AppSettings`/`AccentTheme`), no new import is needed; otherwise add `import APTradeDomain`.

- [ ] **Step 5: Localize the top tab switcher labels**

Find where the tab switcher pills render their titles from `Tab` (the switcher built from `Tab.allCases`, displaying each tab's label — search for `Tab.allCases` and the `Text(...)` that shows `tab.rawValue` or the tab title). Replace the displayed title with a `tr(_:)` lookup mapping each tab to its key:

```swift
                    // where each tab pill's title is rendered:
                    Text(tabTitle(tab))
```
Add the helper to the same view:
```swift
    private func tabTitle(_ tab: Tab) -> String {
        switch tab {
        case .watchlist: return tr(.watchlist)
        case .portfolio: return tr(.portfolio)
        case .news:      return tr(.news)
        }
    }
```
Leave the `Tab` enum's `rawValue` strings untouched (they may be used as stable identifiers); only the **displayed** label routes through `tr`.

- [ ] **Step 6: Build + full suite**

Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build`
Expected: zero errors.
Run: `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/RootView.swift
git commit -m "feat(settings): live language picker (drawer row + subpage) + localized tabs"
```

---

### Migration tasks (5–10): replace hardcoded literals with `tr(.key)`

**Shared procedure for every migration task below.** Each task targets specific file(s). For each:

1. **Enumerate** the file's user-facing string literals (Step 1 of each task names the surfaces; run `grep -nE '"[^"]*"' <file>` and judgment-filter per the Global Constraints — skip SF Symbol names, asset/UserDefaults keys, format specifiers).
2. For each user-facing literal, **add a `L10n.Key` case** (raw value = the English text) under the appropriate `// MARK:` section in `L10n.swift`, and **add a `table` row** with English + de/it/es translations (anchor terms verbatim; standard fluent finance translation otherwise). Reuse an existing key if the exact English string already has one — do not duplicate.
3. **Replace** the literal in the view with `Text(tr(.key))` (or, for non-`Text` consumers like `accountRow(title:)`/`subpageHeader(title:)`/`Label`/`.navigationTitle`/accessibility labels, pass `tr(.key)`). For interpolated strings, make the key a format string (`"%d results"` → de/it/es equivalents) and call `String(format: tr(.key), count)`.
4. **Verify:** `swift build` (zero errors) → `swift test --filter L10nTests` (completeness still green for the new keys) → `swift test` (full suite, no regressions).
5. **Confirm no user-facing English literal remains** in the migrated file: re-grep and eyeball; note any intentionally-skipped literal (e.g. a ticker placeholder) in the commit body.
6. **Commit** with the per-task message.

No automated UI test is added by migration tasks (presentation glue, exactly like the News view tasks); the catalog-completeness test + clean build + the no-remaining-literal check are the gates. GUI click-through falls to the user (computer-use can't drive the bare dev binary).

---

### Task 5: Migrate RootView (account drawer + all subpages)

**Files:**
- Modify: `Sources/APTradeApp/RootView.swift`
- Modify: `Sources/APTradeApp/L10n.swift` (add keys + translations)

**Surfaces to migrate** (follow the Shared procedure): every account-drawer row title (Profile, Account Settings, Notifications, Appearance, Export Portfolio Data, Security & Privacy, Help & Support, About APTrade, Sign Out, Login) and the icon-row titles; every `subpageHeader(title:)`; all `sectionLabel(...)`, `themeOptionRow(title:subtitle:)`, toggle/row labels and descriptive copy inside the Profile, Account Settings, Notifications, Appearance, Security & Privacy, Help & Support, and About subpages; any empty/status copy. (`tr(.language)`, `tr(.watchlist/.portfolio/.news)` already exist from Tasks 3–4 — reuse.) Add `// MARK: Settings / account` and `// MARK: Appearance` sections to `L10n.swift` for the new keys.

- [ ] **Step 1:** Enumerate RootView user-facing literals (`grep -nE '"[^"]*"' Sources/APTradeApp/RootView.swift`, filter per constraints).
- [ ] **Step 2:** Add a `L10n.Key` + 4-language `table` row for each (anchor terms verbatim).
- [ ] **Step 3:** Replace each literal with `tr(.key)` at its call site.
- [ ] **Step 4:** `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build` → zero errors.
- [ ] **Step 5:** `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter L10nTests` → green; then full `swift test` → all pass.
- [ ] **Step 6:** Re-grep RootView; confirm no user-facing English literal remains (note skips).
- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/RootView.swift Sources/APTradeApp/L10n.swift
git commit -m "i18n: localize RootView account drawer + settings subpages"
```

---

### Task 6: Migrate WatchlistView

**Files:**
- Modify: `Sources/APTradeApp/WatchlistView.swift`, `Sources/APTradeApp/WatchlistSection.swift`
- Modify: `Sources/APTradeApp/L10n.swift`

**Surfaces:** header/title, search field placeholder, section/column labels, empty-state copy, any add/remove/reorder affordance labels and accessibility labels. Add a `// MARK: Watchlist` section to `L10n.swift`.

- [ ] **Step 1:** Enumerate user-facing literals in both files.
- [ ] **Step 2:** Add `L10n.Key` + 4-language rows.
- [ ] **Step 3:** Replace with `tr(.key)`.
- [ ] **Step 4:** `swift build` (DEVELOPER_DIR) → zero errors.
- [ ] **Step 5:** `swift test --filter L10nTests` → green; full `swift test` → all pass.
- [ ] **Step 6:** Re-grep both files; confirm clean.
- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/WatchlistView.swift Sources/APTradeApp/WatchlistSection.swift Sources/APTradeApp/L10n.swift
git commit -m "i18n: localize Watchlist"
```

---

### Task 7: Migrate PortfolioView + performance/chart surfaces

**Files:**
- Modify: `Sources/APTradeApp/PortfolioView.swift`, `Sources/APTradeApp/PerformanceSection.swift`, `Sources/APTradeApp/ExpandableValueChart.swift`
- Modify: `Sources/APTradeApp/L10n.swift`

**Surfaces:** the Holdings / Allocation / Activity / Performance sub-switcher labels; metric titles (Total Return, Annualized Return/CAGR, Volatility, Max Drawdown, Sharpe, Beta, Alpha, diversification/effective-holdings, benchmark labels); top-movers strip header; allocation asset-class labels (Stocks/ETFs/Crypto); activity/realized-P&L labels; export labels; chart axis/empty/crosshair labels; reset-portfolio copy. Use anchor terms for Holdings/Allocation/Activity/Performance/Total Return. Add a `// MARK: Portfolio` section. Leave numeric/currency formatting unchanged.

- [ ] **Step 1:** Enumerate user-facing literals across the three files.
- [ ] **Step 2:** Add `L10n.Key` + 4-language rows (anchor terms verbatim).
- [ ] **Step 3:** Replace with `tr(.key)`.
- [ ] **Step 4:** `swift build` (DEVELOPER_DIR) → zero errors.
- [ ] **Step 5:** `swift test --filter L10nTests` → green; full `swift test` → all pass.
- [ ] **Step 6:** Re-grep the three files; confirm clean.
- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/PortfolioView.swift Sources/APTradeApp/PerformanceSection.swift Sources/APTradeApp/ExpandableValueChart.swift Sources/APTradeApp/L10n.swift
git commit -m "i18n: localize Portfolio (holdings/allocation/activity/performance + charts)"
```

---

### Task 8: Migrate News surfaces + category pills

**Files:**
- Modify: `Sources/APTradeApp/NewsView.swift`, `Sources/APTradeApp/AssetNewsSection.swift`, `Sources/APTradeApp/ArticleRow.swift`
- Modify: `Sources/APTradeApp/L10n.swift`

**Surfaces:** the category pills (General/Crypto/Merger) — route each `NewsCategory` to `tr(.newsGeneral/.newsCrypto/.newsMerger)` **in the view** rather than localizing the domain enum; the Saved toggle label, filter placeholder, all empty-state and no-key copy ("No headlines right now", "No saved articles", "Connect a news source", "Refresh", and the no-key instructional sentence), the asset-detail "News" section header, and any accessibility label on the bookmark/open affordances. **Do not** localize article headline/summary/source text (dynamic provider data). Add a `// MARK: News` section.

Category mapping helper in `NewsView` (and reuse in `AssetNewsSection` if it shows category):
```swift
    private func categoryTitle(_ category: NewsCategory) -> String {
        switch category {
        case .general: return tr(.newsGeneral)
        case .crypto:  return tr(.newsCrypto)
        case .merger:  return tr(.newsMerger)
        }
    }
```
Suggested translations — General: Allgemein/Generali/Generales; Crypto: Krypto/Cripto/Cripto; Merger: Fusionen/Fusioni/Fusiones.

- [ ] **Step 1:** Enumerate user-facing literals across the three files (exclude article data bindings).
- [ ] **Step 2:** Add `L10n.Key` + 4-language rows (including the three category keys).
- [ ] **Step 3:** Replace literals with `tr(.key)`; wire the category pills through `categoryTitle(_:)`.
- [ ] **Step 4:** `swift build` (DEVELOPER_DIR) → zero errors.
- [ ] **Step 5:** `swift test --filter L10nTests` → green; full `swift test` → all pass.
- [ ] **Step 6:** Re-grep the three files; confirm only dynamic article data remains as literals/bindings.
- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/NewsView.swift Sources/APTradeApp/AssetNewsSection.swift Sources/APTradeApp/ArticleRow.swift Sources/APTradeApp/L10n.swift
git commit -m "i18n: localize News tab + asset-detail news + category pills"
```

---

### Task 9: Migrate AssetDetailView + Trade + Alert sheets

**Files:**
- Modify: `Sources/APTradeApp/AssetDetailView.swift`, `Sources/APTradeApp/TradeSheet.swift`, `Sources/APTradeApp/PriceAlertSheet.swift`
- Modify: `Sources/APTradeApp/L10n.swift`

**Surfaces:** asset-detail section headers and the position panel labels; chart timeframe/indicator labels and chart-style toggles if rendered as text; Buy/Sell labels, quantity/amount field labels, the Max helper, trade-confirmation copy, order-submit/cancel buttons (anchor: Buy/Sell); price-alert condition labels (Price Above / Price Below / Percent Daily Move / Volume Spike), create/save/delete alert copy. Add `// MARK: Asset detail`, `// MARK: Trade`, `// MARK: Alerts` sections.

- [ ] **Step 1:** Enumerate user-facing literals across the three files.
- [ ] **Step 2:** Add `L10n.Key` + 4-language rows (anchor Buy/Sell verbatim).
- [ ] **Step 3:** Replace with `tr(.key)`.
- [ ] **Step 4:** `swift build` (DEVELOPER_DIR) → zero errors.
- [ ] **Step 5:** `swift test --filter L10nTests` → green; full `swift test` → all pass.
- [ ] **Step 6:** Re-grep the three files; confirm clean (ticker/price bindings may remain).
- [ ] **Step 7: Commit**

```bash
git add Sources/APTradeApp/AssetDetailView.swift Sources/APTradeApp/TradeSheet.swift Sources/APTradeApp/PriceAlertSheet.swift Sources/APTradeApp/L10n.swift
git commit -m "i18n: localize asset detail + trade + price-alert sheets"
```

---

### Task 10: Migrate Command Palette + final sweep

**Files:**
- Modify: `Sources/APTradeApp/CommandPaletteView.swift`
- Modify: `Sources/APTradeApp/DesignKit.swift` (only if it carries user-facing copy; skip SF Symbol/image keys)
- Modify: `Sources/APTradeApp/L10n.swift`
- Possibly modify: any remaining `Sources/APTradeApp/*.swift` view with a straggler literal

**Surfaces:** command-palette search placeholder, "Go to Watchlist"/"Go to Portfolio" labels, result section headers, empty/no-results copy. Then a **final sweep**: grep all of `Sources/APTradeApp/*.swift` for user-facing string literals not yet routed through `tr`, and localize any stragglers (judgment-filter per Global Constraints).

- [ ] **Step 1:** Migrate CommandPaletteView per the Shared procedure (add `// MARK: Command palette`).
- [ ] **Step 2:** Final sweep — `grep -rnE 'Text\("|Label\("|\.navigationTitle\("|placeholder|accessibilityLabel\("' Sources/APTradeApp/*.swift` and review each hit; localize any remaining user-facing literal (add keys/translations); record deliberately-skipped literals.
- [ ] **Step 3:** `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build` → zero errors.
- [ ] **Step 4:** `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test` → all pass (incl. `L10nTests` completeness over the now-full key set).
- [ ] **Step 5: Commit**

```bash
git add Sources/APTradeApp/CommandPaletteView.swift Sources/APTradeApp/L10n.swift
git commit -m "i18n: localize command palette + final string sweep"
```

- [ ] **Step 6: Manual UI verification (user)**

Launch `"$(swift build --show-bin-path)/APTradeApp"`. Open the account drawer → **Language** (globe row) → switch between English / Deutsch / Italiano / Español and confirm: the whole UI relabels instantly (tabs, settings, watchlist, portfolio, news, sheets); the choice survives relaunch; prices/dates remain in their existing format; news headlines stay in their source language. (computer-use can't drive the bare dev binary, so this falls to the user.)

---

## Self-Review

**1. Spec coverage**

| Spec requirement | Task |
|---|---|
| `AppLanguage` enum (en/de/it/es, endonyms) | Task 1 |
| `AppSettings.language` + lenient decode | Task 2 |
| `LocalizationManager` (mirrors ThemeManager, no-clobber persist) | Task 3 |
| `L10n` typed catalog + `tr(_:)` + completeness test | Task 3 (+ extended by 5–10) |
| Live runtime switch (Observation) | Tasks 3–4 (mechanism), visible from 4 on |
| Dedicated drawer **row + subpage** picker | Task 4 |
| Core-UI-chrome string migration | Tasks 5–10 (all view files) |
| News category pills localized in-view, domain enum untouched | Task 8 |
| Out of scope: provider data + number/date/currency formatting | Global Constraints; reaffirmed in Task 10 verification |
| Tests: domain, settings decode, manager, completeness, resolution | Tasks 1,2,3 |

No gaps.

**2. Placeholder scan:** Foundational tasks (1–4) contain complete code. Migration tasks (5–10) use a uniform, explicitly-defined Shared procedure with named surfaces per file and a completeness-test gate — the translation strings are required implementer-produced deliverables (documented in Global Constraints), not "TODO" placeholders. No "add appropriate X" / "similar to Task N" left unresolved.

**3. Type consistency:** `AppLanguage` (Task 1) is consumed identically by `AppSettings` (Task 2), `LocalizationManager` (Task 3), and the picker (Task 4). `L10n.Key`/`L10n.table`/`tr(_:)` defined in Task 3 are the exact symbols extended/called in Tasks 4–10. `LocalizationManager.shared.language` and `tr(.key)` signatures are stable across all consumers. The seed keys (`.watchlist/.portfolio/.news/.language`) defined in Task 3 are reused (not redefined) by Tasks 4–5.
