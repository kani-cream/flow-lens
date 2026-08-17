# Flow Lens Visual Design

## 1. Purpose

Flow Lens is not intended to look like a conventional call graph with identical rectangles connected by arrows.

The visualization must communicate **code meaning**, **analysis state**, and **uncertainty** through a consistent visual language.

The user should feel that they are exploring how a piece of code unfolds, not inspecting a generic graph database.

This document defines that visual language.

---

## 2. Visual Principles

### 2.1 Semantic before decorative

Every major visual distinction should answer a code-understanding question.

Examples:

- Is this the entry point?
- Is this call inside or outside the project?
- Did the flow split?
- Is the order certain?
- Is this async?
- Is the target unresolved or merely ambiguous?
- Did analysis stop because of a configured limit?

Animation that communicates none of these should be minimized.

### 2.2 A flow should feel explorable

The user should be able to start with a readable overview and progressively open deeper method/function internals without losing context.

### 2.3 Do not encode everything with color

Color may reinforce meaning, but shape, iconography, border treatment, labels, position, and connector style must also communicate state so the map remains understandable across themes and accessibility settings.

### 2.4 Respect IntelliJ themes

Flow Lens must feel integrated with IntelliJ rather than looking like an embedded web page from an unrelated product.

Use theme-aware IDE colors/tokens wherever practical.

### 2.5 Preserve orientation

The default reading direction is **top to bottom**.

Automatic layout should avoid unnecessary left/right flipping during progressive updates. New analysis results should extend or gently reflow the existing map rather than making the user's mental model jump around.

---

## 3. Canvas Structure

Conceptual Tool Window:

```text
┌───────────────────────────────────────────────────────────┐
│ FLOW LENS                                  Fit  −  100% + │
├───────────────────────────────────────────────────────────┤
│ Current: PaymentController.purchase()     Depth 3 · 26    │
├───────────────────────────────────────────────────────────┤
│                                                           │
│                 FLOW CANVAS                               │
│                                                           │
│                        ENTRY                              │
│                          │                                │
│                          ▼                                │
│                       CALL                                │
│                          │                                │
│                          ▼                                │
│                       ...                                 │
│                                                           │
├───────────────────────────────────────────────────────────┤
│ Selected node details / status                            │
└───────────────────────────────────────────────────────────┘
```

The canvas is the dominant surface. Sidebars or details must not reduce it to a narrow tree view.

---

## 4. Entry Point

The entry point must be visually stronger than ordinary calls.

Concept:

```text
╭────────────────────────────────────────────╮
│ ▶ PaymentController.purchase              │
│   ENTRY · Java · depth 0                   │
╰────────────────────────────────────────────╯
```

Requirements:

- Larger or more prominent than normal call cards.
- Clearly labeled as the starting point.
- Shows containing type/package context when useful.
- Can be double-clicked to navigate to source.
- Acts as the visual root of the current map.

---

## 5. Method / Function Call Card

Default collapsed card:

```text
╭──────────────────────────────╮
│ ⚡ charge()                  │
│ PaymentService          D1   │
╰──────────────────────────────╯
```

The card should prioritize:

1. Callable name.
2. Containing type/package context.
3. Small state/depth metadata.

Do not permanently fill every card with file paths, signatures, line numbers, counts, and badges. That creates visual noise.

Full details belong in selected state or a details area.

Selected concept:

```text
╭──────────────────────────────────────────────╮
│ ⚡ charge(user, order)                       │
│ PaymentService                               │
│                                              │
│ PROJECT · depth 2 · 4 calls inside           │
│ PaymentService.java:84                       │
╰──────────────────────────────────────────────╯
```

---

## 6. Nested Expansion — "Enter the Method"

A core Flow Lens interaction is opening a call without navigating away from the map.

Collapsed:

```text
╭────────────────────────────╮
│ PaymentService.execute()   │
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

Deeper nested expansion:

```text
╭──────────────────────────────────────────────────────────╮
│ PaymentService.execute()                                 │
│                                                          │
│ gateway.charge()                                         │
│ ╭──────────────────────────────────────────────────────╮ │
│ │ buildRequest()                                       │ │
│ │      │                                               │ │
│ │      ▼                                               │ │
│ │ HttpClient.post()                                    │ │
│ │      │                                               │ │
│ │      ▼                                               │ │
│ │ EXTERNAL · okhttp                                    │ │
│ ╰──────────────────────────────────────────────────────╯ │
╰──────────────────────────────────────────────────────────╯
```

The effect should feel like revealing implementation depth, not opening unrelated floating windows.

Requirements:

- Expansion/collapse should retain the parent context.
- Layout change should be predictable.
- Deep expansion must eventually use progressive disclosure rather than allowing infinitely huge nested cards.
- User can choose `Analyze from Here` to make any supported node the new root if the current map becomes too deep.

---

## 7. Connectors

Connectors communicate more than adjacency.

### Normal synchronous flow

```text
│
▼
```

### Return/reference back to existing path

Use a visually distinct return/back-reference treatment rather than duplicating large subtrees when that would reduce clarity.

### Cycle

```text
A()
 │
 ▼
