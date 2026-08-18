# Flow Lens Test Strategy

## 1. Purpose

Flow Lens depends on language PSI, resolution, indexing, cancellation, progressive UI updates, and optional plugins. A passing compiler build is therefore weak evidence.

This document defines the validation layers required to keep the product correct and maintainable.

The strategy intentionally follows the strongest parts of Repo Lens development: pure core tests, real PSI fixture tests, negative controls, real-repository dogfooding, Plugin Verifier, manual sandbox checks, and validation of the packaged ZIP.

---

## 2. Test pyramid

### Layer A — Pure core tests

Run without IntelliJ where possible.

Cover:

- `FlowAnalysisResult` lifecycle transitions;
- `FlowFrame` / `FlowNode` invariants;
- depth accounting;
- node-budget accounting;
- cycle-path logic;
- ordering-status propagation;
- dispatch/result-state mapping;
- scheduling/progressive ordering structures;
- serialization/export later;
- diagnostics aggregation.

These tests should be fast and run on every change.

### Layer B — IntelliJ fixture tests

Use the IntelliJ test framework and real PSI/language-plugin implementations.

Cover:

- caret → entry-point detection;
- physical vs synthetic/generated declaration detection;
- call extraction/evaluation order;
- target resolution;
- Java ↔ Kotlin dispatch;
- Go PSI call forms;
- source navigation locations;
- smart pointer invalidation/revalidation;
- indexing/Dumb Mode behavior where the framework allows it.

Do not mock PSI when the question being tested is what PSI actually exposes.

### Layer C — Service/lifecycle integration tests

Cover the project-level analysis service:

- one active run per project;
- new run cancels old run;
- late events from old `runId` cannot mutate the current result;
- cancellation propagates rather than becoming failure;
- partial results remain valid;
- source mutation marks results stale;
- settings are snapshotted at start;
- missing optional Go capability is normal;
- progress events are real and end in one terminal lifecycle state.

### Layer D — Flow Canvas behavior tests

Test view-model/layout logic without requiring pixel-perfect rendering where practical.

Cover:

- root frame initially expanded;
- child frames initially collapsed;
- expansion/collapse preserves parent context;
- different semantic states produce different component/view-model treatments;
- approximate/unspecified ordering never uses the definite connector style;
- stale/cancelled/indexing states are distinguishable from unresolved code;
- old-run events are ignored;
- progress updates are coalesced/throttled according to the UI contract.

Pixel/screenshot tests are optional and should be introduced only if stable across supported platforms/themes.

### Layer E — Real-repository smoke tests

Run against real repositories, not only crafted fixtures.

Required pre-v0.1 coverage:

- representative Java repository;
- representative mixed Java/Kotlin repository;
- representative Go repository;
- Flow Lens repository itself where relevant.

Record:

- root-to-first-map latency;
- total analysis time for bounded flow;
- cancellation behavior;
- unexpected unresolved/ambiguous/synthetic paths;
- visual readability at practical graph sizes;
- any platform exceptions/log noise.

### Layer F — Packaged artifact tests

Build the actual distribution ZIP and inspect it every time release checks run.

Manual exercise happens in the sandbox IDE for each milestone. Installing the
built ZIP into a production IntelliJ IDEA Ultimate is a **v1.0 release
acceptance step** (owner decision, 2026-08-17) and is not repeated for earlier
milestones; the sandbox runs the same packaged plugin layout.

Verify:

- plugin loads;
- Tool Window registers;
- icons/resources/localization load;
- Java/Kotlin analysis works;
- Go capability behaves correctly both with and without the Go plugin according to test setup;
- no missing class due to optional dependency packaging;
- disable/restart/update behavior matches documented claims.

---

## 3. Language fixture matrix

### Java fixtures

Required cases:

- linear calls;
- nested calls `save(convert(load()))`;
- chained calls;
- static/private/final exact dispatch;
- ordinary virtual declared-target dispatch;
- interface/abstract ambiguous dispatch;
- constructors;
- explicit `this(...)` / `super(...)`;
- recursion/cycle;
- external JDK/library call;
- depth limit;
- node limit;
- calls inside branch/loop causing `controlFlowIncomplete` in v0.1;
- lambda body traversal boundary;
- Java record/generated/light member behavior if surfaced by selected APIs.

