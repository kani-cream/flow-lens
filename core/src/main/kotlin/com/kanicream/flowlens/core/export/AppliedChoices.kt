package com.kanicream.flowlens.core.export

import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowNode

/**
 * The dispatch choices this result actually applied.
 *
 * Read from the result rather than from the session's choice list, for two
 * reasons. A choice made while reading one flow says nothing about another, and
 * listing it there would put something in the export that is not on the map
 * (`V0.4_SPEC.md` §2). And a choice the run declined to apply — the depth limit
 * blocked it, policy refused the target — is not in effect, however much the
 * session still remembers it.
 *
 * The consequence is the property the export contract wants: the same result
 * produces the same text, whatever the session has done since.
 */
internal object AppliedChoices {

    fun of(result: FlowAnalysisResult): List<ChoiceLine> {
        val lines = LinkedHashMap<String, ChoiceLine>()
        for (frame in result.frames.values) {
            for (node in flatten(frame.events)) {
                val chosen = node.metadata[MarkdownExporter.CHOSEN_KEY] ?: continue
                val symbol = node.targetSymbol ?: continue
                val from = listOfNotNull(symbol.containerName, symbol.displayName).joinToString(".")
                lines.putIfAbsent(from, ChoiceLine(from, chosen))
            }
        }
        return lines.values.sortedBy { it.from }
    }

    private fun flatten(events: List<FlowNode>): List<FlowNode> = events.flatMap { node ->
        listOf(node) + flatten(node.branches.flatMap { it.events })
    }
}
