package com.kanicream.flowlens.core.model

private val STRUCTURAL_KINDS = setOf(
    FlowNodeKind.CONDITION,
    FlowNodeKind.SWITCH,
    FlowNodeKind.LOOP,
    FlowNodeKind.TRY,
)

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
    /**
     * Labelled sections of a structural event: the branches of a condition, the
     * cases of a switch, a loop body, the parts of a try. Empty for a call
     * (`V0.2_SPEC.md` §3).
     */
    val branches: List<FlowBranch> = emptyList(),
    /** Short source-derived text describing the structure, for display only. */
    val structureSummary: String? = null,
) {
    /** True when this event owns branches rather than being a single step. */
    val isStructure: Boolean get() = branches.isNotEmpty()

    /**
     * Default navigation destination (V0.1_SPEC.md section 18): the resolved target
     * declaration when known, otherwise the call site.
     */
    val preferredNavigationLocation: FlowLocation?
        get() = targetLocation ?: callSiteLocation

    init {
        require(depth >= 0) { "depth must be >= 0" }
        if (targetFrameId != null) {
            require(kind == FlowNodeKind.CALL || kind == FlowNodeKind.CONSTRUCTOR || kind == FlowNodeKind.ENTRY) {
                "only callable events may own a target frame, got $kind"
            }
        }
        if (branches.isNotEmpty()) {
            require(kind in STRUCTURAL_KINDS) { "only a structural event may own branches, got $kind" }
        }
    }
}
