# Flow Lens API Stability Policy

## 1. Purpose

Flow Lens depends heavily on IntelliJ Platform language, PSI, navigation, threading, and ToolWindow APIs. API stability is therefore a product-quality requirement, not merely a build concern.

The project must avoid repeating the Repo Lens 1.0.0 situation where Marketplace compatibility verification succeeded but the public version page still showed deprecated/experimental API warnings.

The target is:

> Prefer stable public IntelliJ Platform APIs. Keep deprecated and unstable API usage at or as close to zero as realistically possible, and inspect compiled output rather than only handwritten source.

This document is normative for implementation and release review.

---

## 2. Target Host and exact baseline

The primary v0.1 host is:

- **IntelliJ IDEA Ultimate**

The exact minimum supported IntelliJ IDEA Ultimate **patch/build** is fixed during Milestone 0 and then becomes part of the build/verification matrix.

Do not select only a broad line such as `2026.2` and assume every patch in that line can load the required language plugins. Repo Lens had to pin a later 2026.1 patch because current Marketplace language-plugin versions required it.

Milestone 0 must select a mutually compatible set of:

- IntelliJ IDEA Ultimate build;
- Java toolchain required by that platform;
- Kotlin compiler/plugin/API integration;
- JetBrains Go plugin version;
- IntelliJ Platform Gradle Plugin version.

Go support additionally depends on a compatible JetBrains Go plugin at runtime.

---

## 3. API usage policy

### Stable public API

Preferred and unrestricted when compatible with the supported IDE range.

Use stable public APIs whenever a practical implementation exists.

### Internal API

Examples include APIs annotated/classified as internal such as `@ApiStatus.Internal` or platform implementation classes not intended for third-party plugins.

**Policy: prohibited for normal product code.**

If a required feature appears impossible without internal API access, reconsider the feature boundary or implementation approach before introducing the dependency.

### Scheduled-for-removal API

Examples include `@ApiStatus.ScheduledForRemoval` and deprecated APIs explicitly marked for removal.

**Policy: prohibited.**

Do not introduce new usage. Existing usage discovered during development must be migrated before release unless the feature is removed/deferred.

### Obsolete API

Examples include `@ApiStatus.Obsolete`.

**Policy: prohibited for new code.**

Use the documented replacement.

### Deprecated API

**Target: zero usages.**

Do not introduce a deprecated API when a supported replacement exists.

A temporary compatibility exception requires the exception process in this document and must be isolated.

### Experimental API

Examples include `@ApiStatus.Experimental`.

**Target: zero usages where practical.**

Experimental APIs may change/disappear outside normal compatibility guarantees. They are not acceptable merely to simplify implementation.

Language integrations such as Kotlin or Go may expose a required capability only through an API JetBrains still marks experimental. In that case an explicitly approved, isolated exception is allowed.

### Other constrained APIs

Respect contracts including:

- `@ApiStatus.NonExtendable`;
- `@ApiStatus.OverrideOnly`;
- extension-point lifecycle restrictions;
- dynamic-plugin restrictions;
- availability-by-platform-version contracts.

Compiling successfully does not make contract-invalid API usage acceptable.

---

## 4. Compiled-bytecode rule — lesson from Repo Lens

Plugin Verifier evaluates the produced plugin classes and dependency signatures, not just source lines.

Repo Lens demonstrated that Kotlin can generate compatibility/default-interface bridge methods which the developer did not explicitly write. Those generated methods caused the verifier to report deprecated/experimental `ToolWindowFactory` usages even though the source only implemented the intended factory method.

Flow Lens therefore treats **compiled output as the source of truth for compatibility warnings**.

Rules:

1. Run Plugin Verifier immediately after the minimal ToolWindow/plugin skeleton exists.
2. Re-run it after compiler/JVM-default/Kotlin-toolchain changes.
3. Inspect each warning’s actual bytecode/source provenance.
4. Do not dismiss a warning because no matching handwritten call exists.
5. Prefer preventing compiler-generated compatibility bridges when a stable modern compiler mode supported by the chosen baseline can do so.

For the selected Kotlin toolchain, evaluate the stable modern JVM-default mode appropriate to that version (for example a no-compatibility mode when supported) and verify ToolWindow behavior is unchanged. Do not copy a compiler flag blindly across Kotlin versions.

---

## 5. Warning provenance

Not every verifier warning necessarily originates from Flow Lens source.

Classify warnings as:

```text
FLOW_LENS
COMPILER_GENERATED_FLOW_LENS
LANGUAGE_PLUGIN_SIGNATURE
PLATFORM_TRANSITIVE
UNKNOWN
```

Policy:

- `FLOW_LENS`: fix or explicitly approve before release.
- `COMPILER_GENERATED_FLOW_LENS`: treat as ours; fix compiler/configuration when practical.
- `LANGUAGE_PLUGIN_SIGNATURE` / `PLATFORM_TRANSITIVE`: document exact provenance and determine whether our integration can avoid the reference.
- `UNKNOWN`: release-blocking until understood.

