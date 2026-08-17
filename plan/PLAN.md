# Flow Lens Development Plan

## 1. Product Statement

**Flow Lens is an IntelliJ Platform plugin that turns a selected function or method into an explorable static execution-flow map.**

The user chooses one entry point. Flow Lens follows the reachable code within bounded limits, preserves meaningful execution order, exposes uncertainty instead of hiding it, and presents the result as a visual map that can be explored and navigated back to source.

> Select a method. See where the code flows.

The product is intentionally not a repository-wide analyzer and not a runtime tracer. It is a focused source-understanding tool for reading unfamiliar code, debugging, code review, onboarding, and investigation.

---

## 2. Official Target Languages

Flow Lens targets these languages as first-class product languages:

- **Java**
- **Kotlin**
- **Go**

The visualization and persistence layers must remain language-neutral. Language-specific behavior belongs behind analyzer adapters.

Conceptual architecture:

```text
                    FlowAnalysisEngine
                           │
          ┌────────────────┼────────────────┐
          │                │                │
   Java Analyzer     Kotlin Analyzer     Go Analyzer
          │                │                │
          └────────────────┼────────────────┘
                           ↓
                  FlowAnalysisResult
                           ↓
                     Flow Canvas
```

Java and Kotlin may share JVM/UAST utilities where that improves consistency, but language-specific PSI or analysis APIs may be used where they produce more accurate results. Go support should be isolated behind its own analyzer so the core model and UI do not depend on Go-specific PSI types.

A language must not be advertised as supported unless entry-point detection, call extraction, resolution, source navigation, cycle handling, and core Flow Canvas rendering work reliably for that language.

---

## 3. Product Goals

### Core goals

- Analyze exactly one user-selected method/function as the entry point.
- Follow project-local calls recursively within explicit limits.
- Preserve **evaluation/execution order**, not merely lexical or declaration order.
- Represent the same target being called multiple times as separate call-site events.
- Show external, unresolved, ambiguous, cyclic, and truncated paths explicitly.
- Navigate from any source-backed visual element to code.
- Keep analysis cancellable and responsive on real-world projects.
- Work locally without requiring an AI service or external server.
- Make the analysis visibly progress as the Flow Canvas grows.

### Product differentiation

Flow Lens must not become a prettier Call Hierarchy.

The primary experience is a **semantic Flow Map**: calls, boundaries, branches, loops, async work, ambiguity, and depth are represented differently so that the user can understand the character of the flow at a glance.

Visual quality is a core product requirement, not a later polish task.

---

## 4. Non-Goals

The initial product does not attempt to provide:

- Whole-repository exhaustive control-flow analysis.
- Guaranteed runtime-accurate execution traces.
- Full symbolic execution.
- Runtime instrumentation or profiling.
- Perfect resolution of reflection, framework magic, dynamic proxies, runtime DI configuration, or generated behavior.
- Automatic selection of one implementation when multiple targets are plausible.
- A replacement for IntelliJ Bookmarks or Call Hierarchy.

Flow Lens must prefer an explicit `AMBIGUOUS`, `UNRESOLVED`, `EXTERNAL`, or `LIMIT_REACHED` result over false certainty.

---

## 5. Entry Point and Analysis Semantics

Every analysis starts from exactly one function-like declaration.

For the initial implementation, supported entry points are:

- Java methods and constructors.
- Kotlin named functions and constructors where resolvable.
- Go functions and methods.

The caret may be anywhere inside the supported declaration or its signature. The containing supported declaration becomes the entry point.

Unsupported constructs should fail clearly rather than guessing.

### Evaluation order

"Source order" means the language's meaningful expression evaluation order where Flow Lens can determine it.

Example:

```java
save(convert(load(id)));
```

Expected conceptual order:

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

A simple PSI preorder that produces `foo → a → b` is incorrect for Flow Lens.

### Call site versus symbol

A visual flow node represents an **event at a call site**, not a globally unique method symbol.