### Kotlin fixtures

Required cases:

- top-level function;
- member function;
- extension function;
- nested/chained calls;
- Java ↔ Kotlin cross-language resolution;
- virtual/abstract/interface dispatch;
- constructor with explicit body;
- property syntax does not silently invent unsupported calls;
- default-argument/compiler-generated behavior follows documented v0.1 limitation;
- data-class `copy` / `componentN` and other synthetic/light members are not treated as authored recursive targets by default;
- explicitly authored `equals`/`toString`/other overrides remain eligible;
- lambda/anonymous body traversal boundary;
- source navigation lands on physical source.

### Go fixtures

Required cases:

- package function;
- receiver method;
- nested calls;
- method call resolution;
- built-in terminal behavior where implemented;
- `go f()` preserves `GOROUTINE`;
- `defer f()` preserves `DEFERRED`;
- `defer f(a())` evaluates argument call semantics correctly;
- recursion/cycle;
- external/module dependency terminal;
- function literal traversal boundary;
- `switch`/loop produces simplified-control-flow status in v0.1;
- analyzer unavailable when Go plugin is absent.

---

## 4. Negative controls

Every fixture group must include behavior that must **not** occur.

Examples:

- a synthetic Kotlin data-class method must not create a recursive child frame;
- a field/property access must not become a call unless explicitly supported;
- an external call must not expand library source when libraries are OFF;
- depth exactly at the configured maximum must not stop one level too early;
- node budget must not exceed the configured semantic-node count;
- an unresolved call must not abort unrelated siblings;
- a cancelled run must not later become COMPLETED due to a late event;
- an old run must not replace the current canvas;
- indexing wait must not create fake unresolved nodes;
- missing Go plugin must not prevent plugin startup;
- unsupported control flow must not be drawn as a proven unconditional sequence.

Negative controls are mandatory because many IntelliJ integration bugs present as extra plausible-looking data rather than crashes.

---

## 5. Source provenance regression suite

Maintain a dedicated regression group for generated/synthetic/light declarations.

At minimum:

```text
Kotlin data class
  generated copy/componentN      → do not recurse by default
  explicit authored member      → recurse when otherwise eligible

Kotlin constructor/light PSI
  implicit/generated body        → do not invent authored frame
  explicit constructor body      → analyze

Java generated/light elements
  classify conservatively
```

Any new false-positive/phantom-flow case discovered in real use is added to this suite.

---

## 6. Concurrency, read-action, and cancellation tests

Flow Lens must test platform cooperation, not only correctness of final output.

Required tests/measurements:

- analysis does not require one long read action for an entire recursive flow;
- cancellation is observed between bounded units of work;
- cancellation during a costly resolution does not corrupt the model;
- source modification between operations causes revalidation/stale behavior;
- no service/UI test blocks EDT waiting on a background future;
- rapid restart of analysis cannot interleave run generations;
- project disposal cancels active work without leaking exceptions/resources.

Where direct timing assertions are flaky, instrument the analysis scheduler and assert structural behavior/cancellation points rather than arbitrary milliseconds.

---

## 7. Progressive rendering/progress tests

Progress events must reflect actual work.

Validate:

- STARTING occurs before analysis events;
- root frame becomes available before deep child frames in the user-visible progression;
- waiting-for-index state has no fake unresolved output;
- counters are monotonic where logically applicable;
- terminal status is exactly one of completed/truncated/cancelled/stale/failed;
- throttling/coalescing does not drop the final model state;
- no fake percentage is generated without a known denominator;
- UI update rate remains bounded under a 100-node analysis.

---

## 8. Localization tests

From the first UI milestone:

- English and Japanese bundles have identical key sets;
- every visible lifecycle/status value maps to a bundle key;
- every visible dispatch/execution-mode label maps to a bundle key;
- formatted messages accept required parameters;
- no production UI code introduces obvious hardcoded English/Japanese text outside approved technical literals.

