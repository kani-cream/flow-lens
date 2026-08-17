# Flow Lens Development Plan

## 1. Product Statement

**Flow Lens is an IntelliJ IDEA Ultimate plugin that turns a selected function or method into an explorable static execution-flow map.**

The user chooses one entry point. Flow Lens follows reachable code within bounded limits, preserves meaningful evaluation/execution order, exposes uncertainty instead of hiding it, and presents the result as a visual map that can be explored and navigated back to source.

> Select a method. See where the code flows.

Flow Lens is intentionally not a repository-wide analyzer and not a runtime tracer. It is a focused source-understanding tool for reading unfamiliar code, debugging, code review, onboarding, and investigation.

---

## 2. Target IDE and Languages

### Target IDE

The primary and supported v0.1 host is:

- **IntelliJ IDEA Ultimate**

Other JetBrains IDEs such as GoLand, Android Studio, and remote/client-only environments are not required for v0.1 compatibility.

The exact minimum IntelliJ IDEA Ultimate build supported by the first release will be fixed during the initial build/prototype milestone and recorded in the build configuration.

### Official target languages

Flow Lens treats these as first-class product languages:

- **Java**
- **Kotlin**
- **Go**

Go support is available when the JetBrains Go language plugin is installed and compatible with the target IntelliJ IDEA Ultimate build.

The visualization, navigation abstraction, persistence, and shared analysis model must remain language-neutral. Language-specific PSI/API types stay behind analyzer adapters.

Conceptual architecture:

```text
                         FlowAnalysisEngine
                                │
                     AnalyzerRegistry
                                │
          ┌─────────────────────┼─────────────────────┐
          │                     │                     │
   Java Analyzer         Kotlin Analyzer          Go Analyzer
          │                     │                     │
          └─────────────────────┼─────────────────────┘
                                ↓
                       FlowAnalysisResult
                                ↓
                           Flow Canvas
```

Analysis may cross language boundaries. The analyzer used for the root declaration does not remain fixed for the whole traversal.

Example:

```text
Java Controller
      ↓
Kotlin Service
      ↓
Java Repository
```

Each resolved target is dispatched through `AnalyzerRegistry` according to the target declaration language.

A language must not be advertised as supported unless entry-point detection, explicit-call extraction, resolution, source navigation, cycle handling, mixed-language dispatch where applicable, and core Flow Canvas rendering work reliably for that language.

---

## 3. Product Goals

### Core goals

- Analyze exactly one user-selected method/function as the entry point.
- Follow project-local calls recursively within explicit limits.
- Preserve evaluation/execution order where the language semantics and IDE model allow it.
- Represent repeated calls to the same symbol as separate call-site events.
- Distinguish call-site identity from target-symbol identity.
- Show external, unresolved, ambiguous, cyclic, declared-target, and truncated paths explicitly.
- Navigate both to the call site and, where known, the target declaration.
- Keep analysis cancellable and responsive on real-world projects.
- Work locally without requiring an AI service or external server.
- Make analysis progress visible as the Flow Canvas grows.
- Allow Java, Kotlin, and Go to converge into one shared semantic model without pretending their language semantics are identical.

### Product differentiation

Flow Lens must not become a prettier Call Hierarchy.

The primary experience is a **semantic Flow Map**. Calls, nested function frames, boundaries, branches, loops, async/deferred execution, ambiguity, and depth use distinct visual language so that the user can understand the character of the flow at a glance.

Visual quality is a core product requirement, not a later polish task.

---

## 4. Non-Goals

The initial product does not attempt to provide:

- Whole-repository exhaustive control-flow analysis.
- Guaranteed runtime-accurate execution traces.
- Full symbolic execution.
- Runtime instrumentation or profiling.
- Perfect resolution of reflection, framework magic, runtime dependency injection, generated behavior, or dynamic proxies.
- Complete compiler-desugared/implicit call reconstruction.
- Complete Java/Kotlin async semantics.
- Complete Go goroutine/channel semantics.
- Automatic selection of one runtime implementation when multiple targets are genuinely plausible.
- A replacement for IntelliJ Bookmarks or Call Hierarchy.

Flow Lens must prefer explicit uncertainty over false certainty.