```text
FlowNode
  ├─ callSite
  ├─ targetSymbol
  ├─ sourceLocation
  └─ nodeType
```

If one symbol is called three times, the map contains three call nodes even though they may reference the same target symbol.

---

## 6. Shared Analysis Model

The renderer consumes a language-neutral model.

Core concepts:

```text
FlowAnalysisResult
FlowGraph
FlowNode
FlowEdge
FlowSymbol
FlowSourceLocation
```

Planned node types:

- `ENTRY`
- `METHOD_CALL`
- `CONSTRUCTOR_CALL`
- `CONDITION`
- `SWITCH`
- `LOOP`
- `RETURN`
- `THROW`
- `TRY`
- `CATCH`
- `FINALLY`
- `ASYNC_BOUNDARY`
- `EXTERNAL_CALL`
- `AMBIGUOUS_CALL`
- `UNRESOLVED_CALL`
- `CYCLE`
- `LIMIT_REACHED`
- `CANCELLED`

Planned edge semantics:

- Normal execution.
- Call.
- Return.
- True branch.
- False branch.
- Switch/case branch.
- Loop body/back edge.
- Exception path.
- Async fork.
- Ambiguous candidate.

The internal model must be rich enough for future Flow, Graph, Sequence, and export views without re-running analysis for each renderer.

---

## 7. Resolution Rules

### Project-local

Calls resolved to project content may be recursively analyzed up to configured limits.

### External

Dependency and SDK/library calls stop by default and render as terminal calls beyond a visible project boundary.

### Ambiguous

If static analysis cannot determine one target with sufficient confidence, Flow Lens must not silently pick an implementation.

In v0.1 the call is marked `AMBIGUOUS` and recursive traversal stops there. Later versions may display and selectively expand candidates.

### Unresolved

If the IDE cannot resolve the target, create an `UNRESOLVED_CALL` tied to the call site and continue analysis of other paths.

### Cycles

Cycle detection is traversal-path based, not global. The same symbol may legitimately appear in separate branches or separate call sites.

---

## 8. Analysis Limits

The default settings are fixed as:

```text
Max method depth          3
Max total nodes         100
Project sources only     ON
Include tests           OFF
Include libraries       OFF
```

**Depth counts method/function boundaries only.** Structural visual nodes such as conditions do not consume recursive depth.

When a limit is reached, Flow Lens inserts a visible `LIMIT_REACHED` terminal marker. It must never silently truncate a map.

Only one active analysis is allowed per project in v0.1. Starting a new analysis cancels the previous one.

If the user cancels analysis, already-produced nodes remain visible and the result is marked partial/cancelled.

---

## 9. Flow Canvas — Primary Visualization

The primary v0.1 visualization is **Flow Canvas**, hosted in the Flow Lens Tool Window.

A plain Swing tree or a linear `A → B → C` visualization is not considered the target user experience.

The canvas begins with a vertical top-to-bottom reading direction but uses semantic visual structures rather than identical boxes and arrows.

Core visual language:

- Entry point: prominent entry card.
- Method/function call: compact callable card.
- Nested expansion: a method can visually open to reveal its internal flow.
- Condition: split/fan-out structure.
- Loop: bounded container with a visible iteration/back-edge concept.
- Async: separate lane/fork instead of a normal synchronous connector.
- External code: rendered beyond a project boundary.
- Ambiguous target: fan-out/possibility treatment, not an error treatment.
- Unresolved target: broken/unknown endpoint.
- Depth/node limit: explicit continuation/truncation marker.
- Cycle: back-reference instead of recursively duplicating forever.

The visual grammar is defined separately in `plan/VISUAL_DESIGN.md`.

### Progressive analysis

The canvas should grow as analysis completes:

```text
Entry
  ↓
resolved call
  ↓
resolving…
```

Counters update during analysis, for example:

```text
26 nodes · 12 project calls · 3 external · 1 ambiguous
```

This is functional feedback, not decorative animation.

---

## 10. Tool Window and Interaction

