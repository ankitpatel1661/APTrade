# KMP Walking Skeleton — Design Spec

**Date:** 2026-07-01
**Status:** Approved (design), pending implementation plan
**Author:** APTrade team

## Context

APTrade is a native SwiftUI investment app for macOS + iOS, built on Clean
Architecture (Domain → Application → Infrastructure → Presentation) as a Swift
Package (`Package.swift`, `Sources/APTrade*`). The team wants to reach **four
platforms** — macOS, iOS, Windows, Android — without duplicating business logic
or sacrificing the native Apple experience.

**Chosen strategy: Kotlin Multiplatform (KMP).** Share the Domain +
Application + Infrastructure layers as one Kotlin core (compiled natively per
platform, exported to Apple as an `.xcframework`); keep native UI per platform
(SwiftUI on Apple, Jetpack Compose on Android, Compose-Desktop/WinUI on Windows).
This maps directly onto the existing Clean Architecture, whose
`Application/Ports.swift` protocol boundary is exactly the seam KMP splits on.

This spec covers **only the first increment**: a *walking skeleton*. The full
port is a multi-increment program (rough estimate 5–7 months to four-platform
parity with 1–2 engineers) and is explicitly out of scope here.

## Goal of this increment

Prove the end-to-end pipeline — **shared Kotlin → `Shared.xcframework` →
SwiftUI** — works on this machine, using the thinnest slice that touches every
architectural layer, before investing in networking, Android, or Windows.

## Scope decisions (locked)

1. **First increment = walking skeleton, Apple-only.** No Android SDK required.
2. **Slice depth = full layers, stubbed data.** Domain → Application →
   Infrastructure → xcframework → SwiftUI, with a stub repository (no real
   networking).
3. **Integration surface = minimal sample harness.** A standalone SwiftUI app
   consumes the xcframework. The real APTrade app and `Package.swift` remain
   **untouched and green**; wiring into the real app's `CompositionRoot` is a
   later increment.

## Toolchain reality (verified 2026-07-01)

| Tool | Status | Action |
|------|--------|--------|
| JDK | Corretto **11** present | **Upgrade to JDK 17** (Homebrew) — prerequisite |
| Gradle | not installed | none — Gradle *wrapper* self-downloads |
| Kotlin compiler | not installed | none — Gradle brings it |
| Android SDK | **absent** (`ANDROID_HOME` empty) | none — no `androidTarget` this increment |
| Xcode / Swift 6.3 | healthy | baseline for xcframework + harness |
| Homebrew 6.0.5 | present | used to install JDK 17 |

## Architecture

### Build coexistence

Gradle and SwiftPM live side by side at the repo root. Nothing in `Package.swift`
or `Sources/APTrade*` changes in this increment.

```
Trading app/
├── Package.swift            ← unchanged, still builds macOS/iOS
├── Sources/APTrade*         ← unchanged
├── settings.gradle.kts      ← NEW (Gradle root)
├── build.gradle.kts         ← NEW
├── gradle/ + gradlew        ← NEW (wrapper self-downloads Gradle)
├── shared/                  ← NEW KMP module (the Kotlin core)
└── skeletonHarness/         ← NEW tiny SwiftUI app consuming the xcframework
```

### `shared` module targets (Apple-only for now)

- `iosArm64`, `iosSimulatorArm64`, `macosArm64` → assembled into
  `Shared.xcframework`.
- `jvm` → fast JVM unit-test target for CI-style verification without a
  simulator.
- **No `androidTarget`** — deliberately omitted so zero Android SDK is needed.
  Added in a later increment.

Namespace: `com.aptrade.shared`, with `.domain`, `.application`,
`.infrastructure` sub-packages mirroring the Swift layer names.

## Components (the vertical slice)

```
shared/src/commonMain/kotlin/com/aptrade/shared/
├── domain/
│   ├── Money.kt          ← real port of Money.swift (value object)
│   └── Quote.kt          ← minimal entity: symbol, price: Money, changePct
├── application/
│   ├── QuoteRepository.kt   ← interface (a "Port", mirrors Ports.swift)
│   └── FetchMarketQuotes.kt ← use case: suspend fun(symbols) -> List<Quote>
└── infrastructure/
    └── StubQuoteRepository.kt ← returns hardcoded quotes (e.g. AAPL, BTC)
```

- **Money.kt** — real port preserving the rounding/currency semantics of
  `Money.swift`; validates the Swift→Kotlin translation pattern on non-trivial
  logic.
- **FetchMarketQuotes** — a `suspend` function, deliberately exercising the
  Kotlin coroutine → Swift `async/await` bridge (the most likely source of
  surprises) now, on a stub, rather than later on real networking.
- **QuoteRepository** — the Port. `StubQuoteRepository` implements it; a future
  Ktor implementation replaces the stub behind the same interface with **no
  consumer change**.

### Sample harness (`skeletonHarness/`)

A minimal standalone SwiftUI app (its own small `.xcodeproj`) that:

1. links `Shared.xcframework`,
2. `import Shared`, constructs `FetchMarketQuotes(StubQuoteRepository())`,
3. `await`s the use case and renders the stub quotes in a `List`.

Fully separate from the real app; `Package.swift` never sees it.

## Data flow

`SwiftUI harness` → calls `FetchMarketQuotes` (from xcframework) →
`FetchMarketQuotes` calls `QuoteRepository` → `StubQuoteRepository` returns
hardcoded `List<Quote>` → bridged to Swift `async` → rendered in a `List`.

## Error handling

- `FetchMarketQuotes` signature returns a plain `List<Quote>` for the stub; the
  Port is defined to permit throwing (`suspend fun quotes(...)` may throw) so the
  future networking impl can surface failures. The harness wraps the `await` in
  `do/catch` to prove the error path bridges to Swift.
- No custom error taxonomy in this increment (that arrives with the real
  `AppError` port later).

## Testing

- `commonTest`: port `MoneyTests` → Kotlin as the **parity check** (same cases
  as the Swift suite). Seeds the pattern for migrating the other ~47 test files.
- `commonTest`: `FetchMarketQuotesTest` asserts the use case returns the stub
  list.
- Runnable via `./gradlew :shared:jvmTest` — verifiable on this machine once
  JDK 17 is installed.

## Verification / Definition of Done (evidence-based)

1. `./gradlew :shared:jvmTest` — Kotlin `Money` + use-case tests pass on JVM.
2. `./gradlew :shared:assembleSharedXCFramework` — produces `Shared.xcframework`.
3. `xcodebuild` the harness → launches → shows stub quotes. **Visual confirmation
   is the user's** (standing constraint: the dev binary cannot be UI-verified by
   the agent; the agent confirms build success, the user confirms rendering).
4. Existing `Package.swift` macOS/iOS build still green — run to prove no
   regression.

## Out of scope (YAGNI for this increment)

Ktor / real networking; `androidTarget`; Windows; wiring into the real app's
`CompositionRoot`; SKIE tuning; secure-storage `expect`/`actual`; any domain
type beyond `Money` and `Quote`.

## Follow-on increments (not designed here)

2. Swap `StubQuoteRepository` for a Ktor + kotlinx.serialization
   `YahooMarketDataRepository` behind the same Port.
3. Wire the real Apple app's `CompositionRoot` to the shared use case via a
   SwiftPM `binaryTarget`.
4. Port remaining Domain + Application + Infrastructure.
5. Add `androidTarget` + Jetpack Compose `androidApp`.
6. Windows front end (Compose-Desktop or WinUI).
