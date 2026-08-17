# Lessons Carried Forward from Repo Lens

## 1. Purpose

Flow Lens should benefit from the engineering history of `kani-cream/repo-lens` instead of relearning the same platform lessons.

This document records concrete observations from the Repo Lens repository, issues, commit history, test strategy, and release preparation, then converts them into Flow Lens decisions.

This is not a code-copying guide. Repo Lens and Flow Lens solve different product problems. The reusable value is in architecture, failure modes, validation discipline, and JetBrains Platform behavior.

---

## 2. Patterns worth preserving

### 2.1 Keep platform-independent logic separate from IntelliJ code

Repo Lens uses a `:core` module and a `:plugin` module. Its core contains the domain model, orchestration, formatting, filtering, and analyzers that do not need IntelliJ types, while the plugin module owns PSI/VFS/UI/navigation/platform integration.

Flow Lens should preserve the same separation where it remains natural.

Recommended shape:

```text
flow-lens
├─ core
│  ├─ model
│  ├─ graph/frame semantics
│  ├─ traversal policy that does not require PSI objects
│  ├─ diagnostics/result state
│  └─ pure transformations/export
│
└─ plugin
   ├─ IntelliJ lifecycle/service
   ├─ Java analyzer adapter
   ├─ Kotlin analyzer adapter
   ├─ Go analyzer adapter
   ├─ PSI/source navigation
   ├─ Tool Window / Flow Canvas
   └─ persistence/settings
```

Do not force a class into `core` if its contract fundamentally requires PSI. The objective is a meaningful boundary, not a cosmetic module split.

Benefits inherited from Repo Lens:

- most semantic tests can run without booting an IDE;
- IntelliJ API churn is concentrated in the plugin layer;
- language-plugin types cannot accidentally leak into the renderer/model;
- bundled Kotlin/coroutine dependencies are less likely to conflict with platform-provided versions.

### 2.2 Use a provider/analyzer seam for language-specific behavior

Repo Lens proved that a language provider can be added without changing the shared model, analyzers, or UI. Its Go provider is registered only when the Go plugin is present through an optional descriptor.

Flow Lens already plans an `AnalyzerRegistry`; keep this as a hard architectural seam.

Adding or changing ordinary Go call extraction should not require changing Flow Canvas semantics. A new language integration should normally require:

- adapter/provider implementation;
- optional dependency descriptor when needed;
- fixtures/tests;
- capability metadata;
- no unrelated core/UI rewrite.

### 2.3 Treat unavailable capabilities as normal states

Repo Lens explicitly models a missing provider as `Unavailable`, not as a plugin failure.

Flow Lens should do the same for optional integrations.

Examples:

- Go plugin not installed: Java/Kotlin Flow Lens still loads and works.
- Required indexes not ready: show a waiting state, not hundreds of unresolved calls.
- A particular semantic is unsupported: expose the limitation/diagnostic rather than guess.

### 2.4 Keep orchestration outside analyzers and UI

Repo Lens separates its project service, orchestrator, analyzers, and UI. The service owns the current coroutine/job, takes a settings snapshot, performs cancellable work, and publishes lifecycle events. Individual analyzer failures are isolated while cancellation propagates.

Flow Lens should preserve these responsibilities:

```text
UI
  ↓ command
Project FlowAnalysisService
  ↓
FlowAnalysisEngine / scheduler
  ↓
Language analyzers
  ↓ events/results
Project service
  ↓ throttled UI model events
Flow Canvas
```

Language analyzers must never manipulate Swing components or ToolWindow state directly.

### 2.5 Snapshot settings at the start of an analysis

Repo Lens snapshots project settings before a run so changing a threshold midway does not produce internally inconsistent output.

Flow Lens must snapshot at least:

- max depth;
- max semantic nodes;
- include-tests policy;
- external/library policy;
- analyzer/capability options relevant to the run.

View-only settings such as current zoom or card expansion remain outside the analysis snapshot.

### 2.6 Failure isolation must distinguish local failure from root failure

Repo Lens learned through dogfooding that some failures belong to one analyzer/finding while others invalidate the entire selected scope.

Flow Lens needs the same distinction.

Node/local failure examples:

- one call cannot resolve;
- one language adapter cannot classify a target;
- one optional semantic is unavailable.

These become local diagnostics/unresolved/unknown states and analysis continues.

Run-level failure examples:

- root declaration is no longer valid before analysis starts;
- required host/platform state is unavailable in a non-recoverable way;
- the project is disposed;
- analysis invariants are corrupted.

Do not turn a root-level invalid state into dozens of repeated node errors.

### 2.7 Use monotonic timings and bounded diagnostics

Repo Lens records per-stage/analyzer elapsed time with a monotonic clock and logs counts/timings without source text.

