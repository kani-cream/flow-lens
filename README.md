# Flow Lens

**An IntelliJ IDEA Ultimate plugin that turns a selected function or method into
an explorable static execution-flow map.**

> Select a method. See where the code flows.

Flow Lens follows reachable explicit calls within bounded limits, preserves
meaningful evaluation order, exposes uncertainty instead of hiding it, and
renders the result as a navigable Flow Canvas that always leads back to source.

It is not a repository-wide analyzer and not a runtime tracer. It is a focused
source-understanding tool for reading unfamiliar code, debugging, review,
onboarding, and investigation.

## What it does

- **Three languages.** Java, Kotlin, and Go (Go requires the JetBrains Go
  plugin). Analysis crosses Java/Kotlin boundaries: the analyzer is chosen per
  resolved target, not once for the root.
- **Real evaluation order.** `save(convert(load()))` reads as `load → convert →
  save`, not as AST order. Chained calls follow the receiver chain, arguments
  evaluate left to right.
- **Honest uncertainty.** Every call carries a resolution status
  (project / external / unresolved / built-in) and a dispatch confidence
  (exact / declared target / ambiguous / unknown). A virtual call that may be
  overridden at runtime says so; an interface with no responsible single
  continuation stops rather than guessing.
- **Execution semantics preserved.** Go `go f()` stays a goroutine and
  `defer f()` stays deferred; neither is drawn as an ordinary synchronous
  continuation.
- **No phantom flow.** Compiler-generated declarations — Kotlin data-class
  `copy()`/`componentN()` and friends — are classified by provenance and never
  entered as if they were code you wrote.
- **Progressive and bounded.** The root picture completes before deeper frames
  grow, within a depth and node budget, with explicit markers wherever analysis
  stopped.
- **Local-first.** No AI service, no server, no network calls, and logs that
  contain counts and timings rather than your source.

## Using it

1. Put the caret inside a Java, Kotlin, or Go function or method.
2. Run **Analyze Flow** from the editor context menu, or press the run button in
   the **Flow Lens** tool window.
3. Explore: click a card to see its details, expand a call to open the analyzed
   body inline, double-click (or Enter) to jump to the target declaration, and
   use **Open Call Site** to jump to the invocation instead.

Keyboard: `↑`/`↓` move, `→`/`←` expand and collapse, `Space` toggles,
`Enter` opens the target, `Shift+Enter` opens the call site, `Esc` clears
selection.

Settings live under **Settings → Tools → Flow Lens**: max call depth (default 3),
max semantic nodes (default 100), and whether traversal may enter test or
library sources (both off).

## Building

```bash
./gradlew build            # compile + core unit tests + IDE fixture tests
./gradlew :plugin:runIde   # sandbox IDE with the plugin installed
./gradlew buildPlugin      # distribution ZIP in plugin/build/distributions
./gradlew :plugin:verifyPlugin
```

Requirements: JDK 21. The build targets IntelliJ IDEA Ultimate 2026.1.5
(build 261) and is verified against the 262 line.

Sample projects for manual verification live in `samples/manual-jvm` and
`samples/manual-go`; each file is annotated with the behavior it demonstrates.

## Project layout

```text
core     language-neutral semantic model, traversal policy, budgets, cycles
plugin   IntelliJ integration: analyzers, analysis service, Flow Canvas, settings
plan     normative planning set (spec, visual design, guardrails, test strategy)
samples  manual verification corpora
```

`core` has no IntelliJ dependency, and a bytecode test enforces that no
mandatory class references Kotlin or Go plugin types, so Flow Lens starts and
works when an optional language plugin is absent.

## Documentation

The `plan/` directory is normative, not aspirational:

| Document | Role |
|---|---|
| `plan/V0.1_SPEC.md` | v0.1 analysis semantics and acceptance cases |
| `plan/VISUAL_DESIGN.md` | Flow Canvas visual language and interaction rules |
| `plan/IMPLEMENTATION_GUARDRAILS.md` | Engineering and lifecycle constraints |
| `plan/API_STABILITY.md` | Platform API policy and verifier gates |
| `plan/TEST_STRATEGY.md` | Required validation layers |
| `plan/KNOWN_LIMITATIONS.md` | Deliberately accepted limitations |
| `plan/MILESTONE_0_RESULTS.md` | Feasibility evidence and baseline selection |

## Status

v0.1 in development. Java, Kotlin, Go, and mixed Java/Kotlin analysis work
end to end; Plugin Verifier reports zero compatibility, deprecated,
experimental, and internal API usages against both the minimum and latest
supported builds.
