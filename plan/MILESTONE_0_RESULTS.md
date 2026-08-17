# Milestone 0 — Feasibility Results

Date: 2026-08-17
Branch: `feat/milestone-0`

This document records the evidence for the Milestone 0 gate defined in
`plan/README.md` and `PLAN.md` section 17. Every claim below is backed by a
committed test, a Plugin Verifier report, or a build artifact; items that were
**not** verified are listed explicitly in section 12.

---

## 1. Selected IntelliJ baseline

| Component | Version | Rationale |
|---|---|---|
| IntelliJ IDEA Ultimate | **2026.1.5 (build 261.27258)** | Earliest 261 patch known to load the current marketplace Go plugin for the 261 line (evidence inherited from Repo Lens and re-verified by this build/test matrix). `sinceBuild=261`, `untilBuild=262.*`. |
| JetBrains Go plugin | **261.26222.22** | Resolves and loads against 2026.1.5; Go PSI fixtures pass. |
| Kotlin compiler (Gradle) | **2.3.21** | The bundled Kotlin plugin of 2026.1 carries Kotlin 2.4-line metadata. A compiler reads metadata one minor version ahead, so 2.3.x compiles directly against the bundled plugin **without `-Xskip-metadata-version-check`** (which `API_STABILITY.md` forbids as a production strategy). Verified empirically: the Kotlin analyzer compiles and resolves against the bundled plugin. |
| Kotlin jvm-default mode | **no-compatibility** | Prevents the compiler-generated Java-default-interface bridges that produced Repo Lens's `ToolWindowFactory` verifier warnings. Verified: zero such warnings in compiled output. |
| IntelliJ Platform Gradle Plugin | **2.18.1** (latest 2.x) | |
| Java toolchain | **JDK 21** | Required by the 2026.1 platform. |
| Gradle | **9.7.0** | |

K2 note: the platform **refuses to load** an optional `org.jetbrains.kotlin`
descriptor unless the main `plugin.xml` declares
`<supportsKotlinPluginMode supportsK2="true"/>`. This was observed directly
(descriptor silently skipped, warning only in `idea.log`) and is now covered by
fixture tests that require the Kotlin analyzer to be registered.

## 2. Module boundary

`core` (pure Kotlin, no IntelliJ types) / `plugin` (all platform integration),
as required by `IMPLEMENTATION_GUARDRAILS.md` section 2. Enforced by:

- `core` has no IntelliJ dependency at all (compileOnly stdlib only);
- `OptionalDependencyIsolationTest` scans compiled bytecode: no class outside
  `analysis/go` references `com.goide`, none outside `analysis/kotlin`
  references `org.jetbrains.kotlin.psi`.

## 3. Java feasibility — CONFIRMED

`JavaFlowAnalyzerTest` (17 tests, real PSI + real JDK):

- caret → entry detection (method body/signature; abstract/native and
  non-method positions rejected);
- evaluation order: `save(convert(load()))` → `load, convert, save`;
  `foo(a(), b())` → `a, b, foo`; chained `source().transform().save()` →
  `source, transform, save`;
- duplicate call sites stay distinct events sharing one target symbol;
- constructor calls (`new`) resolve as EXACT constructors;
- static/private → EXACT; ordinary virtual → DECLARED_TARGET; bodiless
  interface method → AMBIGUOUS (not recursable);
- JDK call → EXTERNAL + LIBRARY origin, terminal;
- unresolved call retained without aborting siblings;
- `if` marks control flow simplified; lambda bodies are boundaries.

## 4. Kotlin feasibility — CONFIRMED

`KotlinFlowAnalyzerTest` (16 tests, real Kotlin PSI/resolution under K2):

- top-level / member / extension / constructor calls resolve; nested and
  chained evaluation order preserved;
- final member → EXACT, `open` member → DECLARED_TARGET, interface fun →
  AMBIGUOUS;
- **synthetic policy**: data-class `copy()` / `componentN()` are never
  recursable (classified through provenance unwrapping, not name denylists);
  explicitly authored members of the same data class stay analyzable;
- property access creates no call events; lambdas are boundaries;
- resolution uses `mainReference.resolve()` (plain PSI reference resolution,
  K2-compatible); no Analysis API session code and no experimental API was
  needed for this scope.

