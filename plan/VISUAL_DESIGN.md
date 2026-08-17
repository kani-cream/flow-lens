# Flow Lens Visual Design

## 1. Purpose

Flow Lens must not look like a conventional call graph made of identical rectangles joined by arrows.

The visualization communicates **code meaning**, **analysis state**, **dispatch confidence**, **execution mode**, and **uncertainty** through a consistent visual language.

The user should feel that code is unfolding into an explorable map, not that they are inspecting a generic graph database.

---

## 2. Visual Principles

### Semantic before decorative

Every meaningful visual distinction should answer a code-understanding question:

- Where did analysis start?
- Is this inside the project?
- Is this target exact, only the declared implementation, ambiguous, or unresolved?
- Is execution synchronous, goroutine/async, or deferred?
- Is ordering certain?
- Is this method body currently expanded?
- Did analysis stop because of a limit, cancellation, indexing, or stale source?

Animation without semantic value should be minimized.

### Explorable, not exhaustive

The canvas presents a comprehensible overview first. Deeper callable bodies are revealed progressively.

### Do not encode state with color alone

Use shape, iconography, border treatment, labels, connectors, grouping, and position. Color only reinforces meaning.

### IntelliJ-native

Respect IntelliJ IDEA Ultimate light/dark themes, UI scaling, keyboard navigation, focus conventions, and accessibility behavior.

### Stable orientation

Default reading direction is top-to-bottom. Progressive analysis should not repeatedly flip branches or cause large map jumps.

---

## 3. Canvas Structure

Conceptual Tool Window:

```text
┌────────────────────────────────────────────────────────────┐
│ FLOW LENS                                   Fit  − 100%  + │
├────────────────────────────────────────────────────────────┤
│ PaymentController.purchase()        D3 · 26 nodes · LIVE   │
├────────────────────────────────────────────────────────────┤
│                                                            │
│                        FLOW CANVAS                         │
│                                                            │
│                         ENTRY                              │
│                           │                                │
│                           ▼                                │
│                       CALL CARDS                           │
│                                                            │
├────────────────────────────────────────────────────────────┤
│ Selected node details / diagnostics / actions             │
└────────────────────────────────────────────────────────────┘
```

The canvas is the dominant surface. Details should not reduce it to a narrow tree-like column.

---

## 4. Initial Expansion Policy

Initial presentation must avoid showing the complete recursive result as one giant map.

Default:

```text
Root FlowFrame            EXPANDED
Direct root calls         VISIBLE
Child target FlowFrames   COLLAPSED
Depth 2+                  COLLAPSED
```

Concept:

```text
╭────────────────────────────────────────╮
│ ▶ PurchaseController.purchase()        │
│ ENTRY · Java · D0                      │
╰────────────────────────────────────────╯
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
   validate()    charge()     save()
                  [5 calls]
```

Expanding `charge()` opens its analyzed `targetFrame` inline.

This policy is mandatory for v0.1 so that a 100-node analysis does not become a 100-node wall.

---

## 5. Entry Point

The root is stronger than ordinary calls.

```text
╭────────────────────────────────────────────╮
│ ▶ PaymentController.purchase              │
│ ENTRY · Java · depth 0                     │
╰────────────────────────────────────────────╯
```

Requirements:

- visually prominent.
- clearly identified as root.
- language/context available without excessive detail.
- double click / Enter opens entry declaration.

---

## 6. Method / Function Call Card

Collapsed project call:

```text
╭──────────────────────────────╮
│ ⚡ charge()                  │
│ PaymentService          D1   │
╰──────────────────────────────╯
```

Priorities:

1. callable name.
2. containing type/package context.
3. compact depth/state indicator.

Avoid permanent file paths, full signatures, line numbers, and many badges on every card.

Selected state may reveal:

```text
╭──────────────────────────────────────────────╮
│ ⚡ charge(user, order)                       │
│ PaymentService                               │
│ PROJECT · D1 · DECLARED TARGET               │
│ 4 calls available inside                     │
│                                              │
│ Open Target · Open Call Site · Expand        │
╰──────────────────────────────────────────────╯
```