B()
 │
 ╰──────── ↩ cycle to A()
```

### Approximate/unspecified ordering

When ordering is not fully known, connector or group styling should communicate that the layout is a visualization choice rather than a guaranteed execution sequence.

Never use an ordinary solid sequential connector to imply certainty that the analyzer does not have.

---

## 8. Conditions and Branches

Full branch visualization begins in v0.2, but its visual grammar is defined now.

Concept:

```text
                     │
                     ▼
          ◆ payment.isRequired() ?
              ╱                 ╲
             ╱                   ╲
        REQUIRED                SKIP
           │                      │
           ▼                      ▼
   ╭──────────────╮       ╭──────────────╮
   │ charge()     │       │ skip()       │
   ╰──────────────╯       ╰──────────────╯
           │                      │
           ╰──────────┬───────────╯
                      ▼
               ╭────────────╮
               │ save()     │
               ╰────────────╯
```

Requirements:

- Condition is not rendered as an ordinary call card.
- Branch labels are visible near the split.
- Reconvergence is visible when statically meaningful.
- Branch widths should be balanced where practical.
- Long condition expressions should be summarized with full source available in details/tooltip.

### v0.1 fallback

When v0.1 encounters control flow it does not structurally model, the canvas must show a clear compact status:

```text
⚠ Control flow simplified
```

Calls must not be visually presented as a proven unconditional runtime path without this disclosure.

---

## 9. Loops

Loops should be containers, not endless repeated call chains.

Concept:

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
                    │
                    ▼
               after loop
```

Requirements:

- Loop boundary is visually explicit.
- Iteration is represented symbolically rather than duplicating the body.
- Calls inside the body can still be expanded.

---

## 10. Async and Parallel Work

Async work must never look like an ordinary synchronous continuation when Flow Lens knows it is async.

Concept:

```text
                         │
                ┌────────┴─────────┐
                │                  │
                ▼                  ▼
            main flow          async lane
                │                  ⋮
                │          ╭──────────────────╮
                │          │ sendNotification │
                │          ╰──────────────────╯
                │
                ▼
            saveOrder()
```

Future language-specific examples:

- Java: executor/future boundary.
- Kotlin: coroutine `launch` / `async` boundary.
- Go: `go f()` goroutine boundary.

For Go v0.1, a call launched by `go` should at minimum retain an async/goroutine marker even if a full lane is deferred.

---

## 11. Ambiguous Target

Ambiguity is a valid analysis result, not a failure.

Collapsed v0.1 concept:

```text
╭──────────────────────────────╮
│ ◇ paymentGateway.charge()   │
│ AMBIGUOUS TARGET             │
╰──────────────────────────────╯
```

Future candidate view:

```text
               ◇ 3 POSSIBLE TARGETS
                  ╱      │      ╲
                 ╱       │       ╲
        StripeGateway  PayPayGateway  MockGateway
              ○              ○             ○
```

Requirements:

- Do not style it like a fatal error.
- Communicate possibility/fan-out.
- Do not automatically present one candidate as definitive.

---

## 12. Unresolved Target

Concept:

```text
╭──────────────────────────────╮
│ ? dynamicCall()             │
│ UNRESOLVED                   │
╰──────────────────────────────╯
       ⋯
```

The connector visually ends rather than implying a known continuation.

The call-site itself remains navigable.

---

## 13. External Project Boundary

External code should feel like crossing the edge of the project.

Concept:

```text
╭──────────────────────────────╮
│ HttpClient.post()            │
│ project code                 │
╰──────────────────────────────╯
              │
              ▼

┄┄┄┄┄┄┄┄┄ PROJECT BOUNDARY ┄┄┄┄┄┄┄┄┄

              │
              ▼
╭──────────────────────────────╮
│ okhttp3.Call.execute()       │
│ EXTERNAL                     │
╰──────────────────────────────╯
```

Requirements:

- Boundary is more important than merely using a different card color.
- External nodes are terminal by default.
- External source navigation may be allowed when the IDE can navigate there, but recursion remains disabled unless explicitly enabled in a later feature.

---

## 14. Depth

Depth should be understandable without turning the canvas into a numeric tree dump.

Cards may show a small `D0`, `D1`, `D2` marker.

Optional navigator concept:

```text
DEPTH
● 0  Entry
● 1  Calls
● 2  Expanded
● 3  Expanded
○ 4  Hidden
```

Depth is method/function recursion depth, not visual nesting count.

Deeper content may become more compact, but readability must remain more important than squeezing everything onto one screen.

---

## 15. Limit Reached

Do not silently end a connector.

Concept:

```text
╭──────────────────────────────╮
│ … depth limit reached       │
│ Expand with higher limit     │
╰──────────────────────────────╯
```

