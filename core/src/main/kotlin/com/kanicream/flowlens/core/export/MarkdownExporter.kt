package com.kanicream.flowlens.core.export

import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowFrame
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.ResolutionStatus

/**
 * The flow as a document (`V0.4_SPEC.md` §6).
 *
 * Markers are words rather than glyphs: a document is read, not scanned, and a
 * reader who did not see the canvas has no legend for "◇".
 */
object MarkdownExporter {

    fun export(request: ExportRequest): String {
        val result = request.result
        val root = result.rootFrame ?: return ""
        val out = StringBuilder()

        out.append("# ").append(root.symbol.displayName).append("\n\n")
        out.append(headline(request, result, root)).append("\n\n")

        appendEvents(out, request, root.events, indent = 0)

        appendNotFollowed(out, request)
        appendChoices(out, request)
        return out.toString()
    }

    /** The reader's word for a structure kind, falling back to the enum name. */
    internal fun kindName(node: FlowNode, request: ExportRequest): String =
        request.context.strings.kinds[node.kind.name] ?: node.kind.name.lowercase()

    private fun headline(request: ExportRequest, result: FlowAnalysisResult, root: FlowFrame): String =
        listOfNotNull(
            root.symbol.containerName?.let { "`$it`" },
            root.symbol.languageId,
            "${result.nodeCount} nodes",
            request.context.strings.statuses[result.status.name] ?: result.status.name.lowercase(),
        ).joinToString(" · ")

    private fun appendEvents(
        out: StringBuilder,
        request: ExportRequest,
        events: List<FlowNode>,
        indent: Int,
    ) {
        val pad = "  ".repeat(indent)
        for (node in events) {
            // A body handed to a call is written under that call, not beside it.
            // A sibling bullet would put it in the sequence, which is the one
            // thing it is not (`V0.5_SPEC.md` §5.5).
            val own = if (node.attachedTo != null) "$pad  " else pad
            out.append(own).append("- ").append(describe(node, request)).append("\n")

            if (node.isGroup) {
                // The members are what the group stands for, not a labelled
                // alternative, so they are written straight underneath it.
                node.branches.firstOrNull()?.let {
                    appendEvents(out, request, it.events, own.length / 2 + 1)
                }
                continue
            }
            for (branch in node.branches) {
                val label = listOfNotNull(
                    request.context.strings.branchKinds[branch.kind.name] ?: branch.kind.name.lowercase(),
                    branch.label,
                ).joinToString(" ")
                out.append(own).append("  - **").append(label).append("**")
                if (branch.isEmpty) {
                    out.append(" — ").append(request.context.strings.nothing).append("\n")
                } else {
                    out.append("\n")
                    appendEvents(out, request, branch.events, own.length / 2 + 2)
                }
            }

            val body = node.targetFrameId?.let(request.result::frame)
            // A collapsed frame is a view state, not part of the flow, so the
            // export carries it either way (`V0.4_SPEC.md` §5.3).
            if (body != null && body.events.isNotEmpty()) {
                appendEvents(out, request, body.events, own.length / 2 + 1)
            }
        }
    }

    private fun describe(node: FlowNode, request: ExportRequest): String {
        val s = request.context.strings
        val name = node.targetSymbol?.displayName ?: kindName(node, request)
        val head = StringBuilder("**").append(name).append("**")
        node.targetSymbol?.containerName?.let { head.append(" — `").append(it).append("`") }

        val notes = buildList {
            when (node.dispatchConfidence) {
                DispatchConfidence.AMBIGUOUS -> add(s.ambiguous)
                DispatchConfidence.DECLARED_TARGET -> add(s.declaredTarget)
                else -> Unit
            }
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
            node.metadata[GROUP_SIZE_KEY]?.let { add(s.groupCalls.replace("{0}", it)) }
            if (node.kind == FlowNodeKind.CYCLE) add(s.cycle)
            if (node.kind == FlowNodeKind.LIMIT) add(s.truncated)
            node.metadata[CHOSEN_KEY]?.let { add("${s.chosen}: `$it`") }
            if (node.metadata[CONDITIONAL_KEY] == "true") add(s.conditional)
            if (node.metadata[LIMIT_KEY] == LIMIT_DEPTH) add(s.depthLimited)
            if (node.metadata[TEST_SOURCE_KEY] == "true") add(s.testSource)
        }
        node.sourceSummary?.let { head.append(" `").append(it).append("`") }
        if (notes.isNotEmpty()) head.append(" — ").append(notes.joinToString(", "))
        return head.toString()
    }

    private fun appendNotFollowed(out: StringBuilder, request: ExportRequest) {
        val lines = StopReasons.of(request)
        if (lines.isEmpty()) return
        out.append("\n## ").append(request.context.strings.notFollowed).append("\n\n")
        lines.forEach { out.append("- ").append(it).append("\n") }
    }

    private fun appendChoices(out: StringBuilder, request: ExportRequest) {
        val choices = AppliedChoices.of(request.result)
        if (choices.isEmpty()) return
        out.append("\n## ").append(request.context.strings.dispatchChoices).append("\n\n")
        for (choice in choices) {
            out.append("- `").append(choice.from).append("` → `").append(choice.to)
                .append("` (").append(request.context.strings.chosenByReader).append(")\n")
        }
    }

    // The metadata keys live in the plugin module, which core cannot see; they
    // are part of the model's contract either way and are asserted in tests.
    internal const val CHOSEN_KEY = "flowlens.chosen"
    internal const val CONDITIONAL_KEY = "flowlens.conditional"
    internal const val LIMIT_KEY = "flowlens.limit"
    internal const val LIMIT_DEPTH = "depth"
    internal const val TEST_SOURCE_KEY = "flowlens.testSource"
    internal const val GROUP_SIZE_KEY = "flowlens.groupSize"
}
