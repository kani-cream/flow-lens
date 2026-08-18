package com.kanicream.flowlens.core.export

import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.ResolutionStatus

/**
 * Why the map stops where it does, as counted lines. The same set the status
 * area shows (`V0.3_SPEC.md` §7.3), so an export and the tool window cannot
 * disagree about what was left out.
 *
 * A reason with no instances is omitted, and a flow with nothing to explain
 * produces no section at all (`V0.4_SPEC.md` §6).
 */
internal object StopReasons {

    fun of(request: ExportRequest): List<String> {
        val s = request.context.strings
        val nodes = request.result.frames.values.flatMap { flatten(it.events) }
        return buildList {
            count(nodes) {
                it.metadata[MarkdownExporter.LIMIT_KEY] == MarkdownExporter.LIMIT_DEPTH
            }?.let { add("$it — ${s.depthLimited}") }
            count(nodes) { it.resolutionStatus == ResolutionStatus.UNRESOLVED }
                ?.let { add("$it — ${s.unresolved}") }
            count(nodes) { it.resolutionStatus == ResolutionStatus.EXTERNAL }
                ?.let { add("$it — ${s.external}") }
            count(nodes) { it.kind == FlowNodeKind.CYCLE }?.let { add("$it — ${s.cycle}") }
            count(nodes) { it.kind == FlowNodeKind.LIMIT }?.let { add(s.truncated) }
            if (request.result.controlFlowIncomplete) add(s.controlFlowSimplified)
        }
    }

    private fun count(nodes: List<FlowNode>, matches: (FlowNode) -> Boolean): Int? =
        nodes.count(matches).takeIf { it > 0 }

    /** Structures own branches, so a flat walk of a frame is not enough. */
    private fun flatten(events: List<FlowNode>): List<FlowNode> = events.flatMap { node ->
        listOf(node) + flatten(node.branches.flatMap { it.events })
    }
}