Manual check both English and Japanese IDE UI at least once before v0.1 acceptance.

---

## 9. Plugin Verifier matrix

Run Plugin Verifier in CI as soon as the plugin skeleton exists.

After Milestone 0 baseline selection, verify at least:

1. minimum supported IntelliJ IDEA Ultimate build;
2. latest/current build the release claims to support.

Include required language-plugin dependencies in the verification/test environment.

Record separately:

- compatibility errors;
- internal API usage;
- scheduled-for-removal usage;
- deprecated usage;
- experimental usage;
- warnings attributable to Flow Lens bytecode;
- warnings originating only from dependency/plugin signatures.

A verifier warning count is not allowed to grow silently.

---

## 10. Build/CI strategy

Recommended CI flow:

```text
checkout
  ↓
setup exact JDK/toolchain
  ↓
core tests
  ↓
plugin fixture/integration tests
  ↓
build distribution
  ↓
plugin structure verification
  ↓
Plugin Verifier matrix
  ↓
API stability regression check
  ↓
upload distribution artifact
```

IntelliJ distributions and verifier targets can consume large disk space. CI should avoid caching re-derivable extracted/transformed IDE directories and may proactively remove unrelated preinstalled toolchains if runner disk pressure requires it.

Do not make release reliability depend on a developer machine’s warm Gradle/IDE cache.

---

## 11. Manual sandbox checklist

Create a maintained manual-test document once the plugin skeleton exists.

At minimum verify:

1. start sandbox from clean/reproducible Gradle task;
2. open Java sample, analyze, navigate target/call site;
3. open Kotlin sample, analyze, including synthetic negative control;
4. open mixed Java/Kotlin sample;
5. open Go sample with Go plugin enabled;
6. confirm behavior without Go capability where practical;
7. test indexing/wait state;
8. edit source during analysis and observe stale state;
9. cancel during deeper analysis;
10. start a second run quickly and confirm no late-event contamination;
11. inspect 100-node/truncation case;
12. test keyboard navigation;
13. test light/dark theme readability;
14. test Japanese/English UI;
15. inspect IDE log for exceptions/source leakage;
16. install the built ZIP into a fresh sandbox/IDE and repeat core smoke path.

Manual expected results should be tabulated, not described only as “looks OK”.

---

## 12. Dogfooding rule

Before each milestone is closed:

- run the feature on at least one real repository relevant to its language/semantic scope;
- record any false flow, phantom target, UI ambiguity, performance issue, or lifecycle issue;
- either fix it with regression coverage or record it explicitly in `KNOWN_LIMITATIONS.md`;
- re-run Plugin Verifier when platform/API-facing code changed.

Dogfooding evidence is part of milestone completion.

---

## 13. Bug-to-regression rule

Any confirmed production/dogfood bug in these categories must produce a regression test or deterministic manual reproduction step:

- PSI/source-shape correctness;
- generated/synthetic source;
- resolution/dispatch;
- ordering;
- cycles/limits;
- cancellation;
- stale source/indexing;
- optional dependency loading;
- ToolWindow lifecycle;
- localization;
- verifier/API warning;
- packaging/resource loading.

A code fix without evidence that the counterexample stays fixed is incomplete.

---

## 14. v0.1 release gate

v0.1 may be considered release-ready only when:

- Java/Kotlin/Go required fixture matrix passes;
- generated/synthetic regression suite passes;
- mixed Java/Kotlin fixture passes;
- cancellation/indexing/stale/run-generation tests pass;
- ~100-node canvas/progressive-update test or benchmark is acceptable;
- real-repository smoke completed for Java, mixed JVM, and Go;
- Plugin Verifier has no unexplained compatibility/API regressions;
- actual distribution ZIP is built and installed successfully;
- English/Japanese UI checks pass;
- known limitations are current;
- no source content is emitted to normal logs;
- manual sandbox checklist has no release-blocking failures.

The purpose of the gate is not maximum test count. It is confidence that the plugin behaves correctly in the exact areas where JetBrains plugins tend to fail outside ordinary unit tests.