Flow Lens should log only diagnostic metadata by default:

- language/analyzer ID;
- stage;
- semantic node counts;
- resolution-status counts;
- elapsed time;
- cancellation/stale/failure category.

Do not log source bodies. Avoid file paths, method arguments, or symbol names unless a deliberate debug mode is introduced later.

---

## 3. Failures and surprises that Flow Lens must not repeat

### 3.1 Compiler-generated Kotlin members can look like source declarations

Repo Lens observed UAST exposing synthetic Kotlin data-class members such as `copy()` and `componentN()` as light methods. Those generated methods created substantial false-positive noise in a realistic project. Earlier development also encountered implicit Kotlin constructors appearing as misleading method bodies.

Flow Lens is even more sensitive because it follows callable bodies recursively.

Rule:

> A resolvable PSI/UAST callable is not automatically an authored callable.

Flow Lens must retain source provenance for declarations/targets, conceptually:

```text
SourceOrigin
- PHYSICAL_SOURCE
- SYNTHETIC
- GENERATED
- LIBRARY
- UNKNOWN
```

v0.1 recursive analysis should normally enter only project-local physical/authored source. Synthetic/generated callables are not followed unless that semantic is deliberately supported and covered by fixtures.

Prefer a general physical-source/provenance check over denylisting names like `copy` or `component1`.

Required regressions include:

- Kotlin data class synthetic methods;
- Kotlin implicit constructors/light elements;
- explicitly authored overrides remain visible;
- Java record/compiler-generated members if the selected APIs surface them similarly.

### 3.2 Plugin Verifier must inspect compiled output early

Repo Lens 1.0.0 was Marketplace-compatible but still displayed 4 deprecated and 6 experimental API usages. Most were not handwritten source calls: Kotlin generated Java-default-interface bridge methods on `ToolWindowFactory`, causing verifier warnings for methods Repo Lens never explicitly overrode.

Lesson:

> Source review alone cannot prove API cleanliness. Plugin Verifier evaluates compiled bytecode and dependency signatures.

Flow Lens must run Plugin Verifier immediately after the minimal ToolWindow/plugin skeleton is created and after meaningful compiler/build-setting changes.

For the selected Kotlin toolchain, evaluate the modern JVM-default mode (for example the stable no-compatibility mode appropriate to that Kotlin version) so unnecessary compatibility bridges are not emitted. This must be validated against the chosen IntelliJ baseline rather than copied blindly.

Verifier warnings must also be attributed:

- Flow Lens bytecode/API usage: ours to fix or explicitly approve;
- language-plugin/transitive signature warning: record provenance and determine whether it is avoidable.

A Marketplace `Compatible` verdict is not an all-clear result.

### 3.3 Never hold a large read action across many reference resolutions

Repo Lens had to change an analyzer that performed many `ReferencesSearch` calls inside one file-wide read action. The design could delay write actions on large files.

Flow Lens must not place the entire recursive graph traversal inside one giant read action.

Preferred pattern:

1. acquire a bounded/cancellable read action;
2. extract or resolve one callable/frame or a small batch;
3. convert necessary PSI information into stable project data/pointers;
4. release the read action;
5. check cancellation;
6. schedule the next bounded operation.

Use smart pointers/stable source handles when work crosses suspension points. Never keep invalid PSI assumptions alive just because a coroutine resumed later.

### 3.4 Do not block the EDT waiting for asynchronous platform work

A Repo Lens language-plugin test exposed a deadlock pattern: EDT waiting synchronously on a future while plugin/VFS initialization needed the EDT.

Flow Lens must never use `Future.get`, blocking joins, or equivalent waits on the EDT for analysis/index/language-plugin work.

Tests with Java/Kotlin/Go plugin environments should intentionally exercise this lifecycle because headless test behavior can expose platform deadlocks earlier than manual use.

### 3.5 Analysis activity cannot be an afterthought

Repo Lens shipped with a functionally strong but visually static `Analyze → wait → final table` experience, leading to a follow-up issue to expose real analysis activity.

Flow Lens has already chosen progressive Flow Canvas rendering. Preserve that decision at the architecture level, not only the visual mockup level.

The analysis pipeline must have a progress/event contract from v0.1.

Progress must be real:

- extracting root frame;
- resolving calls;
- analyzing child frames;
- waiting for indexes;
- layout/render update;
- completed/truncated/cancelled/stale.

Do not invent percentages when a reliable denominator is not known.

Do not emit one Swing/message-bus update per tiny PSI event. Batch or throttle presentation updates so progress visibility does not become an EDT performance problem.

### 3.6 Clear or mark old results immediately when a new run starts

Repo Lens dogfooding found that stale results beneath a new failure/running status looked current and left actions enabled against the old selection.