A public Marketplace warning count must never be accepted merely because the plugin is marked `Compatible`.

---

## 6. Unstable API exception process

An unstable/deprecated API exception is allowed only when all of the following are true:

1. The capability is required for an accepted Flow Lens feature.
2. No stable public API provides an equivalent practical implementation.
3. Removing/deferring the feature would materially reduce intended functionality.
4. Usage is isolated behind a narrow adapter/interface.
5. The unstable type does not leak into `core`, shared `model`, rendering, persistence, or unrelated language integrations.
6. The reason is documented next to the adapter or in project documentation.
7. A replacement/removal path is recorded.
8. Compatibility tests cover supported IDE builds.
9. Plugin Verifier output is allowlisted by exact known provenance rather than ignored by count alone.

The exception is technical debt, not an ordinary dependency.

Concept:

```text
FlowAnalysisEngine
      │
      ▼
Stable Flow Lens interface
      │
      ▼
KotlinAnalyzerAdapter
      │
      └─ narrowly isolated experimental JetBrains API, only if unavoidable
```

The renderer/shared model must never know the unstable IntelliJ type was involved.

---

## 7. Language analyzer rules

### Java

Prefer stable IntelliJ Java PSI/UAST/public resolution APIs.

Do not use internal implementation classes to obtain richer call-resolution information unless no stable design can satisfy the accepted feature and an exception is approved.

### Kotlin

Flow Lens may require Kotlin-specific semantic/analysis APIs beyond generic UAST for accurate resolution.

Rules:

- prefer stable/public Kotlin plugin APIs;
- keep Kotlin-specific types inside the Kotlin analyzer integration;
- do not expose Kotlin analysis types into the shared Flow Lens model;
- experimental Kotlin API usage requires an explicit exception and compatibility coverage;
- distinguish authored physical source from synthetic/light/compiler-generated declarations before recursive flow analysis.

#### Kotlin metadata compatibility

Repo Lens could use a test-only metadata-version bypass because it did not call Kotlin plugin APIs directly; it only needed the plugin loaded for parsing.

Flow Lens **does** rely on Kotlin semantic/resolution APIs. Therefore:

- do not use a blanket `-Xskip-metadata-version-check` or equivalent as the normal compatibility solution for the Kotlin analyzer;
- choose compatible compiler/plugin/API versions during Milestone 0;
- if a narrow test-only bypass is ever considered, document exactly why it is safe and ensure no production semantic code relies on the incompatible metadata surface.

### Go

Go support is isolated behind a compatible JetBrains Go plugin dependency.

Rules:

- prefer documented/public Go PSI/API surfaces;
- keep Go-specific types behind the Go analyzer/optional descriptor;
- core/plugin startup paths that do not need Go must not directly load Go implementation classes;
- experimental Go API usage requires an explicit exception and compatibility coverage;
- if a desired semantic requires internal-only API, reduce/defer that semantic before accepting internal coupling.

---

## 8. Optional dependency rule

The Go language plugin is optional for Flow Lens startup.

The plugin descriptor/configuration must ensure:

- Flow Lens starts without Go installed;
- Java/Kotlin functionality remains usable;
- no class-loading error occurs because a mandatory class references Go implementation types;
- Go analyzer/extensions are registered only when the dependency is present;
- capability availability can be explained to the user.

Add a regression test/check for both dependency-present and dependency-absent states.

---

## 9. Build tooling

Use the IntelliJ Platform Gradle Plugin 2.x line appropriate to the selected modern platform baseline.

The exact Java toolchain follows the chosen IntelliJ Platform build and is fixed in Gradle configuration.

Build configuration should also make compiler behavior relevant to verifier output explicit, including Kotlin JVM-default behavior where applicable.

Avoid bundling platform-provided runtime libraries in a way that can create classpath/version conflicts. Inspect the produced ZIP/JARs rather than assuming Gradle scopes are correct.

---

## 10. IDE inspections

Development should enable and act on JetBrains inspections that detect:

- unstable API usage;
- obsolete API usage;
- deprecated API usage;
- plugin.xml validity;
- APIs unavailable in the minimum IDE build;
- invalid extension/implementation contracts;
- incorrect optional dependency registration where detectable.

Warnings in these categories must not be casually suppressed.

A suppression requires the same justification/provenance discipline as an API-stability exception.

---

## 11. Plugin Verifier quality gate

Plugin Verifier is part of development from Milestone 0, not only release preparation.

At minimum it must report/check:

- binary compatibility problems;
- deprecated API usage;
- scheduled-for-removal API usage;
- experimental API usage;
- internal API usage;
- missing dependencies;
- invalid plugin structure.

Preferred release state:

```text
Compatibility problems       0
Internal API usages          0
Scheduled-for-removal usages 0
Obsolete API usages          0
Deprecated API usages        0
Experimental API usages      0 preferred
Unknown warning provenance   0
```

Approved exceptions may prevent a literal all-zero result, but every exception must be documented, isolated, intentional, and reviewed.