---

## 7. Nested Expansion — Enter the Method

A call card can reveal its analyzed child `FlowFrame` without leaving the map.

Collapsed:

```text
╭────────────────────────────╮
│ PaymentService.execute()   │
│ 3 calls inside             │
╰────────────────────────────╯
```

Expanded:

```text
╭──────────────────────────────────────────────────╮
│ PaymentService.execute()                         │
│                                                  │
│   validate()                                     │
│      │                                           │
│      ▼                                           │
│   createTransaction()                            │
│      │                                           │
│      ▼                                           │
│   gateway.charge()                               │
│                                                  │
╰──────────────────────────────────────────────────╯
```

Requirements:

- retain parent context.
- predictable re-layout.
- expansion should grow from the selected card.
- deep content remains progressively collapsible.
- `Analyze from Here` can promote a selected callable to the new root.

### One box per call

An expanded call is **one container**: the call card is its header and the
analyzed body is drawn inside it. The body is never a second box placed below
the call.

This is a correctness requirement, not decoration. The canvas expresses two
different relationships — the sequence of events inside a frame, and the
containment of a callee's body — and both are vertical. Drawing the body as a
sibling box makes the sequence connector to the next call appear to start at the
last nested call, implying a call that does not exist:

```text
save()                    save()  ──┐
  │                       ╰ audit()  │   ← wrong: reads as audit() → validate()
audit()        instead of            │
  │                       validate() ┘
validate()
```

Rules:

- the container's header names the call exactly once;
- the body is inset on both sides so nesting is visible without a second frame
  header;
- sequence connectors attach to the container's outer edge, so a connector always
  means "the next event in this frame";
- a collapsed call stays a plain card.

---

## 8. Connectors and Ordering

### Deterministic synchronous flow

```text
│
▼
```

### Approximate ordering

Use a visually weaker/different connector or group treatment.

```text
┊
▽
```

Exact styling is implementation-defined, but the ordinary solid sequential connector must not imply certainty when `orderingStatus != DETERMINISTIC`.

### Cycle

```text
A()
 │
 ▼
B()
 │
 ╰──────── ↩ cycle to A()
```

A cycle is a back-reference, not another recursively duplicated body.

---

## 9. Dispatch Confidence

Dispatch confidence is separate from resolution location.

### EXACT

Normal project-call treatment. No warning required.

### DECLARED_TARGET

A concrete declaration body is being followed, but runtime override may select another implementation.

Concept:

```text
╭──────────────────────────────╮
│ ⚡ execute()                 │
│ PaymentService               │
│ ◇ declared target            │
╰──────────────────────────────╯
```

Requirements:

- not styled as an error.
- visually distinct enough that users do not mistake it for guaranteed dispatch.
- details explain `Runtime override may differ`.
- child frame may still be expanded.

### AMBIGUOUS

No responsible single continuation is selected.

```text
╭──────────────────────────────╮
│ ◇ paymentGateway.charge()   │
│ AMBIGUOUS TARGET             │
╰──────────────────────────────╯
```

v0.1 stops here.

Future candidate exploration:

```text
               ◇ 3 POSSIBLE TARGETS
                  ╱      │      ╲
                 ╱       │       ╲
             Stripe    PayPay     Mock
```

---

## 10. Unresolved Target

```text
╭──────────────────────────────╮
│ ? dynamicCall()             │
│ UNRESOLVED                   │
╰──────────────────────────────╯
       ⋯
```

The connector ends visibly.

Default navigation opens the call site because there is no known target declaration.

---

## 11. External Project Boundary

External code must feel like crossing out of project-owned source.

The boundary is **local to a call edge**, not necessarily one canvas-wide horizontal line.

Preferred concept:

```text
╭──────────────────────────────╮
│ HttpClient.post()            │
│ project code                 │
╰──────────────────────────────╯
              │
              ║ PROJECT BOUNDARY
              ▼
╭──────────────────────────────╮
│ okhttp3.Call.execute()       │
│ EXTERNAL                     │
╰──────────────────────────────╯
```