Flow Lens must never visually present the previous flow as the current analysis after a new root starts.

At analysis start:

- cancel the previous run;
- invalidate/replace the Current Flow ownership immediately;
- disable actions whose target belongs to the old run;
- show the new root/running state;
- move keyboard focus to the canvas/result surface when appropriate.

A retained previous result, if a future history feature adds one, must be clearly labelled historical rather than current.

### 3.7 Do not force every language into Java semantics

Repo Lens’s Go provider intentionally did not invent Java-style type-body metrics because Go methods live outside receiver type bodies.

Flow Lens must preserve one visual/model vocabulary without pretending language semantics are identical.

Examples:

- Go `defer` and goroutine execution mode remain distinct;
- Kotlin compiler-generated/default/delegated behavior is not invented from Java assumptions;
- future Java/Kotlin exceptions and Go panic/recover/select may require language-specific mapping into shared semantic categories.

The shared model is a semantic interchange model, not a lowest-common-denominator AST.

### 3.8 Stable identity must not be based only on line number

Repo Lens documents that finding IDs containing line numbers move when code shifts, which is acceptable for its ignore behavior but unsuitable for durable symbol bookmarks.

Flow Lens must not use source offset/line as the sole durable identity for future Flow Pins/Saved Flows.

Use, where available:

- language/symbol-qualified identity;
- containing declaration + signature;
- project-relative file fallback;
- smart pointer during a live project session;
- source offset only as a navigation/fallback discriminator.

Refactor survival remains best-effort, but line-number identity alone is insufficient.

### 3.9 Plugin dynamic-unload claims require real testing

Repo Lens documents that Plugin Verifier’s optimistic dynamic/unload assessment did not match reality; hot reload had to be disabled because the class loader could not unload safely.

Flow Lens must not mark extension points/components dynamic or promise no-restart updates merely because the descriptor permits it.

If dynamic unload is desired later, test actual install/disable/enable/update behavior and ensure listeners, coroutines, services, tool-window content, and language adapters dispose correctly.

For v0.1, restart-required development/update behavior is acceptable if that is the robust choice.

### 3.10 Exact patch compatibility matters

Repo Lens pinned IntelliJ IDEA 2026.1.5 rather than merely `2026.1` because current Marketplace language-plugin versions required a later 261 patch build.

Flow Lens must select an exact IntelliJ IDEA Ultimate patch during Milestone 0 by testing the real Java/Kotlin/Go dependency combination.

Do not assume all patch releases inside a `sinceBuild` line can load the required Go/Kotlin plugin versions.

### 3.11 Do not use Kotlin metadata bypasses as a substitute for compatibility

Repo Lens could use a test-only Kotlin metadata-version bypass because its source did not call Kotlin plugin APIs; it only needed the plugin loaded for parsing.

Flow Lens will actually use Kotlin semantic/resolution APIs. Therefore a blanket metadata-version bypass is not an acceptable compatibility strategy for the Kotlin analyzer.

Pin a compatible Kotlin/compiler/plugin/API combination and fix the integration rather than suppressing metadata incompatibility.

---

## 4. Test and release lessons

### 4.1 Keep pure unit tests and real PSI fixture tests

Repo Lens combines pure `core` tests with plugin fixture tests over real Java/Kotlin/Go PSI.

Flow Lens must do both.

Pure tests should cover model/traversal rules without IDEA. Fixture tests should prove what JetBrains PSI/resolution APIs actually return.

### 4.2 Samples need positive and negative controls

Repo Lens’s sample/manual-test corpus defines not only expected findings but also cases that must remain silent at exact boundaries.

Flow Lens sample fixtures should similarly include:

- calls that must appear;
- calls that must not appear;
- exact/declared/ambiguous dispatch;
- synthetic members that must not be followed;
- depth/node-limit boundaries;
- recursion/cycles;
- external calls;
- Go `go`/`defer`;
- mixed Java/Kotlin calls;
- branch/control-flow simplified warning;
- optional Go plugin unavailable state.

### 4.3 Every real bug earns a regression fixture

Repo Lens’s strongest hardening came from dogfooding and converting counterexamples into tests.

Flow Lens adopts the same rule:

> A correctness bug caused by a source shape, PSI shape, lifecycle state, or platform version is not considered fixed until a regression fixture or reproducible smoke step exists.

### 4.4 Dogfood on real repositories before milestone acceptance

Fixture success is necessary but not sufficient.

At minimum before v0.1 release, use Flow Lens on:

- a real Java repository;
- a real mixed Java/Kotlin repository;
- a real Go repository;
- Flow Lens itself where meaningful.

Record surprises as either fixes or explicit known limitations.

### 4.5 Build the distribution ZIP in CI and install it

Repo Lens CI builds/tests/verifies and uploads the plugin ZIP artifact. Release quality also includes an actual artifact install test.

