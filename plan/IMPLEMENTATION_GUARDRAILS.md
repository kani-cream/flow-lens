# Flow Lens Implementation Guardrails

## 1. Purpose

This document defines engineering constraints for implementing `PLAN.md` and `V0.1_SPEC.md`.

It is intentionally stricter than a general architecture overview. The rules here exist to prevent platform, lifecycle, performance, and maintainability problems already encountered during Repo Lens development.

---

## 2. Recommended module boundary

Prefer a real separation between platform-independent semantics and IntelliJ integration.

```text
flow-lens
├─ core
│  ├─ model
│  ├─ frame / node semantics
│  ├─ result / diagnostic types
│  ├─ pure traversal scheduling structures
│  └─ export / pure transformations
│
└─ plugin
   ├─ service / lifecycle
   ├─ platform adapters
   ├─ Java analyzer
   ├─ Kotlin analyzer
   ├─ optional Go analyzer
   ├─ PSI navigation
   ├─ settings persistence
   └─ UI / Flow Canvas
```

Rules:

- `core` must not depend on IntelliJ PSI, Project, VirtualFile, Swing, or language-plugin types.
- `plugin` converts platform objects into the language-neutral model.
- Do not invent abstractions merely to move platform behavior into `core`; if PSI is fundamental to a contract, keep that contract in the plugin/platform boundary.
- Avoid bundling duplicate Kotlin stdlib/coroutines when the chosen IntelliJ Platform already provides the required runtime; verify actual Gradle packaging rather than assuming.

---

## 3. Platform/language dependency isolation

### 3.1 Go is optional at runtime

Go integration must be isolated behind an optional JetBrains Go plugin dependency/config descriptor.

Desired behavior:

```text
IDEA Ultimate + Go plugin
  → Java + Kotlin + Go analyzers available

IDEA Ultimate without Go plugin
  → Flow Lens loads normally
  → Java + Kotlin remain available
  → Go capability shown as unavailable with a concise reason
```

No class in a mandatory startup path may directly load a Go-plugin implementation type.

### 3.2 Capability registry

Maintain explicit analyzer capability information instead of inferring availability only from failed class loading.

Conceptual state:

```text
AnalyzerCapability
- language
- available
- requirement
- implementationId
- semanticLevel
```

Add a drift test so the documented/cataloged integrations remain synchronized with registered analyzer adapters.

### 3.3 Language-neutral model is a hard boundary

The following may not appear in the shared model/UI contracts:

- `PsiElement`
- Kotlin Analysis API types
- Go PSI types
- UAST nodes
- JetBrains implementation-specific symbol types

Store language-neutral source/symbol descriptors and keep platform handles behind a navigation/resolution service.

---

## 4. Source provenance and synthetic-code policy

Every resolved callable that may become a target frame should be classified by provenance before recursive analysis.

Conceptual model:

```text
SourceOrigin
- PHYSICAL_SOURCE
- SYNTHETIC
- GENERATED
- LIBRARY
- UNKNOWN
```

Default v0.1 recursion policy:

| Origin | Recursive analysis |
|---|---|
| Physical project source | Yes, within normal limits |
| Synthetic/compiler-generated | No by default |
| Generated project source | No by default unless deliberately enabled later |
| Library/external | No by default |
| Unknown | Conservative stop/diagnostic |

Rules:

- A light/synthetic method exposed by UAST/PSI is not automatically user-authored code.
- Prefer physical-source/origin checks over method-name deny lists.
- Explicitly authored methods that override generated defaults remain authored source.
- Generated semantics may be supported later, but only as a named capability with fixtures and visual semantics.

Required regression families include Kotlin data classes, implicit constructors/light declarations, explicitly authored overrides, and Java generated/light members where applicable.

---

## 5. Read-action granularity

The recursive flow analysis must not execute under one large read action.

### Required pattern

```text
Scheduler
  ↓
short cancellable read/smart-read operation
  - validate pointer/source revision
  - extract one frame or bounded group
  - resolve bounded calls
  - convert to stable model/pointers
  ↓ release read access
cancellation check
  ↓
next operation
```

Rules:

- No project-wide recursive call traversal while holding one read lock.
- No loop of unbounded reference searches inside a single read action.
- Release platform read access between meaningful units of work.
- Preserve `SmartPsiElementPointer` or another appropriate stable handle when PSI must survive across scheduling/suspension boundaries.
- Revalidate PSI/pointer validity before use.
- A source revision change invalidates assumptions rather than being ignored.
- Cancellation checks occur between frames/nodes and around potentially expensive reference/index work.

---

## 6. EDT discipline

The EDT is for UI state capture and rendering, not analysis.

Never on the EDT:

- recursive call resolution;
- large PSI traversal;
- reference searches;
- waiting for smart/index completion;
- blocking `Future.get` / `join` / latch waits for analysis work;
- layout computation that is known to be heavy enough to make typing/navigation lag.

Short actions that must read live UI state (caret/editor/selection) may start on EDT, then immediately hand off immutable/stable input to background work.