---

## 12. CI policy

Target pipeline:

```text
compile
  ↓
core unit tests
  ↓
IDE/language fixture tests
  ↓
plugin structure / distribution build
  ↓
Plugin Verifier matrix
  ↓
API warning provenance/baseline check
  ↓
release artifact
```

Fail build/release for:

- compatibility errors;
- internal API usage;
- scheduled-for-removal API usage;
- newly introduced prohibited API usage;
- invalid plugin structure;
- missing required dependency declarations;
- unexplained verifier warnings;
- growth of a deprecated/experimental allowlist without explicit review.

If approved exceptions exist, CI must prevent the warning set from silently growing. Prefer an exact allowlist/provenance record over accepting “N warnings”.

### CI usage policy

Flow Lens lives in a private repository, where hosted Actions minutes are a
limited budget and an IntelliJ platform build is expensive. CI is a final gate,
not an iteration loop:

1. Everything is verified locally first — full build, tests, and Plugin
   Verifier all run on a developer machine in well under the time a hosted run
   takes.
2. Code review completes before a change is pushed for verification. Asking CI
   to validate code that has not been read yet wastes the budget on work that is
   likely to change.
3. Draft pull requests do not trigger runs; a run happens when the change is
   believed finished.
4. Superseded runs are cancelled rather than allowed to finish.
5. Documentation-only changes do not trigger runs.
6. The expensive release checks — Plugin Verifier and the distribution ZIP —
   run on explicit request rather than on every commit, because they are already
   executed locally before a milestone is closed.

The verifier requirement in this document is about evidence existing before a
milestone or release is accepted, not about running it on every push.

### CI resource lesson

IntelliJ distributions, verifier IDEs, and Gradle transforms can consume tens of GB on hosted runners. Repo Lens hit disk exhaustion during CI.

Flow Lens should:

- avoid caching re-derivable extracted/transformed IDE directories;
- monitor free disk during initial verifier setup;
- remove unrelated preinstalled toolchains on hosted runners if required;
- upload only required distribution/test artifacts.

---

## 13. Supported-version verification

Once the exact v0.1 minimum build is selected, verification covers at least:

1. minimum supported IntelliJ IDEA Ultimate build;
2. latest/current build claimed by the release.

When compatibility range expands, add representative verifier targets rather than assuming binary compatibility across every build.

Required language-plugin dependencies must be represented in testing/verification where relevant.

---

## 14. Dependency upgrade review

When updating IntelliJ Platform, Kotlin plugin/API integration, Go plugin/API integration, Kotlin compiler, or the Gradle platform plugin:

1. review JetBrains incompatible/API notes;
2. confirm exact language-plugin version compatibility;
3. run fixture tests;
4. run Plugin Verifier before merge;
5. inspect new deprecated/experimental/internal/compiler-generated usages;
6. migrate replacements before expanding supported range where practical;
7. verify source navigation and mixed-language resolution again;
8. inspect packaged artifact contents.

A successful compile is not sufficient compatibility validation.

---

## 15. Dynamic plugin lifecycle

Do not assume dynamic unload/update support because Plugin Verifier says the plugin can “probably” be enabled/disabled without restart.

Repo Lens showed that this heuristic can disagree with actual class-loader lifecycle.

Flow Lens may claim dynamic unload/update only after a real test demonstrates:

- ToolWindow content disposes;
- message-bus/listeners dispose;
- coroutines/jobs cancel;
- optional language integrations release references;
- disable/enable/update works without restart and without class-loader leak.

Until then, restart-required behavior is acceptable and should be documented.

---

## 16. Headless/reproducible build behavior

JetBrains headless build tasks can behave differently by host locale/environment. Repo Lens encountered a searchable-options build failure under a Japanese default locale.

Flow Lens should make required headless locale/tooling assumptions explicit in Gradle/CI and test localization independently in the IDE.

Do not rely on a developer workstation’s locale, installed IDE cache, or existing language-plugin state to make CI succeed.

---

## 17. Definition of Done impact

A release-quality milestone is not complete solely because functional acceptance tests pass.

Definition of Done includes:

- no prohibited Internal/ScheduledForRemoval/Obsolete API dependency;
- deprecated API count zero unless an approved exact exception exists;
- experimental API count zero where stable alternatives exist;
- compiler-generated verifier warnings understood and removed where controllable;
- all exceptions isolated behind adapters;
- Plugin Verifier executed for target matrix;
- exact warning provenance recorded;
- no unexplained API-stability regression;
- actual distribution ZIP successfully built/inspected/installed;
- optional Go dependency behavior verified;
- Kotlin semantic API versions are genuinely compatible, not hidden by blanket metadata bypass.

---

## 18. Guiding principle

Flow Lens is intended to be a long-lived IDE extension.

A slightly more conservative implementation built on supported public APIs is preferable to a clever implementation that depends on unstable internals, synthetic compiler artifacts, or accidental compatibility and breaks on the next IDE update.