---

## 5. Entry Point and Analysis Semantics

Every analysis starts from exactly one supported function-like declaration.

Initial entry points:

- Java methods and constructors.
- Kotlin named functions and constructors with an analyzable explicit body.
- Go package functions and methods.

The caret may be anywhere inside the supported declaration or its signature. Unsupported constructs fail clearly rather than guessing.

### Explicit-call scope

v0.1 is primarily an **explicit callable-flow analyzer**.

Compiler-generated or implicit calls are not assumed to exist in the flow unless a language analyzer explicitly supports and tests them.

Examples not automatically expanded in v0.1:

- Kotlin property getter/setter invocation implied by property syntax.
- Kotlin default-argument machinery not represented as an explicit supported semantic case.
- delegated-property expansion.
- Java/Kotlin implicit constructor initialization chains beyond the explicit body rules.
- framework-generated proxies and injected runtime targets.

This limitation must not prevent later analyzers from adding richer semantics.

### Evaluation order

"Source order" means meaningful language evaluation order, not naive PSI preorder.

Example:

```java
save(convert(load(id)));
```

Conceptual order:

```text
load(id)
  ↓
convert(...)
  ↓
save(...)
```

For Java:

```java
foo(a(), b());
```

must be represented as:

```text
a()
 ↓
b()
 ↓
foo()
```

If ordering cannot be established safely, the result carries an ordering status rather than inventing certainty.

---

## 6. Shared Analysis Model

The shared model separates orthogonal concepts. Node kind must not duplicate resolution state.

### 6.1 Result state

```text
FlowAnalysisResult
- status
- rootFrame
- nodeCount
- controlFlowIncomplete
- sourceRevision / modification marker
- diagnostics
```

Result status:

```text
RUNNING
COMPLETED
TRUNCATED
CANCELLED
STALE
FAILED
```

`CANCELLED` and `FAILED` are analysis-result states, not node kinds.

### 6.2 Frames and call sites

Nested expansion requires a first-class callable-body container.

```text
FlowFrame
- symbol
- entryLocation
- depth
- events[]
```

A call-site event may own a child frame when the target body has been analyzed:

```text
FlowNode
- id
- kind
- callSiteLocation
- targetSymbol?
- targetFrame?
- depth
- resolutionStatus
- dispatchConfidence
- executionMode
- orderingStatus
- metadata
```

This permits the UI to collapse a call to one compact card or expand the analyzed target body inline without losing call-site identity.

The same target symbol called twice produces two distinct call nodes, even if their analyzed body can internally reuse cached extraction data.

### 6.3 Node kind

```text
ENTRY
CALL
CONSTRUCTOR
CONDITION
SWITCH
LOOP
RETURN
THROW
TRY
CATCH
FINALLY
CYCLE
LIMIT
STATUS
```

### 6.4 Resolution status

```text
PROJECT_LOCAL
EXTERNAL
UNRESOLVED
BUILT_IN
```

Ambiguity is represented through dispatch confidence rather than duplicating it as a node type.

### 6.5 Dispatch confidence

```text
EXACT
DECLARED_TARGET
AMBIGUOUS
UNKNOWN
```

Meaning:

- `EXACT`: the analyzer can justify a single target for the call semantics being modeled.
- `DECLARED_TARGET`: a concrete declared target body is available and useful to analyze, but runtime overriding/dispatch may select another implementation.
- `AMBIGUOUS`: there is no single body that Flow Lens can responsibly select as the analyzed continuation.
- `UNKNOWN`: target confidence could not be classified reliably.

For `DECLARED_TARGET`, Flow Lens may analyze the declared implementation but must visually indicate that runtime override is possible.

For `AMBIGUOUS`, v0.1 stops recursive traversal at that call. Candidate exploration is a later milestone.

### 6.6 Execution mode

```text
SYNC
ASYNC
GOROUTINE
DEFERRED
UNKNOWN
```

v0.1 does not need full parallel/deferred lane rendering, but known execution semantics such as Go `go f()` and `defer f()` must be preserved as metadata instead of being lost.

### 6.7 Ordering status

```text
DETERMINISTIC
APPROXIMATE
UNSPECIFIED
```