UI application of analysis results must be lightweight and may be batched/throttled.

---

## 7. Analysis service ownership

Use a project-level service as the single owner of the active run.

Responsibilities:

- snapshot settings;
- capture root request;
- cancel/replace the previous run;
- own coroutine/job lifecycle;
- coordinate smart/index waiting;
- invoke analyzer registry/scheduler;
- publish progress and partial result events;
- convert stale/source-change state into a result lifecycle state;
- expose cancellation;
- dispose cleanly with the project.

Analyzers should be stateless where practical or scoped to one run.

### Run identity

Every analysis receives an opaque `runId`/generation.

UI must ignore late events from an older cancelled/stale run.

This prevents:

```text
Run A starts
Run B starts and cancels A
late event from A arrives
UI accidentally mutates Run B canvas
```

---

## 8. Settings snapshot

At run start, create an immutable analysis configuration snapshot.

Include:

- max depth;
- max semantic nodes;
- include-tests;
- library/external traversal policy;
- any language semantic options affecting the result.

Do not read mutable settings repeatedly during traversal.

View state is separate:

- zoom;
- pan;
- selected node;
- expanded/collapsed frames;
- details-panel size.

Changing view state must never trigger semantic re-analysis.

---

## 9. Failure taxonomy

Do not collapse every exceptional condition into `FAILED`.

### Local/event-level

Examples:

- one target unresolved;
- dispatch confidence unknown;
- unsupported synthetic/generated target;
- one optional language semantic unavailable.

Represent these on the relevant call/frame and continue when safe.

### Recoverable run-level state

Examples:

- waiting for indexes;
- source changed and run became stale;
- user cancellation;
- node/depth truncation.

Use the corresponding result state.

### Fatal run-level

Examples:

- root request cannot be established;
- core invariant violation;
- project disposed;
- unrecoverable platform integration failure.

Abort once with a concise diagnostic. Do not repeat the same root failure for every call.

Cancellation exceptions must always propagate as cancellation and must not be wrapped into a generic failure.

---

## 10. Progress-event contract

Progress is part of the architecture from v0.1.

Conceptual event model:

```text
FlowProgress
- runId
- stage
- language?
- currentFrame?
- nodesProduced
- framesAnalyzed
- exactCount
- declaredTargetCount
- ambiguousCount
- externalCount
- elapsed
```

Possible stages:

```text
STARTING
WAITING_FOR_INDEXES
EXTRACTING_ROOT
RESOLVING_ROOT_CALLS
ANALYZING_CHILD_FRAMES
LAYOUT_UPDATE
COMPLETED
TRUNCATED
CANCELLED
STALE
FAILED
```

Rules:

- values must come from real work;
- no fake percentages when total work is unknown;
- analyzers report semantic progress through orchestration, never by touching UI;
- do not emit one EDT update for every tiny PSI event;
- throttle/coalesce rapid progress/model updates while preserving final state;
- cancellation responsiveness outranks animation smoothness.

---

## 11. Previous-result and focus behavior

When a new analysis starts:

1. cancel the previous active run;
2. assign a new run identity;
3. immediately stop presenting the old flow as current;
4. clear/disable actions bound to the previous current run;
5. display the new root/running state as soon as possible;
6. put keyboard focus on the Flow Canvas/result surface when that is the natural next action.

Do not leave a stale flow underneath an error message in a way that looks current.

Default keyboard contract:

- arrows/tab: move focus among relevant canvas elements according to implementation;
- Enter: open target for resolved call;
- explicit action/shortcut: open call site;
- Escape: collapse/exit nested focus where appropriate;
- cancellation remains available without mouse use.

---

## 12. UI component boundaries

Avoid one monolithic ToolWindow panel owning analysis, canvas, detail rendering, selection, actions, and status.

Recommended responsibilities:

```text
FlowLensToolWindowFactory
  creates/disposes content

FlowLensController / Presenter
  binds service events to view models/actions

FlowToolbar
  Analyze / Stop / fit / settings entry points

FlowCanvas
  semantic map rendering + hit testing + pan/zoom

FlowSelectionModel
  selected event/frame + navigation context

FlowDetailsPanel
  full signature/location/confidence/diagnostics

FlowStatusModel / StatusView
  indexing/running/partial/stale/error summaries
```

The exact class names may differ, but the responsibilities must stay separable and testable.

Analysis bounds live in settings, not on the toolbar (owner decision,
2026-08-17): their value applies to the next run, so placing them beside a
running analysis implies an effect on the current result that they do not have.

---

## 13. Logging and privacy

Default logs must never contain source bodies.

Prefer:

```text
runId=...
stage=...
language=Kotlin
nodes=37
frames=11
ambiguous=2
external=5
elapsed=184ms
status=COMPLETED
```

Avoid by default:

- method argument text;
- source snippets;
- full call expressions;
- source code bodies;
- unnecessary absolute file paths.

If a future diagnostic mode exposes more detail, it must be opt-in and documented.

