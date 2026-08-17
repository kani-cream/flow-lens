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
) {
    init {
        require(nodeCount >= 0) { "nodeCount must be >= 0" }
        if (rootFrameId != null) {
            require(frames.containsKey(rootFrameId)) { "rootFrameId must reference a known frame" }
        }
    }

    val rootFrame: FlowFrame? get() = rootFrameId?.let(frames::get)

    fun frame(id: FrameId): FlowFrame? = frames[id]

    val isTerminal: Boolean get() = status != FlowResultStatus.RUNNING
}