## 5. Java ↔ Kotlin feasibility — CONFIRMED

`MixedLanguageFlowTest`: Java root → Kotlin body → Java body in one model.
Light-method unwrapping (`KtLightMethod` → authored `KtNamedFunction`) is done
generically via `navigationElement` + registry probing, so the Java analyzer
never references Kotlin types. The registry dispatches per resolved
declaration, exactly as `V0.1_SPEC.md` section 3 requires.

## 6. Go feasibility — CONFIRMED (plugin-present path)

`GoFlowAnalyzerTest` (11 tests, real Go PSI):

- package functions and receiver methods resolve EXACT with correct symbols;
- nested-call evaluation order preserved;
- `go f()` → `executionMode=GOROUTINE`; `defer f()` → `DEFERRED`;
  `defer consume(produce())` orders `produce` (SYNC) before `consume`
  (DEFERRED);
- function literals are boundaries and not entry points;
- `switch` marks control flow simplified;
- interface method specs classify AMBIGUOUS; `builtin.go` targets BUILT_IN.

Optional dependency: Go analyzer is contributed only by `flow-lens-go.xml`;
bytecode isolation is test-enforced (section 2). The **runtime absence path**
(IDE without the Go plugin installed) is structurally guaranteed but was not
exercised in a live IDE in this milestone — see section 12.

## 7. Flow Canvas feasibility — CONFIRMED

Custom-painted canvas (no JTree), `CanvasViewModelTest` +
`FlowCanvas`/`CanvasViewModelBuilder`:

- root frame expanded, child frames collapsed by default; inline nested
  expansion below the owning card with retained parent context;
- distinct treatments (shape/border/glyph/badge, not color alone) for entry,
  exact, declared-target, ambiguous, unresolved, external (local
  PROJECT-BOUNDARY marker on the connector), built-in, cycle, and limit;
- goroutine/defer badges; non-deterministic ordering renders a dashed
  connector;
- **100 semantic nodes**: layout in well under 500 ms (measured ~single-digit
  ms in tests), zero card overlaps, stable top-to-bottom keyboard order;
- appending events leaves existing card positions bit-identical (progressive
  updates do not shuffle the map);
- pan (drag + scroll pane), zoom (Ctrl/Cmd+wheel, 0.25–2.5×), fit-to-view,
  click selection, double-click/Enter target navigation, Space
  expand/collapse, arrow-key selection.

## 8. Progressive analysis and lifecycle — CONFIRMED

`FlowAnalysisServiceLifecycleTest` (9 tests, real service, real coroutines):

- local-first/breadth-first bias: strict depth-ordered frame queue (unit
  tested) — later shallow frames always run before earlier deep frames;
- progressive snapshots per frame; per-run identity; **stale events from an
  old run are dropped at the service gate** (tested with a forged late event);
- starting a new run cancels and replaces the previous one; navigation
  handles are served only for the current run;
- cycles produce CYCLE back-references (path-based, not global dedup);
- depth limit: beyond-limit calls carry a visible limit marker and are not
  entered; node limit: reserved final slot always fits the LIMIT node, result
  becomes TRUNCATED with all completed nodes intact;
- unsupported caret → single FAILED result with one bundle-keyed diagnostic;
- progress stages are real (STARTING → WAITING_FOR_INDEXES →
  EXTRACTING_ROOT → RESOLVING_ROOT_CALLS → ANALYZING_CHILD_FRAMES →
  terminal); no percentages exist anywhere in the model.

Threading: one bounded cancellable `smartReadAction` per frame, read lock
released and cancellation observed between frames; no analysis work on the
EDT; UI collection throttled (StateFlow conflation + 80 ms delay).

## 9. Plugin Verifier — CONFIRMED

Run immediately after the ToolWindow skeleton (per the plan gate), and re-run
after fixes. Final state against **IU-261.27258.48** and **IU-262.9437.185**:

```
Compatibility problems        0
Internal API usages           0
Scheduled-for-removal usages  0
Obsolete API usages           0
Deprecated API usages         0
Experimental API usages       0
Unknown warning provenance    0
Verdict                       Compatible (both targets)
```

