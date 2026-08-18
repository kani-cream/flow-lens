package com.kanicream.flowlens.core.model

/** Real analysis stages. No synthetic percentages exist anywhere in the model. */
enum class FlowProgressStage {
    STARTING,
    WAITING_FOR_INDEXES,
    EXTRACTING_ROOT,
    RESOLVING_ROOT_CALLS,
    ANALYZING_CHILD_FRAMES,
    LAYOUT_UPDATE,
    COMPLETED,
    TRUNCATED,
    CANCELLED,
    STALE,
    FAILED,
}

/**
 * One progress event. Every value is derived from actual work performed
 * (IMPLEMENTATION_GUARDRAILS.md section 10).
 */
data class FlowProgress(
    val runId: RunId,
    val stage: FlowProgressStage,
    val nodesProduced: Int,
    val framesAnalyzed: Int,
    val exactCount: Int,
    val declaredTargetCount: Int,
    val ambiguousCount: Int,
    val externalCount: Int,
    val unresolvedCount: Int,
    val elapsedMillis: Long,
    /**
     * The callable being analyzed right now, for display. A run that appears
     * stuck should be able to say what it is stuck on (`V0.3_SPEC.md` §7.1).
     * Display only: the logged summary stays counters (guardrails §13).
     */
    val currentCallable: String? = null,
    /**
     * The run's node budget, so the UI can show how much of it is spent. An
     * exploration has no knowable total, so this is the only honest denominator
     * (`V0.3_SPEC.md` §7.2).
     */
    val nodeBudget: Int = 0,
) {
    val isTerminal: Boolean
        get() = when (stage) {
            FlowProgressStage.COMPLETED,
            FlowProgressStage.TRUNCATED,
            FlowProgressStage.CANCELLED,
            FlowProgressStage.STALE,
            FlowProgressStage.FAILED,
            -> true
            else -> false
        }
}
