package com.kanicream.flowlens.ui.status

import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.NodeId
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.service.FlowMetadata
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.FlowProgressStage
import com.kanicream.flowlens.core.model.FlowResultStatus

/** How prominently a status should be presented. Never encoded by color alone. */
enum class StatusTone { IDLE, RUNNING, DONE, WARNING, ERROR }

/**
 * Everything the status area shows for one (progress, result) pair. Derived
 * state only — no Swing, no analysis access — so the mapping is unit-testable.
 */
data class FlowStatusViewState(
    /** The analyzed root, so the flow's subject stays visible when scrolled. */
    val rootTitle: String?,
    val headline: String,
    val counters: String?,
    val tone: StatusTone,
    val stopEnabled: Boolean,
    val reanalyzeEnabled: Boolean,
    val simplifiedControlFlow: Boolean,
    val diagnostics: List<String>,
    /**
     * How much of the node budget is spent, 0..1, or null when nothing is
     * running. Not progress toward completion: an exploration has no knowable
     * total, and a full bar means truncation is imminent (`V0.3_SPEC.md` §7.2).
     */
    /**
     * What the run is working on, so a run that looks stuck can say on what
     * (`V0.3_SPEC.md` §7.1). Null once the run is over.
     */
    val currentCallable: String? = null,
    val budgetFraction: Double? = null,
    /** Why the map stops where it does, most significant first (§7.3). */
    val stopReasons: List<StopReason> = emptyList(),
)

/**
 * One reason the map is shaped the way it is, with a way into it: activating a
 * reason selects the first node it describes, so the summary is an entry point
 * rather than a report.
 */
data class StopReason(val text: String, val count: Int, val firstNode: NodeId?)

/**
 * Maps analysis lifecycle state to the status view. Result state wins over the
 * progress stage for terminal runs so a partial result is never presented as
 * current work, and indexing/cancelled/stale never look like unresolved code
 * (`VISUAL_DESIGN.md` §16).
 */
object FlowStatusModel {

    fun stateOf(progress: FlowProgress?, result: FlowAnalysisResult?): FlowStatusViewState {
        if (progress == null && result == null) {
            return FlowStatusViewState(
                rootTitle = null,
                headline = FlowLensBundle.message("status.idle"),
                counters = null,
                tone = StatusTone.IDLE,
                stopEnabled = false,
                reanalyzeEnabled = false,
                simplifiedControlFlow = false,
                diagnostics = emptyList(),
            )
        }
        // The result reaches its terminal state before the terminal progress event,
        // and the two are collected by independently throttled collectors, so a
        // finished run must not keep showing a running stage.
        val stage = when {
            result?.isTerminal == true -> stageOf(result.status)
            progress != null -> progress.stage
            else -> stageOf(result!!.status)
        }
        // Either signal reaching a terminal state ends the run; if only one of them
        // has been observed yet, that one decides.
        val running = !(progress?.isTerminal ?: false) && !(result?.isTerminal ?: false)
        return FlowStatusViewState(
            rootTitle = result?.rootFrame?.symbol?.displayName,
            headline = FlowLensBundle.message("status.stage.${stage.name}"),
            counters = progress?.let {
                FlowLensBundle.message(
                    "status.counters",
                    it.nodesProduced,
                    it.framesAnalyzed,
                    it.externalCount,
                    it.ambiguousCount,
                )
            },
            tone = toneOf(stage),
            stopEnabled = running,
            reanalyzeEnabled = !running && needsReanalysis(stage),
            simplifiedControlFlow = result?.controlFlowIncomplete == true,
            diagnostics = result?.diagnostics.orEmpty().map { diagnostic ->
                FlowLensBundle.message(diagnostic.messageKey)
            }.distinct(),
            currentCallable = progress?.currentCallable?.takeIf { running },
            budgetFraction = progress
                ?.takeIf { running && it.nodeBudget > 0 }
                ?.let { (it.nodesProduced.toDouble() / it.nodeBudget).coerceIn(0.0, 1.0) },
            stopReasons = if (running) emptyList() else stopReasonsOf(result),
        )
    }