The renderer must not draw an ordinary certain sequential connector when the model says ordering is approximate or unspecified.

---

## 7. Resolution Policy

### Exact cases

Typical cases expected to be `EXACT` when resolvable include:

- static functions/methods.
- private/final non-overridable methods where language semantics justify it.
- constructors.
- Java `super.method()` style explicit superclass dispatch.
- Kotlin top-level functions.
- Go package functions.
- other analyzer-specific cases where one target is statically established.

### Declared target

For ordinary virtual methods where IntelliJ resolves a concrete declared body but runtime overriding remains possible, Flow Lens may continue into the declared body with:

```text
resolutionStatus = PROJECT_LOCAL
dispatchConfidence = DECLARED_TARGET
```

The UI must not present this as guaranteed runtime dispatch.

### Ambiguous

Use `AMBIGUOUS` when no responsible single continuation can be chosen, for example an interface/abstract contract with multiple plausible implementations and no concrete body suitable as the analyzed continuation.

v0.1 stops there.

### Unresolved

If no reliable target can be found:

- retain the call-site node.
- mark it `UNRESOLVED`.
- continue analyzing unrelated events.

One failed call must not fail the entire analysis.

### External

Dependency, SDK, JDK/runtime, and other non-project calls are terminal by default. External source navigation may still be offered when IntelliJ can navigate to it.

---

## 8. Language-Specific v0.1 Semantics

### Java

Support explicit methods, constructors, normal method calls, static calls, `new`, explicit `this(...)`, and explicit `super(...)` constructor invocations.

Constructor analysis in v0.1 covers explicit constructor body semantics Flow Lens can observe reliably. It does not promise reconstruction of every implicit field/initializer/super-initialization step.

### Kotlin

Support named top-level/member functions, analyzable constructors, normal/member/extension calls, and explicit constructor calls.

v0.1 does not promise complete implicit/compiler-generated callable expansion. Property accessors, delegated properties, default arguments, operators, and generated bridges are only shown when deliberately implemented and tested.

### Go

Support package functions, receiver methods, and ordinary calls.

Preserve:

- `go f()` as `executionMode = GOROUTINE`.
- `defer f()` as `executionMode = DEFERRED`.

For a deferred call, argument-expression evaluation events remain ordered where language semantics define them, while the deferred target invocation itself must not be presented as an ordinary immediate synchronous continuation.

Go built-ins may be represented as built-in terminal operations.

---

## 9. Mixed-Language Dispatch

Analyzer selection occurs per resolved callable, not only once at the root.

Conceptual API:

```text
FlowLanguageAnalyzerRegistry
- findAnalyzer(element)
- findAnalyzer(languageId)

FlowLanguageAnalyzer
- supports(element)
- findEntryPoint(caret)
- extractDirectFlow(callable)
- resolveCall(callSite)
```

Java ↔ Kotlin transitions must be supported when IntelliJ resolves the target declaration across those languages.

Go normally remains within Go source but still passes through the same analyzer registry and shared model.

---

## 10. Traversal and Progressive Analysis

Flow Lens uses a **local-first / breadth-first-biased** progressive strategy rather than diving deeply into the first branch before the root picture is known.

Preferred progression:

```text
1. Extract root frame direct events.
2. Render root frame.
3. Resolve/analyze depth-1 project-local callees.
4. Render/update their availability.
5. Continue toward deeper levels within limits.
```

This keeps the initial map understandable and reduces disruptive canvas re-layout.

The implementation may use internal scheduling that differs from strict BFS for performance, but user-visible progressive results should prioritize completing the current frame before deeply expanding one early branch.

---

## 11. Analysis Limits

Defaults:

```text
Max method/function depth      3
Max persistent semantic nodes 100
Include tests                 OFF
Include libraries             OFF
```

Depth counts callable-body crossings only. Conditions and visual containers do not consume recursive depth.

### Node counting

The 100-node budget counts persistent semantic model nodes.

Transient UI-only states such as a temporary `resolving…` indicator do not count.

The final reserved semantic slot may be used for a `LIMIT` node so the result never silently truncates.

Limits are enforced during traversal.

---

## 12. Cycle Detection

