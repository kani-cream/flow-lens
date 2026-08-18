package com.kanicream.flowlens.ui.details

import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.service.FlowMetadata

/** One labelled fact about the selected event. */
data class DetailRow(val label: String, val value: String)

/** Everything the details area shows for one selection. */
data class FlowDetailsViewState(
    val title: String,
    val subtitle: String?,
    val rows: List<DetailRow>,
    val openTargetEnabled: Boolean,
    val openCallSiteEnabled: Boolean,
)

/**
 * Maps a selected [FlowNode] to labelled detail rows. Pure so the exact set of
 * facts a user can read for each semantic state is testable without Swing.
 */
object FlowDetailsModel {

    fun stateOf(node: FlowNode?): FlowDetailsViewState {
        if (node == null) {
            return FlowDetailsViewState(
                title = FlowLensBundle.message("details.no.selection"),
                subtitle = null,
                rows = emptyList(),
                openTargetEnabled = false,
                openCallSiteEnabled = false,
            )
        }
        return FlowDetailsViewState(
            title = node.targetSymbol?.displayName ?: kindTitleOf(node),
            subtitle = node.targetSymbol?.containerName,
            rows = rowsOf(node),
            openTargetEnabled = node.targetLocation != null,
            openCallSiteEnabled = node.callSiteLocation != null,
        )
    }

    /**
     * An event with no target names its kind, and adds what it acts on when the
     * source says: the condition of a branch, the expression a `return` hands
     * back. Without it every terminator reads the same.
     */
    private fun kindTitleOf(node: FlowNode): String {
        val kind = FlowLensBundle.message("enum.kind.${node.kind.name}")
        return node.sourceSummary?.let { "$kind $it" } ?: kind
    }

    private fun rowsOf(node: FlowNode): List<DetailRow> = buildList {
        add(row("details.kind", FlowLensBundle.message("enum.kind.${node.kind.name}")))
        node.targetSymbol?.let { add(row("details.language", it.languageId)) }
        node.resolutionStatus?.let {
            add(row("details.resolution", FlowLensBundle.message("enum.resolution.${it.name}")))
        }
        node.dispatchConfidence?.let { confidence ->
            val text = FlowLensBundle.message("enum.dispatch.${confidence.name}")
            add(
                row(
                    "details.dispatch",
                    if (confidence == DispatchConfidence.DECLARED_TARGET) {
                        "$text — ${FlowLensBundle.message("details.dispatch.override.hint")}"
                    } else {
                        text
                    },
                ),
            )
        }
        add(row("details.execution", FlowLensBundle.message("enum.execution.${node.executionMode.name}")))
        add(
            row(
                "details.ordering",
                buildString {
                    append(FlowLensBundle.message("enum.ordering.${node.orderingStatus.name}"))
                    if (node.metadata[FlowMetadata.CONDITIONAL] == "true") {
                        append(" — ").append(FlowLensBundle.message("details.conditional.hint"))
                    }
                },
            ),
        )
        node.metadata[FlowMetadata.CHOSEN]?.let {
            // Whose body is under this call, and that a reader chose it rather
            // than the analyzer proving it (`V0.4_SPEC.md` §4.5).
            add(row("details.chosen", FlowLensBundle.message("details.chosen.value", it)))
        }
        node.metadata[FlowMetadata.ORIGIN]?.let {
            add(row("details.origin", FlowLensBundle.message("enum.origin.$it")))
        }
        if (node.metadata[FlowMetadata.LIMIT] == FlowMetadata.LIMIT_DEPTH) {
            add(row("details.limit", FlowLensBundle.message("details.limit.depth")))
        }
        if (node.metadata[FlowMetadata.TEST_SOURCE] == "true") {
            add(row("details.origin.test", FlowLensBundle.message("card.badge.test.source")))
        }
        add(row("details.depth", node.depth.toString()))
        node.callSiteLocation?.let {
            add(row("details.location", "${it.presentablePath}:${it.line}"))
        }
        if (node.kind == FlowNodeKind.CYCLE) {
            add(row("details.cycle", FlowLensBundle.message("details.cycle.hint")))
        }
    }

    private fun row(labelKey: String, value: String) =
        DetailRow(FlowLensBundle.message(labelKey), value)
}
