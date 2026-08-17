# Flow Lens Known Limitations

## 1. Purpose

This document records deliberate product/analysis limitations as they become known.

A limitation should be documented when it is accepted, not discovered by users as an unexplained surprise.

The initial entries below are v0.1 design boundaries already implied by the planning documents.

---

## 2. Static analysis is not runtime tracing

Flow Lens shows a bounded static approximation of callable flow.

It does not guarantee the exact runtime path.

Runtime behavior may differ because of:

- polymorphism/overrides;
- dependency injection;
- reflection;
- generated proxies;
- framework/runtime configuration;
- native code;
- dynamic loading;
- callbacks/event systems not explicitly modeled;
- concurrency/scheduling.

The UI must expose uncertainty through dispatch/order/execution states instead of hiding it.

---

## 3. v0.1 primarily follows explicit calls

v0.1 does not promise complete compiler-desugared/implicit call reconstruction.

Examples intentionally outside default v0.1 semantics unless explicitly implemented and tested:

- Kotlin property getter/setter calls implied by syntax;
- delegated properties;
- Kotlin default-argument machinery;
- operator/desugared calls;
- compiler bridges;
- implicit initialization chains;
- framework-generated proxy methods.

---

## 4. Generated and synthetic declarations are conservative stops

JetBrains PSI/UAST can expose compiler-generated/light declarations that look callable.

v0.1 normally does not recurse into targets classified as synthetic/generated rather than physical authored project source.

This may omit some executable implementation detail, but it prevents phantom flows such as generated Kotlin data-class members from being presented as authored code.

Generated-code support may be added later as an explicit capability.

---

## 5. Control flow is simplified in v0.1

Full branch/loop/control-flow visualization is a v0.2 feature.

v0.1 may discover calls inside conditions/loops but must mark the frame/result as simplified and must not visually claim a single unconditional runtime sequence.

Short-circuit/conditional language constructs must be treated conservatively when full path semantics are unavailable.

---

## 6. Polymorphic dispatch is conservative

Flow Lens distinguishes:

- exact target;
- declared target where runtime override may differ;
- ambiguous target with no responsible single continuation.

v0.1 stops at genuinely ambiguous calls rather than selecting one implementation for visual convenience.

Candidate enumeration/selection is planned for a later milestone.

---

## 7. External/library calls stop by default

Dependency, JDK/runtime, SDK, and other non-project targets are terminal by default.

The IDE may still navigate to external source where available, but Flow Lens does not recursively expand library bodies in the default v0.1 analysis.

---

## 8. Tests are excluded from recursive traversal by default

Production-code analysis does not automatically follow calls into test-only source unless the user enables test inclusion.

A user may still explicitly start an analysis from a supported test method/function according to v0.1 rules.

---

## 9. Go concurrency is only partially modeled in v0.1

Flow Lens preserves known metadata for:

- `go f()` as goroutine execution;
- `defer f()` as deferred execution.

v0.1 does not promise a complete goroutine scheduler model, channel communication graph, `select` runtime behavior, or full defer-stack execution model.

The minimal visual treatment must still avoid presenting known goroutine/deferred calls as ordinary synchronous continuations.

---

## 10. Java/Kotlin async frameworks are not fully modeled in v0.1

Executors, CompletableFuture chains, callbacks, Kotlin coroutines, reactive streams, and framework async dispatch are not guaranteed to receive first-class async-flow semantics in v0.1.

If the analyzer does not know the asynchronous boundary confidently, it must not invent one.

---

## 11. Lambda/anonymous/local callable bodies are traversal boundaries in v0.1

Lambdas, anonymous functions, Go function literals, and similar nested callable bodies are not independent entry points in v0.1 and are not automatically flattened into the enclosing method’s ordinary synchronous flow.

Future callback/lambda semantics should model the invocation boundary explicitly rather than treating nested source text as if it executes immediately.

---

## 12. Analysis is intentionally bounded

Default bounds:

- max callable depth: 3;
- max persistent semantic nodes: 100.

A truncated analysis shows an explicit limit marker. More code may exist beyond that marker.

The limits are product safeguards, not a claim that deeper code is irrelevant.

---

## 13. Index-dependent resolution may wait

When required IntelliJ indexes are unavailable, Flow Lens may wait for smart/indexed state rather than producing misleading unresolved calls.

This can delay analysis immediately after project open, branch changes, dependency changes, or large external file operations.

---

## 14. Source changes can invalidate an active result

If source changes during analysis and the existing model can no longer be trusted, Flow Lens marks the run stale/cancels affected traversal and asks for re-analysis.

It does not attempt to merge arbitrary old/new PSI snapshots into one supposedly current map.

---

## 15. Go support depends on the JetBrains Go plugin

The Flow Lens plugin itself targets IntelliJ IDEA Ultimate.

Go analysis is available only when a compatible JetBrains Go plugin is installed/enabled for the selected IDE build.

Missing Go capability is a normal degraded state; Java/Kotlin functionality should remain available.

---

## 16. Other JetBrains IDEs are not v0.1 compatibility targets

v0.1’s supported host is IntelliJ IDEA Ultimate.

GoLand, Android Studio, JetBrains Client/remote-only configurations, and other IDE products may work partially but are not release-contract targets until explicitly added and verified.

---

## 17. Durable Flow Pin identity will be best-effort

Flow Pins/Saved Flows are planned for later milestones.

When implemented, durable identity must not depend only on line number/source offset. Refactors can still make symbol recovery ambiguous, especially across language/plugin changes.

The product should surface a missing/moved pin honestly rather than silently navigating to a guessed symbol.

---

## 18. Dynamic plugin unload/update is not promised

v0.1 does not promise that Flow Lens can be disabled/updated without restarting the IDE.

Any future dynamic-unload claim requires real lifecycle testing, not only a verifier heuristic.

---

## 19. Visual layout is semantic, not an execution proof

Flow Canvas layout choices help users understand structure. Position alone must not be interpreted as proof of runtime order unless the corresponding ordering/dispatch semantics are deterministic.

Approximate/unspecified ordering must use a visibly different treatment.

---

## 20. Maintenance rule

Whenever dogfooding, fixture testing, Plugin Verifier, or a user report reveals a surprising but currently accepted behavior:

1. decide whether it is a bug or limitation;
2. if bug, add regression evidence and fix it;
3. if accepted limitation, add/update this file;
4. ensure UI/documentation does not imply stronger support than reality.

Known limitations are part of the product contract, not a dumping ground for unresolved defects.