This model works inside nested frames and multiple unrelated external branches.

Requirements:

- boundary is more meaningful than just a card color.
- recursion stops by default.
- external source navigation may still work where IntelliJ can navigate.

---

## 12. Execution Mode

Execution mode must not be lost in the visual layer.

### SYNC

Normal connector.

### GOROUTINE / ASYNC

At minimum in v0.1, show a semantic badge/connector distinction when known.

Go concept:

```text
main flow
   │
   ├──────── ⚡ goroutine ────────► notify()
   │
   ▼
save()
```

A full parallel lane may come later.

### DEFERRED

Go `defer` must not look like immediate synchronous continuation.

```text
╭──────────────────────────────╮
│ ↩ cleanup()                 │
│ DEFERRED                     │
╰──────────────────────────────╯
```

The visual grammar should communicate "scheduled for later execution", without pretending Flow Lens has fully modeled the runtime defer stack in v0.1.

### UNKNOWN

Do not invent async/sync semantics beyond what the analyzer knows.

---

## 13. Conditions and Branches

Full branch visualization begins in v0.2.

Concept:

```text
                     │
                     ▼
          ◆ payment.isRequired() ?
              ╱                 ╲
        REQUIRED                SKIP
           │                      │
           ▼                      ▼
       charge()                 skip()
           │                      │
           ╰──────────┬───────────╯
                      ▼
                    save()
```

Requirements:

- condition uses a different semantic shape than a call.
- branch labels visible.
- reconvergence visible when statically meaningful.
- long conditions summarized; source available in details.

### v0.1 fallback

When v0.1 encounters unsupported structural control flow:

```text
⚠ Control flow simplified
```

The map must not present flattened branch calls as a proven unconditional sequence.

---

## 14. Loops

v0.2 grammar:

```text
╭────────────────────────────────────────╮
│ ↻ FOR EACH order IN orders            │
│                                        │
│    validate(order)                     │
│         │                              │
│         ▼                              │
│    process(order)                      │
│         │                              │
│         ╰──────────── ↻ next           │
╰────────────────────────────────────────╯
```

Loops are containers, not repeated duplicated chains.

---

## 15. Limits

Depth limit:

```text
╭──────────────────────────────╮
│ … depth limit reached       │
│ Increase depth to continue   │
╰──────────────────────────────╯
```

Node limit:

```text
╭──────────────────────────────╮
│ … node limit reached        │
│ Showing up to 100 nodes      │
╰──────────────────────────────╯
```

Never silently end a connector where more code exists but was not analyzed.

---

## 16. Analysis Progress and Lifecycle

### Resolving

```text
purchase()
    │
    ◌ resolving…
```

Counters may show:

```text
26 nodes · 12 project · 3 external · 1 ambiguous
```

### Waiting for indexes

```text
◌ Waiting for IntelliJ indexes…
```

This must not appear as unresolved code.

### Cancelled

Keep the partial map visible with a clear result-level indicator.

### Stale

When relevant source changed during analysis:

```text
⚠ Source changed · Re-analyze
```

Do not keep presenting the result as current.

### Failed

A concise result-level failure may be shown while retaining safe partial content.

---

## 17. Progressive Layout

User-visible progression is local-first.

Preferred sequence:

```text
1. Root frame appears.
2. Root direct calls appear.
3. Root-level picture stabilizes.
4. Child frames become available for expansion.
5. Deeper analysis continues within limits.
```

Avoid deeply growing the first child while later root calls are still missing.

Existing node positions should remain as stable as practical when new content arrives.

---

## 18. Selection, Details, and Navigation

When a node is selected:

- emphasize it.
- emphasize root-to-node context where practical.
- slightly de-emphasize unrelated branches without hiding them.
- show full signature/location/status in details.

Source actions:

```text
Open Target
Open Call Site
Expand / Collapse
Analyze from Here
```

Default:

