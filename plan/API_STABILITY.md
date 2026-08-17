# Flow Lens API Stability Policy

## 1. Purpose

Flow Lens depends heavily on IntelliJ Platform language and PSI APIs. API stability is therefore a product-quality requirement, not merely a build concern.

The project should avoid repeating a situation where Marketplace compatibility verification succeeds but the released plugin still contains a noticeable number of deprecated or experimental API usages.

The target is:

> Prefer stable public IntelliJ Platform APIs. Keep deprecated and unstable API usage at or as close to zero as realistically possible.

This document is normative for implementation and release review.

---

## 2. Target Host

The primary v0.1 host is:

- **IntelliJ IDEA Ultimate**

The exact minimum supported IntelliJ IDEA Ultimate build is fixed during Milestone 0 and then becomes part of the build/verification matrix.

Java, Kotlin, and Go support are implemented against APIs available in the supported IntelliJ IDEA Ultimate environment. Go support additionally depends on a compatible JetBrains Go plugin.

---

## 3. API Usage Policy

### 3.1 Stable public API

Preferred and unrestricted when compatible with the supported IDE range.

Use stable public APIs whenever a practical implementation exists.

### 3.2 Internal API

Examples include APIs annotated or classified as internal, such as `@ApiStatus.Internal` / IntelliJ internal API.

**Policy: prohibited for normal product code.**

A third-party plugin must not rely on internal APIs merely because they are convenient or expose richer implementation details.

If a required feature appears impossible without internal API access, stop and reconsider the feature boundary or implementation approach before introducing the dependency.

### 3.3 Scheduled-for-removal API

Examples include `@ApiStatus.ScheduledForRemoval` and deprecated APIs explicitly marked for removal.

**Policy: prohibited.**

Do not introduce new usage.

Existing usage discovered during development must be migrated before release unless the feature itself is removed or deferred.

### 3.4 Obsolete API

Examples include `@ApiStatus.Obsolete`.

**Policy: prohibited for new code.**

Use the documented replacement.

### 3.5 Deprecated API

**Target: zero usages.**

Do not introduce a deprecated API when a supported replacement exists.

If a deprecated API is temporarily unavoidable because the replacement is unavailable for the supported IDE range, the usage must follow the exception process in Section 4 and must not leak across the architecture.

### 3.6 Experimental API

Examples include `@ApiStatus.Experimental`.

**Target: zero usages where practical.**

Experimental APIs may change or disappear without normal compatibility guarantees. They are therefore not acceptable merely to simplify implementation.

However, language integrations such as Kotlin or Go may occasionally expose necessary capabilities only through APIs that JetBrains still classifies as experimental. In that case an explicitly approved exception is allowed under Section 4.

### 3.7 Other constrained APIs

Respect API contracts such as:

- `@ApiStatus.NonExtendable`
- `@ApiStatus.OverrideOnly`
- extension-point lifecycle restrictions
- dynamic-plugin restrictions
- availability-by-platform-version contracts

Do not use an API in a technically compiling but contract-invalid way.

---

## 4. Unstable API Exception Process

An unstable/deprecated API exception is allowed only when all of the following are true:

1. The capability is required for an accepted Flow Lens feature.
2. No stable public API provides an equivalent practical implementation.
3. Removing or deferring the feature would materially reduce the product's intended functionality.
4. The usage is isolated behind a narrow adapter/interface.
5. The unstable type does not leak into `model`, rendering, persistence, or other language-neutral modules.
6. The reason for the exception is documented next to the adapter or in project documentation.
7. A replacement/removal path is recorded.
8. Compatibility tests cover the integration against supported IDE builds.

The exception must be treated as technical debt with an explicit owner/path to removal, not as an ordinary dependency.

### Example architectural isolation

```text
FlowAnalysisEngine
      │
      ▼
Stable project interface
      │
      ▼
KotlinAnalyzerAdapter
      │
      └─ narrowly isolated experimental JetBrains API, if unavoidable
```

The renderer must never know that an experimental IntelliJ API was involved.

---

## 5. Language Analyzer Rules

### Java

Prefer stable IntelliJ Java PSI/UAST APIs.

Do not use internal implementation classes to obtain richer call-resolution information unless no stable design can satisfy the feature and an exception is explicitly approved.

### Kotlin

Kotlin integration may require language-specific analysis APIs beyond generic UAST for accurate resolution.

Rules:

- Prefer stable/public Kotlin plugin APIs where possible.
- Keep Kotlin-specific API usage inside the Kotlin analyzer integration.
- Do not expose Kotlin analysis types into the shared Flow Lens model.
- Experimental Kotlin API usage requires an explicit exception and compatibility coverage.

### Go

Go support is isolated behind the Go analyzer integration and a compatible JetBrains Go plugin dependency.

Rules:

- Prefer documented/public Go plugin PSI/API surfaces.
- Do not directly couple core Flow Lens code to Go plugin implementation classes.
- Experimental Go API usage requires an explicit exception and compatibility coverage.
- If a desired Go semantic requires internal-only APIs, first reduce the semantic scope rather than automatically accepting the internal dependency.

---

## 6. Build Tooling

For the supported modern IntelliJ Platform baseline, use the current IntelliJ Platform Gradle Plugin 2.x line rather than the obsolete Gradle IntelliJ Plugin 1.x line.

The exact Java toolchain must follow the selected IntelliJ Platform target. Once the minimum v0.1 IDE build is fixed, the Java version is fixed in build configuration rather than inferred differently by individual developers.

---

## 7. IDE Inspections

Development should enable and pay attention to JetBrains inspections that detect API-contract problems, including:

- unstable API usage
- obsolete API usage
- deprecated API usage
- plugin.xml validity
- APIs unavailable in the configured minimum IDE version
- invalid extension/implementation contracts

Warnings in these categories must not be casually suppressed.

A suppression requires the same justification as an API-stability exception.

---

## 8. Plugin Verifier Quality Gate

Plugin Verifier is part of the release process and should be integrated into CI as early as practical.

At minimum, verification must detect/report:

- binary compatibility problems
- deprecated API usage
- scheduled-for-removal API usage
- experimental API usage
- internal API usage
- missing dependencies
- invalid plugin structure

### Required release state

The preferred verifier state is:

```text
Compatibility problems       0
Internal API usages          0
Scheduled-for-removal usages 0
Obsolete API usages          0
Deprecated API usages        0
Experimental API usages      0 preferred
```

Approved experimental/deprecated exceptions may prevent a literal all-zero verifier result. Such exceptions must be documented, isolated, intentional, and reviewed before release.

A verifier warning is not automatically harmless simply because Marketplace accepts the plugin.

---

## 9. CI Policy

The CI pipeline should evolve toward the following gates:

```text
compile
  ↓
unit / fixture tests
  ↓
plugin structure verification
  ↓
Plugin Verifier
  ↓
API stability check
  ↓
release artifact
```

### Fail the build/release for

- compatibility errors
- internal API usage
- scheduled-for-removal API usage
- newly introduced prohibited API usage
- invalid plugin structure
- missing required plugin dependencies

### Deprecated / experimental regressions

The baseline goal is zero.

If approved exceptions exist, CI must prevent the count or allowlist from silently growing. A new unstable usage requires explicit review rather than becoming accepted because previous exceptions already exist.

---

## 10. Supported-Version Verification

Once the v0.1 minimum IntelliJ IDEA Ultimate build is selected, verification should cover at least:

1. the minimum supported build
2. the current/latest supported build targeted for release

When the compatibility range expands, add representative verifier targets rather than assuming binary compatibility across the entire range.

Language-plugin dependencies, especially Go and Kotlin integration, must be included in verification where required.

---

## 11. Dependency Upgrade Review

When updating IntelliJ Platform, Kotlin plugin/API integration, Go plugin/API integration, or the Gradle platform plugin:

1. review JetBrains incompatible-API notes for the target release
2. run Plugin Verifier before merging the upgrade
3. inspect new deprecated/experimental/internal usages
4. migrate replacements before expanding the supported IDE range where practical
5. verify source navigation and language-resolution fixtures again

Do not treat a successful compile as sufficient compatibility validation.

---

## 12. Definition of Done Impact

A Flow Lens milestone is not complete solely because its functional acceptance tests pass.

For release-quality milestones, Definition of Done also includes:

- no prohibited Internal/ScheduledForRemoval/Obsolete API dependency
- deprecated API count at zero unless an approved exception exists
- experimental API count at zero where stable alternatives exist
- all exceptions isolated behind adapters
- Plugin Verifier executed for the supported target matrix
- no unexplained API-stability warnings introduced by the milestone

---

## 13. Guiding Principle

Flow Lens is intended to be a long-lived IDE extension.

A slightly more conservative implementation built on supported public APIs is preferable to a clever implementation that depends on unstable IntelliJ internals and becomes fragile on the next IDE update.
