package com.kanicream.flowlens.core.model

/**
 * One semantic event at one call site. Call-site identity, not target identity:
 * two calls to the same target are two nodes.
 */
data class FlowNode(
    val id: NodeId,
    val kind: FlowNodeKind,
    val callSiteLocation: FlowLocation?,
    val targetSymbol: FlowSymbol?,
    val targetLocation: FlowLocation?,
    val targetFrameId: FrameId?,
    val depth: Int,
    val resolutionStatus: ResolutionStatus?,
    val dispatchConfidence: DispatchConfidence?,
    val executionMode: ExecutionMode,
    val orderingStatus: OrderingStatus,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(depth >= 0) { "depth must be >= 0" }
        if (targetFrameId != null) {
            require(kind == FlowNodeKind.CALL || kind == FlowNodeKind.CONSTRUCTOR || kind == FlowNodeKind.ENTRY) {
                "only callable events may own a target frame, got $kind"
            }
        }
    }
}
