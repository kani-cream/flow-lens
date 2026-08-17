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