Cycle detection is based on the current callable traversal path, not global symbol deduplication.

```text
A.foo()
  ↓
B.bar()
  ↓
A.foo()
  ↓
CYCLE → A.foo()
```

The same symbol may appear again in another call site or independent path.

---

## 13. Indexing, Source Changes, and Analysis Lifetime

Flow Lens must not convert temporary IDE indexing limitations into misleading `UNRESOLVED` results.

When required indexes are unavailable:

```text
Waiting for IntelliJ indexes…
```

Analysis begins or resumes when the required smart/indexed state is available.

If relevant source changes while an analysis is running and the existing result can no longer be trusted:

- cancel/stop the affected analysis cooperatively.
- mark the result `STALE`.
- preserve already-rendered data for inspection when safe.
- offer re-analysis rather than silently mixing old and new PSI state.

Only one active analysis is allowed per project in v0.1.

---

## 14. Flow Canvas

The primary v0.1 visualization is **Flow Canvas** in a dedicated Tool Window.

A plain `JTree` or simple `A → B → C` call diagram is not the target experience.

### Initial expansion policy

At first presentation:

- root `FlowFrame`: expanded.
- direct call cards: visible.
- child frames at depth 1+: collapsed by default.
- deeper analyzed content may be available without being visually expanded.

The user expands a call card to reveal its `targetFrame` inline.

This prevents a 100-node analysis from opening as a 100-node wall.

### External boundary

`PROJECT BOUNDARY` is a semantic crossing local to an edge/call. It is not required to be one canvas-wide horizontal line.

Example:

```text
HttpClient.post()
      │
      ║ PROJECT BOUNDARY
      ▼
okhttp.execute()
```

This remains correct inside nested frames and multiple external branches.

Detailed rules live in `plan/VISUAL_DESIGN.md`.

---

## 15. Navigation

Call nodes have two potentially distinct source destinations:

1. **Target declaration** — where the resolved method/function is defined.
2. **Call site** — where this specific event occurs.

Default interaction:

- Double click / Enter on a resolved call: open target declaration.
- Context action: `Open Call Site`.
- Context action: `Open Target` when known.
- Unresolved call: navigation defaults to call site.
- Entry card: opens entry declaration.

This preserves the distinction between event identity and symbol identity.

---

## 16. Performance and Threading

- Never perform meaningful traversal/resolution work on the EDT.
- Use cancellable IntelliJ read/index access patterns.
- Avoid whole-project scans during normal analysis.
- Reuse IDE indexes and resolution APIs.
- Enforce limits during traversal.
- Memoize repeated direct-flow extraction/resolution within one analysis when safe.
- Keep progressive partial results structurally valid.
- Treat source modification and invalid PSI as a reason to revalidate/cancel rather than continue with stale assumptions.

A dedicated benchmark fixture should eventually cover at least:

- a medium Java project.
- mixed Java/Kotlin project.
- Go project.
- 100-node limit behavior.
- cancellation during resolution.

---

## 17. Roadmap

### Milestone 0 — Feasibility prototypes

Before product implementation is considered stable, prove independently:

- Java entry detection + call resolution.
- Kotlin entry detection + call resolution under the chosen IntelliJ baseline.
- Go entry detection + call resolution with the Go plugin dependency.
- Java ↔ Kotlin analyzer switching.
- Flow Canvas prototype handling ~100 semantic nodes.
- progressive layout without excessive reflow.

This milestone may use disposable prototype code.

### v0.1 — Multi-language Method Flow + Flow Canvas

- IntelliJ IDEA Ultimate plugin skeleton.
- analyzer registry.
- Java analyzer.
- Kotlin analyzer.
- Go analyzer.
- mixed Java/Kotlin traversal.
- shared `FlowFrame` / `FlowNode` model.
- evaluation-order-aware explicit calls.
- resolution + dispatch confidence.
- recursive project-local analysis.
- depth/node limits.
- cycle detection.
- external/unresolved/ambiguous/declared-target states.
- Go goroutine/defer metadata.
- indexing wait + stale source handling.
- cancellation with partial results.
- Flow Canvas in Tool Window.
- nested frame expansion.
- progressive local-first rendering.
- call-site and target navigation.

