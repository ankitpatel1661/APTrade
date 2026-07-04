# Plan: Cleanup sweep — dead code, unused symbols, redundant comments

Authority: user request 2026-07-04 ("optimize the whole project, remove extra code/files
and redundant comments, without losing project integrity or the build"), calibrated per
user choice: KEEP contract/divergence comments. Branch: kmp-cleanup-sweep off main @
1ec5b0a. Baselines: shared 162 / android 13 / desktop 164 / Swift 193 / 3 slices.

## The comment policy (binding — the whole point of "calibrated")

REMOVE a comment only when it merely restates WHAT the adjacent code does and adds no
why/contract/history. Examples: "// increment the counter", "// build the request",
a KDoc that just repeats the signature in prose.

KEEP (never remove, even if wordy):
- Contract KDoc: RAW-vs-formatted money contract, Main-confinement requirements,
  MONEY_MATH rationale, Esc-ownership chains, @preconcurrency rationale.
- Every comment containing (case-insensitive): DIVERGENCE, parity, "matches macOS",
  "matches Swift", MONEY_MATH, "must not drift", calibration values, DEVIATION.
- Why-comments explaining non-obvious choices (clamps, fallbacks, forward-fill,
  premultiply, channel order, ATOMIC_MOVE, cache no-close rationale).
- File-header KDoc that names the Swift source a file transcribes.
- License/attribution (none known) and TODO(6b.3)-style forward pointers.
When in doubt → KEEP. A wrongly-removed contract is worse than a kept nit.

## Dead-code policy

Remove: unused imports, unused private functions/properties, unused parameters
(update call sites), unreachable branches, files with zero references, commented-out
code blocks. Do NOT remove: macOS-parity-reserved paths that are documented + tested
(FetchPortfolioPerformance's sinceInception trim, RiskMetrics.rebase — both stay),
public API surface consumed by the Swift bridge (verify via grep in Sources/ before
deleting anything public in :shared), test seams (internal ctors, injectable clocks).

Known catalogued targets (from prior review deferrals — fix these explicitly):
unused assertEquals import (TechnicalIndicatorsTest.kt); unused currencyCode param in
drawHoldingRow (PdfPortfolioRenderer.kt — update call site); "CIO client" comment
imprecision (FinnhubNewsRepository.kt — reword to name the expect/actual, one line);
unused TextOverflow import (designkit Components.kt, if still present).

### Task 1: Kotlin sweep (shared + desktopApp + androidApp)

Method: enable/inspect compiler warnings (./gradlew compile tasks print unused-import
warnings? Kotlin doesn't by default — use a grep+read pass per file instead; IDE-style
inspection is unavailable, so: for each .kt file, check imports against usages, check
private symbols for references, check params). Prioritize main source; tests too
(unused fixtures/helpers). Apply the comment policy per file. The four catalogued
targets are mandatory. Per-module suites after each module; full gradle suites at end
(--rerun-tasks, XML-counted: 162/13/164 expected — counts must NOT drop; deleting a
test is FORBIDDEN unless it references deleted dead code, and then flag it).

### Task 2: Swift sweep (Sources/ + Tests/) + repo files

Same policies for Swift (doc comments with parity/contract content stay). Repo-level:
find committed files with zero references (old assets? stale scripts?) — list candidates
in the report BEFORE deleting; anything ambiguous → keep + flag. Do NOT touch:
docs/superpowers/** (project history), .claude/**, README, CLAUDE.md, logo/, brand
resources, .github/, .gitlab-ci.yml, scripts/. swift test 193 after (DEVELOPER_DIR).

### Task 3: Full regression + close-out

xcframework reassembly (3 slices) + swift test 193 + iOS APTradeLite-Package ARCHS=arm64
+ all gradle suites --rerun-tasks XML-counted. No docs changes expected (README claims
must all still hold — spot-check the sections that cite code details). Suite failure or
count drop = BLOCKED.

- Verification bar: identical test counts everywhere; zero behavior change intended.
- Final whole-branch review checks a SAMPLE of removed comments against the policy
  (any removed contract/divergence comment = Critical) and greps for accidental
  public-API deletions.
