# Plan: Increment 6d.2 — Light theme, settings pages, deferred-minors batch

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> to implement this plan task-by-task.

Branch: kmp-6d2-theme-settings off main @ f0f8f6f. Baselines: shared 222 / android 34 /
desktop 224 / Swift 200 / 3 slices. Parity source: the macOS Swift app — the explorer
report (ledger, 2026-07-05) pinned every fact below; implementers READ THE SWIFT FILES
named per task and transcribe exactly, as all prior shared work did.

**Goal:** Desktop reaches macOS settings parity — light theme (9 exact color pairs +
isDark mechanism), the four remaining panel pages, confirmTrades wired to the trade
dialog — plus the deferred-minors batch (store mutexes, Android detail fixes, one
boundary test). This closes the roadmap except the language increment.

## Global Constraints
- Money amountText/BigDecimal; MONEY_MATH DecimalMode(38, HALF_AWAY_FROM_ZERO) on any
  division; CancellationException-first in every catch; Main-confined VMs (plain vars);
  Esc ownership chain (palette → dialog/sheet layers → back); comment policy
  (contracts/divergences kept).
- commonMain framework-free apart from sanctioned Ktor; no java.time/AWT in :shared.
- Suites --rerun-tasks XML-counted (measured counts govern); DEVELOPER_DIR for Apple;
  the final task re-proves xcframework/Swift/iOS (:shared CHANGES this increment —
  Task 4's Buy/Sell mutex).
- JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; ./gradlew.
- HARD RULE: implementers work directly; no Agent-tool delegation; run commands in the
  plain foreground and read the output yourself.
- STANDING WAIVER (6a user ruling): UI composition ships without composable tests; pure
  logic still gets unit tests.

## Scope decisions (recorded)
- Accent ramp + up/down colors are mode-INDEPENDENT (Theme.swift:91-97 comment: "the
  accent is brand color, not a mode signal") — never branch them on isDark.
- Desktop confirm-trades UX = an in-dialog confirm layer inside TradeDialog (Compose
  desktop has no native confirmationDialog) — RECORDED MECHANISM DIVERGENCE; strings
  ≡ macOS TradeSheet L10n values.
- Security page: ONLY confirmTrades is functional; biometricLogin/requireAuthOnLaunch/
  analyticsSharing are persisted-only — macOS is identical (HONEST PARITY). The three
  Data link rows are decorative (macOS: close-only).
- Profile/AccountSettings/Help pages are hardcoded display-only, transcribed verbatim
  incl. "Enabled (Touch ID)" (macOS hardcodes the same strings; display-only parity).
- Language row stays PlaceholderPage — own increment, recorded.
- Theme switch = instant colorScheme swap (macOS animates 0.25s easeInOut; Compose
  animation deferred as polish — note for the human gate).
- isDarkMode persists in AppSettings, default true ≡ macOS.

### Task 1: DesignKit light theme core

Read Sources/APTradeApp/Theme.swift (the 9 computed pairs + ThemeManager) and
Sources/APTradeApp/DesignKit.swift recoloredBrandImage(_:accent:isDark:) (lines ~95-151)
first; then desktop designkit/DK.kt, BrandTint.kt, Components.kt (BrandWordmark).
1. DK gains `val isDark = mutableStateOf(true)` (the existing `accent` MutableState is
   the precedent — snapshot reads recompose consumers). The 9 color vals become getters
   branching on isDark.value, EXACT light values (dark values stay byte-identical):
   bgTop #F8F6F2, bgBottom #F1EEE7, surface #EAE6DE, surfaceHi #DFD9CD,
   hairline = Color.Black.copy(alpha = 0.09f) (dark stays White 0.07f),
   textPrimary #1E1C18, textSecondary #605A4F, textTertiary #8E8779, silver #565148
   (CORRECTED from an earlier #564F47 explorer mis-conversion — Theme.swift:110's
   components 0.337/0.318/0.282 × 255 = 86/81/72; the Swift components govern).
   up/down and the accent ramp UNCHANGED (mode-independent — scope decision).
2. dkColorScheme(): light branch via lightColorScheme(...) mirroring the existing dark
   mapping field-for-field; APTradeDesktopTheme reads DK.isDark so the whole tree
   recomposes on toggle.
3. BrandTint light branch: neutral-classified pixels (r − b < 40/255) remap to charcoal
   RGB(30, 28, 24)/255 = #1E1C18 when !isDark (macOS keeps shipped silver only when
   isDark — DesignKit.swift ~line 130); gold-pixel handling unchanged. Tint cache key
   gains isDark ("accent-isDark" ≡ macOS BrandImage key); BrandWordmark re-keys its
   produceState on (accent, isDark). Update BrandTint.kt's "desktop is dark-only" doc
   comment — it is no longer true.
4. Tests (pure logic): the neutral→charcoal remap in light mode vs pass-through in dark
   mode on the same fixture pixels (extend the existing BrandTint tests' style); a color
   table test pinning all 9 light hex values and re-pinning the 9 dark values.

### Task 2: isDarkMode persistence + Appearance Theme rows

Read Sources/APTradeApp/RootView.swift appearancePage (lines 440-462) +
Sources/APTradeApp/L10n.swift (keys .theme/.accent/.dark/.darkSubtitle/.light/
.lightSubtitle, lines ~89-94); then desktop ui/AccountPanel.kt (AppearancePage),
Main.kt (settings startup + persistSettings seam), infra/FileSettingsStore.kt.
1. AppSettings + SettingsDTO grow isDarkMode: Boolean = true (lenient back-compat
   preserved — an existing settings file without the key loads isDarkMode=true,
   test-pinned like 6d.1's {"accent"}-only test).
2. Main.kt startup LaunchedEffect additionally applies DK.isDark.value =
   loaded.isDarkMode; a theme change persists via the existing persistSettings seam
   (it.copy(isDarkMode = ...)) — same fire-and-forget path as accent.
3. AppearancePage gains a THEME section ABOVE the existing ACCENT section:
   SectionLabel "Theme"; two selectable rows with the AccentRow selection anatomy —
   "Dark" / subtitle "Default — gold on black" (moon glyph) and "Light" / subtitle
   "Charcoal on warm white" (sun glyph); tapping the non-current row toggles
   (macOS ThemeManager.toggle() semantics: no-op when already selected).
4. Tests: settings round-trip + back-compat incl. isDarkMode (store layer; the page
   itself is waiver territory).

### Task 3: Security/Profile/AccountSettings/Help pages + confirmTrades wiring

Read Sources/APTradeApp/RootView.swift (profilePage 373-390, accountSettingsPage
392-411, securityPage 535-563, helpPage 565-583) + Sources/APTradeApp/TradeSheet.swift
(lines 12-66: confirmTrades snapshot, attemptSubmit, confirmationDialog, the
.confirmBuyTitleFormat/.confirmSellTitleFormat/.confirmEstimateFormat/.confirmBuy/
.confirmSell L10n formats — read L10n.swift for the exact format strings); then
desktop AccountPanel.kt, portfolio/TradeDialog.kt, Main.kt TradeDialog call site
(lines ~462-474).
1. AppSettings + SettingsDTO grow biometricLogin=true, requireAuthOnLaunch=true,
   confirmTrades=true, analyticsSharing=false (lenient decode, test-pinned back-compat).
2. SecurityPage (replaces the placeholder route): AUTHENTICATION section — ToggleRow
   "Biometric Login"/"Unlock with Touch ID / Face ID", "Require Auth on Launch"/
   "Ask every time the app opens", "Confirm Trades"/"Re-authenticate before buy / sell"
   — all persist via the settings path; PRIVACY section — "Share Usage Analytics"/
   "Anonymous diagnostics to improve APTrade"; DATA section — decorative link rows
   "Change Password", "Manage Devices" (value "2 active"), "Clear Local Cache"
   (close-only ≡ macOS).
3. ProfilePage: detail rows Name "Ankit Patel", Date of Birth "January 1, 1995",
   Email "ankitpatel.svnit@gmail.com". AccountSettingsPage: Trading Mode "Simulated
   paper trading", Starting Balance "$100,000.00", Display Currency "USD ($)",
   Default Tab "Watchlist", Biometric Login "Enabled (Touch ID)" (static display
   row — macOS displays static text here too, NOT bound to the toggle). HelpPage:
   RESOURCES — "FAQ", "User Guide", "Keyboard Shortcuts"; CONTACT — "Email Support"
   (value "support@aptrade.app"), "Report a Problem" — all decorative. Placeholder
   routing shrinks to Language only.
4. confirmTrades → TradeDialog: the dialog receives a confirmTrades: Boolean snapshot
   taken when it opens (macOS snapshots at sheet init — TradeSheet.swift:18); submit
   flow becomes attemptSubmit ≡ TradeSheet.swift:60-66 — when the flag is on, an
   in-dialog confirm layer appears (title/message/buttons from the L10n format
   strings, e.g. "Confirm Buy"/"Confirm Sell" + estimated cost/proceeds line built
   from the dialog's existing estimate math); Confirm fires the original onSubmit,
   Cancel returns to the form; Esc dismisses the confirm layer FIRST, then the dialog
   (extend the existing Esc-ownership comment chain).
5. Tests: back-compat for the 4 new fields (old file → defaults); the attemptSubmit
   gating truth table as pure logic (flag on → confirm step, flag off → direct
   submit), factored so it is testable without composables.

### Task 4: Shared trade mutex + desktop settings mutex + signedMoney boundary

Read shared/src/commonMain/kotlin/com/aptrade/shared/application/NewsUseCases.kt
ToggleBookmark (lines 45-62 — THE pattern) + BuyAsset.kt + SellAsset.kt; desktop
AppGraph.kt persistSettings (lines ~157-177); designkit/MoneyText.kt +
MoneyTextTest.kt.
1. BuyAsset and SellAsset each gain a private Mutex; the load→mutate→save sequence
   runs inside mutex.withLock ≡ ToggleBookmark. The quote fetch stays OUTSIDE the
   lock (no network under a mutex — KDoc why; validation depending on the quote also
   stays outside; only the store RMW is serialized). KDoc the shared-single-instance
   requirement exactly as ToggleBookmark's doc does (AppGraph/PortfolioGraph already
   hand one instance to all consumers on both desktop and Android).
2. Desktop AppGraph persistSettings: a file-level private Mutex serializes the
   load→mutate→save (closes the RMW lost-update window recorded by 6d.1's final
   review); KDoc updated.
3. MoneyTextTest: pin the rounding-crosses-zero boundary — signedMoney("-0.001")
   (raw signum is negative so no "+", formatMoney rounds to scale 2 giving "$0.00";
   assert the actual current output and comment the two-signum interaction).
4. Tests: concurrent-interleave tests for the trade mutex (two racing buys on one
   store via a gated fake — both land, no lost update; same for sell or a mixed
   pair) ≥2 in shared commonTest; a persistSettings race pin (two concurrent
   disjoint-field mutations both survive) in desktop tests.

### Task 5: Android detail fixes — kind-gate + quote pass-through

Read androidApp detail/DetailViewModel.kt (state, loadProfile ~line 83, tradeAsset()
106-117) + detail/DetailScreen.kt (trade-sheet call site 122-140) +
portfolio/TradeSheet.kt (TradeSheetInfo.priceText, consumed ~line 85); desktop
Main.kt TradeTarget for the priceText precedent.
1. DetailViewModel: fetch the quote alongside profile/chart (its own isolated
   coroutine, macOS-parity silent failure → priceText stays null; Cancellation-first);
   state carries priceText (Money.amountText-derived display string ≡ the portfolio
   entry path's format) and the resolved AssetKind.
2. Kind-gate (the 6b.4 deferred fix): trade entry (BUY/SELL buttons) stays disabled
   until the profile request RESOLVES (success or error). On success tradeAsset()
   uses the profile's real kind (crypto never misclassified as Stock); on profile
   ERROR the existing Stock fallback remains, now documented as the error-only path
   (update the tradeAsset() doc comment — its "only when genuinely absent" claim
   becomes true).
3. DetailScreen: TradeSheetInfo.priceText = the VM's priceText (the AssistChip slot
   at TradeSheet.kt:85 finally renders on the detail entry path, ≡ portfolio path).
4. Tests (≥4): crypto kind resolved before trade (no Stock misclassification);
   buttons gated until resolve; priceText flows to the sheet info; quote failure
   leaves trading alive with null priceText. Android suite 34 → 38+.

### Task 6: Full regression + docs

xcframework (3 slices) + swift test 200 + iOS ARCHS=arm64 + all gradle suites
XML-counted (measured counts govern; :shared changed in Task 4 — the full Apple
re-proof is mandatory). README: light-theme + settings-pages paragraph (divergences:
in-dialog confirm layer, decorative link rows, instant theme swap); roadmap
close-out — 6d.2 ships (prune it), "Still to come" = the desktop language switcher
only. SKILL.md check (only touch if now wrong). Docs-only commits; any regression
failure = BLOCKED.

- Coverage: light theme core → T1; theme persistence/UI → T2; pages + confirm → T3;
  mutexes + boundary test → T4; Android detail pair → T5; proof/docs → T6.
- Known unknowns w/ guardrails: exact confirm-dialog L10n format strings (T3 reads
  L10n.swift directly); lightColorScheme Material3 slot mapping (T1 mirrors the dark
  mapping field-for-field); Android quote fetch API shape (T5 reads FetchMarketQuotes
  usage in QuotesViewModel).