Node-limit concept:

```text
╭──────────────────────────────╮
│ … node limit reached        │
│ Showing first 100 nodes      │
╰──────────────────────────────╯
```

The visual should suggest that more code exists without pretending it was analyzed.

---

## 16. Analysis in Progress

Analysis progress should be visible directly on the map.

Initial:

```text
╭──────────────────────────────╮
│ ▶ purchase()                │
╰──────────────────────────────╯
             │
             ◌ resolving…
```

Later:

```text
purchase()
    │
    ▼
validate()
    │
    ▼
PaymentService.execute()
    │
    ◌ resolving…
```

Status counters:

```text
26 nodes · 12 project · 3 external · 1 ambiguous
```

Requirements:

- Newly resolved content appears progressively.
- Existing nodes should not jump unnecessarily.
- Progress state is cancellable.
- Cancelled maps remain visible with a partial-result indicator.

---

## 17. Motion

Motion should help the user understand what changed.

Good uses:

- New node gently appearing from its parent connector.
- Expanded method container growing from the selected card.
- Branches separating when a condition is resolved.
- Viewport easing just enough to keep newly expanded content visible.

Avoid:

- Constant pulsing after analysis is complete.
- Decorative particles.
- Large automatic zoom changes.
- Re-layout animations that make the entire map drift on every inserted node.
- Motion that slows navigation.

Respect reduced-motion/accessibility preferences when available.

---

## 18. Selection and Focus

Selection should make one path easier to follow.

When a node is selected:

- Emphasize the node.
- Emphasize the path from root to that node where practical.
- De-emphasize unrelated branches slightly without making them disappear.
- Show full source/signature/location details outside the normal compact card.

Keyboard navigation must be possible for core interactions.

---

## 19. Zoom, Pan, and Fit

The canvas must support large-enough flows without becoming unusable.

Required or strongly preferred:

- Scroll/pan.
- Zoom in/out.
- Fit current flow to viewport.
- Center selected node.
- Restore a readable zoom when starting a new analysis.

Avoid forcing the user to precisely drag tiny scrollbars through a large flow.

---

## 20. Theme and Accessibility

Requirements:

- Light and dark IntelliJ themes.
- State is not encoded with color alone.
- Adequate contrast for text and connectors.
- Icons/shapes remain understandable under common color-vision deficiencies.
- Text scales consistently with IntelliJ UI settings where feasible.
- Reduced-motion preference is respected where the platform exposes it.

---

## 21. Visual State Matrix

| Semantic state | Primary visual treatment | Connector behavior | v0.1 |
|---|---|---|---|
| Entry | Prominent root card | Starts main path | Required |
| Project call | Compact callable card | Normal flow | Required |
| Constructor/function variant | Callable card + semantic icon/label | Normal flow | Required |
| Selected | Expanded emphasis/details | Highlight path | Required |
| Resolving | Temporary progress endpoint | In-progress connector | Required |
| External | Beyond project boundary | Stops by default | Required |
| Unresolved | Unknown/broken endpoint | Stops | Required |
| Ambiguous | Possibility/fan-out treatment | Stops in v0.1 | Required |
| Cycle | Back-reference marker | Points to prior path | Required |
| Depth/node limit | Continuation/truncation marker | Stops | Required |
| Simplified control flow | Warning/status treatment | Conservative layout | Required |
| Condition | Diamond/split | Multiple branches | v0.2 |
| Branch merge | Merge junction | Reconverges | v0.2 |
| Loop | Container + iteration marker | Back edge | v0.2 |
| Async | Parallel lane/fork | Async edge | Later / metadata in v0.1 |

---

## 22. v0.1 Visual Acceptance Criteria

v0.1 visual implementation is acceptable only if:

1. It does not look or behave like a plain call-tree widget.
2. Entry, project call, external, unresolved, ambiguous, cycle, limit, and resolving states are visually distinguishable without relying only on color.
3. The map grows progressively during analysis.
4. Existing content remains reasonably stable as new nodes arrive.
5. A selected node can reveal additional detail without making all nodes permanently verbose.
6. The user can navigate from the map back to source.
7. Large-enough flows can be scrolled/panned and fitted to the viewport.
8. Unsupported v0.1 control-flow semantics are explicitly disclosed instead of visually implied as certain.
9. Java, Kotlin, and Go use the same visual grammar.
10. Light and dark themes remain readable.

---

## 23. Visual Identity Summary

Flow Lens should visually communicate this progression:

```text
CODE ENTRY
    ↓
FLOW UNFOLDS
    ↓
METHODS OPEN
    ↓
BOUNDARIES / UNCERTAINTY BECOME VISIBLE
    ↓
USER FOLLOWS THE PATH BACK INTO CODE
```

The goal is not to draw more arrows.

The goal is to make static code structure feel like a navigable map whose visual vocabulary explains what kind of flow the analyzer actually found.
