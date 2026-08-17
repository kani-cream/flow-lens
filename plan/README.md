# Flow Lens Planning Index

This directory is the normative planning set for Flow Lens.

The documents are intentionally separated by responsibility so implementation decisions do not become buried in one large design file.

## Documents

| Document | Role |
|---|---|
| `PLAN.md` | Product direction, architecture, scope, milestones, and roadmap |
| `V0.1_SPEC.md` | v0.1 analysis semantics and acceptance behavior |
| `VISUAL_DESIGN.md` | Flow Canvas visual language and interaction rules |
| `API_STABILITY.md` | IntelliJ / language-plugin API stability and compatibility policy |
| `REPO_LENS_LESSONS.md` | Audit of lessons carried forward from the Repo Lens project |
| `IMPLEMENTATION_GUARDRAILS.md` | Normative engineering constraints derived from the design and Repo Lens experience |
| `TEST_STRATEGY.md` | Automated, fixture, smoke, manual, verifier, and artifact test strategy |
| `KNOWN_LIMITATIONS.md` | Explicitly accepted product/analysis limitations that must not be hidden from users |

## Precedence

For v0.1 implementation:

1. `V0.1_SPEC.md` defines observable analysis semantics.
2. `VISUAL_DESIGN.md` defines observable Flow Canvas behavior.
3. `IMPLEMENTATION_GUARDRAILS.md` defines engineering/lifecycle constraints.
4. `API_STABILITY.md` defines allowed platform API usage.
5. `TEST_STRATEGY.md` defines evidence required before a milestone is accepted.
6. `PLAN.md` defines the higher-level product intent and roadmap.

If two documents appear to conflict, do not silently choose the easier interpretation. Record the conflict and resolve the planning documents before implementation continues.

## Repo Lens inheritance rule

Flow Lens is a new product, but Repo Lens is treated as a completed predecessor project whose engineering experience is reusable.

The goal is not to copy Repo Lens mechanically. The goal is to preserve patterns that worked and explicitly prevent failures already observed there.

The carried-forward rules are summarized in `REPO_LENS_LESSONS.md` and made enforceable in `IMPLEMENTATION_GUARDRAILS.md`, `TEST_STRATEGY.md`, and `API_STABILITY.md`.

## Implementation start gate

Production implementation may begin after Milestone 0 proves the platform-specific uncertainties identified in `PLAN.md` and the additional predecessor-derived checks below:

- exact IntelliJ IDEA Ultimate patch/build selected and compatible with required Java/Kotlin/Go integrations;
- Go optional dependency loads and disappears cleanly without breaking the plugin;
- Kotlin analysis path does not surface compiler-generated declarations as authored source;
- ToolWindow skeleton is run through Plugin Verifier immediately, not only near release;
- representative Java, Kotlin, and Go PSI fixtures run in the chosen test harness;
- Flow Canvas prototype can accept incremental updates without flooding or blocking the EDT;
- cancellation works between bounded analysis/read operations;
- a built ZIP can be installed into a sandbox IDE and exercised manually.

Milestone completion requires evidence, not only code being present.