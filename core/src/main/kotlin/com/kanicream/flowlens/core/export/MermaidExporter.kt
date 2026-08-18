package com.kanicream.flowlens.core.export

import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.core.model.ResolutionStatus

/**
 * The flow as a Mermaid flowchart (`V0.4_SPEC.md` §7), which GitHub renders
 * inside Markdown.
 *
 * Ids are positional so the diagram diffs cleanly; the names live in the labels.
 * A structure becomes a subgraph, so a branch reads as a branch rather than as
 * a straight line, and a connector that cannot claim "this runs next" is dashed,
 * matching the canvas rule.
 */
object MermaidExporter {

    fun export(request: ExportRequest): String {
        val root = request.result.rootFrame ?: return ""
        val out = StringBuilder("flowchart TD\n")
        val ids = IdSource()
        val rootId = ids.next()
        out.append("  ").append(rootId).append("[\"")
            .append(escape(root.symbol.displayName)).append("\"]\n")

        emitSequence(out, request, ids, root.events, parent = rootId)
        return out.toString()
    }

    /**
     * Emits one ordered sequence and returns the last node emitted, so a caller
     * can continue the chain after it.
     */
    private fun emitSequence(
        out: StringBuilder,
        request: ExportRequest,
        ids: IdSource,
        events: List<FlowNode>,
        parent: String,
    ): String {
        var previous = parent
        for (node in events) {
            val id = ids.next()
            out.append("  ").append(id).append("[\"").append(label(node, request)).append("\"]\n")
            out.append("  ").append(previous).append(edge(node)).append(id).append("\n")

            if (node.branches.isNotEmpty()) {
                for (branch in node.branches) {
                    val subgraph = ids.nextSubgraph()
                    val title = listOfNotNull(branch.kind.name.lowercase(), branch.label)
                        .joinToString(" ")
                    out.append("  subgraph ").append(subgraph).append("[\"")
                        .append(escape(title)).append("\"]\n")
                    if (branch.isEmpty) {
                        val empty = ids.next()
                        out.append("    ").append(empty).append("[\"")
                            .append(escape(request.context.strings.nothing)).append("\"]\n")
                    } else {
                        emitSequence(out, request, ids, branch.events, parent = id)
                    }
                    out.append("  end\n")
                }
            }

            val body = node.targetFrameId?.let(request.result::frame)
            if (body != null && body.events.isNotEmpty()) {
                emitSequence(out, request, ids, body.events, parent = id)
            }
            previous = id
        }
        return previous
    }

    private fun label(node: FlowNode, request: ExportRequest): String {
        val s = request.context.strings
        val name = node.targetSymbol?.displayName ?: node.kind.name.lowercase()
        val notes = buildList {
            if (node.dispatchConfidence == DispatchConfidence.AMBIGUOUS) add(s.ambiguous)
            if (node.dispatchConfidence == DispatchConfidence.DECLARED_TARGET) add(s.declaredTarget)
            if (node.resolutionStatus == ResolutionStatus.UNRESOLVED) add(s.unresolved)
            if (node.resolutionStatus == ResolutionStatus.EXTERNAL) add(s.external)
            if (node.kind == FlowNodeKind.CYCLE) add(s.cycle)
            if (node.kind == FlowNodeKind.LIMIT) add(s.truncated)
            node.metadata[MarkdownExporter.CHOSEN_KEY]?.let { add("${s.chosen}: $it") }
        }
        val summary = node.sourceSummary?.let { " $it" } ?: ""
        val head = escape(name + summary)
        return if (notes.isEmpty()) head else head + "<br/>" + escape(notes.joinToString(", "))
    }

    /** A step that cannot claim "this runs next" gets a dashed edge. */
    private fun edge(node: FlowNode): String {
        val uncertain = node.orderingStatus != OrderingStatus.DETERMINISTIC ||
            node.executionMode != ExecutionMode.SYNC ||
            node.metadata[MarkdownExporter.CONDITIONAL_KEY] == "true"
        return if (uncertain) " -.-> " else " --> "
    }

    /**
     * Mermaid labels are parsed, so a quote or an angle bracket in a generic
     * type or a condition summary would end the label early and break the whole
     * diagram. `#nn;` is Mermaid's own escape.
     */
    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '"' -> append("#quot;")
                '<' -> append("#lt;")
                '>' -> append("#gt;")
                '#' -> append("#35;")
                '[', ']', '(', ')', '{', '}' -> append("#").append(ch.code).append(";")
                '\n', '\r' -> append(" ")
                else -> append(ch)
            }
        }
    }

    private class IdSource {
        private var next = 0
        private var subgraphs = 0
        fun next(): String = "n${next++}"
        fun nextSubgraph(): String = "s${subgraphs++}"
    }
}