    /**
     * Counts the reasons the map ends where it does. A reason with no instances
     * is omitted entirely: a run with nothing to explain shows nothing, which is
     * what makes the presence of a line mean something (`V0.3_SPEC.md` §7.3).
     */
    private fun stopReasonsOf(result: FlowAnalysisResult?): List<StopReason> {
        if (result == null || !result.isTerminal) return emptyList()
        val nodes = result.frames.values.flatMap { frame -> allNodes(frame.events) }
        return listOfNotNull(
            reason("status.reason.depth.limited", nodes) {
                it.metadata[FlowMetadata.LIMIT] == FlowMetadata.LIMIT_DEPTH
            },
            reason("status.reason.unresolved", nodes) {
                it.resolutionStatus == ResolutionStatus.UNRESOLVED
            },
            reason("status.reason.external", nodes) {
                // A group stands for its members and is not a call of its own.
                // Counting both would say a run of three left the project four
                // times (`V1.0_GROUPING_SPEC.md` §5.4).
                it.resolutionStatus == ResolutionStatus.EXTERNAL && !it.isGroup
            },
            reason("status.reason.cycle", nodes) { it.kind == FlowNodeKind.CYCLE },
            reason("status.reason.truncated", nodes) { it.kind == FlowNodeKind.LIMIT },
            // Not a reason the map stops, but the same kind of disclosure: a body
            // is on the map whose timing nothing justified (`V0.5_SPEC.md` §6).
            reason("status.reason.callback.timing", nodes) {
                it.kind == FlowNodeKind.CALLBACK && it.executionMode == ExecutionMode.UNKNOWN
            },
        )
    }

    private fun reason(
        key: String,
        nodes: List<FlowNode>,
        matches: (FlowNode) -> Boolean,
    ): StopReason? {
        val hits = nodes.filter(matches)
        if (hits.isEmpty()) return null
        return StopReason(
            text = FlowLensBundle.message(key, hits.size),
            count = hits.size,
            firstNode = hits.first().id,
        )
    }

    /** Structural nodes own branches, so a flat walk of a frame is not enough. */
    private fun allNodes(events: List<FlowNode>): List<FlowNode> = events.flatMap { node ->
        listOf(node) + allNodes(node.branches.flatMap { it.events })
    }

    private fun stageOf(status: FlowResultStatus): FlowProgressStage = when (status) {
        FlowResultStatus.RUNNING -> FlowProgressStage.ANALYZING_CHILD_FRAMES
        FlowResultStatus.COMPLETED -> FlowProgressStage.COMPLETED
        FlowResultStatus.TRUNCATED -> FlowProgressStage.TRUNCATED
        FlowResultStatus.CANCELLED -> FlowProgressStage.CANCELLED
        FlowResultStatus.STALE -> FlowProgressStage.STALE
        FlowResultStatus.FAILED -> FlowProgressStage.FAILED
    }

    private fun toneOf(stage: FlowProgressStage): StatusTone = when (stage) {
        FlowProgressStage.COMPLETED -> StatusTone.DONE
        FlowProgressStage.TRUNCATED,
        FlowProgressStage.CANCELLED,
        FlowProgressStage.STALE,
        -> StatusTone.WARNING
        FlowProgressStage.FAILED -> StatusTone.ERROR
        else -> StatusTone.RUNNING
    }

    /** Re-analysis is offered when the run ended without a trustworthy full result. */
    private fun needsReanalysis(stage: FlowProgressStage): Boolean = when (stage) {
        FlowProgressStage.STALE,
        FlowProgressStage.CANCELLED,
        FlowProgressStage.FAILED,
        FlowProgressStage.TRUNCATED,
        -> true
        else -> false
    }
}
