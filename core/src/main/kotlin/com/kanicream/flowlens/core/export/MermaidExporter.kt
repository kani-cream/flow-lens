package com.kanicream.flowlens.core.export

import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.NodeId
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.core.model.ResolutionStatus

/**
 * The flow as a Mermaid flowchart (`V0.4_SPEC.md` §7), which GitHub renders
 * inside Markdown.
 *
 * Nodes are declared first, subgraphs list only their members, and every edge is
 * emitted last. Mermaid assigns a node to whichever subgraph first mentions it,
 * including as an edge endpoint, so an edge written inside a `then` block would
 * drag the `if` node into its own branch.
 */
object MermaidExporter {

    fun export(request: ExportRequest): String {
        val root = request.result.rootFrame ?: return ""
        val doc = Document(request)
        val rootId = doc.declare(escape(root.symbol.displayName))
        // The root is a callable like any other: a flow that calls back into it
        // has to point at this node rather than draw a second one.
        doc.register(root.symbol.key, rootId)
        doc.walk(root.events, from = setOf(rootId))
        return doc.render()
    }

    private class Document(val request: ExportRequest) {
        private val nodes = mutableListOf<String>()
        private val edges = mutableListOf<String>()
        private val subgraphs = mutableListOf<Subgraph>()

        /** Where each callable was first drawn, so a cycle can point back at it. */
        private val idsByKey = mutableMapOf<String, String>()

        /** Node identity, so an attached body can name the call it belongs to. */
        private val idsByNode = mutableMapOf<NodeId, String>()

        private class Subgraph(val id: String, val title: String, val members: MutableList<String>)

        fun declare(label: String): String {
            val id = "n${nodes.size}"
            nodes += "  $id[\"$label\"]"
            return id
        }

        fun register(key: String, id: String) {
            idsByKey.putIfAbsent(key, id)
        }

        /**
         * Emits one sequence and returns the ends the next event follows from.
         *
         * A structure has several ends — one per branch — and they all lead to
         * whatever comes after it. Returning a single node instead made the step
         * after an `if` hang off the `if` itself, drawn as a third alternative
         * beside `then` and `else` rather than as where the paths come back
         * together (`V0.2_SPEC.md` §7).
         */
        fun walk(events: List<FlowNode>, from: Set<String>): Set<String> {
            var frontier = from
            for (node in events) {
                // A cycle is an edge back to where that callable was already
                // drawn, rather than a second node claiming to be a new call.
                val repeats = node.takeIf { it.kind == FlowNodeKind.CYCLE }
                    ?.targetSymbol?.key
                    ?.let { idsByKey[it] }
                if (repeats != null) {
                    val label = inlineLabel(label(node, request))
                    frontier.forEach { edges += "  $it -.->|$label| $repeats" }
                    // A cycle ends its path; what follows the structure comes
                    // from the other branches.
                    frontier = emptySet()
                    continue
                }

                val id = declare(label(node, request))
                node.targetSymbol?.key?.let { register(it, id) }
                idsByNode[node.id] = id

                // A body handed to a call hangs off that call. Drawing it in the
                // sequence would put whatever follows the call after the body
                // instead, which for an asynchronous one reverses the meaning
                // (`V0.5_SPEC.md` §5.5).
                val owner = node.attachedTo?.let { idsByNode[it] }
                if (owner != null) {
                    edges += "  $owner${edge(node)}$id"
                } else {
                    frontier.forEach { edges += "  $it${edge(node)}$id" }
                }

                val body = node.targetFrameId?.let(request.result::frame)
                if (body != null && body.events.isNotEmpty()) {
                    // A body is a detour: the sequence continues from the call,
                    // not from the last thing the callee did.
                    walk(body.events, from = setOf(id))
                }

                // An attached body is not where the sequence continues from.
                if (owner != null) continue
                frontier = if (node.branches.isEmpty()) {
                    setOf(id)
                } else {
                    branchEnds(node, id)
                }
                if (frontier.isEmpty()) frontier = setOf(id)
            }
            return frontier
        }

