package com.kanicream.flowlens.core.engine

import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowBranch
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

/**
 * A structural event under construction: its branches are filled in as the
 * analyzer walks them, then sealed into an immutable [FlowNode].
 */
class StructureSpec(
    val kind: FlowNodeKind,
    val callSiteLocation: FlowLocation?,
    val summary: String?,
    /** Technical markers such as "this loop body runs at least once". Never source text. */
    val metadata: Map<String, String> = emptyMap(),
)

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
    /** The call this body was handed to; see [com.kanicream.flowlens.core.model.FlowNode.attachedTo]. */
    val attachedTo: NodeId? = null,
    /**
     * Short source-derived text for display, used by terminators to say what a
     * `return` hands back. A call needs none: its target names it.
     */
    val sourceSummary: String? = null,
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
    private val openStructures = mutableListOf<StructureHandle>()
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
        val childId = newFrame(symbol, entryLocation, depth = parent.depth + 1)
        val linked = linkChildFrame(ownerFrame, ownerNode, childId)
        check(linked) { "node $ownerNode not found in frame $ownerFrame" }
        return childId
    }

    /**
     * Points the owning call at its analyzed body. The call may sit directly in
     * the frame, inside a branch that is still being collected, or inside one
     * that is already sealed, so all three are searched.
     */
    private fun linkChildFrame(frameId: FrameId, nodeId: NodeId, childId: FrameId): Boolean {
        for (structure in openStructures.asReversed()) {
            if (structure.frameId == frameId && structure.link(nodeId, childId)) return true
        }
        val frame = frames.getValue(frameId)
        val index = frame.events.indexOfFirst { it.id == nodeId }
        if (index >= 0) {
            frame.events[index] = frame.events[index].copy(targetFrameId = childId)
            return true
        }
        val updated = frame.events.map { linkWithin(it, nodeId, childId) }
        if (updated != frame.events.toList()) {
            frame.events.clear()
            frame.events += updated
            return true
        }
        return false
    }

    /** Rebuilds [node] with the child frame attached somewhere in its branches. */
    private fun linkWithin(node: FlowNode, nodeId: NodeId, childId: FrameId): FlowNode {
        if (node.branches.isEmpty()) return node
        return node.copy(
            branches = node.branches.map { branch ->
                branch.copy(
                    events = branch.events.map { event ->
                        when {
                            event.id == nodeId -> event.copy(targetFrameId = childId)
                            else -> linkWithin(event, nodeId, childId)
                        }
                    },
                )
            },
        )
    }

    /** Adds one ordinary semantic event. Returns null when only the reserved limit slot remains. */
    fun addEvent(frameId: FrameId, spec: FlowEventSpec): NodeId? {
        if (!budget.tryClaimOrdinary()) return null
        return appendNode(frameId, spec)
    }

    /**
     * Opens a structural event — a condition, switch, loop, or try — in
     * [frameId]. Its branches are collected through [openBranch] and the whole
     * structure is sealed by [closeStructure].
     *
     * A structure claims a node slot like any other persistent semantic node
     * (`PLAN.md` §11); returns null when only the reserved limit slot remains.
     */
    fun openStructure(frameId: FrameId, spec: StructureSpec): StructureHandle? {
        if (!budget.tryClaimOrdinary()) return null
        val handle = StructureHandle(NodeId(nextNodeId++), frameId, spec)
        openStructures += handle
        return handle
    }

    /** Starts collecting a labelled section of an open structure. */
    fun openBranch(structure: StructureHandle, kind: BranchKind, label: String?) {
        structure.openBranch(kind, label)
    }

    /** Finishes the current section of an open structure. */
    fun closeBranch(structure: StructureHandle) {
        structure.closeBranch()
    }

    /**
     * Seals a structure and appends it to its frame, or to the enclosing
     * structure's current branch when structures nest.
     */
    fun closeStructure(structure: StructureHandle) {
        structure.closeBranch()
        openStructures.remove(structure)
        val node = FlowNode(
            id = structure.id,
            kind = structure.spec.kind,
            callSiteLocation = structure.spec.callSiteLocation,
            targetSymbol = null,
            targetLocation = null,
            targetFrameId = null,
            depth = frames.getValue(structure.frameId).depth,
            resolutionStatus = null,
            dispatchConfidence = null,
            executionMode = ExecutionMode.SYNC,
            orderingStatus = OrderingStatus.DETERMINISTIC,
            branches = structure.branches(),
            sourceSummary = structure.spec.summary,
            metadata = structure.spec.metadata,
        )
        emit(structure.frameId, node)
    }

    /**
     * Adds a library group: one run of consecutive calls that were not entered,
     * drawn as one card that carries them (`V1.0_GROUPING_SPEC.md` §4).
     *
     * **The group claims one node and its members claim none.** That is half the
     * reason the rule exists: a route table of forty-seven unenterable calls
     * used to exhaust the budget before the analysis reached anything the reader
     * came for. The members are still produced, resolved and classified — what
     * changes is what the budget is spent on.
     *
     * Returns null when only the reserved limit slot remains.
     */
    fun addGroup(frameId: FrameId, spec: FlowEventSpec, members: List<FlowEventSpec>): NodeId? {
        require(spec.kind == FlowNodeKind.EXTERNAL_GROUP) { "a group must be an EXTERNAL_GROUP" }
        require(members.size >= 2) { "a group of fewer than two members is just a call" }
        if (!budget.tryClaimOrdinary()) return null
        val depth = frames.getValue(frameId).depth
        val id = NodeId(nextNodeId++)
        val memberNodes = members.map { member ->
            FlowNode(
                id = NodeId(nextNodeId++),
                kind = member.kind,
                callSiteLocation = member.callSiteLocation,
                targetSymbol = member.targetSymbol,
                targetLocation = member.targetLocation,
                targetFrameId = null,
                depth = depth,
                resolutionStatus = member.resolutionStatus,
                dispatchConfidence = member.dispatchConfidence,
                executionMode = member.executionMode,
                orderingStatus = member.orderingStatus,
                metadata = member.metadata,
                sourceSummary = member.sourceSummary,
            )
        }
        emit(
            frameId,
            FlowNode(
                id = id,
                kind = spec.kind,
                callSiteLocation = spec.callSiteLocation,
                targetSymbol = spec.targetSymbol,
                targetLocation = null,
                targetFrameId = null,
                depth = depth,
                resolutionStatus = spec.resolutionStatus,
                dispatchConfidence = null,
                executionMode = spec.executionMode,
                orderingStatus = spec.orderingStatus,
                metadata = spec.metadata,
                branches = listOf(FlowBranch(BranchKind.GROUP, null, memberNodes)),
            ),
        )
        return id
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

    /** The callable a frame belongs to, for progress display. */
    fun frameSymbol(frameId: FrameId): FlowSymbol? = frames[frameId]?.symbol

    fun markFrameComplete(frameId: FrameId) {
        frames.getValue(frameId).bodyComplete = true
    }

    fun addDiagnostic(diagnostic: FlowDiagnostic) {
        diagnostics += diagnostic
    }

    /**
     * Seals every structure still being collected, innermost first. Traversal
     * can stop inside a branch — the node budget runs out, or the run is
     * cancelled — and a snapshot must still contain what was found there.
     */
    fun closeOpenStructures() {
        while (openStructures.isNotEmpty()) {
            closeStructure(openStructures.last())
        }
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
        val id = NodeId(nextNodeId++)
        emit(
            frameId,
            FlowNode(
                id = id,
                kind = spec.kind,
                callSiteLocation = spec.callSiteLocation,
                targetSymbol = spec.targetSymbol,
                targetLocation = spec.targetLocation,
                targetFrameId = null,
                depth = frames.getValue(frameId).depth,
                resolutionStatus = spec.resolutionStatus,
                dispatchConfidence = spec.dispatchConfidence,
                executionMode = spec.executionMode,
                orderingStatus = spec.orderingStatus,
                metadata = spec.metadata,
                attachedTo = spec.attachedTo,
                sourceSummary = spec.sourceSummary,
            ),
        )
        return id
    }

    /**
     * Places a finished event where the traversal currently is: inside the
     * innermost open branch of this frame, or in the frame itself.
     */
    private fun emit(frameId: FrameId, node: FlowNode) {
        val enclosing = openStructures.lastOrNull { it.frameId == frameId && it.hasOpenBranch }
        if (enclosing != null) {
            enclosing.add(node)
        } else {
            frames.getValue(frameId).events += node
        }
    }

    /**
     * A structure being built. Events emitted while one of its branches is open
     * land in that branch instead of the frame's top-level sequence.
     */
    class StructureHandle internal constructor(
        internal val id: NodeId,
        internal val frameId: FrameId,
        internal val spec: StructureSpec,
    ) {
        private val completed = mutableListOf<FlowBranch>()
        private var currentKind: BranchKind? = null
        private var currentLabel: String? = null
        private var currentEvents = mutableListOf<FlowNode>()

        internal val hasOpenBranch: Boolean get() = currentKind != null

        internal fun openBranch(kind: BranchKind, label: String?) {
            closeBranch()
            currentKind = kind
            currentLabel = label
            currentEvents = mutableListOf()
        }

        internal fun closeBranch() {
            val kind = currentKind ?: return
            completed += FlowBranch(kind, currentLabel, currentEvents.toList())
            currentKind = null
            currentLabel = null
            currentEvents = mutableListOf()
        }

        internal fun add(node: FlowNode) {
            currentEvents += node
        }

        internal fun branches(): List<FlowBranch> = completed.toList()

        /** Attaches a child frame to a call collected in the open branch. */
        internal fun link(nodeId: NodeId, childId: FrameId): Boolean {
            val index = currentEvents.indexOfFirst { it.id == nodeId }
            if (index < 0) return false
            currentEvents[index] = currentEvents[index].copy(targetFrameId = childId)
            return true
        }
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