Flow Lens performs no network call as part of normal analysis.

---

## 14. Localization from first UI commit

All user-facing UI chrome should use a resource bundle from the first implementation.

Required languages initially:

- English;
- Japanese.

Tests must assert:

- English/Japanese bundle keys are identical;
- every result/status enum with a visible label has a localized key;
- no obvious fallback raw key appears in fixture UI paths.

Do not postpone localization until release hardening.

---

## 15. Build/baseline rules

Milestone 0 must pin:

- exact IntelliJ IDEA Ultimate patch/build;
- Java toolchain required by that platform;
- Kotlin compiler/plugin/API compatibility;
- exact compatible Go plugin version for test/runtime integration;
- IntelliJ Platform Gradle Plugin version.

Do not choose only a broad major/minor line and assume all required language plugins load on the earliest patch.

The baseline may advance only through an explicit compatibility decision with verifier/fixture evidence.

---

## 16. Dynamic loading and disposal

Do not mark Flow Lens as dynamically unload-safe based only on Plugin Verifier heuristics.

If any extension point or plugin descriptor entry is declared dynamic:

- register/dispose message-bus connections correctly;
- cancel project/application coroutine work;
- dispose canvas listeners/timers/resources;
- ensure optional language adapters do not keep class-loader references;
- manually test disable/enable/update without restart.

Until that evidence exists, restart-required updates are acceptable.

---

## 17. Performance budgets

Exact release numbers can be refined after Milestone 0, but v0.1 must measure rather than merely describe performance as "responsive".

Measure at minimum:

- time to show root frame;
- time to complete a typical depth-3 / <=100-node analysis;
- cancellation latency;
- number/duration of bounded read operations;
- layout/update time for ~100 semantic nodes;
- EDT update frequency during progressive analysis;
- memory retained after replacing/cancelling a flow.

If a benchmark reveals platform-specific outliers, record them as a known limitation or reduce the scope before release.

---

## 18. CI is a gate, not a debugger

The private repository has a limited pool of GitHub Actions minutes, and a CI
run costs the whole team's budget. Push when the work is finished, not to find
out whether it is.

Before a push that triggers CI:

1. **Reproduce any failure locally.** If it cannot be reproduced, say why — a
   timing window that only opens on a slower machine is a real answer; "it
   passed here" is not.
2. **Enumerate before fixing.** A race has a shape: list every window in the
   function and the state transitions around it, then close all of them. Fixing
   the one instance that the failure happened to expose is how one CI run
   becomes three.
3. **Rerun from scratch.** `--rerun-tasks` over the whole suite, more than once.
   An up-to-date task reports success without running anything.
4. **Review before pushing**, not in parallel with CI.

v0.3 spent four runs on one test, plus one more on a documentation-only push.
The second and third found real product bugs, so those runs were not wasted —
but they were only needed because the first fix was pushed as a hypothesis
instead of a conclusion. The account's free minutes ran out as a result.

### What actually costs minutes

The workflow triggers on `pull_request` and `workflow_dispatch` only. There is
no `push` trigger, and the build job is guarded by
`github.event.pull_request.draft == false`.

| Action | Billed |
|---|---|
| Pushing a branch with no open PR | no |
| Opening or updating a **draft** PR | no — the job is skipped |
| Marking a PR ready, or pushing to a ready PR | **yes** |
| Merging | no |

`paths-ignore` does not help once a PR contains code: for `pull_request` events
the filter is evaluated against the whole PR diff, not the push. A
documentation-only commit to an open, ready PR runs the full build. Keep a PR in
draft until the work is finished, then mark it ready once.

### The local gate

Everything CI checks can be checked locally, and must be before a push that
bills:

```text
./gradlew build --rerun-tasks                    # 360 tests from scratch
./gradlew test -Pflowlens.isolate=true           # one JVM per class
./gradlew verifyPlugin buildPlugin               # both target builds, ZIP
```

`-Pflowlens.isolate=true` forks a JVM per test class. The light fixture shares
one project across a whole run, so a suite can read state another suite left
behind and pass for the wrong reason. That is the class of defect a hosted
runner found twice; isolation makes it reproducible here instead.

What a hosted runner still adds is different timing on different hardware, which
is how the two Stop races surfaced. Repeated local runs reduce but do not remove
that gap — which is a reason to spend a run at a milestone boundary, not a reason
to spend one per fix.

---

## 19. Definition of Done impact

A v0.1 feature is not done merely because it works in one sandbox.

For changes touching analysis/platform/UI lifecycle, Definition of Done includes as applicable:

- pure semantic tests;
- real PSI fixture tests;
- negative/synthetic-source fixtures;
- cancellation/indexing/source-change behavior;
- Plugin Verifier;
- manual sandbox verification;
- real-repository dogfooding;
- actual ZIP artifact build/install check;
- documentation of newly accepted limitations;
- no unexplained API stability regression.

See `TEST_STRATEGY.md` for the validation matrix.