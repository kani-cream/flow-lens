package com.kanicream.flowlens.core.engine

import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowDiagnostic
import com.kanicream.flowlens.core.model.FlowFrame
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowLocation
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.FrameId
import com.kanicream.flowlens.core.model.NodeId
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId

/** Input for one ordinary semantic event. Depth is derived from the owning frame. */
data class FlowEventSpec(
    val kind: FlowNodeKind,
    val callSiteLocation: FlowLocation?,
    val targetSymbol: FlowSymbol?,
    val targetLocation: FlowLocation? = null,
    val resolutionStatus: ResolutionStatus?,
    val dispatchConfidence: DispatchConfidence?,
    val executionMode: ExecutionMode = ExecutionMode.SYNC,
    val orderingStatus: OrderingStatus = OrderingStatus.DETERMINISTIC,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Builds the semantic model of one run and publishes immutable snapshots.
 *
 * The builder owns node/frame identity and the node budget; traversal policy
 * (cycles, depth, scheduling) is the engine's responsibility. Not thread-safe;
 * confined to the run that owns it.
 */
class FlowModelBuilder(
    private val runId: RunId,
    limits: FlowLimits,
    private val sourceRevision: Long,
) {
    private val budget = NodeBudget(limits.maxNodes)
    private val frames = LinkedHashMap<FrameId, MutableFrame>()
    private val diagnostics = mutableListOf<FlowDiagnostic>()
    private var rootFrameId: FrameId? = null
    private var nextNodeId = 0
    private var nextFrameId = 0

    var controlFlowIncomplete: Boolean = false

    /** True once the node budget forced a LIMIT marker. */
    var wasTruncated: Boolean = false
        private set

    val nodeCount: Int get() = budget.used
    val frameCount: Int get() = frames.size

    fun openRootFrame(symbol: FlowSymbol, entryLocation: FlowLocation?): FrameId {
        check(rootFrameId == null) { "root frame already opened" }
        val id = newFrame(symbol, entryLocation, depth = 0)
        rootFrameId = id
        return id
    }

    /**
     * Opens the analyzed body of the call event [ownerNode] as a child frame and
     * links it to that node. The caller must have verified depth/cycle policy.
     */
    fun openChildFrame(
        ownerFrame: FrameId,
        ownerNode: NodeId,
        symbol: FlowSymbol,
        entryLocation: FlowLocation?,
    ): FrameId {
        val parent = frames.getValue(ownerFrame)
        val index = parent.events.indexOfFirst { it.id == ownerNode }
        check(index >= 0) { "node $ownerNode not found in frame $ownerFrame" }
        val childId = newFrame(symbol, entryLocation, depth = parent.depth + 1)
        parent.events[index] = parent.events[index].copy(targetFrameId = childId)
        return childId
    }

    /** Adds one ordinary semantic event. Returns null when only the reserved limit slot remains. */
    fun addEvent(frameId: FrameId, spec: FlowEventSpec): NodeId? {
        if (!budget.tryClaimOrdinary()) return null
        return appendNode(frameId, spec)
    }

    /** Adds the single LIMIT marker into [frameId] using the reserved budget slot. */
    fun addLimitEvent(frameId: FrameId, location: FlowLocation? = null): NodeId? {
        if (!budget.tryClaimLimitSlot()) return null
        wasTruncated = true
        return appendNode(
            frameId,
            FlowEventSpec(
                kind = FlowNodeKind.LIMIT,
                callSiteLocation = location,
                targetSymbol = null,
                resolutionStatus = null,
                dispatchConfidence = null,
                executionMode = ExecutionMode.UNKNOWN,
                orderingStatus = OrderingStatus.DETERMINISTIC,
            ),
        )
    }

    fun markFrameComplete(frameId: FrameId) {
        frames.getValue(frameId).bodyComplete = true
    }

    fun addDiagnostic(diagnostic: FlowDiagnostic) {
        diagnostics += diagnostic
    }

    fun snapshot(status: FlowResultStatus): FlowAnalysisResult =
        FlowAnalysisResult(
            runId = runId,
            status = status,
            rootFrameId = rootFrameId,
            frames = frames.mapValues { (_, f) -> f.toImmutable() },
            nodeCount = budget.used,
            controlFlowIncomplete = controlFlowIncomplete,
            sourceRevision = sourceRevision,
            diagnostics = diagnostics.toList(),
        )

    private fun newFrame(symbol: FlowSymbol, entryLocation: FlowLocation?, depth: Int): FrameId {
        val id = FrameId(nextFrameId++)
        frames[id] = MutableFrame(id, symbol, entryLocation, depth)
        return id
    }

    private fun appendNode(frameId: FrameId, spec: FlowEventSpec): NodeId {
        val frame = frames.getValue(frameId)
        val id = NodeId(nextNodeId++)
        frame.events += FlowNode(
            id = id,
            kind = spec.kind,
            callSiteLocation = spec.callSiteLocation,
            targetSymbol = spec.targetSymbol,
            targetLocation = spec.targetLocation,
            targetFrameId = null,
            depth = frame.depth,
            resolutionStatus = spec.resolutionStatus,
            dispatchConfidence = spec.dispatchConfidence,
            executionMode = spec.executionMode,
            orderingStatus = spec.orderingStatus,
            metadata = spec.metadata,
        )
        return id
    }

    private class MutableFrame(
        val id: FrameId,
        val symbol: FlowSymbol,
        val entryLocation: FlowLocation?,
        val depth: Int,
    ) {
        val events = mutableListOf<FlowNode>()
        var bodyComplete = false

        fun toImmutable(): FlowFrame =
            FlowFrame(id, symbol, entryLocation, depth, events.toList(), bodyComplete)
    }
}
