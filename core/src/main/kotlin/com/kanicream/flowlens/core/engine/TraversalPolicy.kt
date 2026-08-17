package com.kanicream.flowlens.core.engine

import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin

/**
 * Language-neutral facts about one resolved call target, as far as traversal
 * policy is concerned.
 */
data class TargetFacts(
    val hasAnalyzableBody: Boolean,
    val origin: SourceOrigin,
    val resolution: ResolutionStatus,
    val dispatch: DispatchConfidence,
    val inTestSource: Boolean,
)

/**
 * Decides whether a resolved target's body may be entered
 * (`V0.1_SPEC.md` §8, §11, guardrails §4). Pure and settings-driven so the rule
 * set is testable without an IDE and identical for every language.
 */
object TraversalPolicy {

    fun mayEnterBody(target: TargetFacts, limits: FlowLimits): Boolean {
        if (!target.hasAnalyzableBody) return false
        if (!dispatchAllowsContinuation(target.dispatch)) return false
        if (!originAllowed(target.origin, limits)) return false
        if (!locationAllowed(target.resolution, limits)) return false
        if (target.inTestSource && !limits.includeTests) return false
        return true
    }

    /**
     * `AMBIGUOUS` has no responsible single continuation and `UNKNOWN` was never
     * classified, so neither may be followed in v0.1.
     */
    private fun dispatchAllowsContinuation(dispatch: DispatchConfidence): Boolean =
        dispatch == DispatchConfidence.EXACT || dispatch == DispatchConfidence.DECLARED_TARGET

    /**
     * Only authored source is followed by default. Compiler-generated and
     * unclassified declarations are conservative stops so generated members can
     * never appear as authored flow.
     */
    private fun originAllowed(origin: SourceOrigin, limits: FlowLimits): Boolean = when (origin) {
        SourceOrigin.PHYSICAL_SOURCE -> true
        SourceOrigin.LIBRARY -> limits.includeLibraries
        SourceOrigin.SYNTHETIC, SourceOrigin.GENERATED, SourceOrigin.UNKNOWN -> false
    }

    private fun locationAllowed(resolution: ResolutionStatus, limits: FlowLimits): Boolean =
        when (resolution) {
            ResolutionStatus.PROJECT_LOCAL -> true
            ResolutionStatus.EXTERNAL -> limits.includeLibraries
            ResolutionStatus.UNRESOLVED, ResolutionStatus.BUILT_IN -> false
        }
}