v0.1 uses a dedicated **Tool Window**, not an editor tab.

Primary interactions:

- `Flow Lens > Analyze Execution Flow` from the editor context menu.
- `Analyze Current Method` in the Tool Window.
- Single click: select a visual element and show details.
- Double click / Enter: navigate to source.
- Context action: `Open Source`.
- Context action: `Analyze from Here`.
- Context action: `Pin Method` when Flow Pins are available.
- Expand/collapse nested method flow.
- Fit canvas to viewport.
- Zoom/pan if required by the chosen canvas implementation.

The current flow is ephemeral in v0.1 and is not required to survive IDE restart.

---

## 11. Flow Pins and Saved Flows

These are workflow features, not generic bookmarks.

### Flow Pins

A Flow Pin is a named function/method entry point used for quick navigation and re-analysis.

Possible metadata:

- User-defined name.
- Stable symbol reference where possible.
- File path/signature fallback.
- Optional note.
- Date pinned.

### Saved Flows

A Saved Flow stores:

- Entry point.
- User-defined name.
- Analysis settings.
- Later: candidate choices for ambiguous calls.
- Later: collapsed/expanded UI state.

The graph itself may be regenerated rather than permanently persisted.

---

## 12. Language-Specific Future Semantics

The shared model should anticipate asynchronous and language-specific constructs without pretending they are synchronous.

Examples:

### Java

- Executor / CompletableFuture patterns may later create `ASYNC_BOUNDARY` nodes where reliably recognizable.

### Kotlin

- `launch`, `async`, coroutine boundaries, and suspend-related flow may later receive explicit async semantics.

### Go

- `go f()` should eventually render as a goroutine fork/lane rather than a normal call.
- Channel-related flow may be explored later, but complete concurrency modeling is not an initial goal.

These advanced semantics are outside v0.1 unless implementation proves straightforward. The analyzer architecture must not block them.

---

## 13. Architecture

```text
flow-lens
├─ analysis
│  ├─ core
│  ├─ java
│  ├─ kotlin
│  ├─ go
│  ├─ resolution
│  ├─ traversal
│  └─ limits
├─ model
│  ├─ FlowAnalysisResult
│  ├─ FlowGraph
│  ├─ FlowNode
│  ├─ FlowEdge
│  ├─ FlowSymbol
│  └─ FlowSourceLocation
├─ ui
│  ├─ toolwindow
│  ├─ canvas
│  ├─ layout
│  ├─ components
│  └─ details
├─ navigation
├─ persistence
│  ├─ pins
│  └─ savedflows
├─ export
│  ├─ markdown
│  └─ mermaid
└─ settings
```

Key boundaries:

1. Analyzer code may depend on language-specific PSI/API types.
2. `model` must not.
3. Rendering consumes only the language-neutral analysis result.
4. Export consumes the analysis model, never the rendered UI.
5. Visual layout and visual semantics are separate from source-resolution logic.

---

## 14. Performance and Threading

- Never run meaningful analysis synchronously on the EDT.
- Use IntelliJ read-action and cancellation semantics correctly.
- Avoid whole-project scans during normal analysis.
- Reuse indexes/reference-resolution facilities.
- Enforce node/depth limits during traversal, not after building a huge graph.
- Memoize repeated resolution work within one analysis when safe.
- Do not recursively expand libraries by default.
- Partial results must remain structurally valid while analysis is running.

---

## 15. Roadmap

### v0.1 — Multi-language Method Flow + Flow Canvas

Goal: prove the complete core experience on Java, Kotlin, and Go.

Scope:

- IntelliJ plugin skeleton.
- Java analyzer.
- Kotlin analyzer.
- Go analyzer.
- Current method/function detection.
- Calls and constructors/functions in evaluation order.
- Recursive project-local analysis.
- Depth = method/function boundary.
- Node limit.
- Cycle detection.
- External terminal nodes.
- Unresolved terminal nodes.
- Ambiguous terminal nodes without automatic implementation selection.
- One active analysis per project.
- Cancellation with partial results.
- Flow Canvas in Tool Window.
- Semantic node/card styling.
- Progressive canvas growth and counters.
- Source navigation.