        /** Each branch's last node, which is where the paths reconverge. */
        private fun branchEnds(node: FlowNode, structureId: String): Set<String> {
            val ends = mutableSetOf<String>()
            for (branch in node.branches) {
                val title = listOfNotNull(
                    request.context.strings.branchKinds[branch.kind.name]
                        ?: branch.kind.name.lowercase(),
                    branch.label,
                ).joinToString(" ")
                val group = Subgraph("s${subgraphs.size}", escape(title), mutableListOf())
                subgraphs += group
                if (branch.isEmpty) {
                    val empty = declare(escape(request.context.strings.nothing))
                    group.members += empty
                    edges += "  $structureId${" --> "}$empty"
                    ends += empty
                } else {
                    val before = nodes.size
                    ends += walk(branch.events, from = setOf(structureId))
                    for (index in before until nodes.size) group.members += "n$index"
                }
            }
            return ends
        }

        fun render(): String = buildString {
            // Fenced, because the point is pasting into GitHub Markdown, where a
            // bare "flowchart TD" is a paragraph of text rather than a diagram.
            append("```mermaid\n")
            append("flowchart TD\n")
            nodes.forEach { append(it).append("\n") }
            for (group in subgraphs) {
                append("  subgraph ").append(group.id).append("[\"").append(group.title)
                    .append("\"]\n")
                group.members.forEach { append("    ").append(it).append("\n") }
                append("  end\n")
            }
            edges.forEach { append(it).append("\n") }
            // What the diagram cannot draw is stated instead of dropped, so both
            // formats disclose the same things (`V0.4_SPEC.md` §5.2).
            for (line in StopReasons.of(request)) {
                append("  %% ").append(comment(line)).append("\n")
            }
            for (choice in AppliedChoices.of(request.result)) {
                append("  %% ").append(comment(request.context.strings.dispatchChoices))
                    .append(": ").append(comment(choice.from)).append(" -> ")
                    .append(comment(choice.to)).append("\n")
            }
            append("```\n")
        }
    }

    private fun label(node: FlowNode, request: ExportRequest): String {
        val s = request.context.strings
        val name = node.targetSymbol?.displayName ?: MarkdownExporter.kindName(node, request)
        val notes = buildList {
            if (node.dispatchConfidence == DispatchConfidence.AMBIGUOUS) add(s.ambiguous)
            if (node.dispatchConfidence == DispatchConfidence.DECLARED_TARGET) add(s.declaredTarget)
            when (node.resolutionStatus) {
                ResolutionStatus.UNRESOLVED -> add(s.unresolved)
                ResolutionStatus.EXTERNAL -> add(s.external)
                ResolutionStatus.BUILT_IN -> add(s.builtIn)
                else -> Unit
            }
            when (node.executionMode) {
                ExecutionMode.GOROUTINE -> add(s.goroutine)
                ExecutionMode.DEFERRED -> add(s.deferred)
                ExecutionMode.ASYNC -> add(s.async)
                ExecutionMode.UNKNOWN -> add(s.timingUnknown)
                else -> Unit
            }
            if (node.kind == FlowNodeKind.CYCLE) add(s.cycle)
            if (node.kind == FlowNodeKind.LIMIT) add(s.truncated)
            node.metadata[MarkdownExporter.CHOSEN_KEY]?.let { add("${s.chosen}: $it") }
            if (node.metadata[MarkdownExporter.LIMIT_KEY] == MarkdownExporter.LIMIT_DEPTH) {
                add(s.depthLimited)
            }
            if (node.metadata[MarkdownExporter.TEST_SOURCE_KEY] == "true") add(s.testSource)
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

    /** An edge label cannot carry markup or quotes. */
    private fun inlineLabel(label: String): String =
        label.replace("<br/>", " ").replace("|", " ")

    /** A comment runs to the end of the line, so a newline would escape it. */
    private fun comment(text: String): String = text.replace("\n", " ").replace("\r", " ")

    /**
     * Mermaid labels are parsed, so a quote or an angle bracket in a generic
     * type or a condition summary would end the label early and break the whole
     * diagram. `#nn;` is Mermaid's own escape.
     */
    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                // A label is quoted, so only what could close the quote or inject
                // markup needs escaping. Escaping parentheses as well turned every
                // call into "purchase#40;#41;", which is unreadable for no gain.
                '"' -> append("#quot;")
                '<' -> append("#lt;")
                '>' -> append("#gt;")
                '#' -> append("#35;")
                '\n', '\r' -> append(" ")
                else -> append(ch)
            }
        }
    }
}
