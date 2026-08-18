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

## 20. Conditional calls are marked, not branched (v0.1)

A call inside an `if`/`when`/`switch` branch, a loop body, a `catch` clause, a
short-circuit right operand, or a Go case clause is discovered and marked as
conditional. The renderer then avoids the ordinary certain connector for it and
the result stays `controlFlowIncomplete`.

This is deliberately weaker than a branch model: Flow Lens does not yet show
which branch a call belongs to, does not merge branches, and does not evaluate
conditions. Branch structure is v0.2.

Headers that always execute — the `if` condition, loop and `for`/`range`
clauses, a `switch` subject, the left operand of `&&`/`||`, `try` and `finally`
blocks — are not marked conditional.

---

## 21. Depth-limit and node-limit markers differ (v0.1)

Two different limits produce two different markers:

- **Depth limit**: the call that could not be entered keeps its own card and
  carries an explicit continuation marker with an "increase depth" hint. This
  shows *which* call was not followed without spending node budget.
- **Node limit**: one reserved `LIMIT` node terminates the result and the run
  is reported `TRUNCATED`.

A frame with many blocked calls therefore shows many depth markers but never
consumes the semantic-node budget for them.

---

## 22. Dispatch confidence is scoped to the project

A Java call is reported as a declared target only when some class in the project
actually overrides the resolved method. Java methods are virtual by default, so
without this check every ordinary call would carry a runtime-override warning and
the marker would stop meaning anything.

The consequence is that substitution which leaves no source behind — dynamic
proxies, generated bytecode, dependency injection, class loading, instrumentation
— is not reflected in dispatch confidence. That is the same boundary as §2:
Flow Lens describes the source it can see.

Kotlin needs no equivalent check because its declarations are final unless marked
`open`, `override`, or abstract.

---

## 23. Dispatch confidence for Kotlin is syntactic (v0.1)

Kotlin dispatch classification is based on declaration modifiers and the call
form (`open`/`override`/`abstract`, interface membership, explicit `super.`).
It does not use flow-sensitive information such as smart casts or sealed-type
exhaustiveness, so some calls that are effectively exact at runtime are reported
as `DECLARED_TARGET`.

The bias is deliberate: over-reporting uncertainty is acceptable, claiming false
certainty is not.

---

## 24. `Include libraries` rarely enables recursion in practice

The setting allows traversal into library targets, but a compiled dependency
usually exposes no analyzable body, so enabling it changes little unless the
library ships sources that the IDE resolves to. External calls remain navigable
either way.

---

## 25. Structure is not reachability (v0.2)

v0.2 shows which events are alternatives and which repeat. It does not decide
whether a path can actually be taken.

The consequences:

- events after a structure are shown even when every branch returns or throws;
- loop containers do not express iteration counts, and a loop that never runs
  looks the same as one that always does;
- a condition is never evaluated, folded, or eliminated.

---

## 26. Control flow v0.2 does not represent (v0.2)

These constructs are visible in the source but not in the map.

The first three raise the control-flow-incomplete disclosure, which is what that
warning now means:

- Java `switch` fall-through: each case reads as independent, so a case without
  `break` does not show the next case running after it;
- `break` and `continue` out of a loop are not drawn as edges. A `break` that
  merely ends a switch or select case raises nothing, because the case boundary
  already expresses it;
- short-circuit operands (`a() && b()`), elvis (`?:`), and safe calls (`?.`)
  keep the v0.1 conditional marker instead of becoming structures — but only
  when a call actually sits in the part that may be skipped. `a != null && b > 0`
  hides no call, so it discloses nothing.

The rest are silent, because there is no call the map is failing to show:

- a `catch` section is not connected to the `throw` that could reach it;
- Go `select` shows its cases, not which one a scheduler picks, nor blocking or
  channel direction;
- Go `panic` is an ordinary built-in call rather than a terminator, so it does
  not visibly end its branch the way `return` does.

---

## 27. Branch labels and condition summaries are source text, truncated (v0.2)

A section label (`case 1`, `catch (IOException)`) and a structure's condition
summary are short excerpts of the source, collapsed to a single line and cut at
a fixed length. A long condition is therefore recognizable but not readable in
full; the card navigates to the source, which is the readable copy.

They are display data only. Like every other source-derived string, they are
never written to logs or diagnostics (`IMPLEMENTATION_GUARDRAILS.md` §13).

---

## 28. A structure card names its kind in one vocabulary (v0.2)

Every language shares one word per structure kind, so a Kotlin `when` and a Go
type switch both render as `switch`, and a Kotlin `if` expression as `if`. The
card names the kind of structure rather than the keyword that produced it.

The consequence is that the label does not always match the keyword under the
caret. A Go `select` is the one exception, because it chooses among
communications rather than among values and reading it as a `switch` would be
misleading.

---

## 29. A stored entry does not follow a refactor (v0.3)

Pins, saved flows, and recents store a symbol key — language, qualifier, name,
parameter types — plus a project-relative path. That survives editing anywhere
in the file, moving the declaration within it, and any change that leaves the
signature and path alone.

It does not survive a rename, a signature change, or a moved file. The entry is
then reported as not found and is disabled; nothing tries to match a similar
name. Guessing would make every other mark untrustworthy, which is the whole
value of a mark.

Overloads are distinguished by parameter types as written, so changing a
parameter type breaks the identity even when the code is otherwise unchanged.

---

## 30. A pin marks a callable, not a call site (v0.3)

Pinning `charge()` marks every card whose target is `charge()`, in every flow.
There is no way to mark one call to it and not another.

Pins are project-scoped. They are stored in a project-level file, so a team
that commits it will share the file's contents, but v0.3 provides no way to
export, merge, or reconcile entries between people, and no handling of two
developers editing the list. Sharing as a feature is v0.4.

---

## 31. The budget bar is not a progress bar (v0.3)

An analysis explores; the total is unknown until it finishes. The bar shows how
much of the node budget is spent, which is the only denominator that exists
during a run. A full bar means truncation is imminent, not that the work is
nearly done.

---

## 32. Recents are a capped list, not a history (v0.3)

The ten most recent entry points, ordered by recency, with no search and no
record of when or how often a flow was analyzed. A cancelled run is not
recorded, so the list describes finished work only.

---

## 33. Symbol keys changed in v0.3 hardening

A symbol key now includes enough of the file's location to be unique within the
project: a Go function is keyed by its directory rather than its package name,
because two directories can both be `package main`, and a Java or Kotlin
declaration with no qualified name falls back to the project-relative file path
rather than the bare file name.

Pins and saved flows stored before that change do not match the new keys. They
are reported as not found, which is the same behavior as any other stale entry,
and can be deleted and re-created.

---

## 34. Maintenance rule

Whenever dogfooding, fixture testing, Plugin Verifier, or a user report reveals a surprising but currently accepted behavior:

1. decide whether it is a bug or limitation;
2. if bug, add regression evidence and fix it;
3. if accepted limitation, add/update this file;
4. ensure UI/documentation does not imply stronger support than reality.

Known limitations are part of the product contract, not a dumping ground for unresolved defects.