- resolved call double click / Enter → target declaration.
- unresolved call double click / Enter → call site.
- entry → entry declaration.

Keyboard navigation must support core actions.

---

## 19. Motion

Good motion:

- new node gently emerging from a parent connector.
- child frame expanding from its call card.
- branch separation when structural control flow becomes available.
- small viewport easing to keep new content visible.

Avoid:

- constant pulsing after completion.
- decorative particles.
- full-map drift on each new node.
- large automatic zoom changes.
- motion that delays navigation.

Respect reduced-motion settings where available.

---

## 20. Zoom, Pan, and Fit

Required/strongly preferred:

- scroll/pan.
- zoom in/out.
- fit current visible flow.
- center selected node.
- sane zoom reset for new root analysis.

The user should not need to drag tiny scrollbars across a huge canvas.

---

## 21. Theme and Accessibility

Requirements:

- IntelliJ light/dark themes.
- semantic state not encoded by color alone.
- adequate text/connector contrast.
- usable under common color-vision deficiencies.
- text follows IDE UI scaling where practical.
- keyboard focus visible.
- reduced motion honored where exposed by the platform.

---

## 22. Visual State Matrix

| Semantic state | Primary treatment | v0.1 |
|---|---|---|
| Entry | Prominent root card | Required |
| Project call / EXACT | Compact callable card | Required |
| Project call / DECLARED_TARGET | Callable card + uncertainty marker | Required |
| Ambiguous | Possibility endpoint | Required |
| Unresolved | Unknown/broken endpoint | Required |
| External | Local project-boundary crossing | Required |
| Built-in | Compact terminal operation | Optional/Supported |
| Constructor | Callable card + constructor semantics | Required |
| Child FlowFrame | Inline expandable container | Required |
| Resolving | Temporary progress endpoint | Required |
| Waiting for indexes | Result-level waiting state | Required |
| Cancelled | Partial-result status | Required |
| Stale | Re-analysis status | Required |
| Cycle | Back-reference | Required |
| Limit | Explicit continuation/truncation marker | Required |
| GOROUTINE | Async/goroutine semantic marker | Required metadata + minimal visual |
| DEFERRED | Deferred semantic marker | Required metadata + minimal visual |
| Simplified control flow | Warning/status treatment | Required |
| Condition / branch | Split grammar | v0.2 |
| Loop | Container + back-edge | v0.2 |
| Full async lane | Parallel lane | Later |

---

## 23. v0.1 Visual Acceptance Criteria

v0.1 visual implementation is acceptable only if:

1. it does not look or behave like a plain call-tree widget.
2. root, exact project call, declared-target call, external, unresolved, ambiguous, cycle, limit, and resolving states are distinguishable without color alone.
3. root frame is initially expanded and child frames are collapsed.
4. child methods can open inline as nested frames.
5. analysis grows progressively using a local-first presentation.
6. existing content remains reasonably stable as new results arrive.
7. project boundary is represented locally and remains valid inside nested frames.
8. Go goroutine/deferred metadata receives at least a minimal semantic visual treatment.
9. indexing, cancelled, and stale states are not confused with unresolved code.
10. selection exposes both target and call-site navigation where available.
11. unsupported v0.1 control-flow semantics are explicitly disclosed.
12. Java, Kotlin, and Go use the same core visual grammar.
13. light and dark themes remain readable.
14. flows can be panned/scrolled and fitted to the viewport.

---

## 24. Visual Identity Summary

Flow Lens should communicate this progression:

```text
CODE ENTRY
    ↓
ROOT FLOW APPEARS
    ↓
CALLS BECOME RESOLVED / UNCERTAIN / EXTERNAL
    ↓
USER OPENS METHOD FRAMES
    ↓
DEEPER FLOW UNFOLDS WITHOUT LOSING CONTEXT
    ↓
USER JUMPS BACK TO TARGET OR CALL SITE
```

The goal is not to draw more arrows.

The goal is to make static code structure feel like a navigable map whose visual vocabulary explains what the analyzer actually knows.