Detailed semantics and acceptance cases are defined in `plan/V0.1_SPEC.md`.

### v0.2 — Control Flow

- `if / else`.
- Java/Kotlin switch/when concepts.
- Go switch/select exploration as appropriate.
- loops.
- `return`.
- `throw` / panic-related representation where appropriate.
- basic exception structures for JVM languages.
- branch merge visualization.
- loop containers.

### v0.3 — Developer Workflow

- Flow Pins.
- Saved Flows.
- Recent analyses.
- Analyze from selected node.
- Persistent saved entry points.
- Improved progress/status UX.

### v0.4 — Ambiguity and Sharing

- Candidate implementation discovery and selective expansion.
- Markdown export.
- Mermaid flowchart export.
- Mermaid sequence export where valid.
- Copy structured context for external AI/tools.

### v0.5+ — Advanced Exploration

- Sequence View.
- Alternative/free Graph View.
- Flow snapshot comparison.
- Flow-change detection.
- Framework-aware resolution helpers.
- Async visualization for Java/Kotlin.
- Goroutine visualization for Go.
- Search/filter in large flows.
- Selected-path highlighting.
- Reverse/caller analysis.
- Test discovery.
- Git diff overlay.

---

## 16. Fixed v0.1 Decisions

These are no longer open design questions:

1. Supported product languages are Java, Kotlin, and Go.
2. v0.1 aims to prove basic method/function flow for all three.
3. The primary UI is a Tool Window.
4. The primary visualization is Flow Canvas, not a plain tree.
5. The default reading direction is top-to-bottom.
6. Depth counts method/function boundaries only.
7. Ambiguous targets are not automatically expanded in v0.1.
8. Calls are represented per call site, not deduplicated by target symbol.
9. Evaluation order is preferred over naive PSI lexical traversal.
10. Tests and libraries are excluded from recursive analysis by default.
11. One analysis runs per project at a time.
12. The current flow does not need to persist across restart in v0.1.

---

## 17. Remaining Open Questions

These should be settled by focused prototypes because the correct answer depends on IntelliJ platform behavior or rendering feasibility:

1. Exact PSI/UAST/API combination for each Java/Kotlin semantic case.
2. Exact Go PSI/API integration boundary and plugin dependency arrangement.
3. Canvas technology/layout engine capable of smooth nested expansion without fighting IntelliJ UI conventions.
4. How aggressively layout should animate when nodes are inserted or expanded.
5. Stable symbol persistence strategy for Flow Pins across refactors.
6. Exact language-specific handling of lambdas, callbacks, coroutines, goroutines, and generated/framework code.
7. Whether large flows should switch from automatic progressive layout to explicit user expansion at a threshold.

Open questions must not redefine the product semantics already fixed above.

---

## 18. Product Principles

### Explain uncertainty
Never manufacture certainty static analysis does not have.

### Preserve meaning
Execution order, branch structure, async boundaries, project boundaries, and ambiguity matter more than raw edge count.

### Visual semantics over decoration
Animations and graphics must communicate analysis state or code meaning. Avoid motion that exists only to look busy.

### Progressive disclosure
Show a comprehensible map first and allow deeper exploration without overwhelming the canvas.

### Language-neutral core
Java, Kotlin, and Go should converge into one shared flow model and one visual language.

### IDE-native navigation
The map is an alternate way to understand code, never a dead-end diagram. Every meaningful source-backed element should lead back to code.

### Useful without AI
AI integration is optional future interoperability. The product must be valuable on its own.

---

## 19. Planning Documents

- `plan/PLAN.md` — product direction, architecture, and roadmap.
- `plan/V0.1_SPEC.md` — executable v0.1 semantics and acceptance examples.
- `plan/VISUAL_DESIGN.md` — Flow Lens visual language and interaction rules.