Detailed semantics are in `plan/V0.1_SPEC.md`.

### v0.2 — Control Flow

- `if / else`.
- Java/Kotlin `switch`/`when`.
- Go `switch` and selected `select` representation where appropriate.
- loops.
- return/throw/panic-related termination.
- JVM try/catch/finally.
- branch merge visualization.
- loop containers.

### v0.3 — Developer Workflow

- Flow Pins.
- Saved Flows.
- Recent analyses.
- Analyze from selected node.
- persistent saved entry points.
- improved progress/status UX.

### v0.4 — Ambiguity and Sharing

- candidate implementation discovery and selective expansion.
- Markdown export.
- Mermaid flowchart export.
- Mermaid sequence export where semantically valid.
- copy structured context for external tools/AI.

### v0.5+ — Advanced Exploration

- Sequence View.
- alternate/free Graph View.
- flow snapshot comparison.
- flow-change detection.
- framework-aware resolution helpers.
- async lanes for Java/Kotlin.
- richer goroutine/channel visualization for Go.
- search/filter.
- selected-path highlighting.
- reverse/caller analysis.
- test discovery.
- Git diff overlay.

---

## 18. Fixed v0.1 Decisions

1. Target IDE is **IntelliJ IDEA Ultimate**.
2. Supported product languages are Java, Kotlin, and Go.
3. Go support may depend on the JetBrains Go plugin being installed.
4. Analyzer dispatch occurs per resolved target so mixed Java/Kotlin flows are possible.
5. Primary UI is a Tool Window.
6. Primary visualization is Flow Canvas, not a plain tree.
7. Default reading direction is top-to-bottom.
8. Root frame is expanded; child frames are collapsed initially.
9. Depth counts callable-body boundaries only.
10. Calls are represented per call site.
11. Node kind, resolution, dispatch confidence, execution mode, and ordering are separate model dimensions.
12. Ordinary resolvable virtual methods may use `DECLARED_TARGET`; genuinely non-selectable continuations use `AMBIGUOUS`.
13. v0.1 is explicit-call focused and does not promise compiler-generated/implicit call reconstruction.
14. Go `go` and `defer` semantics are preserved as execution metadata.
15. Tests and libraries are excluded from recursive analysis by default.
16. One analysis runs per project at a time.
17. Indexing must not be misreported as unresolved code.
18. Relevant source changes invalidate/stale an in-progress analysis.
19. Current Flow does not need to persist across restart in v0.1.

---

## 19. Remaining Open Questions

These remain prototype/implementation decisions rather than product-semantic decisions:

1. Exact minimum IntelliJ IDEA Ultimate build for v0.1.
2. Exact Java/Kotlin PSI/UAST/Analysis API combination per semantic case.
3. Exact Go PSI/API integration and optional dependency packaging arrangement.
4. Canvas technology/layout implementation.
5. Motion amount and layout stabilization heuristics.
6. Stable symbol persistence strategy for future Flow Pins across refactors.
7. Which implicit/compiler-generated language constructs graduate into explicit support after v0.1.
8. Threshold at which large analyzed child frames should require explicit expansion rather than eager background extraction.

Open questions must not redefine the fixed semantics above.

---

## 20. Product Principles

### Explain uncertainty
Never manufacture certainty static analysis does not have.

### Preserve meaning
Execution order, dispatch confidence, execution mode, project boundaries, and later branch structure matter more than raw edge count.

### Visual semantics over decoration
Motion and graphics must communicate code meaning or analysis state.

### Progressive disclosure
Analyze enough to be useful, but do not visually explode everything at once.

### Language-neutral core
Java, Kotlin, and Go share a model and visual grammar while keeping language-specific semantics in analyzers.

### IDE-native navigation
The map is never a dead-end diagram. It must lead back to code.

### Useful without AI
AI interoperability is optional; the core product must remain valuable by itself.

---

## 21. Planning Documents

- `plan/PLAN.md` — product direction, architecture, compatibility, and roadmap.
- `plan/V0.1_SPEC.md` — executable v0.1 semantics and acceptance examples.
- `plan/VISUAL_DESIGN.md` — Flow Lens visual language and interaction rules.
