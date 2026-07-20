# M7.3 — Investment Plans (Pies): Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Investment Plans (Pies) to the Android app at parity, closing Milestone 7 across all four platforms.

**Architecture:** The shared Kotlin core (M7.2) already carries ALL domain math, use cases, stores, planner event, and L10n keys — Android consumes them directly. This plan transcribes the desktop VMs/UI (M7.2 Tasks 11–12) into Android Compose idioms, mirroring how the Android app's existing screens transcribe their desktop twins (Portfolio/Calendar/News precedents).

**Tech Stack:** Jetpack Compose (androidApp), shared KMP core, JUnit/coroutines-test.

## Global Constraints

- `export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"`; always `./gradlew`. Counts from JUnit XML with `--rerun-tasks`; NEVER estimate (a fabricated-count incident is on record).
- Baselines: android 146 / shared 479 / desktop 265 — desktop+shared must stay untouched (any shared change requires the full M7.2 Task-13-style xcframework gate; avoid).
- Android AppGraph already HAS the ONE portfolioMutex wired to all 9 co-holders (M7.2 fix 94cfe78 wired ResetPortfolio) — construct the pie use cases with THE same instance, mirroring desktop AppGraph.kt:119-141.
- Toggle gates EXECUTION (adjudicated): AndroidMarketActivityCoordinator's no-op `ContributionCheckDue -> {}` branch (staged in M7.2 Task 9) gets the real handler + launch catch-up, both gated on settings.pieContributions; plan() call passes `pieContributionsEnabled`.
- Recorded Android-UI divergences from desktop are allowed where the app already diverges (bottom sheets instead of dialogs, top-bar navigation instead of tabs) — mirror how the Android Portfolio screen diverges from desktop's PortfolioPane; record each in the README at closeout.
- All strings via shared L10n (keys exist from M7.2 Task 10; PascalCase; trf %s).
- NEVER `git add -A`; explicit paths; trailer `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`; no pushes.
- Branch: `feature/investment-plans-android` off main.

---

### Task 1: Android ViewModels
**Files:** Create `androidApp/src/main/java|kotlin/.../plans/PlansViewModel.kt` + `PieWizardViewModel.kt` (match the app's actual package layout — find it) + test files.
**Transcribe from:** desktopApp/src/main/kotlin/com/aptrade/desktop/plans/{PlansViewModel,PieWizardViewModel}.kt — near-verbatim (both are pure-Kotlin StateFlow VMs over shared use cases; the desktop ones have NO desktop-specific imports — verify, then adapt only constructor/scope conventions to the Android VM precedents in androidApp PortfolioViewModel.kt/CalendarViewModel.kt). Transcribe ALL 42 desktop VM tests (12+30), adapting to the Android test setup (check androidApp's coroutine-test idioms).
- [ ] RED → implement → `./gradlew :androidApp:testDebugUnitTest --rerun-tasks` (146 + 42ish; XML count) → commit `feat(android): Plans and pie-wizard view models`.

### Task 2: Android UI — Plans screen + wizard
**Files:** Create `androidApp/.../plans/PlansScreen.kt` + `PieWizardSheet.kt`; Modify the Portfolio screen's section switcher (find the Android PortfolioScreen's section enum — mirror how desktop added PortfolioSection.Plans) + navigation wiring.
**Anatomy:** transcribe desktop PlansPane/PieWizardDialog structure into the app's idioms: list cards (existing donut/chart components — find Android's allocation donut/bars and reuse; bars acceptable where no donut exists, mirroring the Android Portfolio's recorded allocation-bars divergence), drift badges, detail view with contribute/rebalance/edit/delete, rebalance preview honoring ConfirmTrades via the app's existing TradeConfirm layer, wizard as a modal bottom sheet (app convention) with the 4 steps incl. start-day text field and backtest pane (reuse the app's chart composables), empty state.
- [ ] Implement (declarative-only; VMs own all logic) → `:androidApp:assembleDebug` compiles + unit suite green → commit `feat(android): Plans screen, wizard sheet, portfolio section`.

### Task 3: Coordinator + notifications + settings
**Files:** Modify AndroidMarketActivityCoordinator.kt (replace the `ContributionCheckDue -> {}` no-op: ExecuteDueContributions + per-outcome notify, launch catch-up at start, BOTH gated on settings.pieContributions; mirror the desktop coordinator's M7.2 Task 12 shape and the Android EarningsCheckDue handler's existing conventions — notification formatting placement per the Android precedent), AndroidAlertNotifier (+notifyPieContribution twin + channel if the app uses per-category channels — mirror earnings_reports channel), AppGraph (pie use cases + VMs on THE mutex; plan() call passes pieContributionsEnabled), Settings screen (pieContributions toggle row beside earnings).
- [ ] RED (coordinator tests mirroring desktop's 4) → implement → suite green → commit `feat(android): Plans coordinator wiring, notifications, settings toggle`.

### Task 4: Closeout
- [ ] All suites: `:androidApp:testDebugUnitTest` + `:shared:jvmTest` + `:desktopApp:test` `--rerun-tasks` (XML counts; shared/desktop MUST be at 479/265 untouched) + `:androidApp:assembleDebug`. If ANY shared file was touched (avoid!): full xcframework + Swift gate.
- [ ] README: Android Plans coverage sentence + remove the M7.2 "Android Plans arrives next increment" honesty note + Investment Plans block now four-platform; record Android divergences (bottom-sheet wizard, bars-vs-donut if applicable). Commit `docs: M7.3 closeout — Android Plans at parity; Milestone 7 complete`.

## Self-Review Notes
- Spec coverage: Android §D parity via T1–3; execution semantics inherited from shared core (nothing to re-implement); toggle correction 6 in T3.
- All M7.1/M7.2 corrections live in the shared core Android consumes — no algorithm transcription remains.
