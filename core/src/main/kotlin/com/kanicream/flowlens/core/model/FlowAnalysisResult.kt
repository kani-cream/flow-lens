package com.kanicream.flowlens.core.model

/**
 * Immutable snapshot of one analysis run. Progressive analysis publishes a sequence
 * of snapshots for the same [runId]; frames are referenced by id so partial results
 * stay structurally valid.
 */
data class FlowAnalysisResult(
    val runId: RunId,
    val status: FlowResultStatus,
    val rootFrameId: FrameId?,
    val frames: Map<FrameId, FlowFrame>,
    val nodeCount: Int,
    val controlFlowIncomplete: Boolean,
    val sourceRevision: Long,
    val diagnostics: List<FlowDiagnostic>,
    /**
     * Calls the hide-external option removed (`V1.1_HIDE_EXTERNAL_SPEC.md` §5).
     * They left no node, so the count rides on the result: a map with calls
     * removed must say so, or absence would read as "nothing was called".
     */
    val hiddenExternalCount: Int = 0,
) {
    init {
        require(nodeCount >= 0) { "nodeCount must be >= 0" }
        require(hiddenExternalCount >= 0) { "hiddenExternalCount must be >= 0" }
        if (rootFrameId != null) {
            require(frames.containsKey(rootFrameId)) { "rootFrameId must reference a known frame" }
        }
    }

    val rootFrame: FlowFrame? get() = rootFrameId?.let(frames::get)

    fun frame(id: FrameId): FlowFrame? = frames[id]

    val isTerminal: Boolean get() = status != FlowResultStatus.RUNNING
}