First-run findings, both `FLOW_LENS`-provenance and both fixed:

1. `JBUI.scale(float)` deprecated → migrated to `JBUIScale.scale`.
2. Missing K2 `supportsKotlinPluginMode` declaration → added to the main
   descriptor (also unblocked the optional Kotlin descriptor, see section 1).

No compiler-generated bridge warnings appeared (jvm-default no-compatibility
working as intended — the Repo Lens failure mode did not recur).

## 10. Localization, logging, packaging

- EN/JA bundles from the first UI commit; `FlowLensBundleParityTest` enforces
  key parity, enum label coverage, diagnostic key existence, non-blank values.
- Run logs contain only runId/status/counts/elapsed — no source text, no
  symbol names (verified in the test-run `idea.log`).
- `flow-lens-0.0.1.zip` builds; contents inspected: exactly `core` and
  `plugin` jars, no duplicated Kotlin stdlib/coroutines, descriptors and both
  bundles packaged.
- CI (GitHub Actions): build + all tests + Plugin Verifier + artifact upload,
  with the Repo Lens disk-pressure mitigations.

## 11. Performance observations

- Engine time for fixture flows (2–5 frames): 1–13 ms per run (from run
  summary logs; monotonic clock).
- 100-node canvas layout: single-digit ms in tests (bounded assert < 500 ms).
- Test suite: core 21 tests + plugin 67 tests, all green; full local build
  ~20 s warm.

Real-repository benchmarks (medium Java project, mixed JVM project, Go
project) are **not yet measured** — scheduled for v0.1 hardening per
`TEST_STRATEGY.md` Layer E.

## 12. Known gaps and unresolved risks

1. **Manual sandbox exercise not performed.** This milestone ran in a
   headless session: the packaged ZIP was built and inspected but not
   installed into a live IDE, and `runIde` interaction (canvas feel, focus,
   themes, Japanese UI) is unverified. This is the largest remaining gate
   item; everything needed (`./gradlew :plugin:runIde`, the ZIP) is ready.
2. **Go-plugin-absent runtime path untested in a live IDE.** Structurally
   guaranteed (optional descriptor + bytecode isolation test), but the
   "IDE without Go still starts and analyzes Java/Kotlin" scenario should be
   confirmed once manually.
3. **Stale-source (P) and indexing-wait (O) cases are design-verified, not
   integration-tested.** The per-frame `PsiModificationTracker` guard and
   `smartReadAction` waiting exist and are exercised implicitly, but no
   deterministic automated test mutates source mid-run or toggles dumb mode.
   Needs a deterministic hook (e.g. injectable frame barrier) in v0.1.
4. **Depth-limit marker is a per-call badge, not a separate LIMIT node.**
   A beyond-depth call carries `flowlens.limit=depth` metadata and a visible
   badge; the reserved LIMIT node is used only for the node budget. This
   satisfies "visible limit marker" without spamming one LIMIT node per call;
   if v0.1 wants a dedicated node per frame, it is a small engine change.
5. **Node budget reserves the final slot**, so a flow of exactly `maxNodes`
   ordinary events truncates at `maxNodes-1` + LIMIT. Within spec wording,
   recorded here as the chosen interpretation.
6. **Kotlin dispatch-confidence heuristics are syntactic** (modifier-based).
   Cases like `override` members of final classes or smart-cast receivers may
   deserve Analysis API-backed classification in v0.1+; current behavior is
   conservative (DECLARED_TARGET rather than EXACT).
7. **`untilBuild=262.*`** claims 2026.2 compatibility; verifier passes against
   262.9437 today, but the 262 line should be re-verified as it stabilizes.
8. Kotlin 2.4.x compiler (when the 2026.x platform bundles newer metadata)
   will eventually be needed; the current 2.3.21↔2.4-metadata window is
   one-directional and must be revisited on any baseline advance.

## 13. Recommendation

**GO for v0.1 implementation**, with the section 12 items carried into the
v0.1 plan — in particular the manual sandbox pass and the deterministic
stale/indexing lifecycle tests, which should land before any v0.1 feature
hardening is considered complete.