Flow Lens should treat the ZIP as the product artifact. A successful IDE test fixture does not prove that plugin packaging, descriptors, optional dependencies, icons/resources, or version metadata are correct.

### 4.6 Budget CI disk space for IntelliJ distributions and verifier targets

Repo Lens’s GitHub Actions runner exhausted disk because IntelliJ distributions/verifier environments and Gradle transforms consume tens of GB. Its workflow removes unused preinstalled toolchains and avoids caching re-derivable transform directories.

Flow Lens CI should account for this from the first verifier workflow instead of discovering it during release hardening.

### 4.7 Pin deterministic headless build behavior

Repo Lens encountered `buildSearchableOptions` failure under a Japanese JVM locale and pinned the headless task locale to English.

Flow Lens should keep headless build/test locale explicit where JetBrains tooling requires it and separately test Japanese UI localization in an IDE fixture/manual run.

---

## 5. UX and localization lessons

### 5.1 Localize UI chrome from the start

Repo Lens later moved hardcoded UI chrome to a `DynamicBundle` and added a test that English/Japanese property bundles contain the same keys.

Flow Lens should start this way:

- no new user-facing UI literal without a resource-bundle key;
- English and Japanese bundle key parity test;
- enum/state labels tested for localization coverage;
- semantic exported formats may have an explicit language policy separate from UI locale.

### 5.2 Keep keyboard/focus behavior intentional

Repo Lens dogfooding found focus landing in an unhelpful field when Analyze disabled the initiating button.

Flow Lens should define focus behavior for:

- analysis start;
- first completed root frame;
- node selection;
- Enter/double-click navigation;
- returning from editor to Tool Window;
- cancellation/error states.

The Flow Canvas is not complete if it is mouse-only.

### 5.3 Split complex UI responsibilities early

Repo Lens’s final Tool Window panel became responsible for a broad set of interactions in one large class. This is not necessarily a defect in Repo Lens, but Flow Lens’s canvas/lifecycle is substantially more complex, so repeating that shape would increase coupling.

Flow Lens should separate at least:

```text
FlowLensToolWindowFactory
FlowLensController / presenter
FlowToolbar
FlowCanvas
FlowDetailsPanel
FlowStatusModel / view
FlowSelectionModel
```

The canvas should render state; it should not own analysis orchestration.

---

## 6. Documentation lessons

Repo Lens maintained dedicated design, manual-testing, known-limitations, privacy, installation, checks/scopes, milestone, changelog, and release documentation.

Flow Lens should create documentation as capabilities stabilize rather than waiting for Marketplace release.

In particular:

- maintain `KNOWN_LIMITATIONS.md` from v0.1 development onward;
- keep a manual sandbox checklist with exact expected behavior;
- record IDE/language-plugin version matrix;
- record verifier exceptions with provenance;
- prepare icon/license/change-notes/description before the final release sprint;
- keep privacy/logging behavior explicit even though the plugin is local-only.

---

## 7. Flow Lens rules derived from the audit

The following are mandatory unless a later design decision explicitly replaces them:

1. Keep language-neutral semantic logic independent from JetBrains PSI types where practical.
2. Keep Go integration optional and isolated so missing Go support cannot prevent plugin startup.
3. Introduce source provenance and do not recursively follow compiler-generated/synthetic declarations by default.
4. Break semantic resolution into bounded, cancellable read actions; never hold one recursive read lock for the whole flow.
5. Never synchronously wait for background platform work on the EDT.
6. Snapshot analysis settings per run.
7. Give progress a real event/model contract; batch/throttle UI updates and never invent percentages.
8. Clear ownership of stale previous results as soon as a new analysis begins.
9. Treat capability/index/semantic unavailability as explainable states, not generic failures.
10. Run Plugin Verifier from the first ToolWindow skeleton and inspect compiled bytecode warnings.
11. Select an exact IDEA Ultimate patch compatible with the real Go/Kotlin integration matrix.
12. Do not use blanket Kotlin metadata compatibility bypasses for the Kotlin analyzer.
13. Build both pure semantic tests and real PSI fixture tests for each supported language.
14. Every discovered correctness bug receives a regression case.
15. Dogfood on real Java, mixed Java/Kotlin, and Go repositories before v0.1 acceptance.
16. Build and install the actual ZIP artifact before release acceptance.
17. Localize user-facing UI via bundles from the beginning and test bundle-key parity.
18. Keep default diagnostics free of source content and other unnecessary code identifiers.
19. Do not claim dynamic unload/update support without an actual lifecycle test.
20. Record known limitations as decisions, not surprises.

Implementation detail is specified in `IMPLEMENTATION_GUARDRAILS.md` and validation evidence in `TEST_STRATEGY.md`.